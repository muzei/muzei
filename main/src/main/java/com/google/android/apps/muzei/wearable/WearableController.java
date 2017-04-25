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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.support.media.ExifInterface;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Controller for working with the Android Wear API. Also in charge of dealing with
 * Google Play Services.
 */
public class WearableController {
    private static final String TAG = "WearableController";

    public static synchronized void updateArtwork(Context context) {
        if (ConnectionResult.SUCCESS != GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
            return;
        }
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult = googleApiClient.blockingConnect(5, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            if (connectionResult.getErrorCode() != ConnectionResult.API_UNAVAILABLE) {
                Log.w(TAG, "onConnectionFailed: " + connectionResult);
            }
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
            int rotation = getRotation(context);
            if (rotation != 0) {
                // Rotate the image so that Wear always gets a right side up image
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                image = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(),
                        matrix, true);
            }
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

    private static int getRotation(Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        int rotation = 0;
        try (InputStream in = contentResolver.openInputStream(
                MuzeiContract.Artwork.CONTENT_URI)) {
            if (in == null) {
                return 0;
            }
            ExifInterface exifInterface = new ExifInterface(in);
            int orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
            }
        } catch (IOException|NumberFormatException|StackOverflowError ignored) {
        }
        return rotation;
    }
}
