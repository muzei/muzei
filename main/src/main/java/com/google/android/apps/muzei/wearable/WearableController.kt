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
import android.graphics.Bitmap
import android.util.Log
import com.google.android.apps.muzei.render.BitmapRegionLoader
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.observeNonNull
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

    override fun onCreate(owner: LifecycleOwner) {
        // Update Android Wear whenever the artwork changes
        MuzeiDatabase.getInstance(context).artworkDao().currentArtwork
                .observeNonNull(owner) { artwork ->
                    launch {
                        updateArtwork(artwork)
                    }
                }
    }

    private suspend fun updateArtwork(artwork: Artwork) {
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

        val image: Bitmap = BitmapRegionLoader.newInstance(context.contentResolver,
                artwork.contentUri)?.use { regionLoader ->
            regionLoader.decode(320)
        } ?: return

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
