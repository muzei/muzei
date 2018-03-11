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

import android.graphics.Bitmap
import android.graphics.BitmapFactory.Options
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * Wrapper for [BitmapRegionDecoder] with some extra functionality.
 */
class BitmapRegionLoader @Throws(IOException::class)
private constructor(private val inputStream: InputStream, private val rotation: Int = 0) {

    companion object {
        private const val TAG = "BitmapRegionLoader"

        @Throws(IOException::class)
        fun newInstance(input: InputStream?, rotation: Int = 0): BitmapRegionLoader? {
            if (input == null) {
                return null
            }

            return try {
                BitmapRegionLoader(input, rotation)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error creating BitmapRegionLoader", e)
                null
            }
        }
    }

    private val tempRect = Rect()
    private val bitmapRegionDecoder: BitmapRegionDecoder =
            BitmapRegionDecoder.newInstance(inputStream, false)
                    ?: throw IllegalArgumentException("Unable to create BitmapRegionDecoder")
    private val originalWidth: Int
    private val originalHeight: Int
    private lateinit var rotateMatrix: Matrix

    val width: Int
        @Synchronized get() = if (rotation == 90 || rotation == 270) originalHeight else originalWidth

    val height: Int
        @Synchronized get() = if (rotation == 90 || rotation == 270) originalWidth else originalHeight

    init {
        originalWidth = bitmapRegionDecoder.width
        originalHeight = bitmapRegionDecoder.height
        if (originalWidth <= 0 || originalHeight <= 0) {
            bitmapRegionDecoder.recycle()
            throw IllegalArgumentException("BitmapRegionDecoder does not have a valid size: " +
                    "$originalWidth x $originalHeight")
        }
        checkConfig()
        if (rotation != 0) {
            rotateMatrix = Matrix().apply {
                postRotate(rotation.toFloat())
            }
        }
    }

    /**
     * Renderscript does not support RGBA_F16, so we check to make sure
     * that we get a valid config back when we attempt to get a portion of
     * the BitmapRegionDecoder
     */
    private fun checkConfig() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tempRect.set(0, 0, 1, 1)
            val options = Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmapRegionDecoder.decodeRegion(tempRect, options)
            if (options.outConfig != Bitmap.Config.ARGB_8888) {
                throw IllegalArgumentException("Invalid format, expected ${Bitmap.Config.ARGB_8888}, " +
                        "got ${options.outConfig}")
            }
        }
    }

    /**
     * Key difference, aside from support for rotation, from
     * [BitmapRegionDecoder.decodeRegion] in this implementation is that even
     * if `inBitmap` is given, a sub-bitmap might be returned.
     */
    @Synchronized
    fun decodeRegion(rect: Rect, options: Options = Options()): Bitmap? {
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        var unsampledInBitmapWidth = -1
        var unsampledInBitmapHeight = -1
        val sampleSize = Math.max(1, options.inSampleSize)
        if (options.inBitmap != null) {
            unsampledInBitmapWidth = options.inBitmap.width * sampleSize
            unsampledInBitmapHeight = options.inBitmap.height * sampleSize
        }

        // Decode with rotation
        when (rotation) {
            90 -> tempRect.set(
                    rect.top, originalHeight - rect.right,
                    rect.bottom, originalHeight - rect.left)

            180 -> tempRect.set(
                    originalWidth - rect.right, originalHeight - rect.bottom,
                    originalWidth - rect.left, originalHeight - rect.top)

            270 -> tempRect.set(
                    originalWidth - rect.bottom, rect.left,
                    originalWidth - rect.top, rect.right)

            else -> tempRect.set(rect)
        }

        if (tempRect.isEmpty) {
            return null
        }
        var bitmap: Bitmap = bitmapRegionDecoder.decodeRegion(tempRect, options)
                ?.takeUnless { width == 0 || height == 0 } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && options.outConfig != Bitmap.Config.ARGB_8888) {
            // Renderscript only supports ARGB_8888
            return null
        }

        if (options.inBitmap != null &&
                (tempRect.width() != unsampledInBitmapWidth || tempRect.height() != unsampledInBitmapHeight)) {
            // Need to extract the sub-bitmap
            val subBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0,
                    tempRect.width() / sampleSize,
                    tempRect.height() / sampleSize)
            if (bitmap != options.inBitmap && bitmap != subBitmap) {
                bitmap.recycle()
            }
            bitmap = subBitmap?.takeUnless { width == 0 || height == 0 } ?: return null
        }

        if (rotation != 0) {
            // Rotate decoded bitmap
            val rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0,
                    bitmap.width, bitmap.height,
                    rotateMatrix, true)
            if (bitmap != options.inBitmap && bitmap != rotatedBitmap) {
                bitmap.recycle()
            }
            bitmap = rotatedBitmap?.takeUnless { width == 0 || height == 0 } ?: return null
        }

        return bitmap
    }

    @Synchronized
    fun destroy() {
        bitmapRegionDecoder.recycle()
        try {
            inputStream.close()
        } catch (ignored: IOException) {
        }

    }
}
