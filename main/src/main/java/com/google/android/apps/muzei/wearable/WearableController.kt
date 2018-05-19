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
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.render.BitmapRegionLoader
import com.google.android.apps.muzei.render.sampleSize
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.AvailabilityException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.experimental.launch
import java.io.ByteArrayOutputStream
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

    private val wearableContentObserver by lazy {
        object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean, uri: Uri) {
                launch {
                    updateArtwork()
                }
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Update Android Wear whenever the artwork changes
        context.contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, wearableContentObserver)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.contentResolver.unregisterContentObserver(wearableContentObserver)
    }

    private suspend fun updateArtwork() {
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

        val image: Bitmap? = BitmapRegionLoader.newInstance(context.contentResolver,
                MuzeiContract.Artwork.CONTENT_URI)?.use { regionLoader ->
            val width = regionLoader.width
            val height = regionLoader.height
            val shortestLength = Math.min(width, height)
            val options = BitmapFactory.Options()
            options.inSampleSize = shortestLength.sampleSize(320)
            regionLoader.decodeRegion(Rect(0, 0, width, height), options)
        } ?: return

        val artwork = MuzeiDatabase.getInstance(context).artworkDao().currentArtworkBlocking
        if (image != null && artwork != null) {
            val byteStream = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
            val asset = Asset.createFromBytes(byteStream.toByteArray())
            val dataMapRequest = PutDataMapRequest.create("/artwork").apply {
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
}
