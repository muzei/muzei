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

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.BaseColumns
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.ColumnTypeConverters
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.converter.DateColumnTypeConverter
import com.google.android.apps.muzei.room.converter.UriColumnTypeConverter
import java.util.Date

/**
 * Artwork's representation in Room
 */
@Entity(indices = [(Index(value = ["providerAuthority"]))])
data class Artwork(
        @field:ColumnTypeConverters(UriColumnTypeConverter::class)
        val imageUri: Uri
) {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID)
    var id: Long = 0

    lateinit var providerAuthority: String

    var title: String? = null

    var byline: String? = null

    var attribution: String? = null

    var metaFont = ""

    @ColumnTypeConverters(DateColumnTypeConverter::class)
    @ColumnInfo(name = "date_added")
    var dateAdded = Date()

    companion object {

        fun getContentUri(id: Long): Uri {
            return ContentUris.appendId(Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(MuzeiContract.AUTHORITY)
                    .appendPath("artwork"), id).build()
        }
    }
}

val Artwork.contentUri: Uri
    get() = Artwork.getContentUri(id)
