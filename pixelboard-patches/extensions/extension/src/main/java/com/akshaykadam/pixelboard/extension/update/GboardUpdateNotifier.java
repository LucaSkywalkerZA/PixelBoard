/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import com.akshaykadam.pixelboard.extension.R;
import com.akshaykadam.pixelboard.extension.settings.GboardPatchesSettingsActivity;
import com.akshaykadam.pixelboard.extension.settings.GboardSettingsText;

/** Update notifications. Framework icons are used because extension resources are not merged. */
public final class GboardUpdateNotifier {
    static final String CHANNEL_ID = "pixelboard_updates";
    static final String PROGRESS_CHANNEL_ID = "pixelboard_update_progress";
    static final int NOTIFICATION_ID_UPDATE_AVAILABLE = 0x50420010;
    static final int NOTIFICATION_ID_INSTALL = 0x50420011;
    /**
     * Progress has its own ID: on Android 12L-14 a notification's timeout alarm is not always
     * cancelled, and it must never remove the "ready" or "failed" notification instead.
     */
    static final int NOTIFICATION_ID_PROGRESS = 0x50420012;
    private static final String GROUP_KEY_PREFIX = "com.akshaykadam.pixelboard.update.";
    private static final long PROGRESS_TIMEOUT_MS = 2L * 60L * 1000L;

    /** Intent extra read by the settings screen; the value is one of the {@code UPDATE_ACTION_*}. */
    public static final String EXTRA_UPDATE_ACTION =
            "com.akshaykadam.pixelboard.extension.extra.UPDATE_ACTION";
    /** Open settings and offer to download and install the update. */
    public static final String UPDATE_ACTION_INSTALL = "install";
    /** Open settings to show the update status. */
    public static final String UPDATE_ACTION_SHOW = "show";

    private GboardUpdateNotifier() {
    }

    public static boolean canPostNotifications(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.areNotificationsEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Posts the "update available" notification once per release tag. */
    static void notifyUpdateAvailable(Context context, SharedPreferences preferences,
            GboardUpdateRelease release) {
        if (release == null || release.tagName == null
                || release.tagName.equals(GboardUpdateSettings.readNotifiedTag(preferences))) {
            return;
        }
        // Without permission, leave the tag unmarked so a later check can still notify.
        if (!canPostNotifications(context)) {
            return;
        }
        String version = release.displayVersion();
        // A self-patched build cannot install the release itself, so it only points to it.
        boolean installable = release.hasApk() && GboardUpdateInstaller.isOfficialBuild(context);
        String text = GboardSettingsText.format(context, installable
                        ? R.string.gboard_patches_update_notification_text
                        : R.string.gboard_patches_update_notification_text_manual,
                version);
        Notification.Builder builder = builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(GboardSettingsText.get(
                        context, R.string.gboard_patches_update_notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(
                        release.name == null ? text : text + "\n" + release.name))
                .setContentIntent(settingsIntent(context,
                        installable ? UPDATE_ACTION_INSTALL : UPDATE_ACTION_SHOW))
                .setAutoCancel(true);
        if (post(context, NOTIFICATION_ID_UPDATE_AVAILABLE, builder)) {
            GboardUpdateSettings.writeNotifiedTag(preferences, release.tagName);
        }
    }

    static void showDownloadProgress(Context context, GboardUpdateRelease release, int percent) {
        if (!canPostNotifications(context)) {
            return;
        }
        post(context, NOTIFICATION_ID_PROGRESS, builder(context, PROGRESS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(GboardSettingsText.format(
                        context, R.string.gboard_patches_update_downloading_title,
                        release.displayVersion()))
                .setProgress(100, Math.max(0, percent), percent < 0)
                .setContentIntent(settingsIntent(context, UPDATE_ACTION_SHOW))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Each progress post renews this; it only fires if the process died mid-download.
                .setTimeoutAfter(PROGRESS_TIMEOUT_MS));
    }

    /** Used when the installer needs confirmation while the settings screen is not visible. */
    static void showReadyToInstall(Context context, PendingIntent confirmIntent) {
        if (!canPostNotifications(context)) {
            return;
        }
        post(context, NOTIFICATION_ID_INSTALL, builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(GboardSettingsText.get(
                        context, R.string.gboard_patches_update_ready_title))
                .setContentText(GboardSettingsText.get(
                        context, R.string.gboard_patches_update_ready_text))
                .setContentIntent(confirmIntent)
                .setAutoCancel(true));
    }

    static void showInstallFailed(Context context, String reason) {
        if (!canPostNotifications(context)) {
            return;
        }
        String text = reason == null ? "" : reason;
        post(context, NOTIFICATION_ID_INSTALL, builder(context)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(GboardSettingsText.get(
                        context, R.string.gboard_patches_update_failed_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(settingsIntent(context, UPDATE_ACTION_SHOW))
                .setAutoCancel(true));
    }

    /**
     * Removes a progress notification left by a process that died mid-download. Called once at
     * process start, before any download of this process can begin.
     */
    static void cancelStaleProgress(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || GboardUpdateInstaller.isBusy()) {
            return;
        }
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() == NOTIFICATION_ID_PROGRESS) {
                    manager.cancel(NOTIFICATION_ID_PROGRESS);
                }
            }
        } catch (Throwable ignored) {
            // Nothing to clean up.
        }
    }

    static void cancelUpdateAvailable(Context context) {
        cancel(context, NOTIFICATION_ID_UPDATE_AVAILABLE);
    }

    static void cancelInstall(Context context) {
        cancel(context, NOTIFICATION_ID_PROGRESS);
        cancel(context, NOTIFICATION_ID_INSTALL);
    }

    private static Notification.Builder builder(Context context) {
        return builder(context, CHANNEL_ID);
    }

    private static Notification.Builder builder(Context context, String channelId) {
        ensureChannels(context);
        return new Notification.Builder(context, channelId)
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(true);
    }

    private static void ensureChannels(Context context) {
        // Creating an existing channel only refreshes its name and description, which keeps
        // them in the current language; the user's importance choice is untouched.
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    GboardSettingsText.get(context, R.string.gboard_patches_update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(GboardSettingsText.get(
                    context, R.string.gboard_patches_update_channel_description));
            manager.createNotificationChannel(channel);
            // Progress updates must never make a sound.
            manager.createNotificationChannel(new NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    GboardSettingsText.get(
                            context, R.string.gboard_patches_update_progress_channel_name),
                    NotificationManager.IMPORTANCE_LOW));
        } catch (Throwable throwable) {
            GboardUpdateChecker.logWarning("[update-check] failed to create channels", throwable);
        }
    }

    private static PendingIntent settingsIntent(Context context, String updateAction) {
        Intent intent = new Intent(context, GboardPatchesSettingsActivity.class)
                .putExtra(EXTRA_UPDATE_ACTION, updateAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int requestCode = UPDATE_ACTION_INSTALL.equals(updateAction) ? 1 : 2;
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static boolean post(Context context, int id, Notification.Builder builder) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) {
                return false;
            }
            // An explicit group keeps Android from bundling this with Gboard's own
            // notifications; tapping such a bundle opens the launcher and dismisses ours.
            builder.setGroup(GROUP_KEY_PREFIX + id);
            manager.notify(id, builder.build());
            return true;
        } catch (Throwable throwable) {
            GboardUpdateChecker.logWarning("[update-check] failed to post notification", throwable);
            return false;
        }
    }

    private static void cancel(Context context, int id) {
        try {
            NotificationManager manager = context == null
                    ? null
                    : context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(id);
            }
        } catch (Throwable ignored) {
            // Nothing to clean up.
        }
    }
}
