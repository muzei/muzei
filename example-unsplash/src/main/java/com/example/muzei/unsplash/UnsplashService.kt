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

import androidx.core.net.toUri
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.io.IOException

internal interface UnsplashService {

    companion object {
        @Throws(IOException::class)
        internal fun popularPhotos(): List<UnsplashService.Photo> {
            val okHttpClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        var request = chain.request()
                        val url = request.url().newBuilder()
                                .addQueryParameter("client_id", CONSUMER_KEY).build()
                        request = request.newBuilder().url(url).build()
                        chain.proceed(request)
                    }
                    .build()

            val retrofit = Retrofit.Builder()
                    .baseUrl("https://api.unsplash.com/")
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

            val service = retrofit.create<UnsplashService>(UnsplashService::class.java)

            return service.popularPhotos.execute().body()
                    ?: throw IOException("Response was null")
        }
    }

    @get:GET("photos/curated?order_by=popular&per_page=30")
    val popularPhotos: Call<List<Photo>>

    data class Photo(
            val id: String,
            val urls: Urls,
            val description: String,
            val user: User,
            val links: Links)

    data class Urls(val full: String)

    data class Links(val html: String) {
        val webUri get() = "$html?utm_source=example_api_muzei&utm_medium=referral".toUri()
    }

    data class User(val name: String)
}
