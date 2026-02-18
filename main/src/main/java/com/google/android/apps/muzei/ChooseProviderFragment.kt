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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R

private class StartActivityFromSettings : ActivityResultContract<ComponentName, Boolean>() {
    override fun createIntent(context: Context, input: ComponentName): Intent =
        Intent().setComponent(input)
            .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}

class ChooseProviderFragment : Fragment() {
    companion object {
        private const val TAG = "ChooseProviderFragment"

        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
    }

    private val args: ChooseProviderFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = content {
        AppTheme(
            dynamicColor = false,
        ) {
            val viewModel: ChooseProviderViewModel = viewModel {
                ChooseProviderViewModel(requireActivity().application)
            }
            var startActivityProviderAuthority by rememberSerializable { mutableStateOf("") }
            val providerSetupLauncher = rememberLauncherForActivityResult(
                StartActivityFromSettings()
            ) { success ->
                val provider = startActivityProviderAuthority
                if (success && provider.isNotBlank()) {
                    val context = requireContext()
                    lifecycleScope.launch {
                        withContext(NonCancellable) {
                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                                param(FirebaseAnalytics.Param.ITEM_LIST_ID, provider)
                                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                                param(FirebaseAnalytics.Param.CONTENT_TYPE, "after_setup")
                            }
                            ProviderManager.select(context, provider)
                        }
                    }
                }
                startActivityProviderAuthority = ""
            }
            val providerSettingsLauncher = rememberLauncherForActivityResult(
                StartActivityFromSettings()
            ) { success ->
                val provider = startActivityProviderAuthority
                if (success && provider.isNotBlank()) {
                    viewModel.refreshDescription(provider)
                }
                startActivityProviderAuthority = ""
            }
            val providers by viewModel.providers.collectAsState()
            val context = LocalContext.current
            val pm = context.packageManager
            val resources = LocalResources.current
            val playStoreIntent: Intent = Intent(
                Intent.ACTION_VIEW,
                ("http://play.google.com/store/search?q=Muzei&c=apps" +
                        "&referrer=utm_source%3Dmuzei" +
                        "%26utm_medium%3Dapp" +
                        "%26utm_campaign%3Dget_more_sources").toUri()
            )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .setPackage(PLAY_STORE_PACKAGE_NAME)
            val playStoreProviderInfo = remember(resources) {
                val playStoreComponentName: ComponentName? = playStoreIntent.resolveActivity(pm)
                val playStoreAuthority: String? =
                    if (playStoreComponentName != null) "play_store" else null
                if (playStoreComponentName != null && playStoreAuthority != null) {
                    ProviderInfo(
                        playStoreAuthority,
                        playStoreComponentName.packageName,
                        resources.getString(R.string.get_more_sources),
                        resources.getString(R.string.get_more_sources_description),
                        null,
                        pm.getActivityLogo(playStoreIntent)
                            ?: pm.getApplicationIcon(PLAY_STORE_PACKAGE_NAME),
                        null,
                        null,
                        false
                    )
                } else {
                    null
                }
            }
            val currentProvider by viewModel.currentProvider.collectAsState()
            val unsupportedSources by viewModel.unsupportedSources.collectAsState()
            var scrolledToProvider by rememberSerializable { mutableStateOf(false) }
            val autoScrollToProviderAuthority = if (!scrolledToProvider) {
                args.authority
            } else {
                null
            }
            ChooseProvider(
                providers = providers + if (providers.isNotEmpty() && playStoreProviderInfo != null) {
                    listOf(playStoreProviderInfo)
                } else {
                    emptyList()
                },
                selectedProviderAuthority = currentProvider?.authority,
                drawerSheetContent = {
                    AutoAdvance(
                        contentPadding = WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Top + WindowInsetsSides.End)
                            .asPaddingValues()
                    )
                },
                drawerSheetContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                onNotificationSettingsClick = {
                    Firebase.analytics.logEvent("notification_settings_open") {
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "overflow")
                    }
                    NotificationSettingsDialogFragment.showSettings(
                        context,
                        childFragmentManager
                    )
                },
                unsupportedSourceCount = unsupportedSources.size,
                onUnsupportedSourceLearnMoreClick = {
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.choose_provider_fragment) {
                        navController.navigate(R.id.legacy_info)
                    }
                },
                autoScrollToProviderAuthority = autoScrollToProviderAuthority,
                onAutoScrollToProviderCompleted = {
                    scrolledToProvider = true
                    requireArguments().remove("authority")
                },
                onClick = { providerInfo ->
                    if (providerInfo == playStoreProviderInfo) {
                        Firebase.analytics.logEvent("more_sources_open", null)
                        try {
                            startActivity(playStoreIntent)
                        } catch (_: ActivityNotFoundException) {
                            requireContext().toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
                        } catch (_: SecurityException) {
                            requireContext().toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
                        }
                    } else {
                        if (providerInfo.selected) {
                            val parentFragment = parentFragment?.parentFragment
                            if (context is Callbacks) {
                                Firebase.analytics.logEvent("choose_provider_reselected", null)
                                context.onRequestCloseActivity()
                            } else if (parentFragment is Callbacks) {
                                Firebase.analytics.logEvent("choose_provider_reselected", null)
                                parentFragment.onRequestCloseActivity()
                            }
                        } else if (providerInfo.setupActivity != null) {
                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM) {
                                param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerInfo.authority)
                                param(FirebaseAnalytics.Param.ITEM_NAME, providerInfo.title)
                                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                                param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
                            }
                            try {
                                startActivityProviderAuthority = providerInfo.authority
                                providerSetupLauncher.launch(providerInfo.setupActivity)
                            } catch (e: ActivityNotFoundException) {
                                Log.e(TAG, "Can't launch provider setup.", e)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Can't launch provider setup.", e)
                            }
                        } else {
                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                                param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerInfo.authority)
                                param(FirebaseAnalytics.Param.ITEM_NAME, providerInfo.title)
                                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                                param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
                            }
                            lifecycleScope.launch {
                                withContext(NonCancellable) {
                                    ProviderManager.select(context, providerInfo.authority)
                                }
                            }
                        }
                    }
                },
                onLongClick = { providerInfo ->
                    // Only open third party provider's system settings
                    if (providerInfo.packageName != requireContext().packageName) {
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", providerInfo.packageName, null)
                                )
                            )
                            Firebase.analytics.logEvent("app_settings_open") {
                                param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerInfo.authority)
                                param(FirebaseAnalytics.Param.ITEM_NAME, providerInfo.title)
                                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                            }
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                },
                onSettingsClick = { providerInfo ->
                    Firebase.analytics.logEvent("provider_settings_open") {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerInfo.authority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, providerInfo.title)
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                    }
                    try {
                        startActivityProviderAuthority = providerInfo.authority
                        providerSettingsLauncher.launch(providerInfo.settingsActivity!!)
                    } catch (e: ActivityNotFoundException) {
                        Log.e(TAG, "Can't launch provider settings.", e)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Can't launch provider settings.", e)
                    }
                },
                onBrowseClick = { providerInfo ->
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.choose_provider_fragment) {
                        Firebase.analytics.logEvent("provider_browse_open") {
                            param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerInfo.authority)
                            param(FirebaseAnalytics.Param.ITEM_NAME, providerInfo.title)
                            param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                        }
                        navController.navigate(
                            ChooseProviderFragmentDirections.browse(
                                ProviderContract.getContentUri(providerInfo.authority)
                            )
                        )
                    }
                },
            )
        }
    }

    fun interface Callbacks {
        fun onRequestCloseActivity()
    }
}