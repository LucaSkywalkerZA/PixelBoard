/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.akshaykadam.pixelboard.extension.BuildConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the release APK straight into a {@link PackageInstaller} session and asks Android to
 * install it over the running app.
 *
 * <p>Streaming into the session needs no storage permission or {@code FileProvider}. The download
 * is checked against the SHA-256 digest GitHub publishes for the asset, and Android itself refuses
 * an update signed with a different key. The install always ends with the system confirmation
 * screen.</p>
 */
public final class GboardUpdateInstaller {
    public enum State {
        IDLE,
        PREPARING,
        DOWNLOADING,
        WAITING_FOR_CONFIRMATION,
        FAILED
    }

    public static final String ERROR_UP_TO_DATE = "up_to_date";
    public static final String ERROR_NO_APK = "no_apk";
    public static final String ERROR_DOWNLOAD = "download";
    public static final String ERROR_CORRUPT = "corrupt";
    public static final String ERROR_INSTALL = "install";
    public static final String ERROR_INSTALL_CONFLICT = "install_conflict";
    public static final String ERROR_INSTALL_STORAGE = "install_storage";
    public static final String ERROR_SIGNATURE = "signature";

    static final String ACTION_INSTALL_STATUS =
            "com.akshaykadam.pixelboard.extension.update.action.INSTALL_STATUS";
    static final String EXTRA_NONCE =
            "com.akshaykadam.pixelboard.extension.update.extra.INSTALL_NONCE";
    private static final String SESSION_APK_NAME = "base.apk";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long PROGRESS_INTERVAL_MS = 1000L;
    /** Re-post at least this often so the progress notification's timeout never fires early. */
    private static final long PROGRESS_KEEPALIVE_MS = 30_000L;
    private static final int CONFIRM_REQUEST_CODE = 3;

    private static volatile State state = State.IDLE;
    private static volatile String failureCode;
    private static volatile long bytesDownloaded;
    private static volatile long bytesTotal = -1L;
    private static volatile String expectedNonce;
    private static volatile int activeSessionId = -1;
    private static volatile Intent pendingConfirmation;
    private static volatile Boolean officialSignature;
    private static volatile boolean settingsVisible;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final Object receiverLock = new Object();
    private static boolean receiverRegistered;
    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "GboardPatchesUpdateInstall");
                thread.setDaemon(true);
                return thread;
            });

    private static final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                handleStatus(context, intent);
            } catch (Throwable throwable) {
                GboardUpdateChecker.logWarning("[update-install] status handling failed", throwable);
            }
        }
    };

    private GboardUpdateInstaller() {
    }

    public static State state() {
        return state;
    }

    public static String failureCode() {
        return failureCode;
    }

    public static boolean isBusy() {
        State current = state;
        return current == State.PREPARING || current == State.DOWNLOADING;
    }

    /** Download progress in percent, or {@code -1} while the size is unknown. */
    public static int progressPercent() {
        long total = bytesTotal;
        if (total <= 0L) {
            return -1;
        }
        return (int) Math.min(100L, bytesDownloaded * 100L / total);
    }

    /**
     * Re-opens the system install prompt of the committed session, for when the user left it
     * or it could not be shown. Call it from a foreground Activity.
     */
    public static boolean resumeConfirmation(Context activityContext) {
        Intent confirmation = pendingConfirmation;
        if (state != State.WAITING_FOR_CONFIRMATION || confirmation == null
                || activityContext == null) {
            return false;
        }
        try {
            activityContext.startActivity(
                    new Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable throwable) {
            GboardUpdateChecker.logWarning("[update-install] failed to reopen installer", throwable);
            return false;
        }
    }

    /**
     * Whether this build is signed with the key of the GitHub release APKs, so a release can
     * replace it. Self-patched builds (Morphe Manager, build.sh) use another key, and Android
     * would only refuse the download after the user confirmed it.
     */
    public static boolean isOfficialBuild(Context context) {
        Boolean cached = officialSignature;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean official = matchesReleaseSigner(context, BuildConfig.RELEASE_SIGNING_CERT_SHA256);
        officialSignature = Boolean.valueOf(official);
        return official;
    }

    static boolean matchesReleaseSigner(Context context, String certSha256) {
        if (certSha256 == null || certSha256.trim().isEmpty()) {
            return true;
        }
        byte[] expected = fromHex(certSha256.trim());
        if (expected == null || context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return true;
        }
        try {
            return context.getPackageManager().hasSigningCertificate(
                    context.getPackageName(), expected, PackageManager.CERT_INPUT_SHA256);
        } catch (Throwable throwable) {
            // Leave the decision to Android's own check at install time.
            return true;
        }
    }

    /** Tells the installer whether the settings screen is in the foreground. */
    public static void setSettingsVisible(boolean visible) {
        settingsVisible = visible;
    }

    /** Whether the user has allowed PixelBoard to install apps ("Install unknown apps"). */
    public static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Intent unknownAppSourcesSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * Fetches the latest release, downloads its APK and starts the install on a worker thread.
     * Returns {@code false} if an install is already being prepared.
     */
    public static boolean start(Context context) {
        Context appContext = GboardUpdateChecker.applicationContext(context);
        if (appContext == null || !running.compareAndSet(false, true)) {
            return false;
        }
        failureCode = null;
        bytesDownloaded = 0L;
        bytesTotal = -1L;
        // Sessions abandoned by run() report ABORTED; this keeps those reports from matching.
        expectedNonce = null;
        activeSessionId = -1;
        pendingConfirmation = null;
        setState(State.PREPARING);
        try {
            executor.execute(() -> {
                try {
                    run(appContext);
                } catch (UpdateException exception) {
                    fail(appContext, exception.code, exception.getMessage());
                } catch (Throwable throwable) {
                    fail(appContext, ERROR_DOWNLOAD, String.valueOf(throwable));
                } finally {
                    running.set(false);
                    GboardUpdateUiListener.fire();
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            running.set(false);
            setState(State.IDLE);
            return false;
        }
    }

    private static void run(Context context) throws Exception {
        if (!isOfficialBuild(context)) {
            throw new UpdateException(ERROR_SIGNATURE, "not signed with the release key");
        }
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        abandonOwnSessions(packageInstaller);

        GboardUpdateChecker.Result result = GboardUpdateChecker.check(context, true);
        GboardUpdateRelease release;
        if (result.outcome == GboardUpdateChecker.Outcome.UPDATE_AVAILABLE) {
            release = result.release;
        } else if (result.outcome == GboardUpdateChecker.Outcome.UP_TO_DATE) {
            throw new UpdateException(ERROR_UP_TO_DATE, "already on the latest release");
        } else {
            // GitHub's API could not be reached; fall back to the release seen earlier.
            release = GboardUpdateChecker.availableUpdate(context);
            if (release == null) {
                throw new UpdateException(
                        result.error == null ? ERROR_DOWNLOAD : result.error, "check failed");
            }
        }
        if (!release.hasApk()) {
            throw new UpdateException(ERROR_NO_APK, "release has no APK asset");
        }

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (release.apkSize > 0L) {
            params.setSize(release.apkSize);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        int sessionId = packageInstaller.createSession(params);
        activeSessionId = sessionId;
        boolean committed = false;
        try (PackageInstaller.Session session = packageInstaller.openSession(sessionId)) {
            setState(State.DOWNLOADING);
            download(context, release, session);
            String nonce = UUID.randomUUID().toString();
            expectedNonce = nonce;
            registerStatusReceiver(context);
            Intent statusIntent = new Intent(ACTION_INSTALL_STATUS)
                    .setPackage(context.getPackageName())
                    .putExtra(EXTRA_NONCE, nonce);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The installer adds the status extras, so the intent must stay mutable.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent statusCallback =
                    PendingIntent.getBroadcast(context, sessionId, statusIntent, flags);
            setState(State.WAITING_FOR_CONFIRMATION);
            GboardUpdateNotifier.cancelInstall(context);
            session.commit(statusCallback.getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                try {
                    packageInstaller.abandonSession(sessionId);
                } catch (Throwable ignored) {
                    // The session may already be gone.
                }
            }
        }
    }

    /** Streams the APK into {@code session} and verifies its size and SHA-256 digest. */
    static void download(Context context, GboardUpdateRelease release,
            PackageInstaller.Session session) throws Exception {
        TrafficStats.setThreadStatsTag(GboardUpdateChecker.TRAFFIC_STATS_TAG);
        HttpURLConnection connection =
                (HttpURLConnection) new URL(release.apkUrl).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", GboardUpdateChecker.userAgent());
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new UpdateException(ERROR_DOWNLOAD, "HTTP " + code);
            }
            long expected = release.apkSize > 0L ? release.apkSize : connection.getContentLengthLong();
            bytesTotal = expected > 0L ? expected : -1L;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0L;
            long lastProgressAt = 0L;
            int lastPercent = -1;
            GboardUpdateNotifier.showDownloadProgress(context, release, progressPercent());
            try (InputStream input = connection.getInputStream();
                    OutputStream output = session.openWrite(
                            SESSION_APK_NAME, 0L, expected > 0L ? expected : -1L)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (expected > 0L && written + read > expected) {
                        throw new UpdateException(ERROR_CORRUPT, "download larger than expected");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    written += read;
                    bytesDownloaded = written;
                    long now = System.currentTimeMillis();
                    int percent = progressPercent();
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS && (percent != lastPercent
                            || now - lastProgressAt >= PROGRESS_KEEPALIVE_MS)) {
                        lastProgressAt = now;
                        lastPercent = percent;
                        GboardUpdateNotifier.showDownloadProgress(context, release, percent);
                        GboardUpdateUiListener.fire();
                    }
                }
                session.fsync(output);
            }
            if (expected > 0L && written != expected) {
                throw new UpdateException(ERROR_CORRUPT, "download incomplete");
            }
            String sha256 = toHex(digest.digest());
            if (release.apkSha256 != null && !release.apkSha256.equals(sha256)) {
                throw new UpdateException(ERROR_CORRUPT, "SHA-256 mismatch");
            }
        } catch (IOException exception) {
            throw new UpdateException(ERROR_DOWNLOAD, String.valueOf(exception));
        } finally {
            connection.disconnect();
            TrafficStats.clearThreadStatsTag();
        }
    }

    static void handleStatus(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        String nonce = expectedNonce;
        // Only the installer holds the PendingIntent carrying this nonce, so other apps cannot
        // forge a status or make PixelBoard launch an intent of their choosing.
        if (nonce == null || !nonce.equals(intent.getStringExtra(EXTRA_NONCE))) {
            return;
        }
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (sessionId != -1 && sessionId != activeSessionId) {
            return;
        }
        Context appContext = GboardUpdateChecker.applicationContext(context);
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirmation = confirmationIntent(intent);
                pendingConfirmation = confirmation;
                if (confirmation == null) {
                    expectedNonce = null;
                    fail(appContext, ERROR_INSTALL, "missing confirmation intent");
                } else {
                    promptForConfirmation(appContext, confirmation);
                }
                return;
            case PackageInstaller.STATUS_SUCCESS:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // Usually the process is replaced before a success arrives. Aborted means the
                // user declined, which needs no error.
                expectedNonce = null;
                pendingConfirmation = null;
                GboardUpdateNotifier.cancelInstall(appContext);
                setState(State.IDLE);
                return;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                expectedNonce = null;
                fail(appContext, ERROR_INSTALL_CONFLICT, statusMessage(intent));
                return;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                expectedNonce = null;
                fail(appContext, ERROR_INSTALL_STORAGE, statusMessage(intent));
                return;
            default:
                expectedNonce = null;
                fail(appContext, ERROR_INSTALL, statusMessage(intent));
        }
    }

    private static void promptForConfirmation(Context context, Intent confirmation) {
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Without notification permission the prompt waits for the settings screen, whose
        // install row calls resumeConfirmation().
        if (settingsVisible) {
            try {
                context.startActivity(confirmation);
                return;
            } catch (Throwable throwable) {
                GboardUpdateChecker.logWarning("[update-install] failed to open installer", throwable);
            }
        }
        // Android blocks activity starts from the background, so let the user tap instead.
        PendingIntent pendingIntent = PendingIntent.getActivity(context, CONFIRM_REQUEST_CODE,
                confirmation, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        GboardUpdateNotifier.showReadyToInstall(context, pendingIntent);
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmationIntent(Intent statusIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return statusIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        Object extra = statusIntent.getParcelableExtra(Intent.EXTRA_INTENT);
        return extra instanceof Intent ? (Intent) extra : null;
    }

    private static String statusMessage(Intent intent) {
        return intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
    }

    private static void registerStatusReceiver(Context context) {
        synchronized (receiverLock) {
            if (receiverRegistered) {
                return;
            }
            IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(statusReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    private static void abandonOwnSessions(PackageInstaller packageInstaller) {
        try {
            for (PackageInstaller.SessionInfo info : packageInstaller.getMySessions()) {
                try {
                    packageInstaller.abandonSession(info.getSessionId());
                } catch (Throwable ignored) {
                    // Already committed or finished.
                }
            }
        } catch (Throwable ignored) {
            // Nothing to clean up.
        }
    }

    private static void fail(Context context, String code, String detail) {
        failureCode = code;
        pendingConfirmation = null;
        GboardUpdateChecker.logWarning("[update-install] " + code + ": " + detail, null);
        GboardUpdateNotifier.cancelInstall(context);
        if (ERROR_UP_TO_DATE.equals(code)) {
            setState(State.IDLE);
            return;
        }
        if (!settingsVisible) {
            GboardUpdateNotifier.showInstallFailed(
                    context, GboardUpdateSettingsSection.errorText(context, code));
        }
        setState(State.FAILED);
    }

    private static void setState(State newState) {
        state = newState;
        GboardUpdateUiListener.fire();
    }

    private static byte[] fromHex(String hex) {
        String digits = hex.replace(":", "");
        if (digits.length() % 2 != 0) {
            return null;
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(digits.charAt(index * 2), 16);
            int low = Character.digit(digits.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    static final class UpdateException extends Exception {
        final String code;

        UpdateException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
