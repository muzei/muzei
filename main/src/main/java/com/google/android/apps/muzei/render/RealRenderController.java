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
import android.media.ExifInterface;

import com.google.android.apps.muzei.ArtworkCache;
import com.google.android.apps.muzei.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.TaskQueueService;
import com.google.android.apps.muzei.WearableController;
import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.CurrentArtworkDownloadedEvent;
import com.google.android.apps.muzei.provider.MuzeiProvider;
import com.google.android.apps.muzei.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import static com.google.android.apps.muzei.util.LogUtil.LOGD;
import static com.google.android.apps.muzei.util.LogUtil.LOGE;
import static com.google.android.apps.muzei.util.LogUtil.LOGW;

public class RealRenderController extends RenderController {
    private static final String TAG = LogUtil.makeLogTag(RealRenderController.class);

    private String mLastLoadedPath;

    public RealRenderController(Context context, MuzeiBlurRenderer renderer,
            Callbacks callbacks) {
        super(context, renderer, callbacks);
        if (MuzeiContract.Artwork.getCurrentArtwork(context) == null) {
            reloadCurrentArtwork(true);
        }
    }

    public void onEventMainThread(CurrentArtworkDownloadedEvent e) {
        reloadCurrentArtwork(false);
    }

    @Override
    protected BitmapRegionLoader openDownloadedCurrentArtwork(boolean forceReload) {
        SourceManager sm = SourceManager.getInstance(mContext);
        SourceState selectedSourceState = sm.getSelectedSourceState();
        Artwork currentArtwork = selectedSourceState != null
                ? selectedSourceState.getCurrentArtwork() : null;
        if (currentArtwork == null) {
            return null;
        }

        ArtworkCache artworkCache = ArtworkCache.getInstance(mContext);
        File file = artworkCache.getArtworkCacheFile(sm.getSelectedSource(), currentArtwork);
        if (file == null) {
            return null;
        }

        if (!file.exists() || file.length() == 0) {
            mContext.startService(TaskQueueService.getDownloadCurrentArtworkIntent(mContext));
            return null;
        }

        if (mLastLoadedPath != null
                && mLastLoadedPath.equals(file.getAbsolutePath())
                && !forceReload) {
            return null;
        }

        // Check if there's rotation
        int rotation = 0;
        try {
            ExifInterface exifInterface = new ExifInterface(file.getAbsolutePath());
            int orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
            }
            LOGD(TAG, "Loading artwork with rotation: " + rotation);

        } catch (IOException e) {
            LOGW(TAG, "Couldn't open EXIF interface on file: " + file.getAbsolutePath(), e);
        }

        // Load the stream
        try {
            BitmapRegionLoader loader = BitmapRegionLoader.newInstance(
                    new FileInputStream(file), rotation);
            if (MuzeiProvider.saveCurrentArtworkLocation(mContext, file)) {
                mContext.getContentResolver().insert(MuzeiContract.Artwork.CONTENT_URI, currentArtwork.toContentValues());
            }
            NewWallpaperNotificationReceiver
                    .maybeShowNewArtworkNotification(mContext, currentArtwork, loader);
            WearableController.updateDataLayer(mContext, currentArtwork, loader);
            mLastLoadedPath = file.getAbsolutePath();
            return loader;
        } catch (IOException e) {
            LOGE(TAG, "Error loading image: " + file.getAbsolutePath() + " from " + currentArtwork.getImageUri(), e);
            return null;
        }
    }
}
