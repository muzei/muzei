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

package com.example.muzei.watchface

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import com.google.android.apps.muzei.api.MuzeiContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

/**
 * Class which provides access to the current Muzei artwork image. It also
 * registers a ContentObserver to ensure the image stays up to date
 */
class ArtworkImageLoader(private val context: Context) {

    private val requestSizeSharedFlow = MutableSharedFlow<Size?>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)
    var requestedSize: Size?
        get() = requestSizeSharedFlow.replayCache.firstOrNull()
        set(value) {
            requestSizeSharedFlow.tryEmit(value)
        }

    private val unsizedArtworkFlow: Flow<Bitmap> = callbackFlow {
        // Create a lambda that should be ran to update the artwork
        val updateArtwork = {
            launch(Dispatchers.IO) {
                try {
                    MuzeiContract.Artwork.getCurrentArtworkBitmap(context)?.run {
                        send(this)
                    }
                } catch (e: FileNotFoundException) {
                    Log.e("ArtworkImageLoader", "Error getting artwork image", e)
                }
            }
        }
        // Set up a ContentObserver that will update the artwork when it changes
        val contentObserver: ContentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                updateArtwork()
            }
        }
        context.contentResolver.registerContentObserver(
                MuzeiContract.Artwork.CONTENT_URI, true, contentObserver)
        // And update the artwork immediately to ensure we have the
        // latest artwork
        updateArtwork()

        awaitClose {
            context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }

    val artworkFlow = unsizedArtworkFlow.combine(
            requestSizeSharedFlow.distinctUntilChanged()
    ) { image, size ->
        // Resize the image to the specified size
        when {
            size == null -> image
            image.width > image.height -> {
                val scalingFactor = size.height * 1f / image.height
                Bitmap.createScaledBitmap(image, (scalingFactor * image.width).toInt(),
                        size.height, true)
            }
            else -> {
                val scalingFactor = size.width * 1f / image.width
                Bitmap.createScaledBitmap(image, size.width,
                        (scalingFactor * image.height).toInt(), true)
            }
        }
    }
}