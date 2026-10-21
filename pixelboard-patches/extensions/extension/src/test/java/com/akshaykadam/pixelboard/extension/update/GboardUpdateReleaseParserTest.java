package com.akshaykadam.pixelboard.extension.update;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class GboardUpdateReleaseParserTest {
    private static final String[] ARM64 = {"arm64-v8a", "armeabi-v7a", "armeabi"};

    /** Trimmed copy of the real {@code /releases/latest} response for v18.4.1-Stable-V7. */
    static final String V7_RELEASE_JSON = "{"
            + "\"html_url\":\"https://github.com/Akshayykadam/PixelBoard/releases/tag/v18.4.1-Stable-V7\","
            + "\"tag_name\":\"v18.4.1-Stable-V7\","
            + "\"name\":\"PixelBoard Stable Release (v18.4.1-V7 / Patch v1.2.0)\","
            + "\"draft\":false,\"prerelease\":false,"
            + "\"created_at\":\"2026-10-18T05:11:17Z\",\"published_at\":\"2026-10-18T05:12:04Z\","
            + "\"assets\":["
            + "{\"name\":\"PixelBoard-18.4.1-V7.apk\",\"state\":\"uploaded\","
            + "\"content_type\":\"application/vnd.android.package-archive\",\"size\":132947055,"
            + "\"digest\":\"sha256:d16910e04ab027816b2e5929e67f0aa2fffa6154b9ec49aad3a3336a35b491ff\","
            + "\"browser_download_url\":\"https://github.com/Akshayykadam/PixelBoard/releases/download/v18.4.1-Stable-V7/PixelBoard-18.4.1-V7.apk\"},"
            + "{\"name\":\"patches-1.2.0.mpp\",\"state\":\"uploaded\","
            + "\"content_type\":\"application/octet-stream\",\"size\":1700976,"
            + "\"digest\":\"sha256:b165e2cb6cda49b45985f9e981ecf1570fdfb503957c360ab202f2c588a85b9f\","
            + "\"browser_download_url\":\"https://github.com/Akshayykadam/PixelBoard/releases/download/v18.4.1-Stable-V7/patches-1.2.0.mpp\"}"
            + "],"
            + "\"body\":\"### Highlights\\n* Patch v1.2.0 notes (trimmed)\"}";

    @Test
    public void parsesTheRealLatestRelease() {
        GboardUpdateRelease release = GboardUpdateReleaseParser.parse(V7_RELEASE_JSON, ARM64);

        Assert.assertNotNull(release);
        Assert.assertEquals("v18.4.1-Stable-V7", release.tagName);
        Assert.assertEquals("PixelBoard Stable Release (v18.4.1-V7 / Patch v1.2.0)", release.name);
        Assert.assertEquals(
                "https://github.com/Akshayykadam/PixelBoard/releases/tag/v18.4.1-Stable-V7",
                release.htmlUrl);
        Assert.assertEquals("1.2.0", release.patchVersion);
        Assert.assertEquals(1792300324000L, release.publishedAtMillis);
        Assert.assertEquals("PixelBoard-18.4.1-V7.apk", release.apkName);
        Assert.assertEquals(
                "https://github.com/Akshayykadam/PixelBoard/releases/download/v18.4.1-Stable-V7/PixelBoard-18.4.1-V7.apk",
                release.apkUrl);
        Assert.assertEquals(132947055L, release.apkSize);
        Assert.assertEquals(
                "d16910e04ab027816b2e5929e67f0aa2fffa6154b9ec49aad3a3336a35b491ff",
                release.apkSha256);
        Assert.assertTrue(release.hasApk());
        Assert.assertEquals("1.2.0", release.displayVersion());
    }

    @Test
    public void rejectsDraftsPreReleasesAndGarbage() {
        Assert.assertNull(GboardUpdateReleaseParser.parse(
                V7_RELEASE_JSON.replace("\"draft\":false", "\"draft\":true"), ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse(
                V7_RELEASE_JSON.replace("\"prerelease\":false", "\"prerelease\":true"), ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse("not json", ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse("[]", ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse("", ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse(null, ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse("{\"tag_name\":null}", ARM64));
        Assert.assertNull(GboardUpdateReleaseParser.parse("{\"message\":\"Not Found\"}", ARM64));
    }

    @Test
    public void toleratesMissingOptionalFields() {
        GboardUpdateRelease release = GboardUpdateReleaseParser.parse(
                "{\"tag_name\":\"v19.0.0-Stable\",\"name\":null,\"assets\":[]}", ARM64);

        Assert.assertNotNull(release);
        Assert.assertNull(release.name);
        Assert.assertNull(release.htmlUrl);
        Assert.assertNull(release.patchVersion);
        Assert.assertEquals(0L, release.publishedAtMillis);
        Assert.assertFalse(release.hasApk());
        Assert.assertEquals(-1L, release.apkSize);
        Assert.assertEquals("v19.0.0-Stable", release.displayVersion());
    }

    @Test
    public void ignoresAssetsThatAreStillUploadingOrNotHttps() {
        String json = "{\"tag_name\":\"v1\",\"assets\":["
                + "{\"name\":\"PixelBoard-1.apk\",\"state\":\"starter\","
                + "\"browser_download_url\":\"https://example.invalid/a.apk\"},"
                + "{\"name\":\"PixelBoard-2.apk\",\"state\":\"uploaded\","
                + "\"browser_download_url\":\"http://example.invalid/b.apk\"}]}";
        GboardUpdateRelease release = GboardUpdateReleaseParser.parse(json, ARM64);

        Assert.assertNotNull(release);
        Assert.assertFalse(release.hasApk());
    }

    @Test
    public void picksTheApkForThisDeviceAbiWhenThereAreSeveral() {
        String json = "{\"tag_name\":\"v1\",\"assets\":["
                + apk("PixelBoard-19.0-x86_64.apk") + "," + apk("PixelBoard-19.0-arm64.apk") + "]}";

        Assert.assertEquals("PixelBoard-19.0-arm64.apk",
                GboardUpdateReleaseParser.parse(json, ARM64).apkName);
        Assert.assertEquals("PixelBoard-19.0-x86_64.apk",
                GboardUpdateReleaseParser.parse(json, new String[] {"x86_64"}).apkName);
        // Nothing installable for this device: offer the release page instead of a download.
        Assert.assertFalse(GboardUpdateReleaseParser.parse(json, new String[] {"riscv64"}).hasApk());
    }

    @Test
    public void aSingleApkForAnotherAbiIsNotOffered() {
        String json = "{\"tag_name\":\"v1\",\"assets\":[" + apk("PixelBoard-19.0-x86_64.apk") + "]}";

        Assert.assertFalse(GboardUpdateReleaseParser.parse(json, ARM64).hasApk());
        Assert.assertTrue(GboardUpdateReleaseParser.parse(json, new String[] {"x86_64"}).hasApk());
    }

    @Test
    public void prefersAnUntaggedApkOverOneForAnotherAbi() {
        String json = "{\"tag_name\":\"v1\",\"assets\":["
                + apk("PixelBoard-19.0-V1-x86_64.apk") + "," + apk("PixelBoard-19.0-V1.apk") + "]}";

        Assert.assertEquals("PixelBoard-19.0-V1.apk",
                GboardUpdateReleaseParser.parse(json, ARM64).apkName);
        Assert.assertEquals("PixelBoard-19.0-V1-x86_64.apk",
                GboardUpdateReleaseParser.parse(json, new String[] {"x86_64", "x86"}).apkName);
    }

    @Test
    public void ignoresMalformedDigests() {
        String json = "{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"a.apk\",\"size\":10,"
                + "\"digest\":\"sha256:xyz\","
                + "\"browser_download_url\":\"https://example.invalid/a.apk\"}]}";
        GboardUpdateRelease release = GboardUpdateReleaseParser.parse(json, ARM64);

        Assert.assertNotNull(release);
        Assert.assertTrue(release.hasApk());
        Assert.assertNull(release.apkSha256);
        Assert.assertEquals(10L, release.apkSize);
    }

    @Test
    public void parsesGitHubTimestamps() {
        Assert.assertEquals(0L, GboardUpdateReleaseParser.parseTimestamp(null));
        Assert.assertEquals(0L, GboardUpdateReleaseParser.parseTimestamp("yesterday"));
        Assert.assertEquals(1792300324000L,
                GboardUpdateReleaseParser.parseTimestamp("2026-10-18T05:12:04Z"));
    }

    private static String apk(String name) {
        return "{\"name\":\"" + name + "\",\"state\":\"uploaded\","
                + "\"browser_download_url\":\"https://example.invalid/" + name + "\"}";
    }
}
