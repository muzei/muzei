/*
 * Copyright 2020 Google Inc.
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

package com.google.android.apps.muzei

import android.app.Application
import android.content.Intent
import androidx.core.view.isVisible
import androidx.lifecycle.AndroidViewModel
import androidx.wear.widget.RoundedDrawable
import coil.api.load
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiArtworkItemBinding

class MuzeiArtworkViewModel(application: Application) : AndroidViewModel(application) {
    val artworkLiveData = MuzeiDatabase.getInstance(application).artworkDao().currentArtwork
}

fun MuzeiArtworkItemBinding.create() {
    val context = root.context
    image.setOnClickListener {
        context.startActivity(Intent(context, FullScreenActivity::class.java))
    }
}

fun MuzeiArtworkItemBinding.bind(artwork: Artwork) {
    image.load(artwork.contentUri) {
        allowHardware(false)
        target { loadedDrawable ->
            image.setImageDrawable(RoundedDrawable().apply {
                isClipEnabled = true
                radius = root.context.resources.getDimensionPixelSize(R.dimen.art_detail_image_radius)
                drawable = loadedDrawable
            })
        }
        listener(
                onError = { _, _ -> image.isVisible = false },
                onSuccess = { _, _ -> image.isVisible = true }
        )
    }
    image.contentDescription = artwork.title ?: artwork.byline ?: artwork.attribution
    title.text = artwork.title
    title.isVisible = !artwork.title.isNullOrBlank()
    byline.text = artwork.byline
    byline.isVisible = !artwork.byline.isNullOrBlank()
    attribution.text = artwork.attribution
    attribution.isVisible = !artwork.attribution.isNullOrBlank()
}
