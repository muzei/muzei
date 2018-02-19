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

package com.google.android.apps.muzei.wearable

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.support.media.ExifInterface
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.render.ImageUtil
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.AvailabilityException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Controller for updating Android Wear devices with new wallpapers.
 */
class WearableController(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "WearableController"
    }

    private val wearableHandlerThread by lazy {
        HandlerThread("MuzeiWallpaperService-Wearable").apply {
            start()
        }
    }
    private val wearableContentObserver by lazy {
        object : ContentObserver(Handler(wearableHandlerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri) {
                updateArtwork()
            }
        }
    }

    private val rotation: Int
        get() {
            var rotation = 0
            try {
                context.contentResolver.openInputStream(
                        MuzeiContract.Artwork.CONTENT_URI)?.use { input ->
                    val exifInterface = ExifInterface(input)
                    val orientation = exifInterface.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270
                    }
                }
            } catch (ignored: IOException) {
            } catch (ignored: NumberFormatException) {
            } catch (ignored: StackOverflowError) {
            }

            return rotation
        }

    override fun onCreate(owner: LifecycleOwner) {
        // Update Android Wear whenever the artwork changes
        context.contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, wearableContentObserver)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.contentResolver.unregisterContentObserver(wearableContentObserver)
        wearableHandlerThread.quitSafely()
    }

    private fun updateArtwork() {
        if (ConnectionResult.SUCCESS != GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
            return
        }
        val dataClient = Wearable.getDataClient(context)
        try {
            Tasks.await(GoogleApiAvailability.getInstance()
                    .checkApiAvailability(dataClient), 5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            if (e.cause is AvailabilityException) {
                val connectionResult = (e.cause as AvailabilityException)
                        .getConnectionResult(dataClient)
                if (connectionResult.errorCode != ConnectionResult.API_UNAVAILABLE) {
                    Log.w(TAG, "onConnectionFailed: $connectionResult", e.cause)
                }
            } else {
                Log.w(TAG, "Unable to check for Wear API availability", e)
            }
            return
        } catch (e: InterruptedException) {
            Log.w(TAG, "Unable to check for Wear API availability", e)
            return
        } catch (e: TimeoutException) {
            Log.w(TAG, "Unable to check for Wear API availability", e)
            return
        }

        val image: Bitmap? = getCurrentArtwork()?.let { image ->
            if (rotation != 0) {
                // Rotate the image so that Wear always gets a right side up image
                Bitmap.createBitmap(image, 0, 0, image.width, image.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true)
            } else {
                image
            }
        }

        if (image != null) {
            val byteStream = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
            val asset = Asset.createFromBytes(byteStream.toByteArray())
            val dataMapRequest = PutDataMapRequest.create("/artwork").apply {
                val artwork = MuzeiDatabase.getInstance(context).artworkDao().currentArtworkBlocking
                dataMap.putDataMap("artwork", artwork.toDataMap())
                dataMap.putAsset("image", asset)
            }
            try {
                Tasks.await<DataItem>(dataClient.putDataItem(dataMapRequest.asPutDataRequest().setUrgent()))
            } catch (e: ExecutionException) {
                Log.w(TAG, "Error uploading artwork to Wear", e)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Error uploading artwork to Wear", e)
            }
        }
    }

    private fun getCurrentArtwork(): Bitmap? {
        return try {
            val contentResolver = context.contentResolver
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            options.inJustDecodeBounds = false
            if (options.outWidth > options.outHeight) {
                options.inSampleSize = ImageUtil.calculateSampleSize(options.outHeight, 320)
            } else {
                options.inSampleSize = ImageUtil.calculateSampleSize(options.outWidth, 320)
            }
            contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Unable to read artwork to update Android Wear", e)
            null
        }
    }
}
