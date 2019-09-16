/*
 * Copyright 2014 Google Inc.
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

package com.google.android.apps.muzei.api

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.COLUMN_NAME_BYLINE
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.COLUMN_NAME_PROVIDER_AUTHORITY
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.COLUMN_NAME_TITLE
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import java.util.Date

/**
 * An object representing a single artwork retrieved from the [MuzeiContract.Artwork] table.
 *
 *  Instances of this should only be created by using [Artwork.fromCursor].
 */
class Artwork private constructor(
        /**
         * The authority of the [MuzeiArtProvider] providing this artwork.
         */
        val providerAuthority: String,
        /**
         * When this artwork was added to Muzei.
         */
        val dateAdded: Date,
        /**
         * The user-visible title of the artwork.
         */
        val title: String?,
        /**
         * The user-visible byline, usually containing the author and date, of the artwork.
         *
         * This is generally used as a secondary source of information after the [title].
         */
        val byline: String?,
        /**
         * The user-visible attribution text of the artwork.
         *
         * This is generally used as a tertiary source of information after the
         * [title] and the [byline].
         */
        val attribution: String?,
        /**
         * The image URI of the artwork.
         *
         * In almost all cases you should use
         * [ContentResolver.openInputStream(CONTENT_URI)][ContentResolver.openInputStream]
         * to retrieve the already downloaded artwork.
         */
        val imageUri: Uri?
) {
    companion object {

        /**
         * Deserializes an artwork object from a [Cursor] retrieved from
         * [MuzeiContract.Artwork.CONTENT_URI].
         *
         * @param cursor a [Cursor] retrieved from [MuzeiContract.Artwork.CONTENT_URI],
         * set at the correct position.
         *
         * @return the artwork from the current position of the Cursor.
         */
        @JvmStatic
        fun fromCursor(cursor: Cursor): Artwork {
            val componentNameColumnIndex = cursor.getColumnIndex(COLUMN_NAME_PROVIDER_AUTHORITY)
            val dateAddedColumnIndex = cursor.getColumnIndex(COLUMN_NAME_DATE_ADDED)
            val titleColumnIndex = cursor.getColumnIndex(COLUMN_NAME_TITLE)
            val bylineColumnIndex = cursor.getColumnIndex(COLUMN_NAME_BYLINE)
            val attributionColumnIndex = cursor.getColumnIndex(COLUMN_NAME_ATTRIBUTION)
            val imageUriColumnIndex = cursor.getColumnIndex(COLUMN_NAME_IMAGE_URI)
            return Artwork(
                    providerAuthority = if (componentNameColumnIndex != -1) {
                        cursor.getString(componentNameColumnIndex)
                    } else {
                        throw IllegalArgumentException("Cursor does not have required " +
                                "$COLUMN_NAME_PROVIDER_AUTHORITY column")
                    },
                    dateAdded = if (componentNameColumnIndex != -1) {
                        Date(cursor.getLong(dateAddedColumnIndex))
                    } else {
                        throw IllegalArgumentException("Cursor does not have required " +
                                "$COLUMN_NAME_DATE_ADDED column")
                    },
                    title = if (titleColumnIndex != -1) {
                        cursor.getString(titleColumnIndex)
                    } else {
                        null
                    },
                    byline = if (titleColumnIndex != -1) {
                        cursor.getString(bylineColumnIndex)
                    } else {
                        null
                    },
                    attribution = if (titleColumnIndex != -1) {
                        cursor.getString(attributionColumnIndex)
                    } else {
                        null
                    },
                    imageUri = if (imageUriColumnIndex != -1) {
                        cursor.getString(imageUriColumnIndex)?.takeUnless {
                            it.isEmpty()
                        }?.run {
                            Uri.parse(this)
                        }
                    } else {
                        null
                    }
            )
        }
    }
}
