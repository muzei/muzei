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

package com.google.android.apps.muzei.legacy

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.apps.muzei.util.goAsync
import net.nurik.roman.muzei.legacy.BuildConfig

/**
 * A [BroadcastReceiver] used to trigger actions on legacy sources
 */
class SendActionBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SendActionReceiver"
        private const val KEY_ID = "id"

        @SuppressLint("InlinedApi")
        fun createPendingIntent(
                context: Context,
                id: Int
        ): PendingIntent = PendingIntent.getBroadcast(context, id,
                Intent(context, SendActionBroadcastReceiver::class.java).apply {
                    putExtra(KEY_ID, id)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(KEY_ID, -1)
        if (id == -1) {
            return
        }
        goAsync {
            val currentSource = LegacyDatabase.getInstance(context).sourceDao().getCurrentSource()
            if (currentSource != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Sending command $id to ${currentSource.componentName}")
                }
                currentSource.sendAction(context, id)
            }
        }
    }
}