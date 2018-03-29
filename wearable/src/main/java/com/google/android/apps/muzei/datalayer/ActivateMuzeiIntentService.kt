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
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.preference.PreferenceManager
import android.support.annotation.RequiresApi
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.support.wearable.activity.ConfirmationActivity
import android.util.Log
import android.widget.Toast
import androidx.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.wearable.intent.RemoteIntent
import com.google.android.wearable.playstore.PlayStoreAvailability
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R
import java.io.IOException
import java.util.TreeSet
import java.util.concurrent.ExecutionException

class ActivateMuzeiIntentService : IntentService(TAG) {

    companion object {
        private const val TAG = "ActivateMuzeiService"
        private const val NOTIFICATION_CHANNEL = "activate_muzei"
        private const val INSTALL_NOTIFICATION_ID = 3113
        private const val ACTIVATE_NOTIFICATION_ID = 3114
        private const val ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY = "ACTIVATE_MUZEI_NOTIF_SHOWN"
        private const val ACTION_MARK_NOTIFICATION_READ = "com.google.android.apps.muzei.action.NOTIFICATION_DELETED"
        private const val ACTION_REMOTE_INSTALL_MUZEI = "com.google.android.apps.muzei.action.REMOTE_INSTALL_MUZEI"

        @JvmStatic
        fun maybeShowActivateMuzeiNotification(context: Context) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (preferences.getBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, false)) {
                return
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                    ?: return
            val notifications = notificationManager.activeNotifications
            var hasInstallNotification = false
            var hasActivateNotification = false
            for (notification in notifications) {
                if (notification.id == INSTALL_NOTIFICATION_ID) {
                    hasInstallNotification = true
                } else if (notification.id == ACTIVATE_NOTIFICATION_ID) {
                    hasActivateNotification = true
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel(context)
            }
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            builder.setSmallIcon(R.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, R.color.notification))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(R.string.activate_title))
            val deleteIntent = Intent(context, ActivateMuzeiIntentService::class.java).apply {
                action = ACTION_MARK_NOTIFICATION_READ
            }
            builder.setDeleteIntent(PendingIntent.getService(context, 0, deleteIntent, 0))
            // Check if the Muzei main app is installed
            val capabilityClient = Wearable.getCapabilityClient(context)
            val nodes: Set<Node> = try {
                Tasks.await<CapabilityInfo>(capabilityClient.getCapability(
                        "activate_muzei", CapabilityClient.FILTER_ALL)).nodes
            } catch (e: ExecutionException) {
                Log.e(TAG, "Error getting all capability info", e)
                TreeSet()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error getting all capability info", e)
                TreeSet()
            }

            if (nodes.isEmpty()) {
                if (hasInstallNotification) {
                    // No need to repost the notification
                    return
                }
                // Send an install Muzei notification
                when (PlayStoreAvailability.getPlayStoreAvailabilityOnPhone(context)) {
                    PlayStoreAvailability.PLAY_STORE_ON_PHONE_AVAILABLE -> {
                        builder.setContentText(context.getString(R.string.activate_no_play_store))
                        FirebaseAnalytics.getInstance(context).logEvent("activate_notif_no_play_store", null)
                    }
                    else -> {
                        builder.setContentText(context.getString(R.string.activate_install_muzei))
                        val installMuzeiIntent = Intent(context, ActivateMuzeiIntentService::class.java).apply {
                            action = ACTION_REMOTE_INSTALL_MUZEI
                        }
                        val pendingIntent = PendingIntent.getService(context, 0, installMuzeiIntent, 0)
                        builder.addAction(NotificationCompat.Action.Builder(R.drawable.open_on_phone,
                                context.getString(R.string.activate_install_action), pendingIntent)
                                .extend(NotificationCompat.Action.WearableExtender()
                                        .setHintDisplayActionInline(true)
                                        .setAvailableOffline(false))
                                .build())
                        builder.extend(NotificationCompat.WearableExtender()
                                .setContentAction(0))
                        FirebaseAnalytics.getInstance(context).logEvent("activate_notif_play_store", null)
                    }
                }
                notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build())
                return
            }
            // else, Muzei is installed on the phone/tablet, but not activated
            if (hasInstallNotification) {
                // Clear any install Muzei notification
                notificationManager.cancel(INSTALL_NOTIFICATION_ID)
            }
            if (hasActivateNotification) {
                // No need to repost the notification
                return
            }
            val nodeName = nodes.iterator().next().displayName
            builder.setContentText(context.getString(R.string.activate_enable_muzei, nodeName))
            val launchMuzeiIntent = Intent(context, ActivateMuzeiIntentService::class.java)
            val pendingIntent = PendingIntent.getService(context, 0, launchMuzeiIntent, 0)
            builder.addAction(NotificationCompat.Action.Builder(R.drawable.open_on_phone,
                    context.getString(R.string.activate_action, nodeName), pendingIntent)
                    .extend(NotificationCompat.Action.WearableExtender()
                            .setHintDisplayActionInline(true)
                            .setAvailableOffline(false))
                    .build())
            val background = try {
                BitmapFactory.decodeStream(context.assets.open("starrynight.jpg"))
            } catch (e: IOException) {
                Log.e(TAG, "Error reading default background asset", e)
                null
            }

            builder.extend(NotificationCompat.WearableExtender()
                    .setContentAction(0)
                    .setBackground(background))
            FirebaseAnalytics.getInstance(context).logEvent("activate_notif_installed", null)
            notificationManager.notify(ACTIVATE_NOTIFICATION_ID, builder.build())
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun createNotificationChannel(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(NOTIFICATION_CHANNEL,
                    context.getString(R.string.activate_channel_name),
                    NotificationManager.IMPORTANCE_HIGH)
            channel.enableVibration(true)
            notificationManager?.createNotificationChannel(channel)
        }

        @JvmStatic
        fun clearNotifications(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                    ?: return
            notificationManager.cancel(INSTALL_NOTIFICATION_ID)
            notificationManager.cancel(ACTIVATE_NOTIFICATION_ID)
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        when (intent?.action) {
            ACTION_MARK_NOTIFICATION_READ -> {
                preferences.edit { putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true) }
            }
            ACTION_REMOTE_INSTALL_MUZEI -> {
                val remoteIntent = Intent(Intent.ACTION_VIEW)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setData(Uri.parse("market://details?id=$packageName"))
                RemoteIntent.startRemoteActivity(this, remoteIntent, object : ResultReceiver(Handler()) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                        if (resultCode == RemoteIntent.RESULT_OK) {
                            FirebaseAnalytics.getInstance(this@ActivateMuzeiIntentService)
                                    .logEvent("activate_notif_install_sent", null)
                            preferences.edit {
                                putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true)
                            }
                        } else {
                            Toast.makeText(this@ActivateMuzeiIntentService,
                                    R.string.activate_install_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            else -> {
                // Open on Phone action
                val capabilityClient = Wearable.getCapabilityClient(this)
                val nodes: Set<Node> = try {
                    Tasks.await<CapabilityInfo>(capabilityClient.getCapability(
                            "activate_muzei", CapabilityClient.FILTER_REACHABLE)).nodes
                } catch (e: ExecutionException) {
                    Log.e(TAG, "Error getting reachable capability info", e)
                    TreeSet()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Error getting reachable capability info", e)
                    TreeSet()
                }

                if (nodes.isEmpty()) {
                    Toast.makeText(this, R.string.activate_failed, Toast.LENGTH_SHORT).show()
                } else {
                    FirebaseAnalytics.getInstance(this).logEvent("activate_notif_message_sent", null)
                    // Show the open on phone animation
                    val openOnPhoneIntent = Intent(this, ConfirmationActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                                ConfirmationActivity.OPEN_ON_PHONE_ANIMATION)
                    }
                    startActivity(openOnPhoneIntent)
                    // Clear the notification
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                    notificationManager?.cancel(INSTALL_NOTIFICATION_ID)
                    preferences.edit { putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true) }
                    // Send the message to the phone to open Muzei
                    val messageClient = Wearable.getMessageClient(this)
                    for (node in nodes) {
                        try {
                            Tasks.await(messageClient.sendMessage(node.id,
                                    "notification/open", null))
                        } catch (e: ExecutionException) {
                            Log.w(TAG, "Unable to send Activate Muzei message to ${node.id}", e)
                        } catch (e: InterruptedException) {
                            Log.w(TAG, "Unable to send Activate Muzei message to ${node.id}", e)
                        }
                    }
                }
            }
        }
    }
}
