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

package com.google.android.apps.muzei.datalayer;

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import com.google.android.apps.muzei.FullScreenActivity;
import com.google.android.apps.muzei.complications.ArtworkComplicationProviderService;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.wearable.ArtworkTransfer;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;

/**
 * IntentService responsible to for retrieving the artwork from the DataLayer and caching it locally
 * to make it available via the Artwork API.
 *
 * <p>Optionally pass {@link #SHOW_ACTIVATE_NOTIFICATION_EXTRA} with your Intent to show a
 * notification to activate Muzei if the artwork is not found
 */
public class ArtworkCacheIntentService extends IntentService {
    private static final String TAG = "ArtworkCacheService";
    public static final String SHOW_ACTIVATE_NOTIFICATION_EXTRA = "SHOW_ACTIVATE_NOTIFICATION";

    public ArtworkCacheIntentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        boolean foundArtwork = false;
        DataClient dataClient = Wearable.getDataClient(this);
        // Read all DataItems
        try {
            DataItemBuffer dataItemBuffer = Tasks.await(dataClient.getDataItems());
            Iterator<DataItem> dataItemIterator = dataItemBuffer.singleRefIterator();
            while (dataItemIterator.hasNext()) {
                DataItem dataItem = dataItemIterator.next();
                foundArtwork = foundArtwork || processDataItem(dataClient, dataItem);
            }
            dataItemBuffer.release();
        } catch (ExecutionException|InterruptedException e) {
            Log.e(TAG, "Error getting all data items", e);
        }
        if (foundArtwork) {
            // Enable the Full Screen Activity and Artwork Complication Provider Service only if we've found artwork
            enableComponents(FullScreenActivity.class, ArtworkComplicationProviderService.class);
        }
        if (!foundArtwork && intent != null &&
                intent.getBooleanExtra(SHOW_ACTIVATE_NOTIFICATION_EXTRA, false)) {
            ActivateMuzeiIntentService.maybeShowActivateMuzeiNotification(this);
        } else {
            ActivateMuzeiIntentService.clearNotifications(this);
        }
    }


    private boolean processDataItem(DataClient dataClient, DataItem dataItem) {
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
        final Asset asset = dataMapItem.getDataMap().getAsset("image");
        if (asset == null) {
            Log.w(TAG, "No image asset in datamap.");
            return false;
        }
        Artwork artwork = ArtworkTransfer.fromDataMap(artworkDataMap);
        // Change it so that all Artwork from the phone is attributed to the DataLayerArtSource
        artwork.sourceComponentName = new ComponentName(this, DataLayerArtSource.class);
        // Check if the source info row exists at all.
        MuzeiDatabase database = MuzeiDatabase.getInstance(this);
        Source existingSource = database.sourceDao().getSourceByComponentNameBlocking(artwork.sourceComponentName);
        if (existingSource != null) {
            existingSource.selected = true;
            database.sourceDao().update(existingSource);
        } else {
            Source newSource = new Source(artwork.sourceComponentName);
            newSource.selected = true;
            database.sourceDao().insert(newSource);
        }
        long id = database.artworkDao().insert(this, artwork);
        if (id == 0) {
            Log.w(TAG, "Unable to write artwork information to MuzeiProvider");
            return false;
        }
        DataClient.GetFdForAssetResponse result = null;
        InputStream in = null;
        try (OutputStream out = getContentResolver().openOutputStream(Artwork.getContentUri(id))) {
            if (out == null) {
                // We've already cached the artwork previously, so call this a success
                return true;
            }
            // Convert asset into a file descriptor and block until it's ready
            result = Tasks.await(dataClient.getFdForAsset(asset));
            in = result.getInputStream();
            if (in == null) {
                Log.w(TAG, "Unable to open asset input stream");
                return false;
            }
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (ExecutionException|InterruptedException|IOException e) {
            Log.e(TAG, "Error writing artwork", e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing artwork input stream", e);
            }
            if (result != null) {
                result.release();
            }
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
