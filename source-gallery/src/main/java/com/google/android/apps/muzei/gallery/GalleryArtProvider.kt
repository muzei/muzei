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
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
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

    override fun openArtworkInfo(artwork: Artwork): Boolean {
        val context = context ?: return false
        val uri = artwork.webUri ?: artwork.persistentUri ?: return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open artwork info for $uri", e)
            false
        }
    }

    override fun isArtworkValid(artwork: Artwork): Boolean {
        val context = context ?: return false
        val metadata = artwork.metadata?.toUri() ?: return false
        val cachedFile = GalleryProvider.getCacheFileForUri(context, metadata)
        if (cachedFile?.exists() == true) {
            // We have a local copy of the image
            return true
        }
        // It is a URI we have persistent access to.
        // Check to see if that's still the case
        val imageUri = artwork.persistentUri ?: return false
        return try {
            context.contentResolver.query(imageUri,
                    null, null, null, null).use { data ->
                // Assume if we can access the row, we can access the image itself
                data?.moveToFirst() == true
            }
        } catch (e: Exception) {
            // An exception could mean a lot of things.
            // Delete any cached image and defer to openFile
            artwork.data.run {
                if (exists()) {
                    delete()
                }
            }
            true
        }
    }

    @Throws(IOException::class)
    override fun openFile(artwork: Artwork): InputStream {
        try {
            return super.openFile(artwork)
        } catch(e: FileNotFoundException) {
            // If the source image was deleted, we won't be able to access it again
            throw SecurityException("Source image was deleted: ${e.message}")
        }
    }
}
