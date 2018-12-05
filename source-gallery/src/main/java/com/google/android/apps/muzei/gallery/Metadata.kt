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

package com.google.android.apps.muzei.gallery

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.google.android.apps.muzei.gallery.converter.DateTypeConverter
import com.google.android.apps.muzei.gallery.converter.UriTypeConverter
import java.util.Date

/**
 * Entity representing a row of cached metadata in Room
 */
@Entity(tableName = "metadata_cache", indices = [(Index(value = ["uri"], unique = true))])
internal data class Metadata(
        @field:TypeConverters(UriTypeConverter::class)
        val uri: Uri,
        @field:TypeConverters(DateTypeConverter::class)
        @ColumnInfo(name = "datetime")
        var date: Date? = null,
        var location: String? = null
) {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    var id: Long = 0
}
