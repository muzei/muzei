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

package com.example.muzei.examplesource500px

import android.content.Intent
import android.util.Log
import androidx.net.toUri
import com.example.muzei.examplesource500px.FiveHundredPxService.Photo
import com.example.muzei.examplesource500px.FiveHundredPxService.PhotosResponse
import com.google.android.apps.muzei.api.Artwork
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.*

class FiveHundredPxExampleArtSource : RemoteMuzeiArtSource(SOURCE_NAME) {

    companion object {
        private const val TAG = "500pxExample"
        private const val SOURCE_NAME = "FiveHundredPxExampleArtSource"
        private const val ROTATE_TIME_MILLIS = 3 * 60 * 60 * 1000 // rotate every 3 hours
    }

    override fun onCreate() {
        super.onCreate()
        setUserCommands(BUILTIN_COMMAND_ID_NEXT_ARTWORK)
    }

    @Throws(RemoteMuzeiArtSource.RetryException::class)
    override fun onTryUpdate(@UpdateReason reason: Int) {
        val currentToken = currentArtwork?.token

        val okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    var request = chain.request()
                    val url = request.url().newBuilder()
                            .addQueryParameter("consumer_key", CONSUMER_KEY).build()
                    request = request.newBuilder().url(url).build()
                    chain.proceed(request)
                }
                .build()

        val retrofit = Retrofit.Builder()
                .baseUrl("https://api.500px.com/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

        val service = retrofit.create<FiveHundredPxService>(FiveHundredPxService::class.java)
        val response: PhotosResponse? = try {
            service.popularPhotos.execute().body()
        } catch (e: IOException) {
            Log.w(TAG, "Error reading 500px response", e)
            throw RemoteMuzeiArtSource.RetryException()
        }

        if (response?.photos == null) {
            Log.w(TAG, "Response ${if (response == null) "was null" else "had null photos"}")
            throw RemoteMuzeiArtSource.RetryException()
        }

        val photos = response.photos.filterNot { photo ->
            val images = photo.images
            images.isEmpty() || images[0].https_url.isNullOrEmpty()
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
            token = Integer.toString(photo.id)
            if (photos.size <= 1 || token != currentToken) {
                break
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Loaded ${photo.id} with uri: ${photo.images[0].https_url}")
        }
        publishArtwork(Artwork.Builder()
                .title(photo.name)
                .byline(photo.user.fullname)
                .imageUri(photo.images[0].https_url?.toUri())
                .token(token)
                .viewIntent(Intent(Intent.ACTION_VIEW, "http://500px.com/photo/${photo.id}".toUri()))
                .build())

        scheduleUpdate(System.currentTimeMillis() + ROTATE_TIME_MILLIS)
    }
}

