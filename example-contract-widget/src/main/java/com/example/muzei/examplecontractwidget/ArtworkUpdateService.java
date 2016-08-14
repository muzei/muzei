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

package com.example.muzei.examplecontractwidget;

import android.app.IntentService;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;

import java.io.FileNotFoundException;

/**
 * Handle updating all widgets with the latest artwork
 */
public class ArtworkUpdateService extends IntentService {
    private static final String TAG = "ArtworkUpdateService";

    public ArtworkUpdateService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        ComponentName widget = new ComponentName(this, MuzeiAppWidgetProvider.class);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widget);
        if (appWidgetIds.length == 0) {
            // No app widgets, nothing to do
            Log.w(TAG, "No AppWidgets found");
            return;
        }
        Artwork currentArtwork = MuzeiContract.Artwork.getCurrentArtwork(this);
        if (currentArtwork == null) {
            Log.w(TAG, "No current artwork found");
            return;
        }
        String contentDescription = !TextUtils.isEmpty(currentArtwork.getTitle())
                ? currentArtwork.getTitle()
                : currentArtwork.getByline();
        Bitmap image;
        try {
            image = MuzeiContract.Artwork.getCurrentArtworkBitmap(this);
            // getCurrentArtworkBitmap is a naive method so we manually reduce the size based on the screen size
            // Consider using BitmapFactory.decodeStream(contentResolver.openInputStream(CONTENT_URI), options)
            // where options subsamples the image to the appropriate size if you don't need to full size image
            image = scaleBitmap(image);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Could not find current artwork image", e);
            return;
        }
        if (image == null) {
            Log.w(TAG, "No current artwork bitmap found");
            return;
        }
        // Update the widget(s) with the new artwork information
        PackageManager packageManager = getPackageManager();
        Intent launchIntent = packageManager.getLaunchIntentForPackage("net.nurik.roman.muzei");
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        for (int widgetId : appWidgetIds) {
            RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.widget);
            remoteViews.setContentDescription(R.id.background, contentDescription);
            remoteViews.setImageViewBitmap(R.id.background, image);
            remoteViews.setOnClickPendingIntent(R.id.background, pendingIntent);
            appWidgetManager.updateAppWidget(widgetId, remoteViews);
        }
    }

    private Bitmap scaleBitmap(Bitmap image) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int largestDimension = Math.max(metrics.widthPixels, metrics.heightPixels);
        int width = image.getWidth();
        int height = image.getHeight();
        if (width > height) {
            // landscape
            float ratio = (float) width / largestDimension;
            width = largestDimension;
            height = (int) (height / ratio);
        } else if (height > width) {
            // portrait
            float ratio = (float) height / largestDimension;
            height = largestDimension;
            width = (int) (width / ratio);
        } else {
            height = largestDimension;
            width = largestDimension;
        }
        return Bitmap.createScaledBitmap(image, width, height, true);
    }
}
