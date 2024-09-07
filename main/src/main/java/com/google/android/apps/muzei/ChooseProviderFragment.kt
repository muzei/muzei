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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.legacy.BuildConfig.LEGACY_AUTHORITY
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.util.toast
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.ChooseProviderFragmentBinding
import net.nurik.roman.muzei.databinding.ChooseProviderItemBinding

private class StartActivityFromSettings : ActivityResultContract<ComponentName, Boolean>() {
    override fun createIntent(context: Context, input: ComponentName): Intent =
            Intent().setComponent(input)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == Activity.RESULT_OK
}

class ChooseProviderFragment : Fragment(R.layout.choose_provider_fragment) {
    companion object {
        private const val TAG = "ChooseProviderFragment"
        private const val START_ACTIVITY_PROVIDER = "startActivityProvider"

        private const val PAYLOAD_HEADER = "HEADER"
        private const val PAYLOAD_DESCRIPTION = "DESCRIPTION"
        private const val PAYLOAD_CURRENT_IMAGE_URI = "CURRENT_IMAGE_URI"
        private const val PAYLOAD_SELECTED = "SELECTED"

        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
    }

    private val args: ChooseProviderFragmentArgs by navArgs()
    private var scrolledToProvider = false
    private val viewModel: ChooseProviderViewModel by viewModels()

    private var startActivityProvider: String? = null
    private val providerSetup = registerForActivityResult(StartActivityFromSettings()) { success ->
        val provider = startActivityProvider
        if (success && provider != null) {
            val context = requireContext()
            lifecycleScope.launch(NonCancellable) {
                Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                    param(FirebaseAnalytics.Param.ITEM_LIST_ID, provider)
                    param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, "after_setup")
                }
                ProviderManager.select(context, provider)
            }
        }
        startActivityProvider = null
    }
    private val providerSettings = registerForActivityResult(StartActivityFromSettings()) {
        startActivityProvider?.let { authority ->
            viewModel.refreshDescription(authority)
        }
        startActivityProvider = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityProvider = savedInstanceState?.getString(START_ACTIVITY_PROVIDER)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = ChooseProviderFragmentBinding.bind(view)
        requireActivity().menuInflater.inflate(R.menu.choose_provider_fragment,
                binding.toolbar.menu)
        val context = requireContext()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.auto_advance_settings -> {
                    if (binding.drawer.isDrawerOpen(GravityCompat.END)) {
                        binding.drawer.closeDrawer(GravityCompat.END)
                    } else {
                        Firebase.analytics.logEvent("auto_advance_open", null)
                        binding.drawer.openDrawer(GravityCompat.END)
                    }
                    true
                }
                R.id.auto_advance_disabled -> {
                    Firebase.analytics.logEvent("auto_advance_disabled", null)
                    context.toast(R.string.auto_advance_disabled_description,
                            Toast.LENGTH_LONG)
                    true
                }
                R.id.action_notification_settings -> {
                    Firebase.analytics.logEvent("notification_settings_open") {
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "overflow")
                    }
                    NotificationSettingsDialogFragment.showSettings(context,
                            childFragmentManager)
                    true
                }
                else -> false
            }
        }

        binding.drawer.setStatusBarBackgroundColor(Color.TRANSPARENT)
        binding.drawer.setScrimColor(Color.argb(68, 0, 0, 0))
        viewModel.currentProvider.collectIn(viewLifecycleOwner) { provider ->
            val legacySelected = provider?.authority == LEGACY_AUTHORITY
            binding.toolbar.menu.findItem(R.id.auto_advance_settings).isVisible = !legacySelected
            binding.toolbar.menu.findItem(R.id.auto_advance_disabled).isVisible = legacySelected
            if (legacySelected) {
                binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                        GravityCompat.END)
            } else {
                binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED,
                        GravityCompat.END)
            }
        }

        val spacing = resources.getDimensionPixelSize(R.dimen.provider_padding)
        binding.list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
            ) {
                outRect.set(spacing, spacing, spacing, spacing)
            }
        })
        val adapter = ProviderListAdapter()
        val playStoreAdapter = PlayStoreProviderAdapter()
        binding.list.adapter = ConcatAdapter(adapter, playStoreAdapter)
        viewModel.providers.collectIn(viewLifecycleOwner) {
            adapter.submitList(it) {
                playStoreAdapter.shouldShow = true
                if (args.authority != null && !scrolledToProvider) {
                    val index = it.indexOfFirst { providerInfo ->
                        providerInfo.authority == args.authority
                    }
                    if (index != -1) {
                        scrolledToProvider = true
                        requireArguments().remove("authority")
                        binding.list.smoothScrollToPosition(index)
                    }
                }
            }
        }
        // Show a SnackBar whenever there are unsupported sources installed
        var snackBar: Snackbar? = null
        viewModel.unsupportedSources.map { it.size }
                .distinctUntilChanged().collectIn(viewLifecycleOwner) { count ->
            if (count > 0) {
                snackBar = Snackbar.make(
                        binding.providerLayout,
                        resources.getQuantityString(R.plurals.legacy_unsupported_text, count, count),
                        Snackbar.LENGTH_INDEFINITE
                ).apply {
                    addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            if (isAdded && event != DISMISS_EVENT_CONSECUTIVE) {
                                // Reset the padding now that the SnackBar is dismissed
                                binding.list.updatePadding(bottom = resources.getDimensionPixelSize(
                                        R.dimen.provider_padding))
                            }
                        }
                    })
                    setAction(R.string.legacy_action_learn_more) {
                        val navController = findNavController()
                        if (navController.currentDestination?.id == R.id.choose_provider_fragment) {
                            navController.navigate(R.id.legacy_info)
                        }
                    }
                    show()
                    // Increase the padding when the SnackBar is shown to avoid
                    // overlapping the last element
                    binding.list.updatePadding(bottom = resources.getDimensionPixelSize(
                            R.dimen.provider_padding_with_snackbar))
                }
            } else {
                // There's no unsupported sources installed anymore, so just
                // dismiss any SnackBar that is being shown
                snackBar?.dismiss()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(START_ACTIVITY_PROVIDER, startActivityProvider)
    }

    private fun launchProviderSetup(provider: ProviderInfo) {
        try {
            startActivityProvider = provider.authority
            providerSetup.launch(provider.setupActivity)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        }
    }

    private fun launchProviderSettings(provider: ProviderInfo) {
        try {
            startActivityProvider = provider.authority
            providerSettings.launch(provider.settingsActivity)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider settings.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider settings.", e)
        }
    }

    inner class ProviderViewHolder(
            private val binding: ChooseProviderItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isSelected = false

        fun bind(
                providerInfo: ProviderInfo,
                clickListener: (Boolean) -> Unit
        ) = providerInfo.run {
            itemView.setOnClickListener {
                clickListener(isSelected)
            }
            itemView.setOnLongClickListener {
                if (packageName == requireContext().packageName) {
                    // Don't open Muzei's app info
                    return@setOnLongClickListener false
                }
                // Otherwise open third party extensions
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    Firebase.analytics.logEvent("app_settings_open") {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, authority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, title)
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                    }
                } catch (e: ActivityNotFoundException) {
                    return@setOnLongClickListener false
                }

                true
            }

            setHeader(providerInfo)

            setDescription(providerInfo)

            setImage(providerInfo)

            setSelected(providerInfo)
            binding.settings.setOnClickListener {
                Firebase.analytics.logEvent("provider_settings_open") {
                    param(FirebaseAnalytics.Param.ITEM_LIST_ID, authority)
                    param(FirebaseAnalytics.Param.ITEM_NAME, title)
                    param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                }
                launchProviderSettings(this)
            }
            binding.browse.setOnClickListener {
                val navController = findNavController()
                if (navController.currentDestination?.id == R.id.choose_provider_fragment) {
                    Firebase.analytics.logEvent("provider_browse_open") {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, authority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, title)
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                    }
                    navController.navigate(
                        ChooseProviderFragmentDirections.browse(
                            ProviderContract.getContentUri(authority)
                        )
                    )
                }
            }
        }

        fun setHeader(providerInfo: ProviderInfo) = providerInfo.run {
            binding.icon.setImageDrawable(icon)
            binding.title.text = title
        }

        fun setDescription(providerInfo: ProviderInfo) = providerInfo.run {
            binding.description.text = description
            binding.description.isGone = description.isNullOrEmpty()
        }

        fun setImage(providerInfo: ProviderInfo) = providerInfo.run {
            binding.artwork.isVisible = currentArtworkUri != null
            binding.artwork.load(currentArtworkUri) {
                lifecycle(viewLifecycleOwner)
                listener(
                        onError = { _, _ -> binding.artwork.isVisible = false },
                        onSuccess = { _, _ -> binding.artwork.isVisible = true }
                )
            }
        }

        fun setSelected(providerInfo: ProviderInfo) = providerInfo.run {
            isSelected = selected
            binding.selected.isInvisible = !selected
            binding.settings.isVisible = selected && settingsActivity != null
            binding.browse.isVisible = selected
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
                        (oldItem.title != newItem.title || oldItem.icon != newItem.icon) &&
                                oldItem.copy(title = newItem.title, icon = newItem.icon) ==
                                newItem ->
                            PAYLOAD_HEADER
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
                ProviderViewHolder(ChooseProviderItemBinding.inflate(layoutInflater,
                        parent, false))

        override fun onBindViewHolder(
                holder: ProviderViewHolder,
                position: Int
        ) = getItem(position).run {
            holder.bind(this) { isSelected ->
                if (isSelected) {
                    val context = context
                    val parentFragment = parentFragment?.parentFragment
                    if (context is Callbacks) {
                        Firebase.analytics.logEvent("choose_provider_reselected", null)
                        context.onRequestCloseActivity()
                    } else if (parentFragment is Callbacks) {
                        Firebase.analytics.logEvent("choose_provider_reselected", null)
                        parentFragment.onRequestCloseActivity()
                    }
                } else if (setupActivity != null) {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM) {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, authority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, title)
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
                    }
                    launchProviderSetup(this)
                } else {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, authority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, title)
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
                    }
                    val context = requireContext()
                    lifecycleScope.launch(NonCancellable) {
                        ProviderManager.select(context, authority)
                    }
                }
            }
        }

        override fun onBindViewHolder(
                holder: ProviderViewHolder,
                position: Int,
                payloads: MutableList<Any>
        ) {
            when {
                payloads.isEmpty() -> super.onBindViewHolder(holder, position, payloads)
                payloads[0] == PAYLOAD_HEADER -> holder.setHeader(getItem(position))
                payloads[0] == PAYLOAD_DESCRIPTION -> holder.setDescription(getItem(position))
                payloads[0] == PAYLOAD_CURRENT_IMAGE_URI -> holder.setImage(getItem(position))
                payloads[0] == PAYLOAD_SELECTED -> holder.setSelected(getItem(position))
                else -> throw IllegalArgumentException("Forgot to handle ${payloads[0]}")
            }
        }
    }

    inner class PlayStoreProviderAdapter : RecyclerView.Adapter<ProviderViewHolder>() {
        @SuppressLint("InlinedApi")
        private val playStoreIntent: Intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps" +
                        "&referrer=utm_source%3Dmuzei" +
                        "%26utm_medium%3Dapp" +
                        "%26utm_campaign%3Dget_more_sources"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .setPackage(PLAY_STORE_PACKAGE_NAME)
        private val playStoreComponentName: ComponentName? = playStoreIntent.resolveActivity(
                requireContext().packageManager)
        private val playStoreAuthority: String? = if (playStoreComponentName != null) "play_store" else null
        private val playStoreProviderInfo = if (playStoreComponentName != null && playStoreAuthority != null) {
            val pm = requireContext().packageManager
            ProviderInfo(
                    playStoreAuthority,
                    playStoreComponentName.packageName,
                    requireContext().getString(R.string.get_more_sources),
                    requireContext().getString(R.string.get_more_sources_description),
                    null,
                    pm.getActivityLogo(playStoreIntent)
                            ?: pm.getApplicationIcon(PLAY_STORE_PACKAGE_NAME),
                    null,
                    null,
                    false)
        } else {
            null
        }

        /**
         * We want to wait for the main list to load before showing our item so that the
         * RecyclerView doesn't attempt to keep this footer on screen when the other
         * data loads, so we delay loading until that occurs. Changing this to true is
         * our signal
         */
        var shouldShow = false
            set(value) {
                if (field != value) {
                    field = value
                    if (playStoreProviderInfo != null) {
                        if (value) {
                            notifyItemInserted(0)
                        } else {
                            notifyItemRemoved(0)
                        }
                    }
                }
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ProviderViewHolder(ChooseProviderItemBinding.inflate(layoutInflater,
                        parent, false))

        override fun getItemCount() = if (shouldShow && playStoreProviderInfo != null) 1 else 0

        override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
            holder.bind(playStoreProviderInfo!!) {
                Firebase.analytics.logEvent("more_sources_open", null)
                try {
                    startActivity(playStoreIntent)
                } catch (e: ActivityNotFoundException) {
                    requireContext().toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
                } catch (e: SecurityException) {
                    requireContext().toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
                }
            }
        }
    }


    fun interface Callbacks {
        fun onRequestCloseActivity()
    }
}