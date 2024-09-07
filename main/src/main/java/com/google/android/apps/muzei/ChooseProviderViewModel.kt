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
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.apps.muzei.legacy.BuildConfig.LEGACY_AUTHORITY
import com.google.android.apps.muzei.legacy.LegacySourceManager
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getInstalledProviders
import com.google.android.apps.muzei.sync.ProviderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

data class ProviderInfo(
        val authority: String,
        val packageName: String,
        val title: String,
        val description: String?,
        val currentArtworkUri: Uri?,
        val icon: Drawable,
        val setupActivity: ComponentName?,
        val settingsActivity: ComponentName?,
        val selected: Boolean
) {
    constructor(
            packageManager: PackageManager,
            providerInfo: android.content.pm.ProviderInfo,
            description: String? = null,
            currentArtworkUri: Uri? = null,
            selected: Boolean = false
    ) : this(
                providerInfo.authority,
                providerInfo.packageName,
                providerInfo.loadLabel(packageManager).toString(),
                description,
                currentArtworkUri,
                providerInfo.loadIcon(packageManager),
                providerInfo.metaData?.getString("setupActivity")?.run {
                    ComponentName(providerInfo.packageName, this)
                },
                providerInfo.metaData?.getString("settingsActivity")?.run {
                    ComponentName(providerInfo.packageName, this)
                },
                selected)
}

@SuppressLint("WrongConstant")
class ChooseProviderViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MuzeiDatabase.getInstance(application)

    val currentProvider = database.providerDao().getCurrentProviderFlow()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    val unsupportedSources = LegacySourceManager.getInstance(application).unsupportedSources
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        // The SourceArtProvider should always the last provider listed
        if (p1.authority == LEGACY_AUTHORITY) {
            return@Comparator 1
        } else if (p2.authority == LEGACY_AUTHORITY) {
            return@Comparator -1
        }
        // Then put providers from Muzei on top
        val pn1 = p1.packageName
        val pn2 = p2.packageName
        if (pn1 != pn2) {
            if (application.packageName == pn1) {
                return@Comparator -1
            } else if (application.packageName == pn2) {
                return@Comparator 1
            }
        }
        // Finally, sort providers by their title
        p1.title.compareTo(p2.title)
    }

    /**
     * The set of installed providers, transformed into a list of [ProviderInfo] objects
     */
    private val installedProviders = getInstalledProviders(application).map { providerInfos ->
        val packageManager = application.packageManager
        providerInfos.map { providerInfo ->
            ProviderInfo(packageManager, providerInfo)
        }
    }

    /**
     * The authority of the current MuzeiArtProvider
     */
    private val currentProviderAuthority = database.providerDao().getCurrentProviderFlow()
      .map { provider ->
        provider?.authority
    }

    /**
     * An authority to current artwork URI map
     */
    private val currentArtworkByProvider = database.artworkDao().getCurrentArtworkByProvider()
      .map { artworkForProvider ->
        val artworkMap = mutableMapOf<String, Artwork>()
        artworkForProvider.forEach {  artwork ->
            artworkMap[artwork.providerAuthority] = artwork
        }
        artworkMap
    }

    /**
     * An authority to description map used to avoid querying each
     * MuzeiArtProvider every time.
     */
    private val descriptions = mutableMapOf<String, String>()
    /**
     * MutableStateFlow that should be updated with the current nano time
     * when the descriptions are invalidated.
     */
    private val descriptionInvalidationNanoTime = MutableStateFlow(0L)

    /**
     * Combine all of the separate signals we have into one final set of [ProviderInfo]:
     * - The set of installed providers
     * - the currently selected provider
     * - the current artwork for each provider
     * - the input signal for when the descriptions have been invalidated (we don't
     * care about the value, but we do want to recompute the [ProviderInfo] values)
     */
    val providers = combine(
            installedProviders,
            currentProviderAuthority,
            currentArtworkByProvider,
            descriptionInvalidationNanoTime
    ) { installedProviders, providerAuthority, artworkForProvider, _ ->
        installedProviders.map { providerInfo ->
            val authority = providerInfo.authority
            val selected = authority == providerAuthority
            val description = descriptions[authority] ?: run {
                // Populate the description if we don't already have one
                val newDescription = ProviderManager.getDescription(application, authority)
                descriptions[authority] = newDescription
                newDescription
            }
            val currentArtwork = artworkForProvider[authority]
            providerInfo.copy(
                    selected = selected,
                    description = description,
                    currentArtworkUri = currentArtwork?.imageUri
            )
        }.sortedWith(comparator)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    internal fun refreshDescription(authority: String) {
        // Remove the current description and trigger the invalidation
        // to recompute the description
        descriptions.remove(authority)
        descriptionInvalidationNanoTime.value = System.nanoTime()
    }

    private val localeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Our cached descriptions need to be invalidated when the locale changes
            // so that we re-query for descriptions in the new language
            descriptions.clear()
            descriptionInvalidationNanoTime.value = System.nanoTime()
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            localeChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_LOCALE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(localeChangeReceiver)
    }
}