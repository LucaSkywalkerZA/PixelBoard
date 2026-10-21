package com.akshaykadam.pixelboard.extension.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.akshaykadam.pixelboard.extension.update.GboardUpdateScheduler;

public final class GboardPatchesSettingsProvider extends ContentProvider {
    private static final String TAG = "PixelBoard";
    public static final String AUTHORITY_SUFFIX = ".gboard_patches";

    @Override
    public boolean onCreate() {
        // Providers are created at every process start, so this is a hook-free entry point that
        // does not depend on the Gboard version.
        try {
            GboardUpdateScheduler.start(getContext());
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to start the update check", throwable);
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return new Bundle();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
