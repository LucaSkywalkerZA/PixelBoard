package com.akshaykadam.pixelboard.extension.update;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import com.akshaykadam.pixelboard.extension.BuildConfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public final class GboardUpdateCheckerTest {
    private static final long HOUR = 60L * 60L * 1000L;
    private static final String NEWER_RELEASE_JSON = GboardUpdateReleaseParserTest.V7_RELEASE_JSON
            .replace("Patch v1.2.0", "Patch v99.0.0")
            .replace("patches-1.2.0.mpp", "patches-99.0.0.mpp")
            .replace("v18.4.1-Stable-V7", "v18.4.1-Stable-V99");

    private Application context;
    private SharedPreferences preferences;
    private GboardUpdateTestHttpServer server;
    private String rateLimitRemaining;

    @Before
    public void setUp() throws Exception {
        GboardUpdateTestState.reset();
        context = RuntimeEnvironment.getApplication();
        preferences = GboardUpdateSettings.preferences(context);
        preferences.edit().clear().commit();
        // Robolectric's own install time must not influence the date fallback.
        Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName()).lastUpdateTime = 0L;
        server = new GboardUpdateTestHttpServer("User-Agent", "Accept", "If-None-Match");
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    @Test
    public void decidesUpdatesByPatchVersionNotTag() {
        Assert.assertFalse(GboardUpdateChecker.isUpdateAvailable(null, "1.2.0", 0L));
        Assert.assertTrue(GboardUpdateChecker.isUpdateAvailable(release("1.3.0", 0L), "1.2.0", 0L));
        Assert.assertFalse(GboardUpdateChecker.isUpdateAvailable(release("1.2.0", 0L), "1.2.0", 0L));
        Assert.assertFalse(GboardUpdateChecker.isUpdateAvailable(release("1.1.0", 0L), "1.2.0", 0L));
        Assert.assertTrue(GboardUpdateChecker.isUpdateAvailable(
                release("1.3.0", 10 * HOUR), "1.2.0", 5 * HOUR));
    }

    @Test
    public void offersANewerVersionEvenIfAnOlderBuildWasInstalledAfterItsRelease() {
        // For example someone installs V6 from an old link after V7 is out.
        Assert.assertTrue(GboardUpdateChecker.isUpdateAvailable(
                release("1.3.0", 5 * HOUR), "1.2.0", 6 * HOUR));
    }

    @Test
    public void fallsBackToPublishDateWhenTheReleaseHasNoPatchVersion() {
        Assert.assertTrue(GboardUpdateChecker.isUpdateAvailable(
                release(null, 10 * HOUR), "1.2.0", 5 * HOUR));
        Assert.assertFalse(GboardUpdateChecker.isUpdateAvailable(
                release(null, 5 * HOUR), "1.2.0", 10 * HOUR));
        Assert.assertFalse(GboardUpdateChecker.isUpdateAvailable(release(null, 0L), "1.2.0", 0L));
        Assert.assertFalse(GboardUpdateChecker.isUpdateAvailable(
                release(null, 10 * HOUR), "1.2.0", 0L));
    }

    @Test
    public void checksOncePerDayAndBacksOffAfterFailures() {
        long now = 1000L * HOUR;
        Assert.assertTrue(GboardUpdateChecker.isDue(preferences, now));

        setTimes(now - HOUR, now - HOUR);
        Assert.assertFalse(GboardUpdateChecker.isDue(preferences, now));

        setTimes(now - 25 * HOUR, now - 25 * HOUR);
        Assert.assertTrue(GboardUpdateChecker.isDue(preferences, now));

        setTimes(now - 25 * HOUR, now - HOUR);
        Assert.assertFalse(GboardUpdateChecker.isDue(preferences, now));

        setTimes(now - 25 * HOUR, now - 4 * HOUR);
        Assert.assertTrue(GboardUpdateChecker.isDue(preferences, now));

        setTimes(now + HOUR, now + HOUR);
        Assert.assertTrue(GboardUpdateChecker.isDue(preferences, now));
    }

    @Test
    public void derivesTheRepositoryFromTheBuildConfig() {
        Assert.assertEquals("Akshayykadam/PixelBoard", GboardUpdateChecker.repository());
        Assert.assertEquals("Akshayykadam/PixelBoard",
                GboardUpdateChecker.repositoryFromUrl(BuildConfig.PATCH_REPOSITORY_URL));
        Assert.assertEquals("someone/PixelBoard-fork",
                GboardUpdateChecker.repositoryFromUrl("https://github.com/someone/PixelBoard-fork/"));
        Assert.assertEquals("someone/PixelBoard",
                GboardUpdateChecker.repositoryFromUrl("https://github.com/someone/PixelBoard.git"));
        Assert.assertEquals(GboardUpdateChecker.DEFAULT_REPOSITORY,
                GboardUpdateChecker.repositoryFromUrl("https://gitlab.com/someone/PixelBoard"));
        Assert.assertEquals(GboardUpdateChecker.DEFAULT_REPOSITORY,
                GboardUpdateChecker.repositoryFromUrl(null));
    }

    @Test
    public void currentReleaseIsUpToDateAndIsCachedWithItsEtag() {
        respond(200, GboardUpdateReleaseParserTest.V7_RELEASE_JSON, "\"v7-etag\"");

        GboardUpdateChecker.Result result = GboardUpdateChecker.check(context, true, baseUrl());

        Assert.assertEquals(GboardUpdateChecker.Outcome.UP_TO_DATE, result.outcome);
        Assert.assertEquals("1.2.0", result.release.patchVersion);
        Assert.assertNull(GboardUpdateChecker.availableUpdate(context));
        Assert.assertEquals("v18.4.1-Stable-V7",
                GboardUpdateSettings.readCachedRelease(preferences).tagName);
        Assert.assertEquals("\"v7-etag\"", GboardUpdateSettings.readEtag(preferences));
        Assert.assertTrue(GboardUpdateSettings.readLastSuccessMillis(preferences) > 0L);
        Assert.assertNull(GboardUpdateSettings.readLastError(preferences));
        String request = singleRequest();
        Assert.assertTrue(request, request.startsWith(
                "/repos/Akshayykadam/PixelBoard/releases/latest User-Agent=PixelBoard/"
                        + BuildConfig.PATCH_VERSION));
        Assert.assertTrue(request, request.contains("Accept=application/vnd.github+json"));
        Assert.assertTrue(request, request.endsWith("If-None-Match=null"));
    }

    @Test
    public void newerReleaseIsOfferedAndRevalidatedWithTheEtag() {
        respond(200, NEWER_RELEASE_JSON, "\"v99-etag\"");
        GboardUpdateChecker.Result first = GboardUpdateChecker.check(context, true, baseUrl());

        Assert.assertEquals(GboardUpdateChecker.Outcome.UPDATE_AVAILABLE, first.outcome);
        Assert.assertEquals("99.0.0", GboardUpdateChecker.availableUpdate(context).patchVersion);

        respond(304, null, "\"v99-etag\"");
        GboardUpdateChecker.Result second = GboardUpdateChecker.check(context, true, baseUrl());

        Assert.assertEquals(GboardUpdateChecker.Outcome.UPDATE_AVAILABLE, second.outcome);
        Assert.assertEquals("v18.4.1-Stable-V99", second.release.tagName);
        List<String> requests = server.requests();
        Assert.assertEquals(2, requests.size());
        Assert.assertTrue(requests.get(1), requests.get(1).endsWith("If-None-Match=\"v99-etag\""));
    }

    @Test
    public void backgroundCheckNotifiesOncePerRelease() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        respond(200, NEWER_RELEASE_JSON, null);

        GboardUpdateChecker.check(context, false, baseUrl());
        GboardUpdateChecker.check(context, false, baseUrl());

        Assert.assertEquals(1, Shadows.shadowOf(manager).getAllNotifications().size());
        // Its own group stops Android from bundling it with Gboard's notifications.
        Assert.assertNotNull(Shadows.shadowOf(manager).getAllNotifications().get(0).getGroup());
        Assert.assertEquals("v18.4.1-Stable-V99", GboardUpdateSettings.readNotifiedTag(preferences));
        Assert.assertNotNull(manager.getNotificationChannel(GboardUpdateNotifier.CHANNEL_ID));
        Assert.assertEquals(NotificationManager.IMPORTANCE_LOW,
                manager.getNotificationChannel(GboardUpdateNotifier.PROGRESS_CHANNEL_ID)
                        .getImportance());

        respond(200, GboardUpdateReleaseParserTest.V7_RELEASE_JSON, null);
        GboardUpdateChecker.check(context, false, baseUrl());

        Assert.assertEquals(0, Shadows.shadowOf(manager).getAllNotifications().size());
    }

    @Test
    public void selfPatchedBuildsAreOnlyPointedAtTheRelease() throws Exception {
        GboardUpdateTestState.setOfficialBuild(false);
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        respond(200, NEWER_RELEASE_JSON, null);

        GboardUpdateChecker.check(context, false, baseUrl());

        android.app.Notification notification =
                Shadows.shadowOf(manager).getAllNotifications().get(0);
        Assert.assertEquals("Version 99.0.0 is out. Tap for details.",
                notification.extras.getString(android.app.Notification.EXTRA_TEXT));
    }

    @Test
    public void withoutNotificationPermissionTheReleaseStaysUnannounced() {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        respond(200, NEWER_RELEASE_JSON, null);

        GboardUpdateChecker.Result result = GboardUpdateChecker.check(context, false, baseUrl());

        Assert.assertEquals(GboardUpdateChecker.Outcome.UPDATE_AVAILABLE, result.outcome);
        Assert.assertNull(GboardUpdateSettings.readNotifiedTag(preferences));
    }

    @Test
    public void disabledAutoCheckMakesNoRequest() {
        GboardUpdateSettings.writeAutoCheckEnabled(preferences, false);
        respond(200, NEWER_RELEASE_JSON, null);

        Assert.assertEquals(GboardUpdateChecker.Outcome.DISABLED,
                GboardUpdateChecker.check(context, false, baseUrl()).outcome);
        Assert.assertEquals(GboardUpdateChecker.Outcome.UPDATE_AVAILABLE,
                GboardUpdateChecker.check(context, true, baseUrl()).outcome);
        Assert.assertEquals(1, server.requests().size());
    }

    @Test
    public void failuresAreRecordedAndKeepTheCachedRelease() {
        respond(200, NEWER_RELEASE_JSON, null);
        GboardUpdateChecker.check(context, true, baseUrl());

        rateLimitRemaining = "0";
        respond(403, "{\"message\":\"API rate limit exceeded\"}", null);
        assertFailure(GboardUpdateChecker.ERROR_RATE_LIMITED);
        rateLimitRemaining = null;

        respond(429, null, null);
        assertFailure(GboardUpdateChecker.ERROR_RATE_LIMITED);
        respond(502, null, null);
        assertFailure(GboardUpdateChecker.ERROR_HTTP_PREFIX + "502");
        respond(200, "<html>maintenance</html>", null);
        assertFailure(GboardUpdateChecker.ERROR_UNREADABLE);

        Assert.assertEquals("99.0.0", GboardUpdateChecker.availableUpdate(context).patchVersion);
    }

    @Test
    public void vanishedReleasesStopBeingOffered() {
        for (int code : new int[] {404, 410, 451}) {
            respond(200, NEWER_RELEASE_JSON, "\"etag\"");
            GboardUpdateChecker.check(context, true, baseUrl());
            Assert.assertNotNull(GboardUpdateChecker.availableUpdate(context));

            respond(code, "{\"message\":\"Not Found\"}", null);
            assertFailure(GboardUpdateChecker.ERROR_NOT_FOUND);

            Assert.assertNull(String.valueOf(code), GboardUpdateChecker.availableUpdate(context));
            Assert.assertNull(GboardUpdateSettings.readEtag(preferences));
        }
    }

    @Test
    public void unreachableServerIsANetworkFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        GboardUpdateChecker.Result result =
                GboardUpdateChecker.check(context, true, "http://127.0.0.1:" + closedPort);

        Assert.assertEquals(GboardUpdateChecker.Outcome.FAILED, result.outcome);
        Assert.assertEquals(GboardUpdateChecker.ERROR_NETWORK, result.error);
        Assert.assertFalse(GboardUpdateChecker.isChecking());
    }

    private void assertFailure(String expectedError) {
        GboardUpdateChecker.Result result = GboardUpdateChecker.check(context, true, baseUrl());
        Assert.assertEquals(GboardUpdateChecker.Outcome.FAILED, result.outcome);
        Assert.assertEquals(expectedError, result.error);
        Assert.assertEquals(expectedError, GboardUpdateSettings.readLastError(preferences));
    }

    private void respond(int code, String body, String etag) {
        Map<String, String> headers = new HashMap<String, String>();
        if (etag != null) {
            headers.put("ETag", etag);
        }
        if (rateLimitRemaining != null) {
            headers.put("X-RateLimit-Remaining", rateLimitRemaining);
        }
        server.respond(code, body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private String baseUrl() {
        return server.baseUrl();
    }

    private String singleRequest() {
        List<String> requests = server.requests();
        Assert.assertEquals(1, requests.size());
        return requests.get(0);
    }

    private void setTimes(long lastSuccess, long lastAttempt) {
        preferences.edit()
                .putLong(GboardUpdateSettings.PREF_KEY_LAST_SUCCESS_MS, lastSuccess)
                .putLong(GboardUpdateSettings.PREF_KEY_LAST_ATTEMPT_MS, lastAttempt)
                .commit();
    }

    private static GboardUpdateRelease release(String patchVersion, long publishedAtMillis) {
        return new GboardUpdateRelease("v-test", "Test", "https://github.com/x/y/releases/tag/v",
                patchVersion, publishedAtMillis, "PixelBoard.apk",
                "https://github.com/x/y/releases/download/v/PixelBoard.apk", 10L, null);
    }
}
