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

import android.content.Context
import android.content.Intent
import android.support.wearable.activity.ConfirmationActivity
import android.util.Log
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.util.toastFromBackground
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import net.nurik.roman.muzei.R
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.TreeSet
import java.util.concurrent.ExecutionException

/**
 * Provider handling art from a connected phone
 */
class DataLayerArtProvider : MuzeiArtProvider() {

    companion object {
        private const val TAG = "DataLayerArtProvider"
        const val OPEN_ON_PHONE_ACTION = 1

        fun getAssetFile(context: Context): File =
                File(context.filesDir, "data_layer")
    }

    override fun onLoadRequested(initial: Boolean) {
        if (initial) {
            DataLayerLoadWorker.enqueueLoad()
        }
    }

    override fun onCommand(artwork: Artwork, id: Int) {
        val context = context ?: return
        when(id) {
            OPEN_ON_PHONE_ACTION -> runBlocking {
                // Open on Phone action
                val capabilityClient = Wearable.getCapabilityClient(context)
                val nodes: Set<Node> = try {
                    // We use activate_muzei for compatibility with
                    // older versions of Muzei's phone app
                    capabilityClient.getCapability("activate_muzei",
                            CapabilityClient.FILTER_REACHABLE).await().nodes
                } catch (e: ExecutionException) {
                    Log.e(TAG, "Error getting reachable capability info", e)
                    TreeSet()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Error getting reachable capability info", e)
                    TreeSet()
                }

                if (nodes.isEmpty()) {
                    context.toastFromBackground(R.string.datalayer_open_failed)
                } else {
                    FirebaseAnalytics.getInstance(context).logEvent("data_layer_open_on_phone", null)
                    // Show the open on phone animation
                    val openOnPhoneIntent = Intent(context, ConfirmationActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                                ConfirmationActivity.OPEN_ON_PHONE_ANIMATION)
                    }
                    context.startActivity(openOnPhoneIntent)
                    // Send the message to the phone to open Muzei
                    val messageClient = Wearable.getMessageClient(context)
                    for (node in nodes) {
                        try {
                            // We use notification/open for compatibility with
                            // older versions of Muzei's phone app
                            messageClient.sendMessage(node.id,
                                    "notification/open", null).await()
                        } catch (e: ExecutionException) {
                            Log.w(TAG, "Unable to send Open on phone message to ${node.id}", e)
                        } catch (e: InterruptedException) {
                            Log.w(TAG, "Unable to send Open on phone message to ${node.id}", e)
                        }
                    }
                }
            }
            else -> super.onCommand(artwork, id)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(artwork: Artwork): InputStream {
        val context = context ?: throw FileNotFoundException()
        return FileInputStream(getAssetFile(context))
    }
}
