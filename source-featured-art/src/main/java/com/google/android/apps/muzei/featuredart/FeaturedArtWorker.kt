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

import android.content.Context
import android.text.format.DateUtils
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import ru.gildor.coroutines.okhttp.await
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class FeaturedArtWorker(
        context: Context,
        workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FeaturedArtWorker"
        private const val PREF_NEXT_UPDATE_MILLIS = "next_update_millis"

        private const val QUERY_URL = "https://muzei.co/featured?cachebust=1"

        private const val KEY_IMAGE_URI = "imageUri"
        private const val KEY_TITLE = "title"
        private const val KEY_BYLINE = "byline"
        private const val KEY_ATTRIBUTION = "attribution"
        private const val KEY_TOKEN = "token"
        private const val KEY_DETAILS_URI = "detailsUri"
        private const val MAX_JITTER_MILLIS = 20 * 60 * 1000

        private val DATE_FORMAT_TZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        private val DATE_FORMAT_LOCAL = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        private val SINGLE_THREAD_CONTEXT by lazy {
            Executors.newSingleThreadExecutor { target ->
                Thread(target, "FeaturedArt")
            }.asCoroutineDispatcher()
        }

        init {
            DATE_FORMAT_TZ.timeZone = TimeZone.getTimeZone("UTC")
        }

        internal fun enqueueLoad(context: Context) {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            val nextUpdateMillis = sp.getLong(PREF_NEXT_UPDATE_MILLIS, 0)
            val delay = if (nextUpdateMillis == 0L) {
                0
            } else {
                maxOf(nextUpdateMillis - System.currentTimeMillis(), 0)
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Enqueuing next artwork with delay of " +
                    DateUtils.formatElapsedTime(TimeUnit.MILLISECONDS.toSeconds(delay)))
            }
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                    TAG,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<FeaturedArtWorker>()
                            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .setConstraints(Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build())
                            .build())
        }
    }

    override suspend fun doWork() = withContext(SINGLE_THREAD_CONTEXT) {
        val jsonObject: JSONObject?
        try {
            jsonObject = fetchJsonObject(QUERY_URL)
            val imageUri = jsonObject.optString(KEY_IMAGE_URI) ?: return@withContext Result.success()
            val artwork = Artwork(
                persistentUri = imageUri.toUri(),
                token = jsonObject.optString(KEY_TOKEN).takeUnless { it.isEmpty() } ?: imageUri,
                title = jsonObject.optString(KEY_TITLE),
                byline = jsonObject.optString(KEY_BYLINE),
                attribution = jsonObject.optString(KEY_ATTRIBUTION),
                webUri = jsonObject.optString(KEY_DETAILS_URI).takeUnless { it.isEmpty() }?.toUri())

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Adding new artwork: $imageUri")
            }
            ProviderContract.getProviderClient(applicationContext, FEATURED_ART_AUTHORITY)
                    .addArtwork(artwork)
        } catch (e: JSONException) {
            Log.e(TAG, "Error reading JSON", e)
            return@withContext Result.retry()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading JSON", e)
            return@withContext Result.retry()
        }

        val nextTime: Date? = jsonObject.optString("nextTime").takeUnless {
            it.isEmpty()
        }?.run {
            if (length > 4 && this[length - 3] == ':') {
                substring(0, length - 3) + substring(length - 2)
            } else {
                this
            }
        }?.run {
            // Parse the nextTime
            try {
                DATE_FORMAT_TZ.parse(this)
            } catch (e: ParseException) {
                try {
                    DATE_FORMAT_LOCAL.apply {
                        timeZone = TimeZone.getDefault()
                    }.parse(this)
                } catch (e2: ParseException) {
                    Log.e(TAG, "Can't schedule update; invalid date format '$this'", e2)
                    null
                }
            }
        }

        val nextUpdateMillis = if (nextTime != null)
            nextTime.time + Random.nextInt(MAX_JITTER_MILLIS) // jitter by up to N milliseconds
        else
            System.currentTimeMillis() + 12 * 60 * 60 * 1000 // No next time, default to checking in 12 hours
        val sp = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        sp.edit {
            putLong(PREF_NEXT_UPDATE_MILLIS, nextUpdateMillis)
        }
        Result.success()
    }

    @Throws(IOException::class, JSONException::class)
    private suspend fun fetchJsonObject(url: String): JSONObject {
        val client = OkHttpClient.Builder().build()

        val request = Request.Builder()
                .url(url)
                .build()
        val json = client.newCall(request).await().body()?.string()
        val tokener = JSONTokener(json)
        return tokener.nextValue() as? JSONObject ?: throw JSONException("Expected JSON object.")
    }
}