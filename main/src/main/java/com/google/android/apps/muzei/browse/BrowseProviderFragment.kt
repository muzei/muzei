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

package com.google.android.apps.muzei.browse

import android.arch.lifecycle.ViewModelProvider
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewCompat
import android.support.v7.graphics.drawable.DrawerArrowDrawable
import android.support.v7.recyclerview.extensions.ListAdapter
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.coroutineScope
import com.google.android.apps.muzei.util.observe
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R

class BrowseProviderFragment: Fragment() {
    private val viewModelProvider by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory
                .getInstance(requireActivity().application))
    }
    private val viewModel by lazy {
        viewModelProvider[BrowseProviderViewModel::class.java]
    }
    private val adapter = Adapter()

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.browse_provider_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        ViewCompat.requestApplyInsets(view)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            requireActivity().window.statusBarColor = ContextCompat.getColor(
                    requireContext(), R.color.theme_primary_dark)
        }

        val args = BrowseProviderFragmentArgs.fromBundle(arguments)
        val pm = requireContext().packageManager
        val providerInfo = pm.resolveContentProvider(args.contentUri.authority, 0)
                ?: run {
                    findNavController().popBackStack()
                    return
                }

        view.findViewById<Toolbar>(R.id.browse_toolbar).apply {
            navigationIcon = DrawerArrowDrawable(requireContext()).apply {
                progress = 1f
            }
            setNavigationOnClickListener {
                findNavController().popBackStack()
            }
            title = providerInfo.loadLabel(pm)
        }
        view.findViewById<RecyclerView>(R.id.browse_list).adapter = adapter

        viewModel.setContentUri(args.contentUri)
        viewModel.artLiveData.observe(this) {
            adapter.submitList(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            requireActivity().window.statusBarColor = Color.TRANSPARENT
        }
    }

    class ArtViewHolder(
            private val coroutineScope: CoroutineScope,
            itemView: View
    ): RecyclerView.ViewHolder(itemView) {
        private val imageView = itemView.findViewById<ImageView>(R.id.browse_image)

        fun bind(artwork: Artwork) {
            imageView.contentDescription = artwork.title
            Glide.with(imageView)
                    .load(artwork.imageUri)
                    .into(imageView)
            itemView.setOnClickListener {
                val context = it.context
                coroutineScope.launch(Dispatchers.Main) {
                    withContext(Dispatchers.Default) {
                        FirebaseAnalytics.getInstance(context).logEvent(
                                FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                                FirebaseAnalytics.Param.ITEM_ID to artwork.id,
                                FirebaseAnalytics.Param.ITEM_NAME to artwork.title,
                                FirebaseAnalytics.Param.ITEM_CATEGORY to "artwork",
                                FirebaseAnalytics.Param.CONTENT_TYPE to "browse"))
                        MuzeiDatabase.getInstance(context).artworkDao()
                                .insert(artwork)
                    }
                    context.toast(if (artwork.title.isNullOrBlank()) {
                        context.getString(R.string.browse_set_wallpaper)
                    } else {
                        context.getString(R.string.browse_set_wallpaper_with_title,
                                artwork.title)
                    })
                }
            }
        }
    }

    inner class Adapter: ListAdapter<Artwork, ArtViewHolder>(
            object: DiffUtil.ItemCallback<Artwork>() {
                override fun areItemsTheSame(artwork1: Artwork, artwork2: Artwork) =
                        artwork1.imageUri == artwork2.imageUri

                override fun areContentsTheSame(artwork1: Artwork, artwork2: Artwork) =
                        artwork1 == artwork2
            }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ArtViewHolder(viewLifecycleOwner.coroutineScope,
                        layoutInflater.inflate(R.layout.browse_provider_item, parent, false))

        override fun onBindViewHolder(holder: ArtViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }
}
