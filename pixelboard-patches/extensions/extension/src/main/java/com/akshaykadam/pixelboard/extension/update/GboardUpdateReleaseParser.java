/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.update;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parses the GitHub {@code /releases/latest} response. */
public final class GboardUpdateReleaseParser {
    private static final String SHA256_DIGEST_PREFIX = "sha256:";

    private GboardUpdateReleaseParser() {
    }

    /**
     * Returns the release described by {@code json}, or {@code null} for drafts, pre-releases and
     * anything malformed. Never throws.
     */
    public static GboardUpdateRelease parse(String json, String[] supportedAbis) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
                return null;
            }
            String tagName = string(root, "tag_name");
            if (tagName == null) {
                return null;
            }
            String name = string(root, "name");
            String htmlUrl = httpsUrl(string(root, "html_url"));

            List<String> assetNames = new ArrayList<String>();
            List<JSONObject> apkAssets = new ArrayList<JSONObject>();
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.optJSONObject(index);
                    if (asset == null) {
                        continue;
                    }
                    String assetName = string(asset, "name");
                    if (assetName == null) {
                        continue;
                    }
                    String state = string(asset, "state");
                    if (state != null && !"uploaded".equals(state)) {
                        continue;
                    }
                    assetNames.add(assetName);
                    if (assetName.toLowerCase(Locale.US).endsWith(".apk")
                            && httpsUrl(string(asset, "browser_download_url")) != null) {
                        apkAssets.add(asset);
                    }
                }
            }

            JSONObject apk = selectApkAsset(apkAssets, supportedAbis);
            String apkName = apk == null ? null : string(apk, "name");
            String apkUrl = apk == null ? null : httpsUrl(string(apk, "browser_download_url"));
            long apkSize = apk == null ? -1L : apk.optLong("size", -1L);
            String apkSha256 = apk == null ? null : sha256Digest(string(apk, "digest"));

            return new GboardUpdateRelease(
                    tagName,
                    name,
                    htmlUrl,
                    GboardUpdateVersion.extractPatchVersion(name, assetNames),
                    parseTimestamp(string(root, "published_at")),
                    apkName,
                    apkUrl,
                    apkSize > 0L ? apkSize : -1L,
                    apkSha256);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JSONObject selectApkAsset(List<JSONObject> apkAssets, String[] supportedAbis) {
        if (apkAssets == null || apkAssets.isEmpty()) {
            return null;
        }
        if (supportedAbis != null) {
            for (String abi : supportedAbis) {
                if (abi == null || abi.isEmpty()) {
                    continue;
                }
                for (JSONObject asset : apkAssets) {
                    if (abiTokens(assetName(asset)).contains(abi.toLowerCase(Locale.US))) {
                        return asset;
                    }
                }
            }
        }
        // Otherwise prefer an APK that is not tagged for some other ABI (a universal build).
        for (JSONObject asset : apkAssets) {
            if (abiTokens(assetName(asset)).isEmpty()) {
                return asset;
            }
        }
        // Every APK targets an ABI this device lacks; installing one would fail.
        return null;
    }

    /** The ABI names an asset name is tagged with ("arm64" counts as arm64-v8a). */
    static List<String> abiTokens(String assetName) {
        String name = assetName == null ? "" : assetName.toLowerCase(Locale.US);
        List<String> tokens = new ArrayList<String>();
        if (name.contains("arm64")) {
            tokens.add("arm64-v8a");
        }
        if (name.contains("armeabi") || name.contains("armv7")) {
            tokens.add("armeabi-v7a");
        }
        if (name.contains("x86_64") || name.contains("x86-64")) {
            tokens.add("x86_64");
        } else if (name.contains("x86")) {
            tokens.add("x86");
        }
        if (name.contains("riscv64")) {
            tokens.add("riscv64");
        }
        return tokens;
    }

    private static String assetName(JSONObject asset) {
        return string(asset, "name");
    }

    static long parseTimestamp(String value) {
        if (value == null) {
            return 0L;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try {
            Date date = format.parse(value.trim());
            return date == null ? 0L : date.getTime();
        } catch (ParseException ignored) {
            return 0L;
        }
    }

    private static String sha256Digest(String digest) {
        if (digest == null || !digest.toLowerCase(Locale.US).startsWith(SHA256_DIGEST_PREFIX)) {
            return null;
        }
        String hex = digest.substring(SHA256_DIGEST_PREFIX.length()).trim().toLowerCase(Locale.US);
        if (hex.length() != 64) {
            return null;
        }
        for (int index = 0; index < hex.length(); index++) {
            char character = hex.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return null;
            }
        }
        return hex;
    }

    private static String httpsUrl(String url) {
        if (url == null || !url.toLowerCase(Locale.US).startsWith("https://")) {
            return null;
        }
        return url;
    }

    private static String string(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, null);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
