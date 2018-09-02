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
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newSingleThreadContext

data class ProviderInfo(
        val componentName: ComponentName,
        val title: String,
        val icon: Drawable,
        val setupActivity: ComponentName?,
        val settingsActivity: ComponentName?,
        val selected: Boolean
) {
    constructor(
            packageManager: PackageManager,
            providerInfo: android.content.pm.ProviderInfo,
            selected: Boolean
    ) : this(
            ComponentName(providerInfo.packageName, providerInfo.name),
            providerInfo.loadLabel(packageManager).toString(),
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

    private val currentProviders = HashMap<ComponentName, ProviderInfo>()
    private var activeProvider : Provider? = null

    private val singleThreadContext = newSingleThreadContext("ChooseProvider")

    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        // Put providers from Muzei on top
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

    private val packageChangeReceiver : BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            launch(singleThreadContext) {
                updateProviders(intent.data?.schemeSpecificPart)
            }
        }
    }

    private fun updateProviders(packageName: String? = null) {
        val queryIntent = Intent(MuzeiArtProvider.ACTION_MUZEI_ART_PROVIDER)
        if (packageName != null) {
            queryIntent.`package` = packageName
        }
        val context = getApplication<Application>()
        val pm = context.packageManager
        val resolveInfos = pm.queryIntentContentProviders(queryIntent,
                PackageManager.GET_META_DATA)
        if (resolveInfos != null) {
            val newProviders = HashMap<ComponentName, ProviderInfo>().apply {
                putAll(currentProviders)
            }
            val existingProviders = HashSet(currentProviders.values)
            if (packageName != null) {
                existingProviders.removeAll {
                    it.componentName.packageName != packageName
                }
            }
            for (ri in resolveInfos) {
                val componentName = ComponentName(ri.providerInfo.packageName,
                        ri.providerInfo.name)
                existingProviders.removeAll { it.componentName == componentName }
                if (ri.providerInfo.enabled) {
                    val selected = componentName == activeProvider?.componentName
                    newProviders[componentName] = ProviderInfo(pm, ri.providerInfo, selected)
                } else {
                    newProviders.remove(componentName)
                }
            }
            // Remove providers that weren't found in the resolveInfos
            existingProviders.forEach {
                newProviders.remove(it.componentName)
            }
            currentProviders.clear()
            currentProviders.putAll(newProviders)
            mutableProviders.postValue(currentProviders.values.sortedWith(comparator))
        }
    }

    private val mutableProviders : MutableLiveData<List<ProviderInfo>> = object : MutableLiveData<List<ProviderInfo>>() {
        val currentProviderLiveData = MuzeiDatabase.getInstance(application).providerDao()
                .currentProvider
        val currentProviderObserver = Observer<Provider?> { provider ->
            activeProvider = provider
            if (provider != null) {
                currentProviders.forEach {
                    val newlySelected = it.key == provider.componentName
                    if (it.value.selected != newlySelected) {
                        currentProviders[it.key] = it.value.copy(selected = newlySelected)
                    }
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
            launch(singleThreadContext) {
                updateProviders()
                launch(UI) {
                    if (isActive) {
                        currentProviderLiveData.observeForever(currentProviderObserver)
                    }
                }
            }
        }

        override fun onInactive() {
            if (currentProviderLiveData.hasObservers()) {
                currentProviderLiveData.removeObserver(currentProviderObserver)
            }
            application.unregisterReceiver(packageChangeReceiver)
        }
    }

    val providers : LiveData<List<ProviderInfo>?> = mutableProviders
}
