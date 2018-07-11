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
import com.google.android.apps.muzei.sources.SourceArtProvider
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import net.nurik.roman.muzei.R

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

    companion object {
        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
    }

    private val currentProviders = HashMap<ComponentName, ProviderInfo>()

    @SuppressLint("InlinedApi")
    val playStoreIntent: Intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps" +
                    "&referrer=utm_source%3Dmuzei" +
                    "%26utm_medium%3Dapp" +
                    "%26utm_campaign%3Dget_more_sources"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            .setPackage(PLAY_STORE_PACKAGE_NAME)
    val playStoreComponentName: ComponentName? = playStoreIntent.resolveActivity(
            application.packageManager)

    init {
        if (playStoreComponentName != null) {
            val pm = application.packageManager
            currentProviders[playStoreComponentName] = ProviderInfo(
                    playStoreComponentName,
                    application.getString(R.string.get_more_sources),
                    application.getString(R.string.get_more_sources_description),
                    null,
                    pm.getActivityLogo(playStoreIntent)
                            ?: pm.getApplicationIcon(PLAY_STORE_PACKAGE_NAME),
                    null,
                    null,
                    false)
        }
    }

    private val sourceArtProvider = ComponentName(application, SourceArtProvider::class.java)

    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        // Get More Sources should always be at the end of the list
        if (p1.componentName == playStoreComponentName) {
            return@Comparator 1
        } else if (p2.componentName == playStoreComponentName) {
            return@Comparator -1
        }
        // The SourceArtProvider should always the last provider listed
        if (p1.componentName == sourceArtProvider) {
            return@Comparator 1
        } else if (p2.componentName == sourceArtProvider) {
            return@Comparator -1
        }
        // Then put providers from Muzei on top
        val pn1 = p1.componentName.packageName
        val pn2 = p2.componentName.packageName
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
                existingProviders.removeAll {
                    it.componentName.packageName != packageName
                }
            }
            existingProviders.removeAll {
                it.componentName == playStoreComponentName
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

    internal fun refreshDescription(componentName: ComponentName) {
        launch {
            val updatedDescription = componentName.getProviderDescription(getApplication())
            currentProviders[componentName]?.let { providerInfo ->
                if (providerInfo.description != updatedDescription) {
                    currentProviders[componentName] =
                            providerInfo.copy(description = updatedDescription)
                    mutableProviders.postValue(currentProviders.values.sortedWith(comparator))
                }
            }
        }
    }
}
