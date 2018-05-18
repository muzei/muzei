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

package com.google.android.apps.muzei.featuredart

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.customtabs.CustomTabsIntent
import android.support.v4.content.ContextCompat
import android.util.Log
import android.widget.Toast
import androidx.core.widget.toast
import com.google.android.apps.muzei.api.Artwork
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource
import com.google.android.apps.muzei.api.UserCommand
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone

class FeaturedArtSource : RemoteMuzeiArtSource(SOURCE_NAME) {

    companion object {
        private const val TAG = "FeaturedArtSource"
        private const val SOURCE_NAME = "FeaturedArt"

        private const val QUERY_URL = "http://muzeiapi.appspot.com/featured?cachebust=1"
        private val ARCHIVE_URI: Uri = Uri.parse("http://muzei.co/archive")

        private const val COMMAND_ID_SHARE = 1
        private const val COMMAND_ID_VIEW_ARCHIVE = 2
        private const val COMMAND_ID_DEBUG_INFO = 51

        private const val MAX_JITTER_MILLIS = 20 * 60 * 1000

        private val RANDOM = Random()

        private val DATE_FORMAT_TZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        private val DATE_FORMAT_LOCAL = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        init {
            DATE_FORMAT_TZ.timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun onEnabled() {
        val nextUpdateTimeMillis = sharedPreferences
                .getLong("scheduled_update_time_millis", 0)
        if (nextUpdateTimeMillis == 0L && currentArtwork != null) {
            // Handle cases where we've crashed midway through updating the image
            // and lost our scheduled update
            onUpdate(UPDATE_REASON_OTHER)
        }
    }

    override fun onUpdate(@UpdateReason reason: Int) {
        val commands = ArrayList<UserCommand>()
        if (reason == UPDATE_REASON_INITIAL) {
            // Show initial photo (starry night)
            publishArtwork(Artwork.Builder()
                    .imageUri(Uri.parse("file:///android_asset/starrynight.jpg"))
                    .title("The Starry Night")
                    .token("initial")
                    .byline("Vincent van Gogh, 1889.\nMuzei shows a new painting every day.")
                    .attribution("wikiart.org")
                    .viewIntent(Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://www.wikiart.org/en/vincent-van-gogh/the-starry-night-1889")))
                    .metaFont(MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT)
                    .build())
            commands.add(UserCommand(BUILTIN_COMMAND_ID_NEXT_ARTWORK))
            // show the latest photo in 15 minutes
            scheduleUpdate(System.currentTimeMillis() + 15 * 60 * 1000)
        } else {
            // For everything but the initial update, defer to RemoteMuzeiArtSource
            super.onUpdate(reason)
        }

        commands.apply {
            add(UserCommand(COMMAND_ID_SHARE, getString(R.string.featuredart_action_share_artwork)))
            add(UserCommand(COMMAND_ID_VIEW_ARCHIVE,
                    getString(R.string.featuredart_source_action_view_archive)))
            if (BuildConfig.DEBUG) {
                add(UserCommand(COMMAND_ID_DEBUG_INFO, "Debug info"))
            }
        }
        setUserCommands(commands)
    }

    override fun onCustomCommand(id: Int) {
        super.onCustomCommand(id)
        when {
            COMMAND_ID_SHARE == id -> {
                currentArtwork?.apply {
                    val detailUrl = if ("initial" == token)
                        "http://www.wikipaintings.org/en/vincent-van-gogh/the-starry-night-1889"
                    else
                        viewIntent.dataString
                    val artist = byline
                            .replaceFirst("\\.\\s*($|\\n).*".toRegex(), "").trim { it <= ' ' }

                    // Create and start the Share Intent
                    Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "My Android wallpaper today is '"
                                + title.trim { it <= ' ' }
                                + "' by $artist. #MuzeiFeaturedArt\n\n$detailUrl")
                    }, "Share artwork")?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }?.takeIf { it.resolveActivity(packageManager) != null }?.run {
                        startActivity(this)
                    }
                } ?: run {
                    Log.w(TAG, "No current artwork, can't share.")
                    Handler(Looper.getMainLooper()).post {
                        toast(R.string.featuredart_source_error_no_artwork_to_share)
                    }
                }
            }
            COMMAND_ID_VIEW_ARCHIVE == id -> {
                val cti = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .setToolbarColor(ContextCompat.getColor(this, R.color.featuredart_color))
                        .build()
                cti.intent.apply {
                    data = ARCHIVE_URI
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(packageManager) != null }?.run {
                    try {
                        startActivity(this)
                    } catch (e: SecurityException) {
                        // A non-exported Activity with an <intent-filter> for any
                        // web URL? What could go wrong...well, this could happen
                    }
                }
            }
            COMMAND_ID_DEBUG_INFO == id -> {
                val nextUpdateTimeMillis = sharedPreferences
                        .getLong("scheduled_update_time_millis", 0)
                val nextUpdateTime = if (nextUpdateTimeMillis > 0) {
                    SimpleDateFormat.getDateTimeInstance().format(Date(nextUpdateTimeMillis))
                } else {
                    "None"
                }

                launch(UI) {
                    toast("Next update time: $nextUpdateTime", Toast.LENGTH_LONG)
                }
            }
        }
    }

    @Throws(RemoteMuzeiArtSource.RetryException::class)
    override fun onTryUpdate(reason: Int) {
        val currentArtwork = currentArtwork
        val artwork: Artwork
        val jsonObject: JSONObject
        try {
            jsonObject = fetchJsonObject(QUERY_URL)
            artwork = Artwork.fromJson(jsonObject)
        } catch (e: JSONException) {
            Log.e(TAG, "Error reading JSON", e)
            throw RemoteMuzeiArtSource.RetryException(e)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading JSON", e)
            throw RemoteMuzeiArtSource.RetryException(e)
        }

        if (currentArtwork?.imageUri == artwork.imageUri) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Skipping update of same artwork.")
            }
        } else {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Publishing artwork update: $artwork")
            }
            publishArtwork(artwork.apply { metaFont = MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT })
        }

        jsonObject.optString("nextTime")?.takeUnless { it.isEmpty() }?.run {
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
        }?.apply {
            // jitter by up to N milliseconds
            scheduleUpdate(time + RANDOM.nextInt(MAX_JITTER_MILLIS))
        } ?: run {
            // No next time, default to checking in 12 hours
            scheduleUpdate(System.currentTimeMillis() + 12 * 60 * 60 * 1000)
        }
    }

    @Throws(IOException::class, JSONException::class)
    private fun fetchJsonObject(url: String): JSONObject {
        val client = OkHttpClient.Builder().build()

        val request = Request.Builder()
                .url(url)
                .build()
        val json = client.newCall(request).execute().body()?.string()
        val tokener = JSONTokener(json)
        return tokener.nextValue() as? JSONObject ?: throw JSONException("Expected JSON object.")
    }
}
