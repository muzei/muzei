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

package com.google.android.apps.muzei.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.support.annotation.LayoutRes;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.render.ImageUtil;

import net.nurik.roman.muzei.R;

import java.io.FileNotFoundException;

/**
 * Async operation used to update the AppWidget.
 */
class AppWidgetUpdateTask extends AsyncTask<Void,Void,Boolean> {
    private static final String TAG = "AppWidgetUpdateTask";

    private final Context mContext;

    AppWidgetUpdateTask(Context context) {
        mContext = context;
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        ComponentName widget = new ComponentName(mContext, MuzeiAppWidgetProvider.class);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widget);
        if (appWidgetIds.length == 0) {
            // No app widgets, nothing to do
            Log.i(TAG, "No AppWidgets found");
            return true;
        }
        String[] projection = new String[] {BaseColumns._ID,
            MuzeiContract.Artwork.COLUMN_NAME_TITLE,
            MuzeiContract.Artwork.COLUMN_NAME_BYLINE,
        MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND};
        ContentResolver contentResolver = mContext.getContentResolver();
        Cursor artwork = contentResolver.query(MuzeiContract.Artwork.CONTENT_URI, projection, null, null, null);
        if (artwork == null || !artwork.moveToFirst()) {
            Log.w(TAG, "No current artwork found");
            if (artwork != null) {
                artwork.close();
            }
            return false;
        }
        String title = artwork.getString(artwork.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_TITLE));
        String byline = artwork.getString(artwork.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_BYLINE));
        String contentDescription = !TextUtils.isEmpty(title)
                ? title
                : byline;
        Uri imageUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI,
                artwork.getLong(artwork.getColumnIndex(BaseColumns._ID)));
        boolean supportsNextArtwork = artwork.getInt(
                artwork.getColumnIndex(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND)) != 0;
        artwork.close();

        // Update the widget(s) with the new artwork information
        PackageManager packageManager = mContext.getPackageManager();
        Intent launchIntent = packageManager.getLaunchIntentForPackage(mContext.getPackageName());
        PendingIntent launchPendingIntent = PendingIntent.getActivity(mContext,
                0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Intent nextArtworkIntent = new Intent(mContext, MuzeiAppWidgetProvider.class);
        nextArtworkIntent.setAction(MuzeiAppWidgetProvider.ACTION_NEXT_ARTWORK);
        PendingIntent nextArtworkPendingIntent = PendingIntent.getBroadcast(mContext,
                0, nextArtworkIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        int smallWidgetHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.widget_small_height_breakpoint);
        for (int widgetId : appWidgetIds) {
            Bundle extras = appWidgetManager.getAppWidgetOptions(widgetId);
            int widgetWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), displayMetrics);
            int widgetHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), displayMetrics);
            Bitmap image;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(contentResolver.openInputStream(imageUri), null, options);
                int width = options.outWidth;
                int height = options.outHeight;
                options.inJustDecodeBounds = false;
                options.inSampleSize = Math.min(ImageUtil.calculateSampleSize(width, widgetWidth),
                        ImageUtil.calculateSampleSize(height, widgetHeight));
                image = BitmapFactory.decodeStream(
                        contentResolver.openInputStream(imageUri), null, options);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Could not find current artwork image", e);
                return false;
            }
            // Even after using sample size to scale an image down, it might be larger than the
            // maximum bitmap memory usage for widgets
            Bitmap scaledImage = scaleBitmap(image, widgetWidth, widgetHeight);
            @LayoutRes int widgetLayout = widgetHeight < smallWidgetHeight
                    ? R.layout.widget_small
                    : R.layout.widget;
            RemoteViews remoteViews = new RemoteViews(mContext.getPackageName(), widgetLayout);
            remoteViews.setContentDescription(R.id.widget_background, contentDescription);
            remoteViews.setImageViewBitmap(R.id.widget_background, scaledImage);
            remoteViews.setOnClickPendingIntent(R.id.widget_background, launchPendingIntent);
            remoteViews.setOnClickPendingIntent(R.id.widget_next_artwork, nextArtworkPendingIntent);
            if (supportsNextArtwork) {
                remoteViews.setViewVisibility(R.id.widget_next_artwork, View.VISIBLE);
            } else {
                remoteViews.setViewVisibility(R.id.widget_next_artwork, View.GONE);
            }
            appWidgetManager.updateAppWidget(widgetId, remoteViews);
        }
        return true;
    }

    private Bitmap scaleBitmap(Bitmap image, int widgetWidth, int widgetHeight) {
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0 ||
                widgetWidth == 0 || widgetHeight == 0) {
            return null;
        }
        int largestDimension = Math.max(widgetWidth, widgetHeight);
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
