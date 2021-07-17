/*
 * Copyright 2020 Google Inc.
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

package com.google.android.apps.muzei.api.internal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND_ARTWORK_ID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND_AUTHORITY
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND
import com.google.android.apps.muzei.api.provider.ProviderContract

/**
 * A [BroadcastReceiver] specifically for maintaining backward compatibility with the
 * `getCommands()` and `onCommand()` APIs for apps that upgrade to the latest version
 * of the Muzei API without updating their `MuzeiArtProvider` to use the new
 * [com.google.android.apps.muzei.api.provider.MuzeiArtProvider.getCommandActions].
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
public class RemoteActionBroadcastReceiver : BroadcastReceiver() {
    public companion object {
        /**
         * Construct a [PendingIntent] suitable for passing to
         * [androidx.core.app.RemoteActionCompat] that will trigger the
         * `onCommand()` API of the `MuzeiArtProvider` associated with
         * the [authority].
         *
         * When building your own [androidx.core.app.RemoteActionCompat], you
         * should **not** use this method. Instead, register your own
         * [BroadcastReceiver], [android.app.Service], or directly launch
         * an [android.app.Activity] as needed by your command rather than go
         * through the inefficiency of starting this receiver just to trigger
         * your [com.google.android.apps.muzei.api.provider.MuzeiArtProvider].
         */
        @SuppressLint("InlinedApi")
        public fun createPendingIntent(
                context: Context,
                authority: String,
                artworkId: Long,
                id: Int
        ): PendingIntent {
            val intent = Intent(context, RemoteActionBroadcastReceiver::class.java).apply {
                putExtra(KEY_COMMAND_AUTHORITY, authority)
                putExtra(KEY_COMMAND_ARTWORK_ID, artworkId)
                putExtra(KEY_COMMAND, id)
            }
            return PendingIntent.getBroadcast(context,
                    id + 1000 * artworkId.toInt(),
                    intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val authority = intent?.getStringExtra(KEY_COMMAND_AUTHORITY)
        if (authority != null) {
            val contentUri = ContentUris.withAppendedId(
                    ProviderContract.getContentUri(authority),
                    intent.getLongExtra(KEY_COMMAND_ARTWORK_ID, -1))
            val id = intent.getIntExtra(KEY_COMMAND, -1)
            val pendingResult = goAsync()
            try {
                context.contentResolver.call(contentUri,
                        METHOD_TRIGGER_COMMAND,
                        contentUri.toString(),
                        Bundle().apply { putInt(KEY_COMMAND, id) })
            } finally {
                pendingResult.finish()
            }
        }
    }
}