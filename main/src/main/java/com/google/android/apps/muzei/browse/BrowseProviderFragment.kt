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

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.api.load
import com.google.android.apps.muzei.legacy.LegacySourceServiceProtocol
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.sendAction
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.BrowseProviderFragmentBinding
import net.nurik.roman.muzei.databinding.BrowseProviderItemBinding

class BrowseProviderFragment: Fragment(R.layout.browse_provider_fragment) {

    companion object {
        const val REFRESH_DELAY = 300L // milliseconds
    }

    private val viewModel: BrowseProviderViewModel by viewModels()
    private val args: BrowseProviderFragmentArgs by navArgs()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = BrowseProviderFragmentBinding.bind(view)
        val pm = requireContext().packageManager
        val providerInfo = pm.resolveContentProvider(args.contentUri.authority!!, 0)
                ?: run {
                    findNavController().popBackStack()
                    return
                }

        binding.swipeRefresh.setOnRefreshListener {
            refresh(binding.swipeRefresh)
        }
        binding.toolbar.apply {
            navigationIcon = DrawerArrowDrawable(requireContext()).apply {
                progress = 1f
            }
            setNavigationOnClickListener {
                findNavController().popBackStack()
            }
            title = providerInfo.loadLabel(pm)
            inflateMenu(R.menu.browse_provider_fragment)
            setOnMenuItemClickListener {
                refresh(binding.swipeRefresh)
                true
            }
        }
        val adapter = Adapter()
        binding.list.adapter = adapter

        viewModel.setContentUri(args.contentUri)
        viewModel.artLiveData.observe(viewLifecycleOwner) {
            adapter.submitList(it)
        }
    }

    private fun refresh(swipeRefreshLayout: SwipeRefreshLayout) {
        lifecycleScope.launch {
            ProviderManager.requestLoad(requireContext(), args.contentUri)
            // Show the refresh indicator for some visible amount of time
            // rather than immediately dismissing it. We don't know how long
            // the provider will actually take to refresh, if it does at all.
            delay(REFRESH_DELAY)
            withContext(Dispatchers.Main.immediate) {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    class ArtViewHolder(
            private val owner: LifecycleOwner,
            private val binding: BrowseProviderItemBinding
    ): RecyclerView.ViewHolder(binding.root) {

        fun bind(artwork: Artwork) {
            val context = itemView.context
            binding.image.contentDescription = artwork.title
            binding.image.load(artwork.imageUri) {
                lifecycle(owner)
            }
            itemView.setOnClickListener {
                owner.lifecycleScope.launch(Dispatchers.Main) {
                    FirebaseAnalytics.getInstance(context).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to artwork.id,
                            FirebaseAnalytics.Param.ITEM_NAME to artwork.title,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "artwork",
                            FirebaseAnalytics.Param.CONTENT_TYPE to "browse"))
                    MuzeiDatabase.getInstance(context).artworkDao()
                            .insert(artwork)
                    context.toast(if (artwork.title.isNullOrBlank()) {
                        context.getString(R.string.browse_set_wallpaper)
                    } else {
                        context.getString(R.string.browse_set_wallpaper_with_title,
                                artwork.title)
                    })
                }
            }
            itemView.setOnCreateContextMenuListener(null)
            owner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                val actions = artwork.getCommands(context).filterNot {
                    it.id == LegacySourceServiceProtocol.LEGACY_COMMAND_ID_NEXT_ARTWORK
                }.filterNot {
                    it.title.isNullOrEmpty()
                }
                if (actions.isNotEmpty()) {
                    itemView.setOnCreateContextMenuListener { menu, _, _ ->
                        actions.forEachIndexed { index, action ->
                            menu.add(Menu.NONE, action.id, index, action.title).apply {
                                setOnMenuItemClickListener {
                                    owner.lifecycleScope.launch {
                                        artwork.sendAction(context, action.id)
                                    }
                                    true
                                }
                            }
                        }
                    }
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
                ArtViewHolder(viewLifecycleOwner,
                        BrowseProviderItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: ArtViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }
}
