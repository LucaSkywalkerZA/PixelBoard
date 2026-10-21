package com.akshaykadam.pixelboard.extension.update;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public final class GboardUpdateVersionTest {

    @Test
    public void comparesNumericSegmentsNotStrings() {
        Assert.assertTrue(GboardUpdateVersion.compare("1.10.0", "1.9.9") > 0);
        Assert.assertTrue(GboardUpdateVersion.compare("1.2.1", "1.2.0") > 0);
        Assert.assertTrue(GboardUpdateVersion.compare("2.0.0", "1.99.99") > 0);
        Assert.assertTrue(GboardUpdateVersion.compare("1.0.9", "1.1.0") < 0);
        Assert.assertEquals(0, GboardUpdateVersion.compare("1.2", "1.2.0"));
        Assert.assertEquals(0, GboardUpdateVersion.compare("v1.2.0", "1.2.0"));
        Assert.assertEquals(0, GboardUpdateVersion.compare(" 1.2.0 ", "V1.2.0"));
    }

    @Test
    public void preReleaseSortsBeforeRelease() {
        Assert.assertTrue(GboardUpdateVersion.compare("1.3.0-beta1", "1.3.0") < 0);
        Assert.assertTrue(GboardUpdateVersion.compare("1.3.0-beta1", "1.2.0") > 0);
        Assert.assertTrue(GboardUpdateVersion.isNewer("1.3.0", "1.3.0-beta1"));
    }

    @Test
    public void unparseableVersionsAreNeverNewer() {
        Assert.assertFalse(GboardUpdateVersion.isNewer(null, "1.2.0"));
        Assert.assertFalse(GboardUpdateVersion.isNewer("", "1.2.0"));
        Assert.assertFalse(GboardUpdateVersion.isNewer("latest", "1.2.0"));
        Assert.assertFalse(GboardUpdateVersion.isNewer("1..2", "1.2.0"));
        Assert.assertTrue(GboardUpdateVersion.isNewer("1.2.0", "dev"));
        Assert.assertFalse(GboardUpdateVersion.isNewer("1.2.0", "1.2.0"));
        Assert.assertFalse(GboardUpdateVersion.isNewer("1.1.0", "1.2.0"));
    }

    @Test
    public void extractsPatchVersionFromEveryPublishedRelease() {
        // name, assets, expected patch version (as published on GitHub up to v18.4.1-Stable-V7)
        Object[][] releases = {
                {"PixelBoard Stable Release (v18.4.1-V7 / Patch v1.2.0)",
                        new String[] {"PixelBoard-18.4.1-V7.apk", "patches-1.2.0.mpp"}, "1.2.0"},
                {"PixelBoard Stable Release (v18.4.1-V6 / Patch v1.1.0)",
                        new String[] {"patches-1.1.0.mpp", "PixelBoard-18.4.1-V6.apk"}, "1.1.0"},
                {"PixelBoard Stable Release (v18.4.1-V5 / Patch v1.0.9)",
                        new String[] {"PixelBoard-18.4.1-V5.apk", "patches-1.0.9.mpp"}, "1.0.9"},
                {"PixelBoard Stable Release (v18.4.1-V4 / Patch v1.0.8)",
                        new String[] {"PixelBoard-18.4.1-V4.apk", "patches-1.0.8.mpp"}, "1.0.8"},
                {"PixelBoard Stable Release (v18.4.1-V3 / Patch v1.0.7)",
                        new String[] {"patches-1.0.7.mpp", "PixelBoard-18.4.1-V3.apk"}, "1.0.7"},
                {"PixelBoard Stable Release (v18.4.1_V2 / Patch v1.0.6)",
                        new String[] {"patches-1.0.6.mpp", "PixelBoard-18.4.1-V2.apk"}, "1.0.6"},
                {"PixelBoard Stable Release (v18.4.1 / Patch v1.0.5)",
                        new String[] {"PixelBoard.mpp", "PixelBoard-18.4.1.apk"}, "1.0.5"},
                {"PixelBoard Stable Release (v18.3.1 / Patch v1.0.4)",
                        new String[] {"PixelBoard-18.3.1.1.apk", "patches-1.0.4.mpp"}, "1.0.4"},
                {"PixelBoard v18.3.1 (Patch v1.0.3) Release Notes",
                        new String[] {"PixelBoard-1.0.3.mpp", "PixelBoard-18.3.1.Release.apk"}, "1.0.3"},
                {"PixelBoard v18.3.1 (Beta)", new String[] {"PixelBoard-18.3.1.apk"}, null},
                {"PixelBoard v18.0.3 (Stable)", new String[] {"PixelBoard.apk"}, null},
        };
        for (Object[] release : releases) {
            Assert.assertEquals(String.valueOf(release[0]), release[2],
                    GboardUpdateVersion.extractPatchVersion(
                            (String) release[0], Arrays.asList((String[]) release[1])));
        }
    }

    @Test
    public void neverMistakesTheGboardBaseVersionForThePatchVersion() {
        Assert.assertNull(GboardUpdateVersion.extractPatchVersion(
                "PixelBoard v18.5.0 Stable", Arrays.asList("PixelBoard-18.5.0.mpp",
                        "PixelBoard-18.5.0-V1.apk")));
        Assert.assertNull(GboardUpdateVersion.extractPatchVersion(
                "PixelBoard Stable Release (v18.5.0-V1)", Collections.<String>emptyList()));
    }

    @Test
    public void bundleAssetWinsOverTitle() {
        Assert.assertEquals("1.3.0", GboardUpdateVersion.extractPatchVersion(
                "PixelBoard Stable Release (v18.5.0-V1 / Patch v1.2.9)",
                Arrays.asList("patches-1.3.0.mpp")));
    }

    @Test
    public void toleratesFutureNamingVariations() {
        Assert.assertEquals("2.0.0", GboardUpdateVersion.extractPatchVersion(
                "PixelBoard 19.0 / Patches v2.0.0", null));
        Assert.assertEquals("1.3.0", GboardUpdateVersion.extractPatchVersion(
                "Patch 1.3.0.", null));
        Assert.assertEquals("1.3.0", GboardUpdateVersion.extractPatchVersion(
                null, Arrays.asList("Patches-v1.3.0.MPP")));
        Assert.assertEquals("1.4.0-rc.1", GboardUpdateVersion.extractPatchVersion(
                null, Arrays.asList("patches-1.4.0-rc.1.mpp")));
        Assert.assertEquals("1.2.10", GboardUpdateVersion.extractPatchVersion(
                "Release (V8 / Patch v1.2.10)", Arrays.asList("PixelBoard.mpp", null)));
        Assert.assertNull(GboardUpdateVersion.extractPatchVersion(
                "Dispatched v1.2.0", null));
    }
}
