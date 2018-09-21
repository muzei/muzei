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

package com.google.android.apps.muzei.room

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_OPEN_ARTWORK_INFO_SUCCESS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_OPEN_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import com.google.android.apps.muzei.util.toastFromBackground
import net.nurik.roman.muzei.androidclientcommon.R
import java.util.ArrayList

private const val TAG = "Artwork"

suspend fun Artwork.openArtworkInfo(context: Context) {
    val success = ContentProviderClientCompat.getClient(
            context, imageUri)?.use { client ->
        try {
            val result = client.call(METHOD_OPEN_ARTWORK_INFO, imageUri.toString())
            result?.getBoolean(KEY_OPEN_ARTWORK_INFO_SUCCESS)
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider for $imageUri crashed while opening artwork info", e)
            false
        }
    } ?: false
    if (!success) {
        context.toastFromBackground(R.string.error_view_details)
    }
}

suspend fun Artwork.getCommands(context: Context) : List<UserCommand> {
    return ContentProviderClientCompat.getClient(context, imageUri)?.use { client ->
        return try {
            val result = client.call(METHOD_GET_COMMANDS, imageUri.toString())
            val commandsString = result?.getString(KEY_COMMANDS, null)
            MuzeiContract.Sources.parseCommands(commandsString)
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider for $imageUri crashed while retrieving commands", e)
            ArrayList()
        }
    } ?: ArrayList<UserCommand>().also {
        Log.i(TAG, "Could not connect to provider for $imageUri while retrieving commands")
    }
}

suspend fun Artwork.sendAction(context: Context, id: Int) {
    ContentProviderClientCompat.getClient(context, imageUri)?.use { client ->
        try {
            client.call(METHOD_TRIGGER_COMMAND,
                    imageUri.toString(),
                    Bundle().apply { putInt(KEY_COMMAND, id) })
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider for $imageUri crashed while sending action", e)
        }
    }
}
