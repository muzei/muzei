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
import android.net.Uri;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        dataItemBuffer.release();
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
        Uri artworkUri = getContentResolver().insert(MuzeiContract.Artwork.CONTENT_URI, artwork.toContentValues());
        if (artworkUri == null) {
            Log.w(TAG, "Unable to write artwork information to MuzeiProvider");
            return false;
        }
        OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(artworkUri);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = assetInputStream.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing artwork", e);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing artwork", e);
            }
        }
        enableComponents(FullScreenActivity.class);
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
