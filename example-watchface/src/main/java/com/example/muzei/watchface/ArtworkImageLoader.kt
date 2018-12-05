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

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.lifecycle.MutableLiveData
import com.google.android.apps.muzei.api.MuzeiContract
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * AsyncTaskLoader which provides access to the current Muzei artwork image. It also
 * registers a ContentObserver to ensure the image stays up to date
 */
class ArtworkImageLoader(private val context: Context) : MutableLiveData<Bitmap>() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: ArtworkImageLoader? = null

        fun getInstance(context: Context): ArtworkImageLoader {
            val applicationContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: ArtworkImageLoader(applicationContext)
            }
        }
    }

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }
    private val contentObserver: ContentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            loadInBackground()
        }
    }

    private var future: Future<*>? = null
    var requestedSize: Size? = null
        set(value) {
            field = value
            if (hasActiveObservers()) {
                loadInBackground()
            }
        }

    override fun onActive() {
        context.contentResolver.registerContentObserver(
                MuzeiContract.Artwork.CONTENT_URI, true, contentObserver)
        loadInBackground()
    }

    fun loadInBackground() {
        future?.cancel(true)
        future = executor.submit {
            try {
                val size = requestedSize
                MuzeiContract.Artwork.getCurrentArtworkBitmap(context)?.run {
                    when {
                        size == null -> this
                        width > height -> {
                            val scalingFactor = size.height * 1f / height
                            Bitmap.createScaledBitmap(this, (scalingFactor * width).toInt(),
                                    size.height, true)
                        }
                        else -> {
                            val scalingFactor = size.width * 1f / width
                            Bitmap.createScaledBitmap(this, size.width,
                                    (scalingFactor * height).toInt(), true)
                        }
                    }?.run {
                        postValue(this)
                    }
                }
            } catch (e: FileNotFoundException) {
                Log.e("ArtworkImageLoader", "Error getting artwork image", e)
            }
        }
    }

    override fun onInactive() {
        context.contentResolver.unregisterContentObserver(contentObserver)
    }
}
