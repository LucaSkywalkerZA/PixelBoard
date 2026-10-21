/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

/** The parts of a GitHub release the update check needs. */
public final class GboardUpdateRelease {
    public final String tagName;
    public final String name;
    public final String htmlUrl;
    /** Patch bundle version shipped by the release, or {@code null} if it does not say. */
    public final String patchVersion;
    /** {@code published_at} in epoch milliseconds, or {@code 0} when unknown. */
    public final long publishedAtMillis;
    public final String apkName;
    public final String apkUrl;
    /** APK size in bytes, or {@code -1} when unknown. */
    public final long apkSize;
    /** Lower-case hex SHA-256 of the APK from the asset digest, or {@code null}. */
    public final String apkSha256;

    public GboardUpdateRelease(String tagName, String name, String htmlUrl, String patchVersion,
            long publishedAtMillis, String apkName, String apkUrl, long apkSize,
            String apkSha256) {
        this.tagName = tagName;
        this.name = name;
        this.htmlUrl = htmlUrl;
        this.patchVersion = patchVersion;
        this.publishedAtMillis = publishedAtMillis;
        this.apkName = apkName;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
        this.apkSha256 = apkSha256;
    }

    public boolean hasApk() {
        return apkUrl != null && !apkUrl.isEmpty();
    }

    /** Short label for the UI: the patch version when known, otherwise the tag. */
    public String displayVersion() {
        if (patchVersion != null && !patchVersion.isEmpty()) {
            return patchVersion;
        }
        return tagName == null ? "" : tagName;
    }
}
