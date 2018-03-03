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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.support.annotation.LayoutRes
import android.support.media.ExifInterface
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent
import com.google.android.apps.muzei.render.BitmapRegionLoader
import com.google.android.apps.muzei.render.sampleSize
import com.google.android.apps.muzei.room.MuzeiDatabase
import net.nurik.roman.muzei.R
import org.greenrobot.eventbus.EventBus
import java.io.IOException

/**
 * Async operation used to update the widget or provide a preview for pinning the widget.
 */
open class AppWidgetUpdateTask(@field:SuppressLint("StaticFieldLeak") private val context: Context,
                               private val showingPreview: Boolean = false)
    : AsyncTask<Void, Void, Boolean>() {

    companion object {
        private const val TAG = "AppWidgetUpdateTask"
    }

    override fun doInBackground(vararg params: Void): Boolean? {
        val widget = ComponentName(context, MuzeiAppWidgetProvider::class.java)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(widget)
        if (!showingPreview && appWidgetIds.isEmpty()) {
            // No app widgets, nothing to do
            return true
        } else if (showingPreview && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !appWidgetManager.isRequestPinAppWidgetSupported)) {
            // No preview to show
            return false
        }
        val source = MuzeiDatabase.getInstance(context).sourceDao().currentSourceBlocking
        val artwork = MuzeiDatabase.getInstance(context).artworkDao().currentArtworkBlocking
        if (source == null || artwork == null) {
            Log.w(TAG, "No current artwork found")
            return false
        }
        val contentDescription = artwork.title ?: artwork.byline
        val imageUri = artwork.contentUri
        val wasce = EventBus.getDefault().getStickyEvent(
                WallpaperActiveStateChangedEvent::class.java)
        val supportsNextArtwork = wasce?.isActive == true && source.supportsNextArtwork

        // Update the widget(s) with the new artwork information
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val launchPendingIntent = PendingIntent.getActivity(context,
                0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        val nextArtworkIntent = Intent(context, MuzeiAppWidgetProvider::class.java).apply {
            action = MuzeiAppWidgetProvider.ACTION_NEXT_ARTWORK
        }
        val nextArtworkPendingIntent = PendingIntent.getBroadcast(context,
                0, nextArtworkIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        if (showingPreview) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                val remoteViews = createRemoteViews(imageUri, contentDescription,
                        launchPendingIntent, nextArtworkPendingIntent, supportsNextArtwork,
                        context.resources.getDimensionPixelSize(R.dimen.widget_min_width),
                        context.resources.getDimensionPixelSize(R.dimen.widget_min_height))
                val extras = Bundle()
                extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, remoteViews)
                try {
                    return appWidgetManager.requestPinAppWidget(widget, extras, null)
                } catch (ignored: IllegalStateException) {
                    // The user exited out of the app before we could pop up the pin widget dialog
                }

            }
            return false
        }
        val displayMetrics = context.resources.displayMetrics
        val minWidgetSize = context.resources.getDimensionPixelSize(
                R.dimen.widget_min_size)
        for (widgetId in appWidgetIds) {
            val extras = appWidgetManager.getAppWidgetOptions(widgetId)
            var widgetWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat(), displayMetrics).toInt()
            widgetWidth = Math.max(Math.min(widgetWidth, displayMetrics.widthPixels), minWidgetSize)
            var widgetHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    extras.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat(), displayMetrics).toInt()
            widgetHeight = Math.max(Math.min(widgetHeight, displayMetrics.heightPixels), minWidgetSize)
            var success = false
            while (!success) {
                val remoteViews = createRemoteViews(imageUri, contentDescription, launchPendingIntent,
                        nextArtworkPendingIntent, supportsNextArtwork, widgetWidth, widgetHeight) ?: return false
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
        return true
    }

    private fun createRemoteViews(imageUri: Uri, contentDescription: String,
                                  launchPendingIntent: PendingIntent,
                                  nextArtworkPendingIntent: PendingIntent,
                                  supportsNextArtwork: Boolean,
                                  widgetWidth: Int, widgetHeight: Int): RemoteViews? {
        val contentResolver = context.contentResolver
        val smallWidgetHeight = context.resources.getDimensionPixelSize(
                R.dimen.widget_small_height_breakpoint)
        val image: Bitmap?
        try {
            // Check if there's rotation
            var rotation = 0
            try {
                contentResolver.openInputStream(imageUri)?.use { input ->
                    val exifInterface = ExifInterface(input)
                    val orientation = exifInterface.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't open EXIF interface on artwork", e)
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Couldn't open EXIF interface on artwork", e)
            } catch (e: StackOverflowError) {
                Log.w(TAG, "Couldn't open EXIF interface on artwork", e)
            }

            val regionLoader = BitmapRegionLoader.newInstance(
                    contentResolver.openInputStream(imageUri), rotation)
                    ?: throw IOException("BitmapRegionLoader returned null: bad image format?")
            val width = regionLoader.width
            val height = regionLoader.height
            val options = BitmapFactory.Options()
            options.inSampleSize = Math.max(width.sampleSize(widgetWidth / 2),
                    height.sampleSize(widgetHeight / 2))
            image = regionLoader.decodeRegion(Rect(0, 0, width, height), options)
            regionLoader.destroy()
        } catch (e: IOException) {
            Log.e(TAG, "Could not load current artwork image", e)
            return null
        }

        // Even after using sample size to scale an image down, it might be larger than the
        // maximum bitmap memory usage for widgets
        val scaledImage = image?.scale(widgetWidth, widgetHeight)
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

    private fun Bitmap.scale(widgetWidth: Int, widgetHeight: Int) : Bitmap? {
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
}
