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

package com.google.android.apps.muzei.room

import android.content.ComponentName
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter
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
