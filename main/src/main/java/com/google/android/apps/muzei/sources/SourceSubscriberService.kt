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

package com.google.android.apps.muzei.sources

import android.app.IntentService
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN
import com.google.android.apps.muzei.api.internal.SourceState
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.SourceDao
import kotlinx.coroutines.runBlocking
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.SOURCES_AUTHORITY
import java.util.ArrayList

class SourceSubscriberService : IntentService("SourceSubscriberService") {

    companion object {
        private const val TAG = "SourceSubscriberService"
    }

    override fun onHandleIntent(intent: Intent?) {
        if (ACTION_PUBLISH_STATE != intent?.action) {
            return
        }
        // Handle API call from source
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val state = intent.getBundleExtra(EXTRA_STATE)?.run {
            SourceState.fromBundle(this)
        } ?: return // If there's no state, there's nothing to change

        val database = MuzeiDatabase.getInstance(this)
        runBlocking {
            database.withTransaction {
                update(database.sourceDao(), token, state)
            }
        }
    }

    private suspend fun update(sourceDao: SourceDao, sourceToken: String, state: SourceState)  {
        val source = sourceDao.getCurrentSource()
        if (source == null || sourceToken != source.componentName.flattenToShortString()) {
            Log.w(TAG, "Dropping update from non-selected source, token=$sourceToken " +
                    "does not match token for ${source?.componentName}")
            return
        }

        source.apply {
            description = state.description
            wantsNetworkAvailable = state.wantsNetworkAvailable
            supportsNextArtwork = false
            commands = ArrayList()
        }
        val numSourceActions = state.numUserCommands
        for (i in 0 until numSourceActions) {
            val command = state.getUserCommandAt(i)
            when (state.getUserCommandAt(i)?.id) {
                MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK -> source.supportsNextArtwork = true
                else -> source.commands.add(command)
            }
        }
        sourceDao.update(source)

        val currentArtwork = state.currentArtwork
        if (currentArtwork?.imageUri != null) {
            val newArtwork = Artwork().apply {
                metadata = source.componentName.toShortString()
                persistentUri = currentArtwork.imageUri
                title = currentArtwork.title
                byline = currentArtwork.byline
                attribution = currentArtwork.attribution
                token = currentArtwork.imageUri.toString()
                webUri = currentArtwork.viewIntent?.toUri(Intent.URI_INTENT_SCHEME)?.toUri()
            }

            val artworkUri = ProviderContract.getProviderClient(this,
                    SOURCES_AUTHORITY).setArtwork(newArtwork)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Set artwork to: $artworkUri")
            }
        } else if (currentArtwork != null) {
            Log.w(TAG, "Skipping artwork with an imageUri: $currentArtwork")
        }
    }
}
