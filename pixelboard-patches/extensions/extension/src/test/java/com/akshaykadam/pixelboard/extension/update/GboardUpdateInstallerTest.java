package com.akshaykadam.pixelboard.extension.update;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class GboardUpdateInstallerTest {
    private static final byte[] APK_BYTES = "not really an APK, but bytes all the same"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private Application context;
    private GboardUpdateTestHttpServer server;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        GboardUpdateTestState.reset();
        server = new GboardUpdateTestHttpServer();
        server.respond(200, APK_BYTES, null);
    }

    @After
    public void tearDown() throws Exception {
        server.close();
        GboardUpdateTestState.reset();
    }

    @Test
    public void downloadStreamsTheVerifiedApkIntoTheSession() throws Exception {
        PackageInstaller.Session session = openSession();

        GboardUpdateInstaller.download(context, release(APK_BYTES.length, sha256(APK_BYTES)), session);

        Assert.assertEquals(100, GboardUpdateInstaller.progressPercent());
    }

    @Test
    public void downloadRejectsADigestMismatch() throws Exception {
        assertDownloadFails(release(APK_BYTES.length, sha256("other".getBytes())),
                GboardUpdateInstaller.ERROR_CORRUPT);
    }

    @Test
    public void downloadRejectsATruncatedOrOversizedFile() throws Exception {
        assertDownloadFails(release(APK_BYTES.length + 10L, null), GboardUpdateInstaller.ERROR_CORRUPT);
        assertDownloadFails(release(APK_BYTES.length - 10L, null), GboardUpdateInstaller.ERROR_CORRUPT);
    }

    @Test
    public void downloadReportsHttpErrors() throws Exception {
        server.respond(404, null, null);
        assertDownloadFails(release(APK_BYTES.length, null), GboardUpdateInstaller.ERROR_DOWNLOAD);
    }

    @Test
    public void ignoresStatusBroadcastsWithoutTheSessionNonce() throws Exception {
        setNonce("expected-nonce");
        GboardUpdateInstaller.setSettingsVisible(true);

        GboardUpdateInstaller.handleStatus(context,
                pendingUserAction(new Intent("evil.ACTION"), null));
        GboardUpdateInstaller.handleStatus(context,
                pendingUserAction(new Intent("evil.ACTION"), "guessed-nonce"));

        Assert.assertNull(Shadows.shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void opensTheSystemInstallPromptWhileSettingsAreVisible() throws Exception {
        setNonce("nonce");
        GboardUpdateInstaller.setSettingsVisible(true);
        Intent confirm = new Intent("android.content.pm.action.CONFIRM_INSTALL");

        GboardUpdateInstaller.handleStatus(context, pendingUserAction(confirm, "nonce"));

        Intent started = Shadows.shadowOf(context).getNextStartedActivity();
        Assert.assertNotNull(started);
        Assert.assertEquals("android.content.pm.action.CONFIRM_INSTALL", started.getAction());
        Assert.assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void postsATapToInstallNotificationFromTheBackground() throws Exception {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        setNonce("nonce");

        GboardUpdateInstaller.handleStatus(context,
                pendingUserAction(new Intent("android.content.pm.action.CONFIRM_INSTALL"), "nonce"));

        Assert.assertNull(Shadows.shadowOf(context).getNextStartedActivity());
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Assert.assertEquals(1, Shadows.shadowOf(manager).getAllNotifications().size());
        Assert.assertNotNull(Shadows.shadowOf(manager)
                .getNotification(GboardUpdateNotifier.NOTIFICATION_ID_INSTALL).contentIntent);
    }

    @Test
    public void aPromptThatCouldNotBeShownCanBeReopenedFromSettings() throws Exception {
        // Settings not visible and notifications denied: nothing can show the prompt yet.
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        setNonce("nonce");
        GboardUpdateTestState.setState(GboardUpdateInstaller.State.WAITING_FOR_CONFIRMATION);
        GboardUpdateInstaller.handleStatus(context,
                pendingUserAction(new Intent("android.content.pm.action.CONFIRM_INSTALL"), "nonce"));
        Assert.assertNull(Shadows.shadowOf(context).getNextStartedActivity());

        Assert.assertTrue(GboardUpdateInstaller.resumeConfirmation(context));

        Intent started = Shadows.shadowOf(context).getNextStartedActivity();
        Assert.assertEquals("android.content.pm.action.CONFIRM_INSTALL", started.getAction());
    }

    @Test
    public void ignoresStatusesOfOtherSessions() throws Exception {
        setNonce("nonce");
        GboardUpdateTestState.setActiveSession(7);

        GboardUpdateInstaller.handleStatus(context,
                status(PackageInstaller.STATUS_FAILURE_ABORTED, "nonce")
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, 6));
        Assert.assertEquals(GboardUpdateInstaller.State.IDLE, GboardUpdateInstaller.state());

        GboardUpdateTestState.setState(GboardUpdateInstaller.State.DOWNLOADING);
        GboardUpdateInstaller.handleStatus(context,
                status(PackageInstaller.STATUS_FAILURE_ABORTED, "nonce")
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, 6));
        Assert.assertEquals(GboardUpdateInstaller.State.DOWNLOADING, GboardUpdateInstaller.state());
    }

    @Test
    public void releaseSignerCheckHandlesUnsetAndMalformedCertificates() {
        Assert.assertTrue(GboardUpdateInstaller.matchesReleaseSigner(context, ""));
        Assert.assertTrue(GboardUpdateInstaller.matchesReleaseSigner(context, null));
        Assert.assertFalse(GboardUpdateInstaller.matchesReleaseSigner(context, "not-hex"));
        Assert.assertFalse(GboardUpdateInstaller.matchesReleaseSigner(context, "abc"));
    }

    @Test
    public void progressNeverSharesAnIdWithTheReadyNotification() throws Exception {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);

        GboardUpdateNotifier.showDownloadProgress(context, release(10L, null), 40);
        GboardUpdateNotifier.showReadyToInstall(context, null);
        Assert.assertEquals(2, Shadows.shadowOf(manager).getAllNotifications().size());

        GboardUpdateNotifier.cancelStaleProgress(context);
        Assert.assertNull(Shadows.shadowOf(manager)
                .getNotification(GboardUpdateNotifier.NOTIFICATION_ID_PROGRESS));
        Assert.assertNotNull(Shadows.shadowOf(manager)
                .getNotification(GboardUpdateNotifier.NOTIFICATION_ID_INSTALL));
    }

    @Test
    public void reportsSignatureConflictsAndForgetsTheNonce() throws Exception {
        setNonce("nonce");
        GboardUpdateInstaller.setSettingsVisible(true);

        GboardUpdateInstaller.handleStatus(context,
                status(PackageInstaller.STATUS_FAILURE_CONFLICT, "nonce"));

        Assert.assertEquals(GboardUpdateInstaller.State.FAILED, GboardUpdateInstaller.state());
        Assert.assertEquals(GboardUpdateInstaller.ERROR_INSTALL_CONFLICT,
                GboardUpdateInstaller.failureCode());

        GboardUpdateInstaller.handleStatus(context,
                pendingUserAction(new Intent("android.content.pm.action.CONFIRM_INSTALL"), "nonce"));
        Assert.assertNull(Shadows.shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void userCancellingTheInstallIsNotAnError() throws Exception {
        setNonce("nonce");

        GboardUpdateInstaller.handleStatus(context,
                status(PackageInstaller.STATUS_FAILURE_ABORTED, "nonce"));

        Assert.assertEquals(GboardUpdateInstaller.State.IDLE, GboardUpdateInstaller.state());
    }

    @Test
    public void unknownSourcesIntentTargetsThisPackage() {
        Intent intent = GboardUpdateInstaller.unknownAppSourcesSettingsIntent(context);
        Assert.assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                intent.getAction());
        Assert.assertEquals("package:" + context.getPackageName(), intent.getDataString());
    }

    private void assertDownloadFails(GboardUpdateRelease release, String expectedCode)
            throws Exception {
        try {
            GboardUpdateInstaller.download(context, release, openSession());
            Assert.fail("download should have failed with " + expectedCode);
        } catch (GboardUpdateInstaller.UpdateException exception) {
            Assert.assertEquals(expectedCode, exception.code);
        }
    }

    private PackageInstaller.Session openSession() throws IOException {
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        int sessionId = packageInstaller.createSession(new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        return packageInstaller.openSession(sessionId);
    }

    private GboardUpdateRelease release(long size, String sha256) {
        return new GboardUpdateRelease("v-test", "Test", null, "99.0.0", 0L, "PixelBoard.apk",
                server.baseUrl() + "/PixelBoard.apk",
                size, sha256);
    }

    private static Intent pendingUserAction(Intent confirmation, String nonce) {
        return status(PackageInstaller.STATUS_PENDING_USER_ACTION, nonce)
                .putExtra(Intent.EXTRA_INTENT, confirmation);
    }

    private static Intent status(int status, String nonce) {
        Intent intent = new Intent(GboardUpdateInstaller.ACTION_INSTALL_STATUS)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
        if (nonce != null) {
            intent.putExtra(GboardUpdateInstaller.EXTRA_NONCE, nonce);
        }
        return intent;
    }

    private static void setNonce(String nonce) throws Exception {
        GboardUpdateTestState.setNonce(nonce);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
