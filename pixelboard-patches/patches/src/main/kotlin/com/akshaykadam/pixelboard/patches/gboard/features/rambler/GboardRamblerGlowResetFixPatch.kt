package com.akshaykadam.pixelboard.patches.gboard.features.rambler

import com.akshaykadam.pixelboard.patches.gboard.shared.GboardMethodTarget
import com.akshaykadam.pixelboard.patches.gboard.shared.findMutableMethodOrNull
import com.akshaykadam.pixelboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import com.akshaykadam.pixelboard.patches.shared.ExternalLabel
import com.akshaykadam.pixelboard.patches.shared.addInstructionsWithLabels
import com.akshaykadam.pixelboard.patches.shared.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** AbstractRamblerUiController.hideUi(animate): the only place the Rambler glow is torn down. */
private val ramblerHideUiTargets = listOf(
    // 18.5.3 release
    GboardMethodTarget(classType = "Lflb;", name = "i", parameterTypes = listOf("Z"), returnType = "V"),
)

private const val CONTINUE_LABEL = "pixelboard_rambler_glow_continue"

/*
 * Closing the keyboard while Rambler is still WAITING calls hideUi twice in a row. The first call starts the
 * animated exit, stores resetUi as the animation's end Runnable and clears the view fields. The second call then
 * sees no view, stops the glow (which drops that pending resetUi) and skips setBackground(null): the stopped glow
 * stays on the header as a frozen colourful bar. Stock Gboard has the same bug (upstream #88, #130).
 * Fix: while an exit animation is pending, a repeated hideUi does nothing, so resetUi runs and clears the glow.
 * Gboard 18.6.3 fixes this itself (the pending reset is kept in a field and finished by a repeated hideUi), so
 * this patch only targets 18.5.3; on later versions the target is missing or fails the shape check and is skipped.
 */
internal val gboardRamblerGlowResetFixPatch = bytecodePatch(
    description = "Stop the Rambler glow from getting stuck after closing the keyboard while voice input starts.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        val method = ramblerHideUiTargets.firstNotNullOfOrNull { findMutableMethodOrNull(it) } ?: return@execute
        val instructions = method.implementation?.instructions?.toList() ?: return@execute
        val owner = method.definingClass

        fun fieldAt(opcode: Opcode, predicate: (FieldReference) -> Boolean) = instructions
            .filter { it.opcode == opcode }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .firstOrNull(predicate)

        fun calls(definingClass: String, name: String) = instructions.any {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == definingClass && ref.name == name
        }

        // Shape check: this must still be the hide method that starts the exit animation.
        if (!calls("Landroid/view/View;", "isShown") ||
            !calls("Landroid/view/View;", "setBackground") ||
            !calls("Landroid/view/Choreographer;", "postFrameCallback")
        ) {
            return@execute
        }
        val viewField = fieldAt(Opcode.IGET_OBJECT) {
            it.definingClass == owner && it.type == "Landroid/view/View;"
        } ?: return@execute
        // The animation's end Runnable is resetUi: a new instance whose constructor takes this controller first.
        val resetUiIndex = instructions.indexOfFirst {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode in setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE) &&
                ref?.name == "<init>" && ref.parameterTypes.firstOrNull()?.toString() == owner
        }
        if (resetUiIndex < 0) return@execute
        val resetUiRegister = when (val init = instructions[resetUiIndex]) {
            is RegisterRangeInstruction -> init.startRegister
            is FiveRegisterInstruction -> init.registerC
            else -> return@execute
        }
        val endRunnableField = instructions.drop(resetUiIndex)
            .filter { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == resetUiRegister }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .firstOrNull { it.type == "Ljava/lang/Runnable;" } ?: return@execute
        // glow.animator: the field on the glow drawable whose type owns the end Runnable.
        val animatorField = fieldAt(Opcode.IGET_OBJECT) { it.type == endRunnableField.definingClass } ?: return@execute
        val glowField = fieldAt(Opcode.IGET_OBJECT) {
            it.definingClass == owner && it.type == animatorField.definingClass
        } ?: return@execute

        val registers = method.implementation!!.registerCount - method.parameterTypes.size - 1
        check(registers >= 1) { "No free local register for the Rambler glow guard" }

        // Keep the subclass reset call that opens the method; v0 is written before it is read after that.
        val insertAt = if (instructions.first().opcode == Opcode.INVOKE_VIRTUAL) 1 else 0
        method.addInstructionsWithLabels(
            insertAt,
            """
                iget-object v0, p0, $viewField
                if-nez v0, :$CONTINUE_LABEL
                iget-object v0, p0, $glowField
                if-eqz v0, :$CONTINUE_LABEL
                iget-object v0, v0, $animatorField
                iget-object v0, v0, $endRunnableField
                if-eqz v0, :$CONTINUE_LABEL
                return-void
            """.trimIndent(),
            ExternalLabel(CONTINUE_LABEL, method.implementation!!.instructions[insertAt]),
        )
    }
}
