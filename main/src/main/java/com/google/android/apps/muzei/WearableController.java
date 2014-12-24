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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.render.BitmapRegionLoader;
import com.google.android.apps.muzei.render.ImageUtil;
import com.google.android.apps.muzei.util.LogUtil;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import static com.google.android.apps.muzei.util.LogUtil.LOGW;
import static com.google.android.apps.muzei.util.LogUtil.LOGV;

/**
 * Controller for working with the Android Wear API. Also in charge of dealing with
 * Google Play Services.
 */
public class WearableController {
    private static final String TAG = LogUtil.makeLogTag(WearableController.class);

    public static synchronized void updateDataLayer(Context context,
                                                    Artwork artwork, BitmapRegionLoader bitmapRegionLoader) {
        if (ConnectionResult.SUCCESS != GooglePlayServicesUtil.isGooglePlayServicesAvailable(context)
                || Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return;
        }
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult = googleApiClient.blockingConnect(5, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            if (connectionResult.getErrorCode() == ConnectionResult.API_UNAVAILABLE) {
                LOGV(TAG, "Wearable API unavailable, cancelling updateDataLayer request");
            } else {
                LOGW(TAG, "onConnectionFailed: " + connectionResult);
            }
            return;
        }
        Rect rect = new Rect();
        int width = bitmapRegionLoader.getWidth();
        int height = bitmapRegionLoader.getHeight();
        rect.set(0, 0, width, height);
        BitmapFactory.Options options = new BitmapFactory.Options();
        if (width > height) {
            options.inSampleSize = ImageUtil.calculateSampleSize(height, 320);
        } else {
            options.inSampleSize = ImageUtil.calculateSampleSize(width, 320);
        }
        Bitmap image = bitmapRegionLoader.decodeRegion(rect, options);
        if (image != null) {
            final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            Asset asset = Asset.createFromBytes(byteStream.toByteArray());
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create("/artwork");
            dataMapRequest.getDataMap().putDataMap("artwork", DataMap.fromBundle(artwork.toBundle()));
            dataMapRequest.getDataMap().putAsset("image", asset);
            Wearable.DataApi.putDataItem(googleApiClient, dataMapRequest.asPutDataRequest()).await();
        }
        googleApiClient.disconnect();
    }
}
