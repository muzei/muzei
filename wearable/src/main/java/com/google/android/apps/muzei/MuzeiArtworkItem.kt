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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.get
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.RoundedDrawable
import coil.load
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.util.collectIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiArtworkItemBinding

class MuzeiArtworkViewModel(application: Application) : AndroidViewModel(application) {
    val currentArtwork = MuzeiDatabase.getInstance(application).artworkDao().getCurrentArtworkFlow()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)
}

class MuzeiArtworkViewHolder(
        private val binding: MuzeiArtworkItemBinding
) : RecyclerView.ViewHolder(binding.root) {
    init {
        val context = binding.root.context
        binding.image.setOnClickListener {
            context.startActivity(Intent(context, FullScreenActivity::class.java))
        }
    }

    fun bind(artwork: Artwork) = binding.run {
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
}

class MuzeiArtworkAdapter<O>(owner: O) : ListAdapter<Artwork, MuzeiArtworkViewHolder>(
        object : DiffUtil.ItemCallback<Artwork>() {
            override fun areItemsTheSame(
                    artwork1: Artwork,
                    artwork2: Artwork
            ) = artwork1.id == artwork2.id

            override fun areContentsTheSame(
                    artwork1: Artwork,
                    artwork2: Artwork
            ) = artwork1 == artwork2
        }
) where O : LifecycleOwner, O : ViewModelStoreOwner {
    init {
        val viewModel: MuzeiArtworkViewModel = ViewModelProvider(owner).get()
        viewModel.currentArtwork.collectIn(owner) { artwork ->
            submitList(listOfNotNull(artwork))
        }
    }

    override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
    ) = MuzeiArtworkViewHolder(MuzeiArtworkItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: MuzeiArtworkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
