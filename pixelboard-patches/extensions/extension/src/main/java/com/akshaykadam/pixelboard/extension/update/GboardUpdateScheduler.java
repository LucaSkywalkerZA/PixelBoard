/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the automatic update check inside the keyboard process.
 *
 * <p>{@link #start(Context)} is called when the process starts. The keyboard process stays alive
 * while PixelBoard is the active input method, so a low-priority daemon thread wakes up every hour
 * and asks GitHub only when {@link GboardUpdateChecker#isDue} says a day has passed. No manifest
 * component or Gboard hook is needed.</p>
 */
public final class GboardUpdateScheduler {
    private static final long FIRST_CHECK_DELAY_MS = 2L * 60L * 1000L;
    private static final long TICK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile Context applicationContext;
    private static final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "GboardPatchesUpdateCheck");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private GboardUpdateScheduler() {
    }

    /** Starts the periodic check once per process. Safe to call from the main thread. */
    public static void start(Context context) {
        Context appContext = GboardUpdateChecker.applicationContext(context);
        if (appContext == null) {
            return;
        }
        applicationContext = appContext;
        if (!started.compareAndSet(false, true)) {
            return;
        }
        submit(() -> GboardUpdateNotifier.cancelStaleProgress(appContext));
        try {
            executor.scheduleWithFixedDelay(
                    GboardUpdateScheduler::runDueCheck,
                    FIRST_CHECK_DELAY_MS,
                    TICK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable throwable) {
            started.set(false);
            GboardUpdateChecker.logWarning("[update-check] failed to schedule", throwable);
        }
    }

    /** Runs a background check now if one is due, for example when settings open. */
    public static void checkIfDue(Context context) {
        start(context);
        submit(GboardUpdateScheduler::runDueCheck);
    }

    /** Runs a user-requested check, ignoring the schedule and the auto-check switch. */
    public static void checkNow(Context context) {
        Context appContext = GboardUpdateChecker.applicationContext(context);
        if (appContext == null) {
            return;
        }
        start(appContext);
        submit(() -> {
            try {
                GboardUpdateChecker.check(appContext, true);
            } catch (Throwable throwable) {
                GboardUpdateChecker.logWarning("[update-check] manual check failed", throwable);
            }
        });
    }

    private static void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The process is shutting down.
        }
    }

    private static void runDueCheck() {
        // Every exception is caught: one escaping would cancel the periodic schedule.
        try {
            Context context = applicationContext;
            if (context == null || !isUserUnlocked(context)) {
                return;
            }
            SharedPreferences preferences = GboardUpdateSettings.preferences(context);
            if (!GboardUpdateSettings.readAutoCheckEnabled(preferences)
                    || !GboardUpdateChecker.isDue(preferences, System.currentTimeMillis())) {
                return;
            }
            GboardUpdateChecker.check(context, false);
        } catch (Throwable throwable) {
            GboardUpdateChecker.logWarning("[update-check] scheduled check failed", throwable);
        }
    }

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        try {
            UserManager userManager = context.getSystemService(UserManager.class);
            return userManager == null || userManager.isUserUnlocked();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
