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
import android.content.pm.PackageInfo;
import android.net.TrafficStats;
import android.os.Build;
import android.util.Log;
import com.akshaykadam.pixelboard.extension.BuildConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the repository's latest GitHub release against the installed patch bundle version.
 *
 * <p>Only extension code is involved (no Gboard bytecode hooks), so the check keeps working when
 * the Gboard base version changes.</p>
 */
public final class GboardUpdateChecker {
    private static final String TAG = "GboardPatches";
    static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    static final long RETRY_INTERVAL_MS = 3L * 60L * 60L * 1000L;
    static final String DEFAULT_REPOSITORY = "Akshayykadam/PixelBoard";
    static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final Pattern GITHUB_REPOSITORY_URL = Pattern.compile(
            "^https://github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final int TRAFFIC_STATS_TAG = 0x50420001;

    public static final String ERROR_NETWORK = "network";
    public static final String ERROR_RATE_LIMITED = "rate_limited";
    public static final String ERROR_NOT_FOUND = "not_found";
    public static final String ERROR_UNREADABLE = "unreadable";
    public static final String ERROR_HTTP_PREFIX = "http_";

    public enum Outcome {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        FAILED,
        BUSY,
        DISABLED
    }

    public static final class Result {
        public final Outcome outcome;
        public final GboardUpdateRelease release;
        public final String error;

        Result(Outcome outcome, GboardUpdateRelease release, String error) {
            this.outcome = outcome;
            this.release = release;
            this.error = error;
        }
    }

    private static final AtomicBoolean checking = new AtomicBoolean(false);

    private GboardUpdateChecker() {
    }

    public static boolean isChecking() {
        return checking.get();
    }

    /**
     * Fetches the latest release and records the result. Blocks on the network, so it must run
     * off the main thread. A background check only posts a notification once per release.
     */
    public static Result check(Context context, boolean userInitiated) {
        return check(context, userInitiated, GITHUB_API_BASE_URL);
    }

    static Result check(Context context, boolean userInitiated, String apiBaseUrl) {
        Context appContext = applicationContext(context);
        if (appContext == null) {
            return new Result(Outcome.FAILED, null, ERROR_NETWORK);
        }
        SharedPreferences preferences = GboardUpdateSettings.preferences(appContext);
        if (!userInitiated && !GboardUpdateSettings.readAutoCheckEnabled(preferences)) {
            return new Result(Outcome.DISABLED, null, null);
        }
        if (!checking.compareAndSet(false, true)) {
            return new Result(Outcome.BUSY, GboardUpdateSettings.readCachedRelease(preferences), null);
        }
        GboardUpdateUiListener.fire();
        try {
            return checkLocked(appContext, preferences, userInitiated, apiBaseUrl);
        } finally {
            checking.set(false);
            GboardUpdateUiListener.fire();
        }
    }

    private static Result checkLocked(Context context, SharedPreferences preferences,
            boolean userInitiated, String apiBaseUrl) {
        long now = System.currentTimeMillis();
        GboardUpdateSettings.writeAttempt(preferences, now);
        GboardUpdateRelease cached = GboardUpdateSettings.readCachedRelease(preferences);
        GboardUpdateRelease release;
        try {
            Response response = fetchLatestRelease(apiBaseUrl,
                    cached == null ? null : GboardUpdateSettings.readEtag(preferences));
            if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED && cached == null) {
                response = fetchLatestRelease(apiBaseUrl, null);
            }
            if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                release = cached;
            } else if (response.code == HttpURLConnection.HTTP_OK) {
                release = GboardUpdateReleaseParser.parse(response.body, Build.SUPPORTED_ABIS);
                if (release == null) {
                    return fail(preferences, ERROR_UNREADABLE);
                }
            } else if (response.code == HttpURLConnection.HTTP_NOT_FOUND
                    || response.code == HttpURLConnection.HTTP_GONE
                    || response.code == 451) {
                // No release is published any more (or the repository is gone): stop offering
                // the one seen earlier.
                GboardUpdateSettings.clearCachedRelease(preferences);
                GboardUpdateNotifier.cancelUpdateAvailable(context);
                return fail(preferences, ERROR_NOT_FOUND);
            } else if (response.code == 429 || (response.code == HttpURLConnection.HTTP_FORBIDDEN
                    && "0".equals(response.rateLimitRemaining))) {
                return fail(preferences, ERROR_RATE_LIMITED);
            } else {
                return fail(preferences, ERROR_HTTP_PREFIX + response.code);
            }
            GboardUpdateSettings.writeSuccess(preferences, now, release, response.etag);
        } catch (Throwable throwable) {
            logWarning("[update-check] request failed", throwable);
            return fail(preferences, ERROR_NETWORK);
        }

        if (isUpdateAvailable(release, installedVersion(), installedAtMillis(context))) {
            if (userInitiated) {
                GboardUpdateSettings.writeNotifiedTag(preferences, release.tagName);
            } else {
                GboardUpdateNotifier.notifyUpdateAvailable(context, preferences, release);
            }
            return new Result(Outcome.UPDATE_AVAILABLE, release, null);
        }
        GboardUpdateNotifier.cancelUpdateAvailable(context);
        return new Result(Outcome.UP_TO_DATE, release, null);
    }

    private static Result fail(SharedPreferences preferences, String error) {
        GboardUpdateSettings.writeFailure(preferences, error);
        return new Result(Outcome.FAILED, null, error);
    }

    /**
     * Decides whether {@code release} is newer than the installed build: the patch versions
     * decide. Only if the release does not name its patch version, being published after this
     * build was installed counts as newer.
     */
    static boolean isUpdateAvailable(GboardUpdateRelease release, String installedVersion,
            long installedAtMillis) {
        if (release == null) {
            return false;
        }
        if (release.patchVersion != null) {
            return GboardUpdateVersion.isNewer(release.patchVersion, installedVersion);
        }
        return release.publishedAtMillis > 0L && installedAtMillis > 0L
                && release.publishedAtMillis > installedAtMillis;
    }

    /** Whether a background check should run now. */
    static boolean isDue(SharedPreferences preferences, long nowMillis) {
        long lastSuccess = GboardUpdateSettings.readLastSuccessMillis(preferences);
        long lastAttempt = GboardUpdateSettings.readLastAttemptMillis(preferences);
        if (lastSuccess > nowMillis || lastAttempt > nowMillis) {
            // The clock moved backwards; do not wait for it to catch up.
            return true;
        }
        return nowMillis - lastSuccess >= CHECK_INTERVAL_MS
                && nowMillis - lastAttempt >= RETRY_INTERVAL_MS;
    }

    /** The cached latest release if it is newer than the installed build, else {@code null}. */
    public static GboardUpdateRelease availableUpdate(Context context) {
        Context appContext = applicationContext(context);
        if (appContext == null) {
            return null;
        }
        try {
            GboardUpdateRelease release = GboardUpdateSettings.readCachedRelease(
                    GboardUpdateSettings.preferences(appContext));
            return isUpdateAvailable(release, installedVersion(), installedAtMillis(appContext))
                    ? release
                    : null;
        } catch (Throwable throwable) {
            logWarning("[update-check] failed to read cached release", throwable);
            return null;
        }
    }

    public static String installedVersion() {
        String version = BuildConfig.PATCH_VERSION;
        return version == null ? "" : version.trim();
    }

    static long installedAtMillis(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo == null ? 0L : packageInfo.lastUpdateTime;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static String repository() {
        return repositoryFromUrl(BuildConfig.PATCH_REPOSITORY_URL);
    }

    static String repositoryFromUrl(String repositoryUrl) {
        if (repositoryUrl != null) {
            Matcher matcher = GITHUB_REPOSITORY_URL.matcher(repositoryUrl.trim());
            if (matcher.matches()) {
                return matcher.group(1) + "/" + matcher.group(2);
            }
        }
        return DEFAULT_REPOSITORY;
    }

    static String userAgent() {
        return "PixelBoard/" + installedVersion() + " (update-check; +https://github.com/"
                + repository() + ")";
    }

    private static final class Response {
        final int code;
        final String body;
        final String etag;
        final String rateLimitRemaining;

        Response(int code, String body, String etag, String rateLimitRemaining) {
            this.code = code;
            this.body = body;
            this.etag = etag;
            this.rateLimitRemaining = rateLimitRemaining;
        }
    }

    private static Response fetchLatestRelease(String apiBaseUrl, String etag) throws IOException {
        URL url = new URL(apiBaseUrl + "/repos/" + repository() + "/releases/latest");
        TrafficStats.setThreadStatsTag(TRAFFIC_STATS_TAG);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", userAgent());
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int code = connection.getResponseCode();
            String body = null;
            if (code == HttpURLConnection.HTTP_OK) {
                try (InputStream input = connection.getInputStream()) {
                    body = readBody(input);
                }
            }
            return new Response(
                    code,
                    body,
                    connection.getHeaderField("ETag"),
                    connection.getHeaderField("X-RateLimit-Remaining"));
        } finally {
            connection.disconnect();
            TrafficStats.clearThreadStatsTag();
        }
    }

    private static String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("Release response too large");
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    static Context applicationContext(Context context) {
        if (context == null) {
            return null;
        }
        Context appContext = context.getApplicationContext();
        return appContext != null ? appContext : context;
    }

    static void logWarning(String message, Throwable throwable) {
        try {
            Log.w(TAG, message, throwable);
        } catch (Throwable ignored) {
            // Logging must not affect Gboard.
        }
    }
}
