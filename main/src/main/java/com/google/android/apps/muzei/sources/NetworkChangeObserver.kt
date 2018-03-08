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

@file:Suppress("DEPRECATION")

package com.google.android.apps.muzei.sources

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.Observer
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.support.v4.content.WakefulBroadcastReceiver
import android.util.Log
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Source
import com.google.android.apps.muzei.sync.TaskQueueService

/**
 * LifecycleObserver responsible for monitoring network connectivity and retrying artwork as necessary
 */
class NetworkChangeObserver internal constructor(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "NetworkChangeObserver"
    }

    private val networkChangeReceiver = object : WakefulBroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val hasConnectivity = !intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)
            if (!hasConnectivity) {
                return
            }
            // Check with components that may not currently be alive but interested in
            // network connectivity changes.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                val retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(context)
                if (retryIntent != null) {
                    WakefulBroadcastReceiver.startWakefulService(context, retryIntent)
                }
            }

            val pendingResult = goAsync()
            val sourcesLiveData = MuzeiDatabase.getInstance(context).sourceDao()
                    .currentSourcesThatWantNetwork
            sourcesLiveData.observeForever(object : Observer<List<Source>> {
                override fun onChanged(sources: List<Source>?) {
                    sourcesLiveData.removeObserver(this)
                    if (sources != null) {
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
                    pendingResult.finish()
                }
            })
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val networkChangeFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(networkChangeReceiver, networkChangeFilter)

        // Ensure we retry loading the artwork if the network changed while the wallpaper was disabled
        val connectivityManager = context.getSystemService(
                Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(context)
        if (retryIntent != null && connectivityManager.activeNetworkInfo?.isConnected == true) {
            context.startService(retryIntent)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        context.unregisterReceiver(networkChangeReceiver)
    }
}
