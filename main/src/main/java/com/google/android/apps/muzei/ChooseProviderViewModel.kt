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

import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.room.getProviderDescription
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.runBlocking

data class ProviderInfo(
        val componentName: ComponentName,
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
            description: String?,
            currentArtworkUri: Uri?,
            selected: Boolean
    ) : this(
                ComponentName(providerInfo.packageName, providerInfo.name),
                providerInfo.loadLabel(packageManager)?.toString() ?: "",
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

class ChooseProviderViewModel(application: Application) : AndroidViewModel(application) {
    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        val pn1 = p1.componentName.packageName
        val pn2 = p2.componentName.packageName
        if (pn1 != pn2) {
            if (application.packageName == pn1) {
                return@Comparator -1
            } else if (application.packageName == pn2) {
                return@Comparator 1
            }
        }
        // These labels should be non-null with the isNullOrEmpty() check above
        val title1 = p1.title
                ?: throw IllegalStateException("Found null label for ${p1.componentName}")
        val title2 = p2.title
                ?: throw IllegalStateException("Found null label for ${p2.componentName}")
        title1.compareTo(title2)
    }

    private val currentProviders = HashMap<ComponentName, ProviderInfo>()
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            updateProviders(intent.data?.schemeSpecificPart)
        }
    }

    private fun updateProviders(packageName: String? = null) {
        val queryIntent = Intent(MuzeiArtProvider.ACTION_MUZEI_ART_PROVIDER)
        if (packageName != null) {
            queryIntent.`package` = packageName
        }
        val pm = getApplication<Application>().packageManager
        val resolveInfos = pm.queryIntentContentProviders(queryIntent,
                PackageManager.GET_META_DATA)
        if (resolveInfos != null) {
            val existingProviders = HashSet(currentProviders.values)
            if (packageName != null) {
                existingProviders.filter {
                    it.componentName.packageName == packageName
                }
            }
            for (ri in resolveInfos) {
                val componentName = ComponentName(ri.providerInfo.packageName,
                        ri.providerInfo.name)
                existingProviders.removeAll { it.componentName == componentName }
                if (ri.providerInfo.enabled) {
                    val context = getApplication<Application>()
                    val selected = currentProviders[componentName]?.selected == true
                    val (description, currentArtwork) = runBlocking(CommonPool) {
                        componentName.getProviderDescription(context) to
                                MuzeiDatabase.getInstance(context).artworkDao()
                                        .getCurrentArtworkForProvider(componentName)
                    }
                    currentProviders[componentName] = ProviderInfo(pm, ri.providerInfo,
                            description, currentArtwork?.imageUri, selected)
                } else {
                    currentProviders.remove(componentName)
                }
            }
            // Remove providers that weren't found in the resolveInfos
            existingProviders.forEach {
                currentProviders.remove(it.componentName)
            }
            mutableProviders.value = currentProviders.values.sortedWith(comparator)
        }
    }

    private val mutableProviders = object : MutableLiveData<List<ProviderInfo>>() {
        val currentProviderLiveData = MuzeiDatabase.getInstance(application).providerDao()
                .currentProvider
        val currentProviderObserver = Observer<Provider?> { provider ->
            if (provider != null) {
                currentProviders.filterValues { it.selected }.forEach {
                    currentProviders[it.key] = it.value.copy(selected = false)
                }
                currentProviders[provider.componentName] =
                        currentProviders[provider.componentName]!!.copy(selected = true)
                value = currentProviders.values.sortedWith(comparator)
            }
        }
        val currentArtworkByProviderLiveData = MuzeiDatabase.getInstance(application).artworkDao()
                .currentArtworkByProvider
        val currentArtworkByProviderObserver = Observer<List<com.google.android.apps.muzei.room.Artwork>> { artworkByProvider ->
            if (artworkByProvider != null) {
                val artworkMap = HashMap<ComponentName, Uri>()
                artworkByProvider.forEach { artwork ->
                    artworkMap[artwork.providerComponentName] = artwork.imageUri
                }
                currentProviders.forEach {
                    currentProviders[it.key] = it.value.copy(currentArtworkUri = artworkMap[it.key])
                }
                value = currentProviders.values.sortedWith(comparator)
            }
        }

        override fun onActive() {
            // Register for package change events
            val packageChangeFilter = IntentFilter().apply {
                addDataScheme("package")
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
            }
            application.registerReceiver(packageChangeReceiver, packageChangeFilter)
            updateProviders()
            currentProviderLiveData.observeForever(currentProviderObserver)
            currentArtworkByProviderLiveData.observeForever(currentArtworkByProviderObserver)
        }

        override fun onInactive() {
            currentArtworkByProviderLiveData.removeObserver(currentArtworkByProviderObserver)
            currentProviderLiveData.removeObserver(currentProviderObserver)
            application.unregisterReceiver(packageChangeReceiver)
        }
    }

    val providers : LiveData<List<ProviderInfo>> = mutableProviders
}
