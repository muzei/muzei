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

package com.google.android.apps.muzei.complications

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.support.wearable.complications.ProviderUpdateRequester
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.MuzeiContract
import net.nurik.roman.muzei.BuildConfig
import java.util.TreeSet

/**
 * Worker which listens for artwork change events and updates the Artwork Complication
 */
@RequiresApi(Build.VERSION_CODES.N)
class ArtworkComplicationWorker(
        context: Context,
        workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "ArtworkComplication"

        internal fun scheduleComplicationUpdate(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ArtworkComplicationWorker>()
                            .setConstraints(Constraints.Builder()
                                    .addContentUriTrigger(MuzeiContract.Artwork.CONTENT_URI, true)
                                    .build())
                            .build())
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Work scheduled")
            }
        }

        internal fun cancelComplicationUpdate(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(TAG)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Work cancelled")
            }
        }
    }

    override fun doWork(): Result {
        val providerUpdateRequester = ProviderUpdateRequester(applicationContext,
                ComponentName(applicationContext, ArtworkComplicationProviderService::class.java))
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val complicationSet = preferences.getStringSet(
                ArtworkComplicationProviderService.KEY_COMPLICATION_IDS, TreeSet())
        if (complicationSet?.isNotEmpty() == true) {
            providerUpdateRequester.requestUpdate(*complicationSet.map { Integer.parseInt(it) }.toIntArray())
        }
        // Reschedule the job to listen for the next change
        scheduleComplicationUpdate(applicationContext)
        return Result.success()
    }
}
