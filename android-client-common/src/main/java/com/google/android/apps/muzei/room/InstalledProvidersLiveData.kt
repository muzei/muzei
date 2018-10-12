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

package com.google.android.apps.muzei.room

import android.arch.lifecycle.MutableLiveData
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newSingleThreadContext

fun ProviderInfo.getComponentName() = ComponentName(packageName, name)

/**
 * LiveData that returns the list of currently installed [MuzeiArtProvider] instances.
 */
class InstalledProvidersLiveData(
        private val context: Context,
        private val coroutineScope: CoroutineScope
) : MutableLiveData<List<ProviderInfo>>() {

    private val currentProviders = HashMap<ComponentName, ProviderInfo>()

    private val singleThreadContext = newSingleThreadContext("ChooseProvider")


    private val packageChangeReceiver : BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            coroutineScope.launch(singleThreadContext) {
                updateProviders(intent.data?.schemeSpecificPart)
            }
        }
    }

    private fun updateProviders(packageName: String? = null) {
        val queryIntent = Intent(MuzeiArtProvider.ACTION_MUZEI_ART_PROVIDER)
        if (packageName != null) {
            queryIntent.`package` = packageName
        }
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
                    it.packageName != packageName
                }
            }
            for (ri in resolveInfos) {
                val componentName = ri.providerInfo.getComponentName()
                existingProviders.removeAll { it.getComponentName() == componentName }
                if (ri.providerInfo.enabled) {
                    newProviders[componentName] = ri.providerInfo
                } else {
                    newProviders.remove(componentName)
                }
            }
            // Remove providers that weren't found in the resolveInfos
            existingProviders.forEach {
                newProviders.remove(it.getComponentName())
            }
            currentProviders.clear()
            currentProviders.putAll(newProviders)
            postValue(currentProviders.values.toList())
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
        context.registerReceiver(packageChangeReceiver, packageChangeFilter)
        coroutineScope.launch(singleThreadContext) {
            updateProviders()
        }
    }

    override fun onInactive() {
        context.unregisterReceiver(packageChangeReceiver)
    }
}
