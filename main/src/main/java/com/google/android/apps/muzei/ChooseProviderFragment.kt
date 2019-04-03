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

package com.google.android.apps.muzei

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig.SOURCES_AUTHORITY
import net.nurik.roman.muzei.R

class ChooseProviderFragment : Fragment(R.layout.choose_provider_fragment) {
    companion object {
        private const val TAG = "ChooseProviderFragment"
        private const val REQUEST_EXTENSION_SETUP = 1
        private const val REQUEST_EXTENSION_SETTINGS = 2
        private const val START_ACTIVITY_PROVIDER = "startActivityProvider"

        private const val PAYLOAD_DESCRIPTION = "DESCRIPTION"
        private const val PAYLOAD_CURRENT_IMAGE_URI = "CURRENT_IMAGE_URI"
        private const val PAYLOAD_SELECTED = "SELECTED"
    }

    private val currentProviderLiveData by lazy {
        MuzeiDatabase.getInstance(requireContext()).providerDao()
                .currentProvider
    }
    private val viewModel: ChooseProviderViewModel by viewModels()
    private val adapter = ProviderListAdapter()

    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout

    private var startActivityProvider: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityProvider = savedInstanceState?.getString(START_ACTIVITY_PROVIDER)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        ViewCompat.requestApplyInsets(view)

        toolbar = view.findViewById(R.id.toolbar)
        requireActivity().menuInflater.inflate(R.menu.choose_provider_fragment,
                toolbar.menu)
        val context = requireContext()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.auto_advance_settings -> {
                    if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                        drawerLayout.closeDrawer(GravityCompat.END)
                    } else {
                        FirebaseAnalytics.getInstance(context).logEvent(
                                "auto_advance_open", null)
                        drawerLayout.openDrawer(GravityCompat.END)
                    }
                    true
                }
                R.id.auto_advance_disabled -> {
                    FirebaseAnalytics.getInstance(context).logEvent(
                            "auto_advance_disabled", null)
                    context.toast(R.string.auto_advance_disabled_description,
                            Toast.LENGTH_LONG)
                    true
                }
                R.id.action_notification_settings -> {
                    FirebaseAnalytics.getInstance(context).logEvent(
                            "notification_settings_open", bundleOf(
                            FirebaseAnalytics.Param.CONTENT_TYPE to "overflow"))
                    NotificationSettingsDialogFragment.showSettings(context,
                            childFragmentManager)
                    true
                }
                else -> false
            }
        }

        drawerLayout = view.findViewById(R.id.choose_provider_drawer)
        drawerLayout.setStatusBarBackgroundColor(Color.TRANSPARENT)
        drawerLayout.setScrimColor(Color.argb(68, 0, 0, 0))
        currentProviderLiveData.observe(this) { provider ->
            val legacySelected = provider?.authority == SOURCES_AUTHORITY
            toolbar.menu.findItem(R.id.auto_advance_settings).isVisible = !legacySelected
            toolbar.menu.findItem(R.id.auto_advance_disabled).isVisible = legacySelected
            if (legacySelected) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                        GravityCompat.END)
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED,
                        GravityCompat.END)
            }
        }

        val providerList = view.findViewById<RecyclerView>(R.id.provider_list)
        val spacing = resources.getDimensionPixelSize(R.dimen.provider_padding)
        providerList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
            ) {
                outRect.set(spacing, spacing, spacing, spacing)
            }
        })
        providerList.adapter = adapter
        viewModel.providers.observe(this) {
            adapter.submitList(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(START_ACTIVITY_PROVIDER, startActivityProvider)
    }

    private fun launchProviderSetup(provider: ProviderInfo) {
        try {
            startActivityProvider = provider.authority
            val setupIntent = Intent()
                    .setComponent(provider.setupActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        }
    }

    private fun launchProviderSettings(provider: ProviderInfo) {
        try {
            startActivityProvider = provider.authority
            val settingsIntent = Intent()
                    .setComponent(provider.settingsActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
            startActivityForResult(settingsIntent, REQUEST_EXTENSION_SETTINGS)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider settings.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider settings.", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_EXTENSION_SETUP -> {
                val provider = startActivityProvider
                if (resultCode == Activity.RESULT_OK && provider != null) {
                    val context = requireContext()
                    GlobalScope.launch {
                        FirebaseAnalytics.getInstance(context).logEvent(
                                FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                                FirebaseAnalytics.Param.ITEM_ID to provider,
                                FirebaseAnalytics.Param.ITEM_CATEGORY to "providers",
                                FirebaseAnalytics.Param.CONTENT_TYPE to "after_setup"))
                        ProviderManager.select(context, provider)
                    }
                }
                startActivityProvider = null
            }
            REQUEST_EXTENSION_SETTINGS -> {
                startActivityProvider?.let { authority ->
                    viewModel.refreshDescription(authority)
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    inner class ProviderViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView), RequestListener<Drawable> {
        private val providerIcon: ImageView = itemView.findViewById(R.id.provider_icon)
        private val providerTitle: TextView = itemView.findViewById(R.id.provider_title)
        private val providerSelected: ImageView = itemView.findViewById(R.id.provider_selected)
        private val providerArtwork: ImageView = itemView.findViewById(R.id.provider_artwork)
        private val providerDescription: TextView = itemView.findViewById(R.id.provider_description)
        private val providerSettings: Button = itemView.findViewById(R.id.provider_settings)
        private val providerBrowse: Button = itemView.findViewById(R.id.provider_browse)

        private var isSelected = false

        fun bind(providerInfo: ProviderInfo) = providerInfo.run {
            itemView.setOnClickListener {
                if (isSelected) {
                    val context = context
                    val parentFragment = parentFragment?.parentFragment
                    if (context is Callbacks) {
                        FirebaseAnalytics.getInstance(requireContext()).logEvent(
                                "choose_provider_reselected", null)
                        context.onRequestCloseActivity()
                    } else if (parentFragment is Callbacks) {
                        FirebaseAnalytics.getInstance(requireContext()).logEvent(
                                "choose_provider_reselected", null)
                        parentFragment.onRequestCloseActivity()
                    }
                } else if (setupActivity != null) {
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(
                            FirebaseAnalytics.Event.VIEW_ITEM, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to authority,
                            FirebaseAnalytics.Param.ITEM_NAME to title,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "providers"))
                    launchProviderSetup(this)
                } else if (providerInfo.authority == viewModel.playStoreAuthority) {
                    FirebaseAnalytics.getInstance(requireContext()).logEvent("more_sources_open", null)
                    try {
                        startActivity(viewModel.playStoreIntent)
                    } catch (e: ActivityNotFoundException) {
                        requireContext().toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
                    } catch (e: SecurityException) {
                        requireContext().toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
                    }
                } else {
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to authority,
                            FirebaseAnalytics.Param.ITEM_NAME to title,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "providers",
                            FirebaseAnalytics.Param.CONTENT_TYPE to "choose"))
                    val context = requireContext()
                    GlobalScope.launch {
                        ProviderManager.select(context, authority)
                    }
                }
            }
            itemView.setOnLongClickListener {
                if (TextUtils.equals(packageName, requireContext().packageName)) {
                    // Don't open Muzei's app info
                    return@setOnLongClickListener false
                }
                // Otherwise open third party extensions
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(
                            "app_settings_open", bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to authority,
                            FirebaseAnalytics.Param.ITEM_NAME to title))
                } catch (e: ActivityNotFoundException) {
                    return@setOnLongClickListener false
                }

                true
            }

            providerIcon.setImageDrawable(icon)

            providerTitle.text = title

            setDescription(providerInfo)

            setImage(providerInfo)

            setSelected(providerInfo)
            providerSettings.setOnClickListener {
                FirebaseAnalytics.getInstance(requireContext()).logEvent(
                        "provider_settings_open", bundleOf(
                        FirebaseAnalytics.Param.ITEM_ID to authority,
                        FirebaseAnalytics.Param.ITEM_NAME to title))
                launchProviderSettings(this)
            }
            providerBrowse.setOnClickListener {
                FirebaseAnalytics.getInstance(requireContext()).logEvent(
                        "provider_browse_open", bundleOf(
                        FirebaseAnalytics.Param.ITEM_ID to authority,
                        FirebaseAnalytics.Param.ITEM_NAME to title))
                findNavController().navigate(
                        ChooseProviderFragmentDirections.browse(
                                ProviderContract.getContentUri(authority)))
            }
        }

        override fun onResourceReady(
                resource: Drawable?,
                model: Any?,
                target: Target<Drawable>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
        ): Boolean {
            providerArtwork.isVisible = true
            return false
        }

        override fun onLoadFailed(e: GlideException?,
                model: Any?,
                target: Target<Drawable>?,
                isFirstResource: Boolean
        ): Boolean {
            providerArtwork.isVisible = false
            return false
        }

        fun setDescription(providerInfo: ProviderInfo) = providerInfo.run {
            providerDescription.text = description
            providerDescription.isGone = description.isNullOrEmpty()
        }

        fun setImage(providerInfo: ProviderInfo) = providerInfo.run {
            providerArtwork.isVisible = currentArtworkUri != null
            if (currentArtworkUri != null) {
                Glide.with(this@ChooseProviderFragment)
                        .load(currentArtworkUri)
                        .addListener(this@ProviderViewHolder)
                        .into(providerArtwork)
            }
        }

        fun setSelected(providerInfo: ProviderInfo) = providerInfo.run {
            isSelected = selected
            providerSelected.isInvisible = !selected
            providerSettings.isVisible = selected && settingsActivity != null
            providerBrowse.isVisible = selected
        }
    }

    inner class ProviderListAdapter : ListAdapter<ProviderInfo, ProviderViewHolder>(
            object : DiffUtil.ItemCallback<ProviderInfo>() {
                override fun areItemsTheSame(
                        providerInfo1: ProviderInfo,
                        providerInfo2: ProviderInfo
                ) = providerInfo1.authority == providerInfo2.authority

                override fun areContentsTheSame(
                        providerInfo1: ProviderInfo,
                        providerInfo2: ProviderInfo
                ) = providerInfo1 == providerInfo2

                override fun getChangePayload(oldItem: ProviderInfo, newItem: ProviderInfo): Any? {
                    return when {
                        oldItem.description != newItem.description &&
                                oldItem.copy(description = newItem.description) ==
                                newItem ->
                            PAYLOAD_DESCRIPTION
                        oldItem.currentArtworkUri != newItem.currentArtworkUri &&
                                oldItem.copy(currentArtworkUri = newItem.currentArtworkUri) ==
                                newItem ->
                            PAYLOAD_CURRENT_IMAGE_URI
                        oldItem.selected != newItem.selected &&
                                oldItem.copy(selected = newItem.selected) == newItem ->
                            PAYLOAD_SELECTED
                        else -> null
                    }
                }
            }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ProviderViewHolder(layoutInflater.inflate(
                    R.layout.choose_provider_item, parent, false))

        override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onBindViewHolder(
                holder: ProviderViewHolder,
                position: Int,
                payloads: MutableList<Any>
        ) {
            when {
                payloads.isEmpty() -> super.onBindViewHolder(holder, position, payloads)
                payloads[0] == PAYLOAD_DESCRIPTION -> holder.setDescription(getItem(position))
                payloads[0] == PAYLOAD_CURRENT_IMAGE_URI -> holder.setImage(getItem(position))
                payloads[0] == PAYLOAD_SELECTED -> holder.setSelected(getItem(position))
                else -> IllegalArgumentException("Forgot to handle ${payloads[0]}")
            }
        }
    }

    interface Callbacks {
        fun onRequestCloseActivity()
    }
}
