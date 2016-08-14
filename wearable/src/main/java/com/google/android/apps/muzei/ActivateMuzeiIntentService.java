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

package com.google.android.apps.muzei;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.preference.PreferenceManager;
import android.support.wearable.activity.ConfirmationActivity;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import net.nurik.roman.muzei.R;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ActivateMuzeiIntentService extends IntentService {
    private static final String TAG = "ActivateMuzeiService";
    private static final int NOTIFICATION_ID = 3113;
    private static final String ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY = "ACTIVATE_MUZEI_NOTIF_SHOWN";
    private static final String ACTION_MARK_NOTIFICATION_READ =
            "com.google.android.apps.muzei.action.NOTIFICATION_DELETED";

    public static void maybeShowActivateMuzeiNotification(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, false)) {
            return;
        }
        Notification.Builder builder = new Notification.Builder(context);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.activate_notification_text));
        Intent deleteIntent = new Intent(context, ActivateMuzeiIntentService.class);
        deleteIntent.setAction(ACTION_MARK_NOTIFICATION_READ);
        builder.setDeleteIntent(PendingIntent.getService(context, 0, deleteIntent, 0));
        Intent launchMuzeiIntent = new Intent(context, ActivateMuzeiIntentService.class);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0, launchMuzeiIntent, 0);
        builder.addAction(new Notification.Action.Builder(R.drawable.ic_open_on_phone,
                context.getString(R.string.common_open_on_phone), pendingIntent)
                .extend(new Notification.Action.WearableExtender()
                        .setAvailableOffline(false))
                .build());
        Bitmap background = null;
        try {
            background = BitmapFactory.decodeStream(context.getAssets().open("starrynight.jpg"));
        } catch (IOException e) {
            Log.e(TAG, "Error reading default background asset", e);
        }
        builder.extend(new Notification.WearableExtender()
                .setContentAction(0)
                .setBackground(background));
        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        preferences.edit().putBoolean(ACTIVATE_MUZEI_NOTIF_SHOWN_PREF_KEY, true).apply();
    }

    public ActivateMuzeiIntentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();
        if (TextUtils.equals(action, ACTION_MARK_NOTIFICATION_READ)) {
            // Clear the notification
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
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
            return;
        }
        Set<Node> nodes =  Wearable.CapabilityApi.getCapability(googleApiClient, "activate_muzei",
                CapabilityApi.FILTER_REACHABLE).await()
                .getCapability().getNodes();
        if (!nodes.isEmpty()) {
            // Show the open on phone animation
            Intent openOnPhoneIntent = new Intent(this, ConfirmationActivity.class);
            openOnPhoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            openOnPhoneIntent.putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                    ConfirmationActivity.OPEN_ON_PHONE_ANIMATION);
            startActivity(openOnPhoneIntent);
            // Clear the notification
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
            // Send the message to the phone to open Muzei
            for (Node node : nodes) {
                Wearable.MessageApi.sendMessage(googleApiClient, node.getId(),
                        "notification/open", null).await();
            }
        }
        googleApiClient.disconnect();
    }
}
