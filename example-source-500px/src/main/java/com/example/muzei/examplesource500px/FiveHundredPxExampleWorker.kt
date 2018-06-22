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

package com.example.muzei.examplesource500px

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

class FiveHundredPxExampleWorker : Worker() {

    companion object {
        private const val TAG = "500pxExample"

        internal fun enqueueLoad() {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<FiveHundredPxExampleWorker>()
                    .setConstraints(Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .build())
        }
    }

    override fun doWork(): Result {
        val photos = try {
            FiveHundredPxService.popularPhotos()
        } catch (e: IOException) {
            Log.w(TAG, "Error reading 500px response", e)
            return Result.RETRY
        }

        if (photos.isEmpty()) {
            Log.w(TAG, "No photos returned from API.")
            return Result.FAILURE
        }

        photos.map { photo ->
            Artwork().apply {
                token = photo.id.toString()
                title = photo.name
                byline = photo.user.fullname
                persistentUri = photo.images[0].https_url?.toUri()
                webUri = "http://500px.com/photo/${photo.id}".toUri()
            }
        }.forEach { artwork ->
            ProviderContract.Artwork.addArtwork(applicationContext,
                    FiveHundredPxExampleArtProvider::class.java,
                    artwork)
        }
        return Result.SUCCESS
    }
}
