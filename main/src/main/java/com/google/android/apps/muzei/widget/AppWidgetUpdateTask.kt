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

package com.google.android.apps.muzei.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sources.allowsNextArtwork
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

private const val TAG = "updateAppWidget"

/**
 * Provide a preview for pinning the widget
 */
@RequiresApi(Build.VERSION_CODES.O)
suspend fun showWidgetPreview(context: Context) {
    val widget = ComponentName(context, MuzeiAppWidgetProvider::class.java)
    val appWidgetManager = AppWidgetManager.getInstance(context)
    if (!appWidgetManager.isRequestPinAppWidgetSupported) {
        // No preview to show
        return
    }
    val provider = MuzeiDatabase.getInstance(context).providerDao().getCurrentProvider()
    val artwork = MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtwork()
    if (provider == null || artwork == null) {
        Log.w(TAG, "No current artwork found")
        return
    }
    val remoteViews = createRemoteViews(context, provider, artwork,
            context.resources.getDimensionPixelSize(R.dimen.widget_min_width),
            context.resources.getDimensionPixelSize(R.dimen.widget_min_height))
            ?: return
    val extras = bundleOf(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW to remoteViews)
    try {
        appWidgetManager.requestPinAppWidget(widget, extras, null)
    } catch (ignored: IllegalStateException) {
        // The user exited out of the app before we could pop up the pin widget dialog
    }
}

/**
 * Async operation used to update the widget or provide a preview for pinning the widget.
 */
suspend fun updateAppWidget(context: Context) = coroutineScope {
    val widget = ComponentName(context, MuzeiAppWidgetProvider::class.java)
    val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@coroutineScope
    val appWidgetIds = appWidgetManager.getAppWidgetIds(widget)
    if (appWidgetIds.isEmpty()) {
        // No app widgets, nothing to do
        return@coroutineScope
    }
    val provider = MuzeiDatabase.getInstance(context).providerDao().getCurrentProvider()
    val artwork = MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtwork()
    if (provider == null || artwork == null) {
        Log.w(TAG, "No current artwork found")
        return@coroutineScope
    }
    val displayMetrics = context.resources.displayMetrics
    val minWidgetSize = context.resources.getDimensionPixelSize(
            R.dimen.widget_min_size)
    for (widgetId in appWidgetIds) {
        launch {
            val extras = appWidgetManager.getAppWidgetOptions(widgetId)
            var widgetWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat(), displayMetrics).toInt()
            widgetWidth = Math.max(Math.min(widgetWidth, displayMetrics.widthPixels), minWidgetSize)
            var widgetHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat(), displayMetrics).toInt()
            widgetHeight = Math.max(Math.min(widgetHeight, displayMetrics.heightPixels), minWidgetSize)
            var success = false
            while (!success) {
                val remoteViews = createRemoteViews(context, provider, artwork,
                        widgetWidth, widgetHeight)
                        ?: return@launch
                try {
                    appWidgetManager.updateAppWidget(widgetId, remoteViews)
                    success = true
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "App widget size $widgetWidth x $widgetHeight exceeded maximum memory, reducing quality")
                    widgetWidth /= 2
                    widgetHeight /= 2
                }
            }
        }
    }
}

private suspend fun createRemoteViews(
        context: Context,
        provider: Provider,
        artwork: Artwork,
        widgetWidth: Int,
        widgetHeight: Int
): RemoteViews? {
    val contentDescription = artwork.title ?: artwork.byline ?: ""
    val imageUri = artwork.contentUri
    val supportsNextArtwork = WallpaperActiveState.value == true &&
            provider.allowsNextArtwork(context)

    // Update the widget(s) with the new artwork information
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val launchPendingIntent = PendingIntent.getActivity(context,
            0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    val nextArtworkIntent = Intent(context, MuzeiAppWidgetProvider::class.java).apply {
        action = MuzeiAppWidgetProvider.ACTION_NEXT_ARTWORK
    }
    val nextArtworkPendingIntent = PendingIntent.getBroadcast(context,
            0, nextArtworkIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    val smallWidgetHeight = context.resources.getDimensionPixelSize(
            R.dimen.widget_small_height_breakpoint)
    val image = ImageLoader.decode(
            context.contentResolver, imageUri,
            widgetWidth / 2, widgetHeight / 2) ?: return null

    // Even after using sample size to scale an image down, it might be larger than the
    // maximum bitmap memory usage for widgets
    val scaledImage = image.scale(widgetWidth, widgetHeight)
    @LayoutRes val widgetLayout = if (widgetHeight < smallWidgetHeight)
        R.layout.widget_small
    else
        R.layout.widget
    val remoteViews = RemoteViews(context.packageName, widgetLayout)
    remoteViews.setContentDescription(R.id.widget_background, contentDescription)
    remoteViews.setImageViewBitmap(R.id.widget_background, scaledImage)
    remoteViews.setOnClickPendingIntent(R.id.widget_background, launchPendingIntent)
    remoteViews.setOnClickPendingIntent(R.id.widget_next_artwork, nextArtworkPendingIntent)
    if (supportsNextArtwork) {
        remoteViews.setViewVisibility(R.id.widget_next_artwork, View.VISIBLE)
    } else {
        remoteViews.setViewVisibility(R.id.widget_next_artwork, View.GONE)
    }
    return remoteViews
}

private fun Bitmap.scale(widgetWidth: Int, widgetHeight: Int): Bitmap? {
    if (width == 0 || height == 0 ||
            widgetWidth == 0 || widgetHeight == 0) {
        return null
    }
    val largestDimension = Math.max(widgetWidth, widgetHeight)
    var width = width
    var height = height
    when {
        width > height -> {
            // landscape
            val ratio = width.toFloat() / largestDimension
            width = largestDimension
            height = (height / ratio).toInt()
        }
        height > width -> {
            // portrait
            val ratio = height.toFloat() / largestDimension
            height = largestDimension
            width = (width / ratio).toInt()
        }
        else -> {
            height = largestDimension
            width = largestDimension
        }
    }
    return Bitmap.createScaledBitmap(this, width, height, true)
}
