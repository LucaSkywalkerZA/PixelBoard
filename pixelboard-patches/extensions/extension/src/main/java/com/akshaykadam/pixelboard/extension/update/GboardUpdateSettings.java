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
import com.akshaykadam.pixelboard.extension.settings.GboardPatchesSettings;

/** Preferences for the GitHub release update check, kept in PixelBoard's settings file. */
public final class GboardUpdateSettings {
    public static final String PREF_KEY_AUTO_CHECK_ENABLED = "pref_update_auto_check_enabled";
    public static final boolean DEFAULT_AUTO_CHECK_ENABLED = true;

    static final String PREF_KEY_LAST_ATTEMPT_MS = "pref_update_last_attempt_ms";
    static final String PREF_KEY_LAST_SUCCESS_MS = "pref_update_last_success_ms";
    static final String PREF_KEY_LAST_ERROR = "pref_update_last_error";
    static final String PREF_KEY_ETAG = "pref_update_etag";
    static final String PREF_KEY_NOTIFIED_TAG = "pref_update_notified_tag";
    static final String PREF_KEY_NOTIFICATION_PERMISSION_ASKED =
            "pref_update_notification_permission_asked";

    static final String PREF_KEY_RELEASE_TAG = "pref_update_release_tag";
    static final String PREF_KEY_RELEASE_NAME = "pref_update_release_name";
    static final String PREF_KEY_RELEASE_URL = "pref_update_release_url";
    static final String PREF_KEY_RELEASE_PATCH_VERSION = "pref_update_release_patch_version";
    static final String PREF_KEY_RELEASE_PUBLISHED_MS = "pref_update_release_published_ms";
    static final String PREF_KEY_RELEASE_APK_NAME = "pref_update_release_apk_name";
    static final String PREF_KEY_RELEASE_APK_URL = "pref_update_release_apk_url";
    static final String PREF_KEY_RELEASE_APK_SIZE = "pref_update_release_apk_size";
    static final String PREF_KEY_RELEASE_APK_SHA256 = "pref_update_release_apk_sha256";

    private GboardUpdateSettings() {
    }

    public static SharedPreferences preferences(Context context) {
        return GboardPatchesSettings.preferences(context);
    }

    public static boolean readAutoCheckEnabled(SharedPreferences preferences) {
        if (preferences == null) {
            return DEFAULT_AUTO_CHECK_ENABLED;
        }
        return preferences.getBoolean(PREF_KEY_AUTO_CHECK_ENABLED, DEFAULT_AUTO_CHECK_ENABLED);
    }

    public static boolean writeAutoCheckEnabled(SharedPreferences preferences, boolean enabled) {
        if (preferences == null) {
            return false;
        }
        return preferences.edit().putBoolean(PREF_KEY_AUTO_CHECK_ENABLED, enabled).commit();
    }

    public static long readLastAttemptMillis(SharedPreferences preferences) {
        return preferences == null ? 0L : preferences.getLong(PREF_KEY_LAST_ATTEMPT_MS, 0L);
    }

    public static long readLastSuccessMillis(SharedPreferences preferences) {
        return preferences == null ? 0L : preferences.getLong(PREF_KEY_LAST_SUCCESS_MS, 0L);
    }

    /** Short failure reason of the last check, or {@code null} if it succeeded. */
    public static String readLastError(SharedPreferences preferences) {
        return preferences == null ? null : preferences.getString(PREF_KEY_LAST_ERROR, null);
    }

    static void writeAttempt(SharedPreferences preferences, long nowMillis) {
        preferences.edit().putLong(PREF_KEY_LAST_ATTEMPT_MS, nowMillis).commit();
    }

    static void writeFailure(SharedPreferences preferences, String error) {
        preferences.edit().putString(PREF_KEY_LAST_ERROR, error).commit();
    }

    static String readEtag(SharedPreferences preferences) {
        return preferences.getString(PREF_KEY_ETAG, null);
    }

    static void writeSuccess(SharedPreferences preferences, long nowMillis,
            GboardUpdateRelease release, String etag) {
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(PREF_KEY_LAST_SUCCESS_MS, nowMillis)
                .remove(PREF_KEY_LAST_ERROR);
        if (release != null) {
            editor.putString(PREF_KEY_RELEASE_TAG, release.tagName)
                    .putString(PREF_KEY_RELEASE_NAME, release.name)
                    .putString(PREF_KEY_RELEASE_URL, release.htmlUrl)
                    .putString(PREF_KEY_RELEASE_PATCH_VERSION, release.patchVersion)
                    .putLong(PREF_KEY_RELEASE_PUBLISHED_MS, release.publishedAtMillis)
                    .putString(PREF_KEY_RELEASE_APK_NAME, release.apkName)
                    .putString(PREF_KEY_RELEASE_APK_URL, release.apkUrl)
                    .putLong(PREF_KEY_RELEASE_APK_SIZE, release.apkSize)
                    .putString(PREF_KEY_RELEASE_APK_SHA256, release.apkSha256);
            if (etag == null) {
                editor.remove(PREF_KEY_ETAG);
            } else {
                editor.putString(PREF_KEY_ETAG, etag);
            }
        }
        editor.commit();
    }

    /** The release seen by the last successful check, or {@code null}. */
    public static GboardUpdateRelease readCachedRelease(SharedPreferences preferences) {
        if (preferences == null) {
            return null;
        }
        String tagName = preferences.getString(PREF_KEY_RELEASE_TAG, null);
        if (tagName == null || tagName.isEmpty()) {
            return null;
        }
        return new GboardUpdateRelease(
                tagName,
                preferences.getString(PREF_KEY_RELEASE_NAME, null),
                preferences.getString(PREF_KEY_RELEASE_URL, null),
                preferences.getString(PREF_KEY_RELEASE_PATCH_VERSION, null),
                preferences.getLong(PREF_KEY_RELEASE_PUBLISHED_MS, 0L),
                preferences.getString(PREF_KEY_RELEASE_APK_NAME, null),
                preferences.getString(PREF_KEY_RELEASE_APK_URL, null),
                preferences.getLong(PREF_KEY_RELEASE_APK_SIZE, -1L),
                preferences.getString(PREF_KEY_RELEASE_APK_SHA256, null));
    }

    static void clearCachedRelease(SharedPreferences preferences) {
        preferences.edit()
                .remove(PREF_KEY_RELEASE_TAG)
                .remove(PREF_KEY_RELEASE_NAME)
                .remove(PREF_KEY_RELEASE_URL)
                .remove(PREF_KEY_RELEASE_PATCH_VERSION)
                .remove(PREF_KEY_RELEASE_PUBLISHED_MS)
                .remove(PREF_KEY_RELEASE_APK_NAME)
                .remove(PREF_KEY_RELEASE_APK_URL)
                .remove(PREF_KEY_RELEASE_APK_SIZE)
                .remove(PREF_KEY_RELEASE_APK_SHA256)
                .remove(PREF_KEY_ETAG)
                .commit();
    }

    static String readNotifiedTag(SharedPreferences preferences) {
        return preferences.getString(PREF_KEY_NOTIFIED_TAG, null);
    }

    static void writeNotifiedTag(SharedPreferences preferences, String tagName) {
        preferences.edit().putString(PREF_KEY_NOTIFIED_TAG, tagName).commit();
    }

    public static boolean readNotificationPermissionAsked(SharedPreferences preferences) {
        return preferences != null
                && preferences.getBoolean(PREF_KEY_NOTIFICATION_PERMISSION_ASKED, false);
    }

    public static void writeNotificationPermissionAsked(SharedPreferences preferences) {
        if (preferences != null) {
            preferences.edit().putBoolean(PREF_KEY_NOTIFICATION_PERMISSION_ASKED, true).apply();
        }
    }
}
