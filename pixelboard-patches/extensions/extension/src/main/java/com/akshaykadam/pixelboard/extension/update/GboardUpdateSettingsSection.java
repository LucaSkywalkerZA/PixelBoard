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
import android.text.format.DateFormat;
import android.text.format.Formatter;
import com.akshaykadam.pixelboard.extension.R;
import com.akshaykadam.pixelboard.extension.settings.GboardPatchesSettingsContract;
import com.akshaykadam.pixelboard.extension.settings.GboardSettingsText;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds the "Updates" section of the PixelBoard settings screen. */
public final class GboardUpdateSettingsSection {
    public interface Actions {
        void checkNow();

        void install();

        void openReleasePage(String url);

        void requestNotificationPermission();

        void setAutoCheckEnabled(boolean enabled);
    }

    private GboardUpdateSettingsSection() {
    }

    public static GboardPatchesSettingsContract.Section build(Context context,
            SharedPreferences preferences, GboardUpdateRelease availableUpdate, Actions actions,
            long nowMillis) {
        List<GboardPatchesSettingsContract.Row> rows =
                new ArrayList<GboardPatchesSettingsContract.Row>();
        boolean autoCheckEnabled = GboardUpdateSettings.readAutoCheckEnabled(preferences);

        if (availableUpdate != null) {
            boolean official = GboardUpdateInstaller.isOfficialBuild(context);
            if (availableUpdate.hasApk() && official) {
                GboardUpdateInstaller.State installState = GboardUpdateInstaller.state();
                rows.add(new GboardPatchesSettingsContract.CommandRow(
                        GboardSettingsText.get(context, R.string.gboard_patches_update_install_title),
                        installSummary(context, availableUpdate, installState),
                        !GboardUpdateInstaller.isBusy(),
                        actions::install));
            } else if (availableUpdate.htmlUrl != null) {
                rows.add(new GboardPatchesSettingsContract.CommandRow(
                        GboardSettingsText.get(context,
                                R.string.gboard_patches_update_open_release_title),
                        GboardSettingsText.get(context, official
                                ? R.string.gboard_patches_update_open_release_summary
                                : R.string.gboard_patches_update_self_signed_summary),
                        true,
                        () -> actions.openReleasePage(availableUpdate.htmlUrl)));
            }
            if (availableUpdate.htmlUrl != null) {
                rows.add(new GboardPatchesSettingsContract.CommandRow(
                        GboardSettingsText.get(context,
                                R.string.gboard_patches_update_release_notes_title),
                        availableUpdate.name != null ? availableUpdate.name : availableUpdate.tagName,
                        true,
                        () -> actions.openReleasePage(availableUpdate.htmlUrl)));
            }
        }

        rows.add(new GboardPatchesSettingsContract.ToggleRow(
                GboardSettingsText.get(context, R.string.gboard_patches_update_auto_check_title),
                GboardSettingsText.get(context, R.string.gboard_patches_update_auto_check_summary),
                true,
                autoCheckEnabled,
                actions::setAutoCheckEnabled));

        boolean checking = GboardUpdateChecker.isChecking();
        rows.add(new GboardPatchesSettingsContract.CommandRow(
                GboardSettingsText.get(context, R.string.gboard_patches_update_check_now_title),
                checkSummary(context, preferences, availableUpdate, checking, nowMillis),
                !checking,
                actions::checkNow));

        if (autoCheckEnabled
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !GboardUpdateNotifier.canPostNotifications(context)) {
            rows.add(new GboardPatchesSettingsContract.CommandRow(
                    GboardSettingsText.get(context,
                            R.string.gboard_patches_update_notifications_title),
                    GboardSettingsText.get(context,
                            R.string.gboard_patches_update_notifications_summary),
                    true,
                    actions::requestNotificationPermission));
        }

        return new GboardPatchesSettingsContract.Section(
                GboardSettingsText.get(context, R.string.gboard_patches_section_updates),
                rows);
    }

    static String installSummary(Context context, GboardUpdateRelease release,
            GboardUpdateInstaller.State installState) {
        switch (installState) {
            case PREPARING:
                return GboardSettingsText.get(context,
                        R.string.gboard_patches_update_install_preparing);
            case DOWNLOADING:
                int percent = GboardUpdateInstaller.progressPercent();
                if (percent < 0) {
                    return GboardSettingsText.get(context,
                            R.string.gboard_patches_update_install_preparing);
                }
                return GboardSettingsText.format(context,
                        R.string.gboard_patches_update_install_downloading,
                        NumberFormat.getPercentInstance(GboardSettingsText.textLocale(context,
                                R.string.gboard_patches_update_install_downloading))
                                .format(percent / 100.0));
            case WAITING_FOR_CONFIRMATION:
                return GboardSettingsText.get(context,
                        R.string.gboard_patches_update_install_waiting);
            case FAILED:
                String failureCode = GboardUpdateInstaller.failureCode();
                if (!isRetryable(failureCode)) {
                    return errorText(context, failureCode);
                }
                return GboardSettingsText.format(context,
                        R.string.gboard_patches_update_error_with_retry,
                        errorText(context, failureCode),
                        GboardSettingsText.get(context,
                                R.string.gboard_patches_update_tap_to_retry));
            case IDLE:
            default:
                if (release.apkSize > 0L) {
                    return GboardSettingsText.format(context,
                            R.string.gboard_patches_update_install_summary,
                            release.displayVersion(),
                            Formatter.formatShortFileSize(context, release.apkSize));
                }
                return GboardSettingsText.format(context,
                        R.string.gboard_patches_update_install_summary_no_size,
                        release.displayVersion());
        }
    }

    static String checkSummary(Context context, SharedPreferences preferences,
            GboardUpdateRelease availableUpdate, boolean checking, long nowMillis) {
        if (checking) {
            return GboardSettingsText.get(context, R.string.gboard_patches_update_checking);
        }
        long lastAttempt = GboardUpdateSettings.readLastAttemptMillis(preferences);
        long lastSuccess = GboardUpdateSettings.readLastSuccessMillis(preferences);
        String lastError = GboardUpdateSettings.readLastError(preferences);
        if (lastError != null && lastAttempt > lastSuccess) {
            return errorText(context, lastError);
        }
        if (availableUpdate != null) {
            return GboardSettingsText.format(context,
                    R.string.gboard_patches_update_check_available,
                    availableUpdate.displayVersion());
        }
        if (lastSuccess > 0L) {
            return GboardSettingsText.format(context,
                    R.string.gboard_patches_update_check_up_to_date,
                    GboardUpdateChecker.installedVersion(),
                    formatTime(context, lastSuccess, nowMillis));
        }
        return GboardSettingsText.format(context,
                R.string.gboard_patches_update_check_never,
                GboardUpdateChecker.installedVersion());
    }

    /** Retrying cannot help when the key, the release or the version is the problem. */
    static boolean isRetryable(String code) {
        return !GboardUpdateInstaller.ERROR_INSTALL_CONFLICT.equals(code)
                && !GboardUpdateInstaller.ERROR_SIGNATURE.equals(code)
                && !GboardUpdateInstaller.ERROR_NO_APK.equals(code)
                && !GboardUpdateInstaller.ERROR_UP_TO_DATE.equals(code);
    }

    /**
     * Date and time in the language of the surrounding text, following the 12/24-hour setting;
     * the year is shown only when it is not the current one.
     */
    static String formatTime(Context context, long millis, long nowMillis) {
        Locale locale = GboardSettingsText.textLocale(
                context, R.string.gboard_patches_update_check_up_to_date);
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMillis);
        String skeleton = (then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "" : "y")
                + "dMMM" + (DateFormat.is24HourFormat(context) ? "Hmm" : "hmm");
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(new Date(millis));
    }

    /** A full sentence describing a check or install failure code. */
    public static String errorText(Context context, String code) {
        int resId;
        if (GboardUpdateChecker.ERROR_RATE_LIMITED.equals(code)) {
            resId = R.string.gboard_patches_update_error_rate_limited;
        } else if (GboardUpdateChecker.ERROR_NOT_FOUND.equals(code)) {
            resId = R.string.gboard_patches_update_error_not_found;
        } else if (GboardUpdateChecker.ERROR_UNREADABLE.equals(code)
                || (code != null && code.startsWith(GboardUpdateChecker.ERROR_HTTP_PREFIX))) {
            resId = R.string.gboard_patches_update_error_unreadable;
        } else if (GboardUpdateInstaller.ERROR_UP_TO_DATE.equals(code)) {
            resId = R.string.gboard_patches_update_error_up_to_date;
        } else if (GboardUpdateInstaller.ERROR_NO_APK.equals(code)) {
            resId = R.string.gboard_patches_update_error_no_apk;
        } else if (GboardUpdateInstaller.ERROR_DOWNLOAD.equals(code)) {
            resId = R.string.gboard_patches_update_error_download;
        } else if (GboardUpdateInstaller.ERROR_CORRUPT.equals(code)) {
            resId = R.string.gboard_patches_update_error_corrupt;
        } else if (GboardUpdateInstaller.ERROR_INSTALL_CONFLICT.equals(code)) {
            resId = R.string.gboard_patches_update_error_conflict;
        } else if (GboardUpdateInstaller.ERROR_INSTALL_STORAGE.equals(code)) {
            resId = R.string.gboard_patches_update_error_storage;
        } else if (GboardUpdateInstaller.ERROR_SIGNATURE.equals(code)) {
            resId = R.string.gboard_patches_update_self_signed_summary;
        } else if (GboardUpdateInstaller.ERROR_INSTALL.equals(code)) {
            resId = R.string.gboard_patches_update_error_install;
        } else {
            resId = R.string.gboard_patches_update_error_network;
        }
        return GboardSettingsText.get(context, resId);
    }
}
