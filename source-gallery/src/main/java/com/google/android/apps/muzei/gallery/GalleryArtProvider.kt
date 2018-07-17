/*
 * Copyright 2018 Google Inc.
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

package com.google.android.apps.muzei.gallery

import android.annotation.SuppressLint
import android.util.Log
import androidx.core.net.toUri
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import kotlinx.coroutines.experimental.launch
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class GalleryArtProvider: MuzeiArtProvider() {
    companion object {
        private const val TAG = "GalleryArtProvider"
    }

    override fun onLoadRequested(initial: Boolean) {
        GalleryScanWorker.enqueueRescan()
    }

    @SuppressLint("Recycle")
    override fun getDescription(): String {
        val context = context ?: return super.getDescription()
        val chosenPhotos = GalleryDatabase.getInstance(context)
                .chosenPhotoDao()
                .chosenPhotosBlocking
        if (chosenPhotos.isEmpty()) {
            return context.getString(R.string.gallery_description)
        }
        query(contentUri, arrayOf(), null, null, null).use { allImages ->
            val numImages = allImages.count
            return context.resources.getQuantityString(
                    R.plurals.gallery_description_choice_template,
                    numImages, numImages)
        }
    }

    @Throws(IOException::class)
    override fun openFile(artwork: Artwork): InputStream {
        val metadata = artwork.metadata?.toUri()
                ?: throw FileNotFoundException("All Gallery artwork should have a metadata Uri")
        try {
            return super.openFile(artwork)
        } catch (e: Exception) {
            Log.i(TAG, "Unable to load $metadata, deleting the row", e)
            launch {
                GalleryDatabase.getInstance(context).chosenPhotoDao().delete(
                        context, metadata)
            }
            throw SecurityException("No permission to load $metadata")
        }
    }
}
