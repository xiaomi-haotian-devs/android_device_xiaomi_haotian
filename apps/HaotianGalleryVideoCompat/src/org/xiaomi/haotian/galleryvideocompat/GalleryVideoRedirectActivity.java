/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.galleryvideocompat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

/**
 * Adapts Xiaomi Gallery's private video-player intents to a standard VIEW
 * intent. The full HyperOS MediaViewer is deliberately not emulated here.
 */
public final class GalleryVideoRedirectActivity extends Activity {
    private static final String TAG = "HaotianGalleryVideo";
    private static final String GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent source = getIntent();
        Uri uri = source != null ? source.getData() : null;
        if (uri == null) {
            Log.w(TAG, "Ignoring video-player intent without a URI");
            finish();
            return;
        }

        String mimeType = source.getType();
        if (TextUtils.isEmpty(mimeType) && "content".equals(uri.getScheme())) {
            try {
                mimeType = getContentResolver().getType(uri);
            } catch (SecurityException e) {
                Log.w(TAG, "Unable to resolve the video MIME type", e);
            }
        }
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "video/*";
        }

        Intent target = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .setPackage(GOOGLE_PHOTOS_PACKAGE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Keep the grant attached when the source is a GalleryOpenProvider
        // content URI and this trampoline forwards it to another process.
        if ("content".equals(uri.getScheme())) {
            target.setClipData(ClipData.newRawUri("video", uri));
        }

        try {
            startActivity(target);
        } catch (ActivityNotFoundException e) {
            // GMS-less variants may not contain Photos. Preserve basic video
            // playback by falling back to the system's standard VIEW route.
            target.setPackage(null);
            try {
                startActivity(target);
            } catch (ActivityNotFoundException fallbackError) {
                Log.e(TAG, "No application can play the requested video", fallbackError);
            }
        } finally {
            finish();
        }
    }
}
