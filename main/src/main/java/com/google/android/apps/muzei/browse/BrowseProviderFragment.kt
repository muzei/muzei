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

import android.app.PendingIntent
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import com.google.android.apps.muzei.util.addMenuProvider
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.util.sendFromBackground
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
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
        viewModel.providerInfo.collectIn(viewLifecycleOwner) { providerInfo ->
            if (providerInfo != null) {
                val pm = requireContext().packageManager
                binding.toolbar.title = providerInfo.loadLabel(pm)
            } else {
                // The contentUri is no longer valid, so we should pop
                findNavController().popBackStack()
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            refresh(binding.swipeRefresh)
        }
        binding.toolbar.apply {
            navigationIcon = DrawerArrowDrawable(requireContext()).apply {
                progress = 1f
            }
            setNavigationOnClickListener {
                val navController = findNavController()
                if (navController.currentDestination?.id == R.id.browse_provider) {
                    navController.popBackStack()
                }
            }
            addMenuProvider(R.menu.browse_provider_fragment) {
                refresh(binding.swipeRefresh)
                true
            }
        }
        val adapter = Adapter()
        binding.list.adapter = adapter
        viewModel.client.collectIn(viewLifecycleOwner) {
            adapter.client = it
        }

        viewModel.artwork.collectIn(viewLifecycleOwner) {
            adapter.submitList(it)
        }
    }

    private fun refresh(swipeRefreshLayout: SwipeRefreshLayout) {
        viewLifecycleOwner.lifecycleScope.launch {
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
        private val binding: BrowseProviderItemBinding,
        private val clientProvider: () -> ContentProviderClientCompat?,
    ): RecyclerView.ViewHolder(binding.root) {

        fun bind(artwork: Artwork) {
            val context = itemView.context
            binding.image.contentDescription = artwork.title
            binding.image.load(artwork.imageUri) {
                lifecycle(owner)
            }
            itemView.setOnClickListener {
                owner.lifecycleScope.launch(Dispatchers.Main) {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, artwork.providerAuthority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, artwork.title ?: "")
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "browse")
                    }
                    // Ensure the date added is set to the current time
                    artwork.dateAdded.time = System.currentTimeMillis()
                    MuzeiDatabase.getInstance(context).artworkDao()
                            .insert(artwork)
                    clientProvider.invoke()?.call(METHOD_MARK_ARTWORK_LOADED, artwork.imageUri.toString())
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
                    it.title.isBlank()
                }
                if (actions.isNotEmpty()) {
                    itemView.setOnCreateContextMenuListener { menu, _, _ ->
                        actions.forEachIndexed { index, action ->
                            menu.add(Menu.NONE, index, index, action.title).apply {
                                setOnMenuItemClickListener { menuItem ->
                                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, artwork.providerAuthority)
                                        param(FirebaseAnalytics.Param.ITEM_NAME, menuItem.title.toString())
                                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
                                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "browse")
                                    }
                                    try {
                                        action.actionIntent.sendFromBackground()
                                    } catch (e: PendingIntent.CanceledException) {
                                        // Why do you give us a cancelled PendingIntent.
                                        // We can't do anything with that.
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
        var client: ContentProviderClientCompat? = null

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ) = ArtViewHolder(
            viewLifecycleOwner,
            BrowseProviderItemBinding.inflate(layoutInflater, parent, false)
        ) {
            client
        }

        override fun onBindViewHolder(holder: ArtViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }
}