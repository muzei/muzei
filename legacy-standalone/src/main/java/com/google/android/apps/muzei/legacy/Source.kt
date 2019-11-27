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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.util.toastFromBackground
import net.nurik.roman.muzei.legacy.BuildConfig
import net.nurik.roman.muzei.legacy.R
import java.util.ArrayList

/**
 * Source information's representation in Room
 */
@Entity(tableName = "sources")
class Source(
        @field:TypeConverters(ComponentNameTypeConverter::class)
        @field:ColumnInfo(name = "component_name")
        @field:PrimaryKey
        val componentName: ComponentName) {

    var selected: Boolean = false

    var label: String? = null

    var defaultDescription: String? = null

    var description: String? = null

    val displayDescription: String?
        get() = if (description.isNullOrEmpty()) defaultDescription else description

    var color: Int = 0

    var targetSdkVersion: Int = 0

    @TypeConverters(ComponentNameTypeConverter::class)
    var settingsActivity: ComponentName? = null

    @TypeConverters(ComponentNameTypeConverter::class)
    var setupActivity: ComponentName? = null

    var wantsNetworkAvailable: Boolean = false

    var supportsNextArtwork: Boolean = false

    @TypeConverters(UserCommandTypeConverter::class)
    var commands: MutableList<UserCommand> = ArrayList()
}

private const val TAG = "Source"
private const val ACTION_HANDLE_COMMAND = "com.google.android.apps.muzei.api.action.HANDLE_COMMAND"
private const val EXTRA_COMMAND_ID = "com.google.android.apps.muzei.api.extra.COMMAND_ID"

suspend fun Source.sendAction(context: Context, id: Int) {
    try {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Sending command $id to $this")
        }
        // Ensure that we have a valid service before sending the action
        context.packageManager.getServiceInfo(componentName, 0)
        context.startService(Intent(ACTION_HANDLE_COMMAND)
                .setComponent(componentName)
                .putExtra(EXTRA_COMMAND_ID, id))
    } catch (e: PackageManager.NameNotFoundException) {
        Log.i(TAG, "Sending action $id to $componentName failed as it is no longer available", e)
        context.toastFromBackground(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
        LegacyDatabase.getInstance(context).sourceDao().delete(this)
    } catch (e: IllegalStateException) {
        Log.i(TAG, "Sending action $id to $componentName failed; unselecting it.", e)
        context.toastFromBackground(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
        LegacyDatabase.getInstance(context).sourceDao()
                .update(apply { selected = false })
    } catch (e: SecurityException) {
        Log.i(TAG, "Sending action $id to $componentName failed; unselecting it.", e)
        context.toastFromBackground(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
        LegacyDatabase.getInstance(context).sourceDao()
                .update(apply { selected = false })
    }
}
