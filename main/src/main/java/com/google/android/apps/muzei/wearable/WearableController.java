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

package com.google.android.apps.muzei.wearable;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.render.ImageUtil;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.TimeUnit;

/**
 * Controller for working with the Android Wear API. Also in charge of dealing with
 * Google Play Services.
 */
public class WearableController {
    private static final String TAG = "WearableController";

    private static @Nullable GoogleApiClient createdConnectedWearableClient(Context context) {
        if (ConnectionResult.SUCCESS != GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                || Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return null;
        }
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult = googleApiClient.blockingConnect(5, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            if (connectionResult.getErrorCode() != ConnectionResult.API_UNAVAILABLE) {
                Log.w(TAG, "onConnectionFailed: " + connectionResult);
            }
            return null;
        }
        return googleApiClient;
    }

    public static synchronized void updateSource(Context context) {
        if (ConnectionResult.SUCCESS != GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                || Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return;
        }
        GoogleApiClient googleApiClient = createdConnectedWearableClient(context);
        if (googleApiClient == null) {
            // Connection failed
            return;
        }
        Cursor cursor = context.getContentResolver().query(MuzeiContract.Sources.CONTENT_URI,
                new String[] {MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                MuzeiContract.Sources.COLUMN_NAME_COMMANDS},
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + "=1", null, null);
        if (cursor == null) {
            googleApiClient.disconnect();
            return;
        }
        PutDataMapRequest dataMapRequest = PutDataMapRequest.create("/source");
        DataMap dataMap = dataMapRequest.getDataMap();
        if (cursor.moveToFirst()) {
            dataMap.putString(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                    cursor.getString(0));
            dataMap.putBoolean(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                    cursor.getInt(1) != 0);
            dataMap.putString(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                    cursor.getString(2));
            dataMap.putBoolean(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                    cursor.getInt(3) != 0);
            dataMap.putBoolean(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                    cursor.getInt(4) != 0);
            dataMap.putString(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                    cursor.getString(5));
        }
        cursor.close();
        Wearable.DataApi.putDataItem(googleApiClient, dataMapRequest.asPutDataRequest().setUrgent()).await();
        googleApiClient.disconnect();

    }

    public static synchronized void updateArtwork(Context context) {
        GoogleApiClient googleApiClient = WearableController.createdConnectedWearableClient(context);
        if (googleApiClient == null) {
            // Connection failed
            return;
        }
        ContentResolver contentResolver = context.getContentResolver();
        Bitmap image = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI), null, options);
            options.inJustDecodeBounds = false;
            if (options.outWidth > options.outHeight) {
                options.inSampleSize = ImageUtil.calculateSampleSize(options.outHeight, 320);
            } else {
                options.inSampleSize = ImageUtil.calculateSampleSize(options.outWidth, 320);
            }
            image = BitmapFactory.decodeStream(contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI), null, options);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Unable to read artwork to update Android Wear", e);
        }
        if (image != null) {
            final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            Asset asset = Asset.createFromBytes(byteStream.toByteArray());
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create("/artwork");
            Artwork artwork = MuzeiContract.Artwork.getCurrentArtwork(context);
            dataMapRequest.getDataMap().putDataMap("artwork", DataMap.fromBundle(artwork.toBundle()));
            dataMapRequest.getDataMap().putAsset("image", asset);
            Wearable.DataApi.putDataItem(googleApiClient, dataMapRequest.asPutDataRequest().setUrgent()).await();
        }
        googleApiClient.disconnect();
    }
}
