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

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.io.IOException

internal interface FiveHundredPxService {

    companion object {
        @Throws(IOException::class)
        internal fun popularPhotos(): List<FiveHundredPxService.Photo> {
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
            val response: FiveHundredPxService.PhotosResponse = service.popularPhotos.execute().body()
                    ?: throw IOException("Response was null")

            return response.photos.filterNot { photo ->
                val images = photo.images
                images.isEmpty() || images[0].https_url.isNullOrEmpty()
            }
        }
    }

    @get:GET("v1/photos?feature=popular&sort=rating&image_size=5&rpp=40")
    val popularPhotos: Call<PhotosResponse>

    data class PhotosResponse(val photos: List<Photo>)

    data class Photo(
            val id: Int,
            val images: List<Image>,
            val name: String?,
            val user: User)

    data class Image(@Suppress("PropertyName")
    val https_url: String? = null)

    data class User(val fullname: String?)
}
