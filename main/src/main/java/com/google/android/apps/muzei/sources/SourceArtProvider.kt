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

package com.google.android.apps.muzei.sources

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.widget.toast
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.featuredart.FeaturedArtSource
import com.google.android.apps.muzei.room.MuzeiDatabase
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import java.net.URISyntaxException

/**
 * A MuzeiArtProvider that encapsulates all of the logic for working with MuzeiArtSources
 */
class SourceArtProvider : MuzeiArtProvider() {

    companion object {
        private const val TAG = "SourceArtProvider"
    }

    override fun onLoadRequested(initial: Boolean) {
        if (initial) {
            // If there's no artwork at all, immediately queue up the next artwork
            sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK)
        }
        // else, Sources will load on their own schedule
    }

    override fun getDescription(): String =
            MuzeiDatabase.getInstance(context).sourceDao().currentSourceBlocking?.run {
                listOf(label, displayDescription)
                        .filterNot { it.isNullOrEmpty() }
                        .joinToString(separator = ": ")
            } ?: ""

    @SuppressLint("Range")
    override fun getCommands(artwork: Artwork): List<UserCommand> =
            MuzeiDatabase.getInstance(context).sourceDao().currentSourceBlocking?.run{
                mutableListOf<UserCommand>().apply{
                    if (supportsNextArtwork) {
                        add(UserCommand(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK,
                                context.getString(R.string.action_next_artwork)))
                    }
                    addAll(commands)
                }
            } ?: super.getCommands(artwork)

    override fun onCommand(artwork: Artwork, id: Int) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Sending command $id for ${artwork.id}")
        }
        sendAction(id)
    }

    private fun sendAction(id: Int) = launch {
        MuzeiDatabase.getInstance(context).sourceDao().getCurrentSource()?.componentName?.run {
            try {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Sending command $id to ${this}")
                }
                // Ensure that we have a valid service before sending the action
                context.packageManager.getServiceInfo(this, 0)
                context.startService(Intent(ACTION_HANDLE_COMMAND)
                        .setComponent(this)
                        .putExtra(EXTRA_COMMAND_ID, id))
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "Sending action $id to $this failed; switching to default.", e)
                launch(UI) {
                    context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
                }
                SourceManager.selectSource(context, FeaturedArtSource::class)
            } catch (e: IllegalStateException) {
                Log.i(TAG, "Sending action $id to $this failed; switching to default.", e)
                launch(UI) {
                    context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
                }
                SourceManager.selectSource(context, FeaturedArtSource::class)
            } catch (e: SecurityException) {
                Log.i(TAG, "Sending action $id to $this failed; switching to default.", e)
                launch(UI) {
                    context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
                }
                SourceManager.selectSource(context, FeaturedArtSource::class)
            }
        }
    }

    override fun openArtworkInfo(artwork: Artwork): Boolean =
            artwork.webUri?.let { webUri ->
                return try {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Opening artwork info for ${artwork.id}")
                    }
                    // Try to parse the metadata as an Intent
                    Intent.parseUri(webUri.toString(), Intent.URI_INTENT_SCHEME)?.run {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Make sure any data URIs granted to Muzei are passed onto the started Activity
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        return try {
                            context.startActivity(this).run { true }
                        } catch (e: RuntimeException) {
                            // Catch ActivityNotFoundException, SecurityException,
                            // and FileUriExposedException
                            Log.w(TAG, "Unable to start view Intent ${this}", e)
                            false
                        }
                    } ?: false
                } catch (e: URISyntaxException) {
                    Log.i(TAG, "Unable to parse viewIntent ${this}", e)
                    false
                }
            } ?: false
}
