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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.core.content.ContextCompat
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun ProviderInfo.getComponentName() = ComponentName(packageName, name)

private fun getProviders(context: Context, packageName: String? = null): List<ProviderInfo> {
    val queryIntent = Intent(MuzeiArtProvider.ACTION_MUZEI_ART_PROVIDER)
    if (packageName != null) {
        queryIntent.`package` = packageName
    }
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    return pm.queryIntentContentProviders(queryIntent, PackageManager.GET_META_DATA).filterNotNull().map {
        it.providerInfo
    }.filter {
        it.enabled
    }
}

/**
 * Get a [Flow] for the list of currently installed [MuzeiArtProvider] instances.
 */
@SuppressLint("WrongConstant")
fun getInstalledProviders(context: Context): Flow<List<ProviderInfo>> = callbackFlow {
    val currentProviders = HashMap<ComponentName, ProviderInfo>()
    val packageChangeReceiver : BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            val packageName = intent.data?.schemeSpecificPart
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    getProviders(context, packageName).forEach { providerInfo ->
                        currentProviders[providerInfo.getComponentName()] = providerInfo
                    }
                }
                Intent.ACTION_PACKAGE_CHANGED, Intent.ACTION_PACKAGE_REPLACED -> {
                    currentProviders.entries.removeAll { it.key.packageName == packageName }
                    getProviders(context, packageName).forEach { providerInfo ->
                        currentProviders[providerInfo.getComponentName()] = providerInfo
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    currentProviders.entries.removeAll { it.key.packageName == packageName }
                }
            }
            trySend(currentProviders.values.toList())
        }
    }
    val packageChangeFilter = IntentFilter().apply {
        addDataScheme("package")
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
    }
    ContextCompat.registerReceiver(
        context,
        packageChangeReceiver,
        packageChangeFilter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
    // Populate the initial set of providers
    getProviders(context).forEach { providerInfo ->
        currentProviders[providerInfo.getComponentName()] = providerInfo
    }
    send(currentProviders.values.toList())

    awaitClose {
        context.unregisterReceiver(packageChangeReceiver)
    }
}