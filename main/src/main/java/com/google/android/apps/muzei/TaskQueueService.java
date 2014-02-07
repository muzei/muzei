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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;

public class TaskQueueService extends IntentService {
    static final String ACTION_DOWNLOAD_CURRENT_ARTWORK
            = "com.google.android.apps.muzei.action.DOWNLOAD_CURRENT_ARTWORK";

    public TaskQueueService() {
        super("TaskQueueService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (ACTION_DOWNLOAD_CURRENT_ARTWORK.equals(action)) {
            // TODO: request a wake lock because this doesn't always get started
            // from a wakeful broadcast receiver

            // Handle internal download artwork request
            ArtworkCache.getInstance(this).maybeDownloadCurrentArtworkSync();
            WakefulBroadcastReceiver.completeWakefulIntent(intent);
        }
    }

    static PendingIntent getArtworkDownloadRetryPendingIntent(Context context) {
        return PendingIntent.getService(context, 0,
                getDownloadCurrentArtworkIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static Intent getDownloadCurrentArtworkIntent(Context context) {
        return new Intent(context, TaskQueueService.class)
                .setAction(ACTION_DOWNLOAD_CURRENT_ARTWORK);
    }
}
