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
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.converter.DateTypeConverter
import com.google.android.apps.muzei.room.converter.UriTypeConverter
import java.util.Date

/**
 * Artwork's representation in Room
 */
@Entity(indices = [(Index(value = ["providerAuthority"]))])
class Artwork(
        @field:TypeConverters(UriTypeConverter::class)
        val imageUri: Uri
) {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID)
    var id: Long = 0

    lateinit var providerAuthority: String

    var title: String? = null

    var byline: String? = null

    var attribution: String? = null

    @MuzeiContract.Artwork.MetaFontType
    var metaFont = MuzeiContract.Artwork.META_FONT_TYPE_DEFAULT

    @TypeConverters(DateTypeConverter::class)
    @ColumnInfo(name = "date_added")
    var dateAdded = Date()

    val contentUri: Uri
        get() = getContentUri(id)

    companion object {

        fun getContentUri(id: Long): Uri {
            return ContentUris.appendId(Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(MuzeiContract.AUTHORITY)
                    .appendPath("artwork"), id).build()
        }
    }
}
