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

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.google.android.apps.muzei.sync.DownloadArtworkTask;
import com.google.android.apps.muzei.util.LogUtil;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.android.apps.muzei.util.LogUtil.LOGE;

public class TaskQueueService extends IntentService {
    private static final String TAG = LogUtil.makeLogTag(TaskQueueService.class);

    static final String ACTION_DOWNLOAD_CURRENT_ARTWORK
            = "com.google.android.apps.muzei.action.DOWNLOAD_CURRENT_ARTWORK";

    private static final String PREF_ARTWORK_DOWNLOAD_ATTEMPT = "artwork_download_attempt";

    private static final long DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS = 30 * 1000;

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
            // This is normally not started by a WakefulBroadcastReceiver so request a
            // new wakelock.
            PowerManager pwm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock lock = pwm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            lock.acquire(DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS);

            try {
                // Handle internal download artwork request
                boolean success = new DownloadArtworkTask(this)
                        .get(DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (success) {
                    cancelArtworkDownloadRetries();
                } else {
                    scheduleRetryArtworkDownload();
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOGE(TAG, "Error downloading artwork", e);
            } finally {
                if (lock.isHeld()) {
                    lock.release();
                }
            }

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

    private void cancelArtworkDownloadRetries() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.cancel(TaskQueueService.getArtworkDownloadRetryPendingIntent(this));
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit().putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0).commit();
    }

    private void scheduleRetryArtworkDownload() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        int reloadAttempt = sp.getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0);
        sp.edit().putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, reloadAttempt + 1).commit();

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        long retryTimeMillis = SystemClock.elapsedRealtime() + (1 << reloadAttempt) * 2000;
        am.set(AlarmManager.ELAPSED_REALTIME, retryTimeMillis,
                TaskQueueService.getArtworkDownloadRetryPendingIntent(this));
    }

    public static Intent maybeRetryDownloadDueToGainedConnectivity(Context context) {
        return (PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0) > 0)
                ? TaskQueueService.getDownloadCurrentArtworkIntent(context)
                : null;
    }
}
