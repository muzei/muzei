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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.preference.PreferenceManager;
import android.service.notification.StatusBarNotification;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.wearable.activity.ConfirmationActivity;
import android.support.wearable.playstore.PlayStoreAvailability;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public class ActivateMuzeiIntentService extends IntentService {
    private static final String TAG = "ActivateMuzeiService";
    private static final int INSTALL_NOTIFICATION_ID = 3113;
    private static final int ACTIVATE_NOTIFICATION_ID = 3114;
    private static final String ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY = "ACTIVATE_MUZEI_NOTIF_SHOWN";
    private static final String ACTION_MARK_NOTIFICATION_READ =
            "com.google.android.apps.muzei.action.NOTIFICATION_DELETED";

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
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
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
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult =
                googleApiClient.blockingConnect(30, TimeUnit.SECONDS);
        Set<Node> nodes = new TreeSet<>();
        if (connectionResult.isSuccess()) {
            nodes =  Wearable.CapabilityApi.getCapability(googleApiClient, "activate_muzei",
                CapabilityApi.FILTER_ALL).await()
                .getCapability().getNodes();
            googleApiClient.disconnect();
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
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String action = intent.getAction();
        if (TextUtils.equals(action, ACTION_MARK_NOTIFICATION_READ)) {
            preferences.edit().putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true).apply();
            return;
        }
        // else -> Open on Phone action
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        ConnectionResult connectionResult =
                googleApiClient.blockingConnect(30, TimeUnit.SECONDS);
        if (!connectionResult.isSuccess()) {
            Log.e(TAG, "Failed to connect to GoogleApiClient.");
            Toast.makeText(this, R.string.activate_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<Node> nodes =  Wearable.CapabilityApi.getCapability(googleApiClient, "activate_muzei",
                CapabilityApi.FILTER_REACHABLE).await()
                .getCapability().getNodes();
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
            for (Node node : nodes) {
                Wearable.MessageApi.sendMessage(googleApiClient, node.getId(),
                        "notification/open", null).await();
            }
        }
        googleApiClient.disconnect();
    }
}
