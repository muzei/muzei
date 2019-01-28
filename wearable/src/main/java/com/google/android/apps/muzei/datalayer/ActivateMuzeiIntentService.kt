/*
 * Copyright 2014 Google Inc.
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

import android.app.IntentService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.apps.muzei.ChooseProviderActivity
import com.google.android.apps.muzei.util.toast
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.wearable.intent.RemoteIntent
import com.google.android.wearable.playstore.PlayStoreAvailability
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.tasks.await
import net.nurik.roman.muzei.R
import java.util.TreeSet
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class ActivateMuzeiIntentService : IntentService(TAG) {

    companion object {
        private const val TAG = "ActivateMuzeiService"
        private const val NOTIFICATION_CHANNEL = "activate_muzei"
        private const val INSTALL_NOTIFICATION_ID = 3113
        private const val ACTIVATE_NOTIFICATION_ID = 3114
        private const val INSTALL_PENDING_PREF_KEY = "INSTALL_PENDING"
        private const val ACTION_MARK_NOTIFICATION_READ = "com.google.android.apps.muzei.action.NOTIFICATION_DELETED"
        private const val ACTION_REMOTE_INSTALL_MUZEI = "com.google.android.apps.muzei.action.REMOTE_INSTALL_MUZEI"

        fun hasPendingInstall(context: Context) =
                PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(INSTALL_PENDING_PREF_KEY, false)

        fun resetPendingInstall(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putBoolean(INSTALL_PENDING_PREF_KEY, false)
            }
        }

        suspend fun checkForPhoneApp(context: Context) {
            val node: Node?
            try {
                node = getNode(context, CapabilityClient.FILTER_ALL)
            } catch (e: TimeoutException) {
                // Google Play services is out of date, nothing more we can do
                return
            }
            if (node != null) {
                // Muzei's phone app is installed, allow use of the DataLayerArtProvider
                context.packageManager.setComponentEnabledSetting(
                        ComponentName(context, DataLayerArtProvider::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP)

                // Ask if they want to use the DataLayerArtProvider
                sendEnableNotification(context, node)
            } else {
                // Muzei's phone app isn't installed, disallow use of the DataLayerArtProvider
                context.packageManager.setComponentEnabledSetting(
                        ComponentName(context, DataLayerArtProvider::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)

                // Check whether they can even install Muzei on their phone
                if (PlayStoreAvailability.getPlayStoreAvailabilityOnPhone(context) ==
                        PlayStoreAvailability.PLAY_STORE_ON_PHONE_AVAILABLE) {
                    // They can install Muzei. Mark that we're about to ask them
                    // since they might not click through the notification but
                    // instead go to their phone manually
                    PreferenceManager.getDefaultSharedPreferences(context).edit {
                        putBoolean(INSTALL_PENDING_PREF_KEY, true)
                    }

                    // Now ask them if they want to install Muzei on their phone
                    sendInstallNotification(context)
                }
            }
        }

        private fun sendEnableNotification(context: Context, node: Node) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                    ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel(context)
            }
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            builder.setSmallIcon(R.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, R.color.notification))
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(R.string.datalayer_enable_title))
                    .setContentText(context.getString(R.string.datalayer_enable_text,
                            node.displayName))
                    .setContentIntent(TaskStackBuilder.create(context)
                            .addNextIntentWithParentStack(Intent(context, ChooseProviderActivity::class.java))
                            .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
            FirebaseAnalytics.getInstance(context).logEvent("activate_notif_installed", null)
            notificationManager.notify(ACTIVATE_NOTIFICATION_ID, builder.build())
        }

        private fun sendInstallNotification(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                    ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel(context)
            }
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            builder.setSmallIcon(R.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, R.color.notification))
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(R.string.datalayer_install_title))
                    .setContentText(context.getString(R.string.datalayer_install_text))
            val deleteIntent = Intent(context, ActivateMuzeiIntentService::class.java).apply {
                action = ACTION_MARK_NOTIFICATION_READ
            }
            builder.setDeleteIntent(PendingIntent.getService(context, 0, deleteIntent, 0))

            val installMuzeiIntent = Intent(context, ActivateMuzeiIntentService::class.java).apply {
                action = ACTION_REMOTE_INSTALL_MUZEI
            }
            val pendingIntent = PendingIntent.getService(context, 0, installMuzeiIntent, 0)
            builder.addAction(NotificationCompat.Action.Builder(R.drawable.open_on_phone,
                    context.getString(R.string.datalayer_install_action), pendingIntent)
                    .extend(NotificationCompat.Action.WearableExtender()
                            .setHintDisplayActionInline(true)
                            .setAvailableOffline(false))
                    .build())
            FirebaseAnalytics.getInstance(context).logEvent("activate_notif_play_store", null)
            notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build())
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun createNotificationChannel(context: Context) {
            val notificationManager = NotificationManagerCompat.from(context)
            val channel = NotificationChannel(NOTIFICATION_CHANNEL,
                    context.getString(R.string.datalayer_activate_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        @Throws(TimeoutException::class)
        private suspend fun getNode(context: Context, capability: Int): Node? {
            val capabilityClient = Wearable.getCapabilityClient(context)
            val nodes: Set<Node> = try {
                capabilityClient.getCapability("activate_muzei", capability).await().nodes
            } catch (e: ExecutionException) {
                Log.e(TAG, "Error getting all capability info", e)
                TreeSet()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error getting all capability info", e)
                TreeSet()
            }
            return nodes.firstOrNull()
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_MARK_NOTIFICATION_READ -> {
                resetPendingInstall(this)
            }
            ACTION_REMOTE_INSTALL_MUZEI -> {
                val remoteIntent = Intent(Intent.ACTION_VIEW)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setData(Uri.parse("market://details?id=$packageName"))
                RemoteIntent.startRemoteActivity(this, remoteIntent, object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (resultCode == RemoteIntent.RESULT_OK) {
                            FirebaseAnalytics.getInstance(this@ActivateMuzeiIntentService)
                                    .logEvent("activate_notif_install_sent", null)
                        } else {
                            toast(R.string.datalayer_install_failed)
                        }
                    }
                })
            }
        }
    }
}
