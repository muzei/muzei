/*
 * Copyright 2017 Google Inc.
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

import android.util.Log
import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONException
import java.util.ArrayList

/**
 * Converts a list of commands into and from a persisted value
 */
object UserCommandTypeConverter {
    private const val TAG = "UserCmdTypeConverter"

    @TypeConverter
    fun fromString(commandsString: String): MutableList<String> {
        val commands = ArrayList<String>()
        if (commandsString.isEmpty()) {
            return commands
        }
        try {
            val commandArray = JSONArray(commandsString)
            (0 until commandArray.length()).mapTo(commands) { index ->
                commandArray.getString(index)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing commands from $commandsString", e)
        }

        return commands
    }

    @TypeConverter
    fun commandsListToString(commands: MutableList<String>?): String =
            JSONArray().apply {
                commands?.forEach { command -> put(command) }
            }.toString()
}
