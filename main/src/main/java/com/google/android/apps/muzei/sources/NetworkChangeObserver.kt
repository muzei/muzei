/*
 * Copyright 2017 Google Inc.
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

package com.google.android.apps.muzei.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.goAsync

/**
 * LifecycleObserver responsible for monitoring network connectivity and retrying artwork as necessary
 */
class NetworkChangeObserver internal constructor(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "NetworkChangeObserver"
    }

    private val networkChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val hasConnectivity = !intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)
            if (!hasConnectivity) {
                return
            }

            goAsync {
                val sources = MuzeiDatabase.getInstance(context).sourceDao()
                        .getCurrentSourcesThatWantNetwork()
                for (source in sources) {
                    val sourceName = source.componentName
                    try {
                        context.packageManager.getServiceInfo(sourceName, 0)
                        context.startService(Intent(ACTION_NETWORK_AVAILABLE)
                                .setComponent(sourceName))
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.i(TAG, "Sending network available to $sourceName failed.", e)
                    } catch (e: IllegalStateException) {
                        Log.i(TAG, "Sending network available to $sourceName failed.", e)
                    } catch (e: SecurityException) {
                        Log.i(TAG, "Sending network available to $sourceName failed.", e)
                    }
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        @Suppress("DEPRECATION")
        val networkChangeFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(networkChangeReceiver, networkChangeFilter)
    }

    override fun onStop(owner: LifecycleOwner) {
        context.unregisterReceiver(networkChangeReceiver)
    }
}
