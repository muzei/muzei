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

import retrofit2.Call
import retrofit2.http.GET

internal interface FiveHundredPxService {
    @get:GET("v1/photos?feature=popular&sort=rating&image_size=5&rpp=40")
    val popularPhotos: Call<PhotosResponse>

    data class PhotosResponse(val photos: List<Photo>)

    data class Photo(val id: Int,
                     val images: List<Image>,
                     val name: String?,
                     val user: User)

    data class Image(@Suppress("PropertyName")
                     val https_url: String? = null)

    data class User(val fullname: String?)
}
