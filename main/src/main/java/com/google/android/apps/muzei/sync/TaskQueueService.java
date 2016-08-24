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

package com.google.android.apps.muzei.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.content.WakefulBroadcastReceiver;

import java.util.List;

public class TaskQueueService extends Service {
    private static final String TAG = "TaskQueueService";

    static final String ACTION_DOWNLOAD_CURRENT_ARTWORK
            = "com.google.android.apps.muzei.action.DOWNLOAD_CURRENT_ARTWORK";

    private static final int LOAD_ARTWORK_JOB_ID = 1;

    private static final String PREF_ARTWORK_DOWNLOAD_ATTEMPT = "artwork_download_attempt";

    private static final long DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS = 30 * 1000;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        if (intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_DOWNLOAD_CURRENT_ARTWORK.equals(action)) {
            // Handle internal download artwork request
            new DownloadArtworkTask(this) {
                PowerManager.WakeLock lock;

                @Override
                protected void onPreExecute() {
                    super.onPreExecute();
                    // This is normally not started by a WakefulBroadcastReceiver so request a
                    // new wakelock.
                    PowerManager pwm = (PowerManager) getSystemService(POWER_SERVICE);
                    lock = pwm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
                    lock.acquire(DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS);
                }

                @Override
                protected void onPostExecute(Boolean success) {
                    super.onPostExecute(success);
                    if (success) {
                        cancelArtworkDownloadRetries();
                    } else {
                        scheduleRetryArtworkDownload();
                    }
                    if (lock.isHeld()) {
                        lock.release();
                    }
                    WakefulBroadcastReceiver.completeWakefulIntent(intent);
                    stopSelf(startId);
                }
            }.execute();

        }
        return START_REDELIVER_INTENT;
    }

    private static PendingIntent getArtworkDownloadRetryPendingIntent(Context context) {
        return PendingIntent.getService(context, 0,
                getDownloadCurrentArtworkIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static Intent getDownloadCurrentArtworkIntent(Context context) {
        return new Intent(context, TaskQueueService.class)
                .setAction(ACTION_DOWNLOAD_CURRENT_ARTWORK);
    }

    private void cancelArtworkDownloadRetries() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            jobScheduler.cancel(LOAD_ARTWORK_JOB_ID);
        } else {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(TaskQueueService.getArtworkDownloadRetryPendingIntent(this));
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            sp.edit().putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0).commit();
        }
    }

    private void scheduleRetryArtworkDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            jobScheduler.schedule(new JobInfo.Builder(LOAD_ARTWORK_JOB_ID,
                    new ComponentName(this, DownloadArtworkJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build());
        } else {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            int reloadAttempt = sp.getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0);
            sp.edit().putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, reloadAttempt + 1).commit();
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            long retryTimeMillis = SystemClock.elapsedRealtime() + (1 << reloadAttempt) * 2000;
            am.set(AlarmManager.ELAPSED_REALTIME, retryTimeMillis,
                    TaskQueueService.getArtworkDownloadRetryPendingIntent(this));
        }
    }

    public static Intent maybeRetryDownloadDueToGainedConnectivity(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            List<JobInfo> pendingJobs = jobScheduler.getAllPendingJobs();
            for (JobInfo pendingJob : pendingJobs) {
                if (pendingJob.getId() == LOAD_ARTWORK_JOB_ID) {
                    return TaskQueueService.getDownloadCurrentArtworkIntent(context);
                }
            }
            return null;
        }
        return (PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0) > 0)
                ? TaskQueueService.getDownloadCurrentArtworkIntent(context)
                : null;
    }
}
