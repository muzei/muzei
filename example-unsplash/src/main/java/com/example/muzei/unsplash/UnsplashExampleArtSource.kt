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

import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.example.muzei.unsplash.UnsplashService.Photo
import com.google.android.apps.muzei.api.Artwork
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource
import com.google.android.apps.muzei.api.UserCommand
import java.io.IOException
import java.util.Random

class UnsplashExampleArtSource : RemoteMuzeiArtSource(SOURCE_NAME) {

    companion object {
        private const val TAG = "UnsplashExample"
        private const val SOURCE_NAME = "UnsplashExampleArtSource"

        private const val COMMAND_ID_VIEW_PROFILE = 1
        private const val COMMAND_ID_VISIT_UNSPLASH = 2

        private const val ROTATE_TIME_MILLIS = 3 * 60 * 60 * 1000 // rotate every 3 hours
    }

    @Throws(RemoteMuzeiArtSource.RetryException::class)
    override fun onTryUpdate(@UpdateReason reason: Int) {
        val currentToken = currentArtwork?.token

        val photos = try {
            UnsplashService.popularPhotos()
        } catch (e: IOException) {
            Log.w(TAG, "Error reading Unsplash response", e)
            throw RemoteMuzeiArtSource.RetryException()
        }

        if (photos.isEmpty()) {
            Log.w(TAG, "No photos returned from API.")
            scheduleUpdate(System.currentTimeMillis() + ROTATE_TIME_MILLIS)
            return
        }

        val random = Random()
        var photo: Photo
        var token: String
        while (true) {
            photo = photos[random.nextInt(photos.size)]
            token = "${photo.id}:${photo.user.links.webUri}"
            if (photos.size <= 1 || token != currentToken) {
                break
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Loaded ${photo.id} with uri: ${photo.urls.full}")
        }
        val attribution = getString(R.string.attribution)
        publishArtwork(Artwork.Builder()
                .title(photo.description ?: attribution)
                .byline(photo.user.name)
                .attribution(if (photo.description != null) attribution else null)
                .imageUri(photo.urls.full.toUri())
                .token(token)
                .viewIntent(Intent(Intent.ACTION_VIEW, photo.links.webUri))
                .build())

        setUserCommands(listOf(
                UserCommand(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK),
                UserCommand(COMMAND_ID_VIEW_PROFILE,
                        getString(R.string.action_view_profile, photo.user.name)),
                UserCommand(COMMAND_ID_VISIT_UNSPLASH, getString(R.string.action_visit_unsplash))))
        scheduleUpdate(System.currentTimeMillis() + ROTATE_TIME_MILLIS)
    }

    override fun onCustomCommand(id: Int) {
        when (id) {
            COMMAND_ID_VIEW_PROFILE -> {
                currentArtwork?.apply {
                    val profileUri = token?.substringAfter(':')?.toUri()
                            ?: return
                    startActivity(Intent(Intent.ACTION_VIEW, profileUri))
                }
            }
            COMMAND_ID_VISIT_UNSPLASH -> {
                val unsplashUri = getString(R.string.unsplash_link) + ATTRIBUTION_QUERY_PARAMETERS
                startActivity(Intent(Intent.ACTION_VIEW, unsplashUri.toUri()))
            }
        }
    }
}

