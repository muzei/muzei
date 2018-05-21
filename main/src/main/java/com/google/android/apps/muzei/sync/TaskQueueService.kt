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
@file:Suppress("DEPRECATION")

package com.google.android.apps.muzei.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.preference.PreferenceManager
import android.support.v4.content.WakefulBroadcastReceiver
import androidx.core.content.edit
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class TaskQueueService : Service() {

    companion object {
        private const val TAG = "muzei:TaskQueueService"

        internal const val ACTION_DOWNLOAD_CURRENT_ARTWORK = "com.google.android.apps.muzei.action.DOWNLOAD_CURRENT_ARTWORK"
        private const val LOAD_ARTWORK_JOB_ID = 1
        private const val PREF_ARTWORK_DOWNLOAD_ATTEMPT = "artwork_download_attempt"
        private const val DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS = 30 * 1000L

        private fun getArtworkDownloadRetryPendingIntent(context: Context): PendingIntent {
            return PendingIntent.getService(context, 0,
                    getDownloadCurrentArtworkIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun getDownloadCurrentArtworkIntent(context: Context): Intent {
            return Intent(context, TaskQueueService::class.java)
                    .setAction(ACTION_DOWNLOAD_CURRENT_ARTWORK)
        }

        fun maybeRetryDownloadDueToGainedConnectivity(context: Context): Intent? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val pendingJobs = jobScheduler.allPendingJobs
                for (pendingJob in pendingJobs) {
                    if (pendingJob.id == LOAD_ARTWORK_JOB_ID) {
                        return TaskQueueService.getDownloadCurrentArtworkIntent(context)
                    }
                }
                return null
            }
            return if (PreferenceManager.getDefaultSharedPreferences(context)
                            .getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0) > 0)
                TaskQueueService.getDownloadCurrentArtworkIntent(context)
            else
                null
        }
    }

    private val executorService : Executor = Executors.newSingleThreadExecutor()
    private val coroutineDispatcher = executorService.asCoroutineDispatcher()
    private lateinit var lock: PowerManager.WakeLock

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // This is normally not started by a WakefulBroadcastReceiver so request a
        // new wakelock.
        val pwm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pwm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        lock.acquire(DOWNLOAD_ARTWORK_WAKELOCK_TIMEOUT_MILLIS)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (ACTION_DOWNLOAD_CURRENT_ARTWORK == intent.action) {
            // Handle internal download artwork request
            launch(coroutineDispatcher) {
                val success = downloadArtwork(this@TaskQueueService)
                if (success) {
                    cancelArtworkDownloadRetries()
                } else {
                    scheduleRetryArtworkDownload()
                }
                WakefulBroadcastReceiver.completeWakefulIntent(intent)
                stopSelf(startId)
            }
            return Service.START_REDELIVER_INTENT
        } else {
            stopSelf()
            return Service.START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (lock.isHeld) {
            lock.release()
        }
    }

    private fun cancelArtworkDownloadRetries() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(LOAD_ARTWORK_JOB_ID)
        } else {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(TaskQueueService.getArtworkDownloadRetryPendingIntent(this))
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            sp.edit {
                putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0)
            }
        }
    }

    private fun scheduleRetryArtworkDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.schedule(JobInfo.Builder(LOAD_ARTWORK_JOB_ID,
                    ComponentName(this, DownloadArtworkJobService::class.java))
                    .setRequiredNetworkType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        JobInfo.NETWORK_TYPE_NOT_ROAMING
                    else
                        JobInfo.NETWORK_TYPE_ANY)
                    .build())
        } else {
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            val reloadAttempt = sp.getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0)
            sp.edit {
                putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, reloadAttempt + 1)
            }
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val retryTimeMillis = SystemClock.elapsedRealtime() + (1 shl reloadAttempt) * 2000
            am.set(AlarmManager.ELAPSED_REALTIME, retryTimeMillis,
                    TaskQueueService.getArtworkDownloadRetryPendingIntent(this))
        }
    }
}
