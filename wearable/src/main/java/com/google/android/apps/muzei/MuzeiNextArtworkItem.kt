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
import android.graphics.Rect
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.wear.widget.RoundedDrawable
import com.google.android.apps.muzei.sync.ProviderManager
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiNextArtworkItemBinding

class MuzeiNextArtworkViewModel(application: Application) : AndroidViewModel(application) {
    val providerLiveData = ProviderManager.getInstance(getApplication())
}

fun MuzeiNextArtworkItemBinding.create() {
    val context = root.context
    nextArtwork.setCompoundDrawablesRelative(RoundedDrawable().apply {
        isClipEnabled = true
        radius = context.resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
        backgroundColor = ContextCompat.getColor(context, R.color.theme_primary)
        drawable = ContextCompat.getDrawable(context, R.drawable.ic_next_artwork)
        bounds = Rect(0, 0, radius * 2, radius * 2)
    }, null, null, null)
    nextArtwork.setOnClickListener {
        ProviderManager.getInstance(context).nextArtwork()
    }
}
