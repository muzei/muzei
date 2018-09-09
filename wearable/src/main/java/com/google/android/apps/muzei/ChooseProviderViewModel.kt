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
import android.arch.lifecycle.Transformations
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.google.android.apps.muzei.datalayer.DataLayerArtProvider
import com.google.android.apps.muzei.room.InstalledProvidersLiveData

data class ProviderInfo(
        val componentName: ComponentName,
        val title: String,
        val icon: Drawable,
        val setupActivity: ComponentName?
) {
    constructor(
            packageManager: PackageManager,
            providerInfo: android.content.pm.ProviderInfo
    ) : this(
            ComponentName(providerInfo.packageName, providerInfo.name),
            providerInfo.loadLabel(packageManager).toString(),
            providerInfo.loadIcon(packageManager),
            providerInfo.metaData?.getString("setupActivity")?.run {
                ComponentName(providerInfo.packageName, this)
            })
}

class ChooseProviderViewModel(application: Application) : AndroidViewModel(application) {

    private val dataLayerArtProvider = ComponentName(application, DataLayerArtProvider::class.java)

    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        // The DataLayerArtProvider should always the first provider listed
        if (p1.componentName == dataLayerArtProvider) {
            return@Comparator -1
        } else if (p2.componentName == dataLayerArtProvider) {
            return@Comparator 1
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

    private val mutableProviders = InstalledProvidersLiveData(application)

    val providers : LiveData<List<ProviderInfo>?> = Transformations
            .map(mutableProviders) { providerInfos ->
                providerInfos.map { providerInfo ->
                    ProviderInfo(application.packageManager, providerInfo)
                }.sortedWith(comparator)
            }
}
