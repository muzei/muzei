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

package com.google.android.apps.muzei.render

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.support.media.ExifInterface
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiContract
import java.io.IOException

class RealRenderController(context: Context, renderer: MuzeiBlurRenderer,
                           callbacks: RenderController.Callbacks)
    : RenderController(context, renderer, callbacks) {

    companion object {
        private const val TAG = "RealRenderController"
    }

    private val contentObserver: ContentObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            reloadCurrentArtwork(false)
        }
    }

    init {
        context.contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, contentObserver)
        reloadCurrentArtwork(false)
    }

    override fun destroy() {
        super.destroy()
        context.contentResolver.unregisterContentObserver(contentObserver)
    }

    override fun openDownloadedCurrentArtwork(forceReload: Boolean): BitmapRegionLoader? {
        // Load the stream
        try {
            // Check if there's rotation
            var rotation = 0
            try {
                context.contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI)?.use { input ->
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

            return BitmapRegionLoader.newInstance(
                    context.contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI), rotation)
        } catch (e: IOException) {
            Log.e(TAG, "Error loading image", e)
            return null
        }

    }
}
