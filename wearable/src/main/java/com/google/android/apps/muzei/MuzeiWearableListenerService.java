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
package com.google.android.apps.muzei;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.provider.MuzeiProvider;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * WearableListenerService responsible to receiving Data Layer changes with updated artwork
 */
public class MuzeiWearableListenerService extends WearableListenerService {
    private static final String TAG = MuzeiWearableListenerService.class.getSimpleName();

    @Override
    public void onDataChanged(final DataEventBuffer dataEvents) {
        long token = Binder.clearCallingIdentity();
        try {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }
                processDataItem(dataEvent.getDataItem());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void processDataItem(DataItem dataItem) {
        if (!dataItem.getUri().getPath().equals("/artwork")) {
            Log.w(TAG, "Ignoring data item " + dataItem.getUri().getPath());
            return;
        }
        DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
        DataMap artworkDataMap = dataMapItem.getDataMap().getDataMap("artwork");
        if (artworkDataMap == null) {
            Log.w(TAG, "No artwork in datamap.");
            return;
        }
        final Artwork artwork = Artwork.fromBundle(artworkDataMap.toBundle());
        final Asset asset = dataMapItem.getDataMap().getAsset("asset");
        if (asset == null) {
            Log.w(TAG, "No image asset in datamap.");
            return;
        }
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult =
                googleApiClient.blockingConnect(30, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            Log.e(TAG, "Failed to connect to GoogleApiClient.");
            return;
        }
        // Convert asset into a file descriptor and block until it's ready
        final DataApi.GetFdForAssetResult getFdForAssetResult =
                Wearable.DataApi.getFdForAsset(googleApiClient, asset).await();
        InputStream assetInputStream = getFdForAssetResult.getInputStream();
        if (assetInputStream == null) {
            Log.w(TAG, "Empty asset input stream (probably an unknown asset).");
            return;
        }
        Bitmap image = BitmapFactory.decodeStream(assetInputStream);
        if (image == null) {
            Log.w(TAG, "Couldn't decode a bitmap from the stream.");
            return;
        }
        File localCache = new File(getCacheDir(), "cache.png");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(localCache);
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.e(TAG, "Error writing local cache", e);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing local cache file", e);
            }
        }
        MuzeiProvider.saveCurrentArtworkLocation(MuzeiWearableListenerService.this, localCache);
        getContentResolver().insert(MuzeiContract.Artwork.CONTENT_URI, artwork.toContentValues());
    }
}
