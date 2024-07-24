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

@file:Suppress("DEPRECATION")

package com.google.android.apps.muzei.room

import android.app.PendingIntent
import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.core.app.RemoteActionCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.os.bundleOf
import androidx.versionedparcelable.ParcelUtils
import com.google.android.apps.muzei.api.BuildConfig.API_VERSION
import com.google.android.apps.muzei.api.R as ApiR
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.api.internal.ProtocolConstants.DEFAULT_VERSION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.GET_ARTWORK_INFO_MIN_VERSION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_GET_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_OPEN_ARTWORK_INFO_SUCCESS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_VERSION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_VERSION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_OPEN_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.RemoteActionBroadcastReceiver
import com.google.android.apps.muzei.legacy.BuildConfig.LEGACY_AUTHORITY
import com.google.android.apps.muzei.legacy.LegacySourceServiceProtocol.LEGACY_COMMAND_ID_NEXT_ARTWORK
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import com.google.android.apps.muzei.util.getParcelableCompat
import com.google.android.apps.muzei.util.sendFromBackground
import com.google.android.apps.muzei.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.androidclientcommon.R
import org.json.JSONArray
import org.json.JSONException

private const val TAG = "Artwork"

suspend fun Artwork.openArtworkInfo(context: Context) {
    val success = ContentProviderClientCompat.getClient(
            context, imageUri)?.use { client ->
        try {
            val versionResult = client.call(METHOD_GET_VERSION)
            val version = versionResult?.getInt(KEY_VERSION) ?: DEFAULT_VERSION
            if (version >= GET_ARTWORK_INFO_MIN_VERSION) {
                val result = client.call(METHOD_GET_ARTWORK_INFO, imageUri.toString())
                val artworkInfo = result?.getParcelableCompat<PendingIntent>(KEY_GET_ARTWORK_INFO)
                try {
                    artworkInfo?.run {
                        sendFromBackground()
                        true
                    } ?: false
                } catch (e: PendingIntent.CanceledException) {
                    Log.w(TAG, "Provider for $imageUri returned a cancelled " +
                            "PendingIntent: $artworkInfo", e)
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send() the PendingIntent $artworkInfo for $imageUri", e)
                    false
                }
            } else {
                val result = client.call(METHOD_OPEN_ARTWORK_INFO, imageUri.toString())
                result?.getBoolean(KEY_OPEN_ARTWORK_INFO_SUCCESS)
            }
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider for $imageUri crashed while opening artwork info", e)
            false
        }
    } ?: false
    if (!success) {
        withContext(Dispatchers.Main.immediate) {
            context.toast(R.string.error_view_details)
        }
    }
}

suspend fun Artwork.getCommands(context: Context) : List<RemoteActionCompat> {
    return ContentProviderClientCompat.getClient(context, imageUri)?.use { client ->
        return try {
            val result = client.call(METHOD_GET_COMMANDS, imageUri.toString(),
                    bundleOf(KEY_VERSION to API_VERSION))
                    ?: return ArrayList()
            val extensionVersion = result.getInt(KEY_VERSION, DEFAULT_VERSION)
            if (extensionVersion >= GET_ARTWORK_INFO_MIN_VERSION) {
                ParcelUtils.getVersionedParcelableList(result, KEY_COMMANDS) ?: ArrayList()
            } else {
                result.getString(KEY_COMMANDS, null)?.run {
                    val commands = mutableListOf<UserCommand>()
                    try {
                        val commandArray = JSONArray(this)
                        for (index in 0 until commandArray.length()) {
                            commands.add(UserCommand.deserialize(commandArray.getString(index)))
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing commands from $this", e)
                    }
                    commands.map { command ->
                        val isLegacyNext = providerAuthority == LEGACY_AUTHORITY &&
                                command.id == LEGACY_COMMAND_ID_NEXT_ARTWORK
                        val iconResourceId = if (isLegacyNext)
                            ApiR.drawable.muzei_launch_command
                        else
                            R.drawable.ic_next_artwork
                        RemoteActionCompat(
                                IconCompat.createWithResource(context, iconResourceId),
                                command.title ?: "",
                                command.title ?: "",
                                RemoteActionBroadcastReceiver.createPendingIntent(context,
                                        providerAuthority, id, command.id)).apply {
                            setShouldShowIcon(isLegacyNext)
                        }
                    }
                } ?: ArrayList()
            }
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider for $imageUri crashed while retrieving commands", e)
            ArrayList()
        }
    } ?: ArrayList<RemoteActionCompat>().also {
        Log.i(TAG, "Could not connect to provider for $imageUri while retrieving commands")
    }
}