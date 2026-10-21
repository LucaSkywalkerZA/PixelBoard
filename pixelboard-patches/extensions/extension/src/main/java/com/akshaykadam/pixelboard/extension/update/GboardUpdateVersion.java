/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patch bundle version helpers for the GitHub release update check.
 *
 * <p>Release tags combine the Gboard base version with a build counter
 * ({@code v18.4.1-Stable-V7}) and the counter restarts when the base changes, so tags cannot be
 * ordered. The patch bundle version ({@code version} in {@code gradle.properties}, compiled into
 * {@code BuildConfig.PATCH_VERSION}) grows with every release and is published as the
 * {@code patches-X.Y.Z.mpp} asset and as "Patch vX.Y.Z" in the release title.</p>
 */
public final class GboardUpdateVersion {
    private static final String VERSION_GROUP =
            "(\\d+(?:\\.\\d+){1,3}(?:-[0-9A-Za-z][0-9A-Za-z.]*)?)";
    private static final Pattern BUNDLE_ASSET_PATTERN = Pattern.compile(
            "^patches[-_]v?" + VERSION_GROUP + "\\.mpp$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATCH_LABEL_PATTERN = Pattern.compile(
            "\\bpatch(?:es)?\\s*v?" + VERSION_GROUP + "(?![0-9A-Za-z]|\\.[0-9A-Za-z])",
            Pattern.CASE_INSENSITIVE);

    private GboardUpdateVersion() {
    }

    /**
     * Returns the patch bundle version a release ships, or {@code null} when neither the bundle
     * asset name nor the release title carries one.
     */
    public static String extractPatchVersion(String releaseName, List<String> assetNames) {
        if (assetNames != null) {
            for (String assetName : assetNames) {
                if (assetName == null) {
                    continue;
                }
                Matcher matcher = BUNDLE_ASSET_PATTERN.matcher(assetName.trim());
                if (matcher.matches()) {
                    return matcher.group(1);
                }
            }
        }
        if (releaseName != null) {
            Matcher matcher = PATCH_LABEL_PATTERN.matcher(releaseName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** Returns {@code true} when {@code candidate} is a strictly newer version than {@code installed}. */
    public static boolean isNewer(String candidate, String installed) {
        if (numericParts(candidate) == null) {
            return false;
        }
        return compare(candidate, installed) > 0;
    }

    /**
     * Compares dotted numeric versions segment by segment ({@code 1.10.0 > 1.9.9}, {@code 1.2 == 1.2.0}).
     * A pre-release suffix ({@code 1.3.0-beta1}) sorts before the plain release. Unparseable versions
     * sort before every parseable one.
     */
    public static int compare(String left, String right) {
        long[] leftParts = numericParts(left);
        long[] rightParts = numericParts(right);
        if (leftParts == null || rightParts == null) {
            if (leftParts == null && rightParts == null) {
                return 0;
            }
            return leftParts == null ? -1 : 1;
        }
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            long leftValue = index < leftParts.length ? leftParts[index] : 0L;
            long rightValue = index < rightParts.length ? rightParts[index] : 0L;
            if (leftValue != rightValue) {
                return leftValue < rightValue ? -1 : 1;
            }
        }
        boolean leftPreRelease = isPreRelease(left);
        boolean rightPreRelease = isPreRelease(right);
        if (leftPreRelease != rightPreRelease) {
            return leftPreRelease ? -1 : 1;
        }
        return 0;
    }

    static long[] numericParts(String version) {
        String core = numericCore(version);
        if (core == null) {
            return null;
        }
        String[] segments = core.split("\\.");
        List<Long> values = new ArrayList<Long>();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.length() > 18) {
                return null;
            }
            values.add(Long.valueOf(Long.parseLong(segment)));
        }
        long[] parts = new long[values.size()];
        for (int index = 0; index < parts.length; index++) {
            parts[index] = values.get(index).longValue();
        }
        return parts;
    }

    private static boolean isPreRelease(String version) {
        String core = numericCore(version);
        if (core == null) {
            return false;
        }
        String trimmed = stripPrefix(version);
        String suffix = trimmed.substring(core.length());
        return suffix.startsWith("-");
    }

    private static String numericCore(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = stripPrefix(version);
        int end = 0;
        while (end < trimmed.length()) {
            char character = trimmed.charAt(end);
            if ((character < '0' || character > '9') && character != '.') {
                break;
            }
            end++;
        }
        String core = trimmed.substring(0, end);
        while (core.endsWith(".")) {
            core = core.substring(0, core.length() - 1);
        }
        if (core.isEmpty() || core.startsWith(".") || core.contains("..")) {
            return null;
        }
        return core;
    }

    private static String stripPrefix(String version) {
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }
}
