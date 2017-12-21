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

package com.google.android.apps.muzei.datalayer;

import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.preference.PreferenceManager;
import android.service.notification.StatusBarNotification;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.wearable.activity.ConfirmationActivity;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.wearable.intent.RemoteIntent;
import com.google.android.wearable.playstore.PlayStoreAvailability;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

public class ActivateMuzeiIntentService extends IntentService {
    private static final String TAG = "ActivateMuzeiService";
    private static final String NOTIFICATION_CHANNEL = "activate_muzei";
    private static final int INSTALL_NOTIFICATION_ID = 3113;
    private static final int ACTIVATE_NOTIFICATION_ID = 3114;
    private static final String ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY = "ACTIVATE_MUZEI_NOTIF_SHOWN";
    private static final String ACTION_MARK_NOTIFICATION_READ =
            "com.google.android.apps.muzei.action.NOTIFICATION_DELETED";
    private static final String ACTION_REMOTE_INSTALL_MUZEI =
            "com.google.android.apps.muzei.action.REMOTE_INSTALL_MUZEI";

    public static void maybeShowActivateMuzeiNotification(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, false)) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(NOTIFICATION_SERVICE);
        StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
        boolean hasInstallNotification = false;
        boolean hasActivateNotification = false;
        for (StatusBarNotification notification : notifications) {
            if (notification.getId() == INSTALL_NOTIFICATION_ID) {
                hasInstallNotification = true;
            } else if (notification.getId() == ACTIVATE_NOTIFICATION_ID) {
                hasActivateNotification = true;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL);
        builder.setSmallIcon(R.drawable.ic_stat_muzei)
                .setColor(ContextCompat.getColor(context, R.color.notification))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .setContentTitle(context.getString(R.string.activate_title));
        Intent deleteIntent = new Intent(context, ActivateMuzeiIntentService.class);
        deleteIntent.setAction(ACTION_MARK_NOTIFICATION_READ);
        builder.setDeleteIntent(PendingIntent.getService(context, 0, deleteIntent, 0));
        // Check if the Muzei main app is installed
        CapabilityClient capabilityClient = Wearable.getCapabilityClient(context);
        Set<Node> nodes = new TreeSet<>();
        try {
            nodes = Tasks.await(capabilityClient.getCapability(
                    "activate_muzei", CapabilityClient.FILTER_ALL)).getNodes();
        } catch (ExecutionException|InterruptedException e) {
            Log.e(TAG, "Error getting all capability info", e);
        }
        if (nodes.isEmpty()) {
            if (hasInstallNotification) {
                // No need to repost the notification
                return;
            }
            // Send an install Muzei notification
            if (PlayStoreAvailability.getPlayStoreAvailabilityOnPhone(context)
                    != PlayStoreAvailability.PLAY_STORE_ON_PHONE_AVAILABLE) {
                builder.setContentText(context.getString(R.string.activate_no_play_store));
                FirebaseAnalytics.getInstance(context).logEvent("activate_notif_no_play_store", null);
            } else {
                builder.setContentText(context.getString(R.string.activate_install_muzei));
                Intent installMuzeiIntent = new Intent(context, ActivateMuzeiIntentService.class);
                installMuzeiIntent.setAction(ACTION_REMOTE_INSTALL_MUZEI);
                PendingIntent pendingIntent = PendingIntent.getService(context, 0, installMuzeiIntent, 0);
                builder.addAction(new NotificationCompat.Action.Builder(R.drawable.open_on_phone,
                        context.getString(R.string.activate_install_action), pendingIntent)
                        .extend(new NotificationCompat.Action.WearableExtender()
                                .setHintDisplayActionInline(true)
                                .setAvailableOffline(false))
                        .build());
                builder.extend(new NotificationCompat.WearableExtender()
                        .setContentAction(0));
                FirebaseAnalytics.getInstance(context).logEvent("activate_notif_play_store", null);
            }
            notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build());
            return;
        }
        // else, Muzei is installed on the phone/tablet, but not activated
        if (hasInstallNotification) {
            // Clear any install Muzei notification
            notificationManager.cancel(INSTALL_NOTIFICATION_ID);
        }
        if (hasActivateNotification) {
            // No need to repost the notification
            return;
        }
        String nodeName = nodes.iterator().next().getDisplayName();
        builder.setContentText(context.getString(R.string.activate_enable_muzei, nodeName));
        Intent launchMuzeiIntent = new Intent(context, ActivateMuzeiIntentService.class);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0, launchMuzeiIntent, 0);
        builder.addAction(new NotificationCompat.Action.Builder(R.drawable.open_on_phone,
                context.getString(R.string.activate_action, nodeName), pendingIntent)
                .extend(new NotificationCompat.Action.WearableExtender()
                        .setHintDisplayActionInline(true)
                        .setAvailableOffline(false))
                .build());
        Bitmap background = null;
        try {
            background = BitmapFactory.decodeStream(context.getAssets().open("starrynight.jpg"));
        } catch (IOException e) {
            Log.e(TAG, "Error reading default background asset", e);
        }
        builder.extend(new NotificationCompat.WearableExtender()
                .setContentAction(0)
                .setBackground(background));
        FirebaseAnalytics.getInstance(context).logEvent("activate_notif_installed", null);
        notificationManager.notify(ACTIVATE_NOTIFICATION_ID, builder.build());
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void createNotificationChannel(Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL,
                context.getString(R.string.activate_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);
    }

    public static void clearNotifications(Context context) {
        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(INSTALL_NOTIFICATION_ID);
        notificationManager.cancel(ACTIVATE_NOTIFICATION_ID);
    }

    public ActivateMuzeiIntentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String action = intent.getAction();
        if (TextUtils.equals(action, ACTION_MARK_NOTIFICATION_READ)) {
            preferences.edit().putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true).apply();
            return;
        } else if (TextUtils.equals(action, ACTION_REMOTE_INSTALL_MUZEI)) {
            Intent remoteIntent =
                    new Intent(Intent.ACTION_VIEW)
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .setData(Uri.parse("market://details?id=" + getPackageName()));
            RemoteIntent.startRemoteActivity(this, remoteIntent, new ResultReceiver(new Handler()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode == RemoteIntent.RESULT_OK) {
                        FirebaseAnalytics.getInstance(ActivateMuzeiIntentService.this)
                                .logEvent("activate_notif_install_sent", null);
                        preferences.edit().putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY,
                                true).apply();
                    } else {
                        Toast.makeText(ActivateMuzeiIntentService.this,
                                R.string.activate_install_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return;
        }
        // else -> Open on Phone action
        CapabilityClient capabilityClient = Wearable.getCapabilityClient(this);
        Set<Node> nodes = new TreeSet<>();
        try {
            nodes = Tasks.await(capabilityClient.getCapability(
                    "activate_muzei", CapabilityClient.FILTER_REACHABLE)).getNodes();
        } catch (ExecutionException|InterruptedException e) {
            Log.e(TAG, "Error getting reachable capability info", e);
        }
        if (nodes.isEmpty()) {
            Toast.makeText(this, R.string.activate_failed, Toast.LENGTH_SHORT).show();
        } else {
            FirebaseAnalytics.getInstance(this).logEvent("activate_notif_message_sent", null);
            // Show the open on phone animation
            Intent openOnPhoneIntent = new Intent(this, ConfirmationActivity.class);
            openOnPhoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            openOnPhoneIntent.putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                    ConfirmationActivity.OPEN_ON_PHONE_ANIMATION);
            startActivity(openOnPhoneIntent);
            // Clear the notification
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            notificationManager.cancel(INSTALL_NOTIFICATION_ID);
            preferences.edit().putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true).apply();
            // Send the message to the phone to open Muzei
            MessageClient messageClient = Wearable.getMessageClient(this);
            for (Node node : nodes) {
                try {
                    Tasks.await(messageClient.sendMessage(node.getId(),
                            "notification/open", null));
                } catch (ExecutionException|InterruptedException e) {
                    Log.w(TAG, "Unable to send Activate Muzei message to " + node.getId(), e);
                }
            }
        }
    }
}
