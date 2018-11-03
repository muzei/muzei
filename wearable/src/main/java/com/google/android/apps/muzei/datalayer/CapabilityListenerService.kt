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

package com.google.android.apps.muzei.datalayer

import android.content.ComponentName
import android.content.pm.PackageManager
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig.DATA_LAYER_AUTHORITY

/**
 * WearableListenerService responsible for listening for the availability of Muzei's phone app
 */
class CapabilityListenerService : WearableListenerService() {

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo?) {
        val removed = capabilityInfo?.nodes?.isEmpty() ?: false
        if (!removed) {
            // Muzei's phone app is installed, allow use of the DataLayerArtProvider
            packageManager.setComponentEnabledSetting(
                    ComponentName(this, DataLayerArtProvider::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
            if (ActivateMuzeiIntentService.hasPendingInstall(this)) {
                val context = this
                GlobalScope.launch {
                    ProviderManager.select(context, DATA_LAYER_AUTHORITY)
                    ActivateMuzeiIntentService.resetPendingInstall(context)
                }
            }
        }
    }
}
