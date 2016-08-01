/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.render;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;

import com.google.android.apps.muzei.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.wearable.WearableController;
import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.util.LogUtil;

import java.io.IOException;

import static com.google.android.apps.muzei.util.LogUtil.LOGE;

public class RealRenderController extends RenderController {
    private static final String TAG = LogUtil.makeLogTag(RealRenderController.class);

    private String mLastLoadedPath;
    private ContentObserver mContentObserver;

    public RealRenderController(Context context, MuzeiBlurRenderer renderer,
            Callbacks callbacks) {
        super(context, renderer, callbacks);
        mContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reloadCurrentArtwork(false);
            }
        };
        context.getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, mContentObserver);
        if (MuzeiContract.Artwork.getCurrentArtwork(context) != null) {
            reloadCurrentArtwork(false);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
    }

    @Override
    protected BitmapRegionLoader openDownloadedCurrentArtwork(boolean forceReload) {
        Artwork currentArtwork = MuzeiContract.Artwork.getCurrentArtwork(mContext);
        // Load the stream
        try {
            BitmapRegionLoader loader = BitmapRegionLoader.newInstance(
                    mContext.getContentResolver().openInputStream(MuzeiContract.Artwork.CONTENT_URI), 0);
            if (!TextUtils.equals(mLastLoadedPath, currentArtwork.getImageUri().toString())) {
                mLastLoadedPath = currentArtwork.getImageUri().toString();
                NewWallpaperNotificationReceiver
                        .maybeShowNewArtworkNotification(mContext, currentArtwork, loader);
                WearableController.updateArtwork(mContext, currentArtwork, loader);
            }
            return loader;
        } catch (IOException e) {
            LOGE(TAG, "Error loading image", e);
            return null;
        }
    }
}
