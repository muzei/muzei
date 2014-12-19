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

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.provider.MuzeiProvider;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * IntentService responsible to for retrieving the artwork from the DataLayer and caching it locally
 * to make it available via the Artwork API.
 *
 * <p>Optionally pass {@link #SHOW_ACTIVATE_NOTIFICATION_EXTRA} with your Intent to show a
 * notification to activate Muzei if the artwork is not found
 */
public class ArtworkCacheIntentService extends IntentService {
    private static final String TAG = ArtworkCacheIntentService.class.getSimpleName();
    public static final String SHOW_ACTIVATE_NOTIFICATION_EXTRA = "SHOW_ACTIVATE_NOTIFICATION";

    public ArtworkCacheIntentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult =
                googleApiClient.blockingConnect(30, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            Log.e(TAG, "Failed to connect to GoogleApiClient: " + connectionResult.getErrorCode());
            return;
        }
        // Read all DataItems
        DataItemBuffer dataItemBuffer = Wearable.DataApi.getDataItems(googleApiClient).await();
        if (!dataItemBuffer.getStatus().isSuccess()) {
            Log.e(TAG, "Error getting all data items: " + dataItemBuffer.getStatus().getStatusMessage());
        }
        Iterator<DataItem> dataItemIterator = dataItemBuffer.singleRefIterator();
        boolean foundArtwork = false;
        while (dataItemIterator.hasNext()) {
            DataItem dataItem = dataItemIterator.next();
            foundArtwork = foundArtwork || processDataItem(googleApiClient, dataItem);
        }
        dataItemBuffer.close();
        if (!foundArtwork && intent != null &&
                intent.getBooleanExtra(SHOW_ACTIVATE_NOTIFICATION_EXTRA, false)) {
            ActivateMuzeiIntentService.maybeShowActivateMuzeiNotification(this);
        }
        googleApiClient.disconnect();
    }


    private boolean processDataItem(GoogleApiClient googleApiClient, DataItem dataItem) {
        if (!dataItem.getUri().getPath().equals("/artwork")) {
            Log.w(TAG, "Ignoring data item " + dataItem.getUri().getPath());
            return false;
        }
        DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
        DataMap artworkDataMap = dataMapItem.getDataMap().getDataMap("artwork");
        if (artworkDataMap == null) {
            Log.w(TAG, "No artwork in datamap.");
            return false;
        }
        final Artwork artwork = Artwork.fromBundle(artworkDataMap.toBundle());
        final Asset asset = dataMapItem.getDataMap().getAsset("image");
        if (asset == null) {
            Log.w(TAG, "No image asset in datamap.");
            return false;
        }
        // Convert asset into a file descriptor and block until it's ready
        final DataApi.GetFdForAssetResult getFdForAssetResult =
                Wearable.DataApi.getFdForAsset(googleApiClient, asset).await();
        InputStream assetInputStream = getFdForAssetResult.getInputStream();
        if (assetInputStream == null) {
            Log.w(TAG, "Empty asset input stream (probably an unknown asset).");
            return false;
        }
        Bitmap image = BitmapFactory.decodeStream(assetInputStream);
        if (image == null) {
            Log.w(TAG, "Couldn't decode a bitmap from the stream.");
            return false;
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
        enableComponents(FullScreenActivity.class);
        if (MuzeiProvider.saveCurrentArtworkLocation(this, localCache)) {
            getContentResolver().insert(MuzeiContract.Artwork.CONTENT_URI, artwork.toContentValues());
        }
        return true;
    }

    private void enableComponents(Class<?>... components) {
        PackageManager packageManager = getPackageManager();
        for (Class<?> component : components) {
            ComponentName componentName = new ComponentName(this, component);
            packageManager.setComponentEnabledSetting(componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
