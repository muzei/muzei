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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.get
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.RoundedDrawable
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sync.ProviderManager
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.androidclientcommon.R as CommonR
import net.nurik.roman.muzei.databinding.MuzeiNextArtworkItemBinding

class MuzeiNextArtworkViewModel(application: Application) : AndroidViewModel(application) {
    val providerLiveData = ProviderManager.getInstance(getApplication())
}

class MuzeiNextArtworkViewHolder(
        binding: MuzeiNextArtworkItemBinding
) : RecyclerView.ViewHolder(binding.root) {
    init {
        binding.run {
            val context = root.context
            nextArtwork.setCompoundDrawablesRelative(RoundedDrawable().apply {
                radius = context.resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
                backgroundColor = ContextCompat.getColor(context, CommonR.color.theme_primary)
                drawable = ContextCompat.getDrawable(context, CommonR.drawable.ic_next_artwork)
                bounds = Rect(0, 0, radius * 2, radius * 2)
            }, null, null, null)
            nextArtwork.setOnClickListener {
                ProviderManager.getInstance(context).nextArtwork()
            }
        }
    }
}

class MuzeiNextArtworkAdapter<O>(owner: O) : ListAdapter<Provider, MuzeiNextArtworkViewHolder>(
        object : DiffUtil.ItemCallback<Provider>() {
            override fun areItemsTheSame(
                    provider1: Provider,
                    provider2: Provider
            ) = provider1.authority == provider2.authority

            override fun areContentsTheSame(
                    provider1: Provider,
                    provider2: Provider
            ) = provider1 == provider2
        }
) where O : LifecycleOwner, O : ViewModelStoreOwner {
    init {
        val viewModel: MuzeiNextArtworkViewModel = ViewModelProvider(owner).get()
        viewModel.providerLiveData.observe(owner) { provider ->
            submitList(listOfNotNull(provider?.takeIf { it.supportsNextArtwork }))
        }
    }

    override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
    ) = MuzeiNextArtworkViewHolder(MuzeiNextArtworkItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: MuzeiNextArtworkViewHolder, position: Int) {
    }
}