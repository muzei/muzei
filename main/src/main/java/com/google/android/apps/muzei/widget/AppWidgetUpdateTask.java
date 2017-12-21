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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.LayoutRes;
import android.support.annotation.Nullable;
import android.support.media.ExifInterface;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.render.BitmapRegionLoader;
import com.google.android.apps.muzei.render.ImageUtil;
import com.google.android.apps.muzei.room.ArtworkSource;
import com.google.android.apps.muzei.room.MuzeiDatabase;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.io.InputStream;

/**
 * Async operation used to update the widget or provide a preview for pinning the widget.
 */
public class AppWidgetUpdateTask extends AsyncTask<ArtworkSource,Void,Boolean> {
    private static final String TAG = "AppWidgetUpdateTask";

    private final Context mContext;
    private final boolean mShowingPreview;

    public AppWidgetUpdateTask(Context context, boolean showingPreview) {
        mContext = context;
        mShowingPreview = showingPreview;
    }

    @Override
    protected Boolean doInBackground(ArtworkSource... params) {
        ComponentName widget = new ComponentName(mContext, MuzeiAppWidgetProvider.class);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widget);
        if (!mShowingPreview && appWidgetIds.length == 0) {
            // No app widgets, nothing to do
            return true;
        } else if (mShowingPreview && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !appWidgetManager.isRequestPinAppWidgetSupported())) {
            // No preview to show
            return false;
        }
        ArtworkSource artworkSource = params != null && params.length == 1
                ? params[0]
                : MuzeiDatabase.getInstance(mContext).artworkDao().getCurrentArtworkWithSourceBlocking();
        if (artworkSource == null) {
            Log.w(TAG, "No current artwork found");
            return false;
        }
        String title = artworkSource.artwork.title;
        String byline = artworkSource.artwork.byline;
        String contentDescription = !TextUtils.isEmpty(title)
                ? title
                : byline;
        Uri imageUri = artworkSource.artwork.getContentUri();
        WallpaperActiveStateChangedEvent wasce = EventBus.getDefault().getStickyEvent(
                WallpaperActiveStateChangedEvent.class);
        boolean supportsNextArtwork = wasce != null && wasce.isActive() && artworkSource.supportsNextArtwork;

        // Update the widget(s) with the new artwork information
        PackageManager packageManager = mContext.getPackageManager();
        Intent launchIntent = packageManager.getLaunchIntentForPackage(mContext.getPackageName());
        PendingIntent launchPendingIntent = PendingIntent.getActivity(mContext,
                0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Intent nextArtworkIntent = new Intent(mContext, MuzeiAppWidgetProvider.class);
        nextArtworkIntent.setAction(MuzeiAppWidgetProvider.ACTION_NEXT_ARTWORK);
        PendingIntent nextArtworkPendingIntent = PendingIntent.getBroadcast(mContext,
                0, nextArtworkIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        if (mShowingPreview) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    appWidgetManager.isRequestPinAppWidgetSupported()) {
                RemoteViews remoteViews = createRemoteViews(imageUri, contentDescription,
                        launchPendingIntent, nextArtworkPendingIntent, supportsNextArtwork,
                        mContext.getResources().getDimensionPixelSize(R.dimen.widget_min_width),
                        mContext.getResources().getDimensionPixelSize(R.dimen.widget_min_height));
                Bundle extras = new Bundle();
                extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, remoteViews);
                try {
                    return appWidgetManager.requestPinAppWidget(widget, extras, null);
                } catch (IllegalStateException ignored) {
                    // The user exited out of the app before we could pop up the pin widget dialog
                }
            }
            return false;
        }
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        int minWidgetSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.widget_min_size);
        for (int widgetId : appWidgetIds) {
            Bundle extras = appWidgetManager.getAppWidgetOptions(widgetId);
            int widgetWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), displayMetrics);
            widgetWidth = Math.max(Math.min(widgetWidth, displayMetrics.widthPixels), minWidgetSize);
            int widgetHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), displayMetrics);
            widgetHeight = Math.max(Math.min(widgetHeight, displayMetrics.heightPixels), minWidgetSize);
            boolean success = false;
            while (!success) {
                RemoteViews remoteViews = createRemoteViews(imageUri, contentDescription, launchPendingIntent,
                        nextArtworkPendingIntent, supportsNextArtwork, widgetWidth, widgetHeight);
                if (remoteViews == null) {
                    return false;
                }
                try {
                    appWidgetManager.updateAppWidget(widgetId, remoteViews);
                    success = true;
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "App widget size " + widgetWidth + " x " + widgetHeight
                            + " exceeded maximum memory, reducing quality");
                    widgetWidth /= 2;
                    widgetHeight /= 2;
                }
            }
        }
        return true;
    }

    @Nullable
    private RemoteViews createRemoteViews(Uri imageUri, String contentDescription,
                                          PendingIntent launchPendingIntent,
                                          PendingIntent nextArtworkPendingIntent, boolean supportsNextArtwork,
                                          int widgetWidth, int widgetHeight) {
        ContentResolver contentResolver = mContext.getContentResolver();
        int smallWidgetHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.widget_small_height_breakpoint);
        Bitmap image;
        try {
            // Check if there's rotation
            int rotation = 0;
            try (InputStream in = contentResolver.openInputStream(imageUri)) {
                if (in == null) {
                    return null;
                }
                ExifInterface exifInterface = new ExifInterface(in);
                int orientation = exifInterface.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                }
            } catch (IOException |NumberFormatException|StackOverflowError e) {
                Log.w(TAG, "Couldn't open EXIF interface on artwork", e);
            }
            BitmapRegionLoader regionLoader = BitmapRegionLoader.newInstance(
                    contentResolver.openInputStream(imageUri), rotation);
            int width = regionLoader.getWidth();
            int height = regionLoader.getHeight();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(ImageUtil.calculateSampleSize(width, widgetWidth / 2),
                    ImageUtil.calculateSampleSize(height, widgetHeight / 2));
            image = regionLoader.decodeRegion(new Rect(0, 0, width, height), options);
        } catch (IOException e) {
            Log.e(TAG, "Could not load current artwork image", e);
            return null;
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
        return remoteViews;
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
