/*
 * Copyright 2017 Google Inc.
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

@file:JvmName("ArtworkTransfer")
package com.google.android.apps.muzei.wearable

import android.os.Bundle
import androidx.net.toUri
import com.google.android.apps.muzei.room.Artwork
import com.google.android.gms.wearable.DataMap

private const val KEY_IMAGE_URI = "imageUri"
private const val KEY_TITLE = "title"
private const val KEY_BYLINE = "byline"
private const val KEY_ATTRIBUTION = "attribution"
private const val KEY_TOKEN = "token"

/**
 * Serializes this artwork object to a [DataMap] representation.
 *
 * @return a serialized version of the artwork.
 * @see toArtwork
 */
fun Artwork.toDataMap(): DataMap {
    val bundle = Bundle()
    bundle.putString(KEY_IMAGE_URI, if (imageUri != null) imageUri.toString() else null)
    bundle.putString(KEY_TITLE, title)
    bundle.putString(KEY_BYLINE, byline)
    bundle.putString(KEY_ATTRIBUTION, attribution)
    bundle.putString(KEY_TOKEN, token)
    return DataMap.fromBundle(bundle)
}

/**
 * Deserializes an artwork object from a [DataMap].
 *
 * @return the artwork from the [DataMap]
 * @see toDataMap
 */
fun DataMap.toArtwork(): Artwork {
    val bundle = toBundle()
    val artwork = Artwork().apply {
        val uriString = bundle.getString(KEY_IMAGE_URI)
        if (!uriString.isNullOrBlank()) {
            imageUri = uriString.toUri()
        }
        title = bundle.getString(KEY_TITLE)
        byline = bundle.getString(KEY_BYLINE)
        attribution = bundle.getString(KEY_ATTRIBUTION)
        token = bundle.getString(KEY_TOKEN)
    }
    return artwork
}
