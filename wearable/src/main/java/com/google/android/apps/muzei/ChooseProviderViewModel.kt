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
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.viewModelScope
import com.google.android.apps.muzei.room.InstalledProvidersLiveData
import net.nurik.roman.muzei.BuildConfig.DATA_LAYER_AUTHORITY

data class ProviderInfo(
        val authority: String,
        val packageName: String,
        val title: String,
        val icon: Drawable,
        val setupActivity: ComponentName?
) {
    constructor(
            packageManager: PackageManager,
            providerInfo: android.content.pm.ProviderInfo
    ) : this(
            providerInfo.authority,
            providerInfo.packageName,
            providerInfo.loadLabel(packageManager).toString(),
            providerInfo.loadIcon(packageManager),
            providerInfo.metaData?.getString("setupActivity")?.run {
                ComponentName(providerInfo.packageName, this)
            })
}

class ChooseProviderViewModel(application: Application) : AndroidViewModel(application) {

    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        // The DataLayerArtProvider should always the first provider listed
        if (p1.authority == DATA_LAYER_AUTHORITY) {
            return@Comparator -1
        } else if (p2.authority == DATA_LAYER_AUTHORITY) {
            return@Comparator 1
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

    private val mutableProviders = InstalledProvidersLiveData(application, viewModelScope)

    val providers : LiveData<List<ProviderInfo>?> = Transformations
            .map(mutableProviders) { providerInfos ->
                providerInfos.asSequence().map { providerInfo ->
                    ProviderInfo(application.packageManager, providerInfo)
                }.sortedWith(comparator).toList()
            }
}
