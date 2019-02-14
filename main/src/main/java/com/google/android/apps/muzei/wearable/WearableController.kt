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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.observeNonNull
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.AvailabilityException
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
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
                    GlobalScope.launch {
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
            GoogleApiAvailability.getInstance().checkApiAvailability(dataClient).await()
        } catch (e: AvailabilityException) {
            val connectionResult = e.getConnectionResult(dataClient)
            if (connectionResult.errorCode != ConnectionResult.API_UNAVAILABLE) {
                Log.w(TAG, "onConnectionFailed: $connectionResult", e.cause)
            }
            return
        } catch (e: ExecutionException) {
            Log.w(TAG, "Unable to check for Wear API availability", e)
            return
        } catch (e: InterruptedException) {
            Log.w(TAG, "Unable to check for Wear API availability", e)
            return
        } catch (e: TimeoutException) {
            Log.w(TAG, "Unable to check for Wear API availability", e)
            return
        }

        val image: Bitmap = ImageLoader.decode(
                context.contentResolver, artwork.contentUri,
                320) ?: return

        val byteStream = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
        val asset = Asset.createFromBytes(byteStream.toByteArray())
        val dataMapRequest = PutDataMapRequest.create("/artwork").apply {
            dataMap.putDataMap("artwork", artwork.toDataMap())
            dataMap.putAsset("image", asset)
        }
        try {
            dataClient.putDataItem(dataMapRequest.asPutDataRequest().setUrgent()).await()
        } catch (e: ExecutionException) {
            Log.w(TAG, "Error uploading artwork to Wear", e)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Error uploading artwork to Wear", e)
        }
    }
}
