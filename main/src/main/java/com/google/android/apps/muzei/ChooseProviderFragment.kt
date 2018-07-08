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
import android.arch.lifecycle.ViewModelProvider
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.support.v4.app.Fragment
import android.support.v4.widget.DrawerLayout
import android.support.v7.recyclerview.extensions.ListAdapter
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.toast
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.select
import com.google.android.apps.muzei.sources.SourceArtProvider
import com.google.android.apps.muzei.util.observe
import com.google.firebase.analytics.FirebaseAnalytics
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.R
import java.lang.Exception

class ChooseProviderFragment : Fragment() {
    companion object {
        private const val TAG = "ChooseProviderFragment"
        private const val REQUEST_EXTENSION_SETUP = 1
        private const val INITIAL_SETUP_PROVIDER = "initialSetupProvider"

        private const val PAYLOAD_CURRENT_IMAGE_URI = "CURRENT_IMAGE_URI"
        private const val PAYLOAD_SELECTED = "SELECTED"
    }

    private val currentProviderLiveData by lazy {
        MuzeiDatabase.getInstance(requireContext()).providerDao()
                .currentProvider
    }
    private val viewModelProvider by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory
                .getInstance(requireActivity().application))
    }
    private val viewModel by lazy {
        viewModelProvider[ChooseProviderViewModel::class.java]
    }
    private val adapter = ProviderListAdapter()

    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout

    private var currentInitialSetupProvider: ComponentName? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInitialSetupProvider = savedInstanceState?.getParcelable(INITIAL_SETUP_PROVIDER)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.choose_provider_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        @Suppress("DEPRECATION")
        view.requestFitSystemWindows()

        toolbar = view.findViewById(R.id.toolbar)
        requireActivity().menuInflater.inflate(R.menu.choose_provider_fragment,
                toolbar.menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.auto_advance_settings -> {
                    if (drawerLayout.isDrawerOpen(Gravity.END)) {
                        drawerLayout.closeDrawer(Gravity.END)
                    } else {
                        drawerLayout.openDrawer(Gravity.END)
                    }
                    true
                }
                R.id.auto_advance_disabled -> {
                    requireContext().toast(R.string.auto_advance_disabled_description,
                            Toast.LENGTH_LONG)
                    true
                }
                R.id.action_notification_settings -> {
                    NotificationSettingsDialogFragment.showSettings(requireContext(),
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
            val sourceArtProvider = ComponentName(requireContext(),
                    SourceArtProvider::class.java)
            val legacySelected = provider?.componentName == sourceArtProvider
            toolbar.menu.findItem(R.id.auto_advance_settings).isVisible = !legacySelected
            toolbar.menu.findItem(R.id.auto_advance_disabled).isVisible = legacySelected
            if (legacySelected) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                        Gravity.END)
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED,
                        Gravity.END)
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
        outState.putParcelable(INITIAL_SETUP_PROVIDER, currentInitialSetupProvider)
    }

    private fun launchProviderSetup(provider: ProviderInfo) {
        try {
            val setupIntent = Intent()
                    .setComponent(provider.setupActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI_SETTINGS, true)
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        }
    }

    private fun launchProviderSettings(provider: ProviderInfo) {
        try {
            val settingsIntent = Intent()
                    .setComponent(provider.settingsActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI_SETTINGS, true)
            startActivity(settingsIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider settings.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider settings.", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_EXTENSION_SETUP -> {
                val provider = currentInitialSetupProvider
                if (resultCode == Activity.RESULT_OK && provider != null) {
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to provider.flattenToShortString(),
                            FirebaseAnalytics.Param.CONTENT_TYPE to "providers"))
                    val context = requireContext()
                    launch {
                        provider.select(context)
                    }
                }
                currentInitialSetupProvider = null
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    inner class ProviderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), Callback {
        private val providerIcon: ImageView = itemView.findViewById(R.id.provider_icon)
        private val providerTitle: TextView = itemView.findViewById(R.id.provider_title)
        private val providerSelected: ImageView = itemView.findViewById(R.id.provider_selected)
        private val providerArtwork: ImageView = itemView.findViewById(R.id.provider_artwork)
        private val providerDescription: TextView = itemView.findViewById(R.id.provider_description)
        private val providerSettings: Button = itemView.findViewById(R.id.provider_settings)

        private var isSelected = false

        fun bind(providerInfo: ProviderInfo) = providerInfo.run {
            itemView.setOnClickListener {
                if (isSelected) {
                    val context = context
                    val parentFragment = parentFragment
                    if (context is Callbacks) {
                        context.onRequestCloseActivity()
                    } else if (parentFragment is Callbacks) {
                        parentFragment.onRequestCloseActivity()
                    }
                } else if (setupActivity != null) {
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(
                            FirebaseAnalytics.Event.VIEW_ITEM, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to componentName.flattenToShortString(),
                            FirebaseAnalytics.Param.ITEM_NAME to title,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "providers"))
                    currentInitialSetupProvider = componentName
                    launchProviderSetup(this)
                } else if (providerInfo.componentName == viewModel.playStoreComponentName) {
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
                            FirebaseAnalytics.Param.ITEM_ID to componentName.flattenToShortString(),
                            FirebaseAnalytics.Param.CONTENT_TYPE to "providers"))
                    val context = requireContext()
                    launch {
                        componentName.select(context)
                    }
                }
            }
            itemView.setOnLongClickListener {
                val pkg = componentName.packageName
                if (TextUtils.equals(pkg, requireContext().packageName)) {
                    // Don't open Muzei's app info
                    return@setOnLongClickListener false
                }
                // Otherwise open third party extensions
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", pkg, null)))
                } catch (e: ActivityNotFoundException) {
                    return@setOnLongClickListener false
                }

                true
            }

            providerIcon.setImageDrawable(icon)

            providerTitle.text = title
            providerDescription.text = description
            providerDescription.isGone = description.isNullOrEmpty()

            setImage(providerInfo)

            setSelected(providerInfo)
            providerSettings.setOnClickListener { launchProviderSettings(this) }
        }

        override fun onSuccess() {
            providerArtwork.isVisible = true
        }

        override fun onError(e: Exception?) {
            providerArtwork.isVisible = false
        }

        fun setImage(providerInfo: ProviderInfo) = providerInfo.run {
            providerArtwork.isVisible = currentArtworkUri != null
            if (currentArtworkUri != null) {
                Picasso.get()
                        .load(currentArtworkUri)
                        .centerCrop()
                        .fit()
                        .into(providerArtwork, this@ProviderViewHolder)
            }
        }

        fun setSelected(providerInfo: ProviderInfo) = providerInfo.run {
            isSelected = selected
            providerSelected.isInvisible = !selected
            providerSettings.isVisible = selected && settingsActivity != null
        }
    }

    inner class ProviderListAdapter : ListAdapter<ProviderInfo, ProviderViewHolder>(
            object : DiffUtil.ItemCallback<ProviderInfo>() {
                override fun areItemsTheSame(
                        providerInfo1: ProviderInfo,
                        providerInfo2: ProviderInfo
                ) = providerInfo1.componentName == providerInfo2.componentName

                override fun areContentsTheSame(
                        providerInfo1: ProviderInfo,
                        providerInfo2: ProviderInfo
                ) = providerInfo1 == providerInfo2

                override fun getChangePayload(oldItem: ProviderInfo, newItem: ProviderInfo): Any? {
                    return when {
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
