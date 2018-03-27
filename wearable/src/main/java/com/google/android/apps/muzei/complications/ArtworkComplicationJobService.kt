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

package com.google.android.apps.muzei.complications

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import android.support.annotation.RequiresApi
import android.support.wearable.complications.ProviderUpdateRequester
import android.util.Log
import com.firebase.jobdispatcher.*
import com.firebase.jobdispatcher.ObservedUri.Flags.FLAG_NOTIFY_FOR_DESCENDANTS
import com.google.android.apps.muzei.api.MuzeiContract
import net.nurik.roman.muzei.BuildConfig
import java.util.*

/**
 * JobService which listens for artwork change events and updates the Artwork Complication
 */
@RequiresApi(Build.VERSION_CODES.N)
class ArtworkComplicationJobService : SimpleJobService() {

    companion object {
        private const val TAG = "ArtworkComplJobService"

        internal fun scheduleComplicationUpdateJob(context: Context) {
            val jobDispatcher = FirebaseJobDispatcher(GooglePlayDriver(context))
            jobDispatcher.schedule(jobDispatcher.newJobBuilder()
                    .setService(ArtworkComplicationJobService::class.java)
                    .setTag("update")
                    .setRecurring(true)
                    .setLifetime(Lifetime.FOREVER)
                    .setTrigger(Trigger.contentUriTrigger(listOf(ObservedUri(MuzeiContract.Artwork.CONTENT_URI, FLAG_NOTIFY_FOR_DESCENDANTS))))
                    .build())
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Job scheduled")
            }
        }

        internal fun cancelComplicationUpdateJob(context: Context) {
            val jobDispatcher = FirebaseJobDispatcher(GooglePlayDriver(context))
            jobDispatcher.cancel("update")
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Job cancelled")
            }
        }
    }

    override fun onRunJob(job: JobParameters): Int {
        val providerUpdateRequester = ProviderUpdateRequester(this,
                ComponentName(this, ArtworkComplicationProviderService::class.java))
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val complicationSet = preferences.getStringSet(
                ArtworkComplicationProviderService.KEY_COMPLICATION_IDS, TreeSet())
        if (complicationSet?.isNotEmpty() == true) {
            providerUpdateRequester.requestUpdate(*complicationSet.map { Integer.parseInt(it) }.toIntArray())
        }
        return RESULT_SUCCESS
    }
}
