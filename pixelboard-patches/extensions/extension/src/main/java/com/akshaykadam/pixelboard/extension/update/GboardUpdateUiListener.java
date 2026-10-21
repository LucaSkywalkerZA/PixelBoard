/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

/** Lets the visible settings screen re-render when update state changes on a worker thread. */
public final class GboardUpdateUiListener {
    private static volatile Runnable listener;

    private GboardUpdateUiListener() {
    }

    /** Sets the callback, or clears it with {@code null}. It may be called on any thread. */
    public static void set(Runnable callback) {
        listener = callback;
    }

    static void fire() {
        Runnable callback = listener;
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (Throwable ignored) {
            // A settings screen refresh failure must never break the update check.
        }
    }
}
