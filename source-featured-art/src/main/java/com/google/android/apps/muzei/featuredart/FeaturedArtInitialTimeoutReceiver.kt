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

package com.google.android.apps.muzei.featuredart

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.featuredart.FeaturedArtInitialTimeoutReceiver.Companion.TIMEOUT_DELAY
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.TimeUnit

/**
 * [BroadcastReceiver] that fires [TIMEOUT_DELAY] after the first real
 * Featured Art is loaded to automatically switch the user over to the
 * new artwork from the initial Starry Night image.
 */
class FeaturedArtInitialTimeoutReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FeaturedArtIntial"
        private val TIMEOUT_DELAY = TimeUnit.MINUTES.toMillis(15L)

        internal fun scheduleTimeout(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE)
                    as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, FeaturedArtInitialTimeoutReceiver::class.java),
                    0)
            alarmManager.set(AlarmManager.RTC,
                    System.currentTimeMillis() + TIMEOUT_DELAY,
                   pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        launch {
            val contentUri = MuzeiArtProvider.getContentUri(context,
                    FeaturedArtProvider::class.java)
            val count = context.contentResolver.delete(contentUri,
                    "${ProviderContract.Artwork.TOKEN} = ?",
                    arrayOf("initial"))
            if (BuildConfig.DEBUG) {
                if (count == 0) {
                    Log.d(TAG, "Initial artwork was already removed when timeout fired")
                } else {
                    Log.d(TAG, "Initial artwork timeout fired, skipping to next artwork")
                }
            }
            result.finish()
        }
    }
}
