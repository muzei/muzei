/*
 * Copyright 2020 Google Inc.
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.wear.activity.ConfirmationActivity
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.apps.muzei.util.goAsync
import com.google.android.apps.muzei.util.toast
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R
import java.util.TreeSet

class OpenOnPhoneReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "OpenOnPhoneReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        goAsync {
            val capabilityClient = Wearable.getCapabilityClient(context)
            val nodes: Set<Node> = try {
                // We use activate_muzei for compatibility with
                // older versions of Muzei's phone app
                capabilityClient.getCapability("activate_muzei",
                        CapabilityClient.FILTER_REACHABLE).await().nodes
            } catch (e: Exception) {
                Log.e(TAG, "Error getting reachable capability info", e)
                TreeSet()
            }

            if (nodes.isEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    context.toast(R.string.datalayer_open_failed)
                }
            } else {
                Firebase.analytics.logEvent("data_layer_open_on_phone", null)
                // Show the open on phone animation
                val openOnPhoneIntent = Intent(context, ConfirmationActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                            ConfirmationActivity.OPEN_ON_PHONE_ANIMATION)
                }
                context.startActivity(openOnPhoneIntent)
                // Use RemoteActivityHelper.startRemoteActivity to open Muzei on the phone
                for (node in nodes) {
                    RemoteActivityHelper(context).startRemoteActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = "android-app://${context.packageName}".toUri()
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }, node.id)
                }
            }
        }
    }
}