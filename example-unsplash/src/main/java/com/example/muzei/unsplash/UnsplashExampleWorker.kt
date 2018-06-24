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

package com.example.muzei.unsplash

import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import java.io.IOException

class UnsplashExampleWorker : Worker() {

    companion object {
        private const val TAG = "UnsplashExample"

        internal fun enqueueLoad() {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<UnsplashExampleWorker>()
                    .setConstraints(Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .build())
        }
    }

    override fun doWork(): Result {
        val photos = try {
            UnsplashService.popularPhotos()
        } catch (e: IOException) {
            Log.w(TAG, "Error reading Unsplash response", e)
            return Result.RETRY
        }

        if (photos.isEmpty()) {
            Log.w(TAG, "No photos returned from API.")
            return Result.FAILURE
        }

        photos.map { photo ->
            Artwork().apply {
                token = photo.id
                title = photo.description
                byline = photo.user.name
                attribution = applicationContext.getString(R.string.attribution)
                persistentUri = photo.urls.full.toUri()
                webUri = photo.links.webUri
            }
        }.forEach { artwork ->
            ProviderContract.Artwork.addArtwork(applicationContext,
                    UnsplashExampleArtProvider::class.java,
                    artwork)
        }
        return Result.SUCCESS
    }
}
