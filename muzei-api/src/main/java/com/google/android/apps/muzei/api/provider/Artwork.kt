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
package com.google.android.apps.muzei.api.provider

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import com.google.android.apps.muzei.api.provider.Artwork.Builder
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.ATTRIBUTION
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.BYLINE
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATA
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATE_ADDED
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATE_MODIFIED
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.METADATA
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.PERSISTENT_URI
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.TITLE
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.TOKEN
import com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.WEB_URI
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Artwork associated with a [MuzeiArtProvider].
 *
 * Artwork can be constructed using the empty constructor and the setter
 * methods found on the Artwork object itself or by using the [Builder].
 *
 * Artwork can then be added to a [MuzeiArtProvider] by calling
 * [addArtwork(Artwork)][MuzeiArtProvider.addArtwork] directly
 * from within a MuzeiArtProvider or by creating a [ProviderClient] and calling
 * [ProviderClient.addArtwork] from anywhere in your application.
 *
 * The static [Artwork.fromCursor] method allows you to convert
 * a row retrieved from a [MuzeiArtProvider] into Artwork instance.
 */
class Artwork private constructor(
        private val _id: Long,
        private val _data: File?,
        private val _dateAdded: Date?,
        private val _dateModified: Date?,
        /**
         * The artwork's user-visible title.
         */
        val title: String?,
        /**
         * The artwork's user-visible byline, usually containing the author and date.
         *
         * This is generally used as a secondary source of information after the
         * [title].
         */
        val byline: String? = null,
        /**
         * The artwork's user-visible attribution text.
         *
         * This is generally used as a tertiary source of information after the
         * [title] and the [byline].
         */
        val attribution: String? = null,
        /**
         * The token that uniquely defines the artwork.
         */
        val token: String? = null,
        /**
         * The artwork's persistent URI, which must resolve to a JPEG or PNG image, ideally
         * under 5MB. Your app should have long-lived access to this URI.
         *
         * When a persistent URI is present, your [MuzeiArtProvider] will store
         * downloaded images in the [cache directory][Context.getCacheDir] and automatically
         * re-download the image as needed. If it is not present, then you must write the image
         * directly to the [MuzeiArtProvider] with
         * [ContentResolver.openOutputStream][android.content.ContentResolver.openOutputStream]
         * and the images will be stored in the [Context.getFilesDir] as it assumed
         * that there is no way to re-download the artwork.
         *
         * @see MuzeiArtProvider.openFile
         */
        val persistentUri: Uri? = null,
        /**
         * The artwork's web URI. This is used by default in [MuzeiArtProvider.getArtworkInfo] to
         * allow the user to view more details about the artwork.
         */
        val webUri: Uri? = null,
        /**
         * The provider specific metadata about the artwork.
         *
         * This is not used by Muzei at all, so can contain any data that makes it easier to query
         * or otherwise work with your Artwork.
         */
        val metadata: String? = null
) {
    /**
     * Construct a new [Artwork].
     *
     * @param title The artwork's user-visible title.
     * @param byline The artwork's user-visible byline, usually containing the author and date.
     * @param attribution The artwork's user-visible attribution text.
     * @param token The token that uniquely defines the artwork.
     * @param persistentUri The artwork's persistent URI, which must resolve to a JPEG or PNG
     * image, ideally under 5MB. Your app should have long-lived access to this URI.
     * @param webUri The artwork's web URI.
     * @param metadata The provider specific metadata about the artwork.
     */
    constructor(
            title: String?,
            byline: String? = null,
            attribution: String? = null,
            token: String? = null,
            persistentUri: Uri? = null,
            webUri: Uri? = null,
            metadata: String? = null
    ) : this(0, null, null, null,
            title, byline, attribution, token, persistentUri, webUri, metadata)

    companion object {
        private val DATE_FORMAT: DateFormat by lazy {
            SimpleDateFormat.getDateTimeInstance()
        }

        /**
         * Converts the current row of the given Cursor to an Artwork object. The
         * assumption is that this Cursor was retrieve from a [MuzeiArtProvider]
         * and has the columns listed in [ProviderContract.Artwork].
         *
         * @param data A Cursor retrieved from a [MuzeiArtProvider], already
         * positioned at the correct row you wish to convert.
         * @return a valid Artwork with values filled in from the
         * [ProviderContract.Artwork] columns.
         */
        @JvmStatic
        fun fromCursor(data: Cursor) = Artwork(
                data.getLong(data.getColumnIndex(BaseColumns._ID)),
                File(data.getString(data.getColumnIndex(DATA))),
                Date(data.getLong(data.getColumnIndex(DATE_ADDED))),
                Date(data.getLong(data.getColumnIndex(DATE_MODIFIED))),
                data.getString(data.getColumnIndex(TITLE)),
                data.getString(data.getColumnIndex(BYLINE)),
                data.getString(data.getColumnIndex(ATTRIBUTION)),
                data.getString(data.getColumnIndex(TOKEN)),
                data.getString(data.getColumnIndex(PERSISTENT_URI))?.takeUnless { it.isEmpty() }?.run {
                    Uri.parse(this)
                },
                data.getString(data.getColumnIndex(WEB_URI))?.takeUnless { it.isEmpty() }?.run {
                    Uri.parse(this)
                },
                data.getString(data.getColumnIndex(METADATA)))
    }

    /**
     * The ID assigned to this Artwork by its [MuzeiArtProvider].
     *
     * Note: this will only be available if the artwork is retrieved from a
     * [MuzeiArtProvider].
     */
    val id get() = _id

    /**
     * The [File] where a local copy of this artwork is stored.
     * When first inserted, this file most certainly does not exist and will be
     * created from the InputStream returned by [MuzeiArtProvider.openFile].
     *
     * Note: this will only be available if the artwork is retrieved from a
     * [MuzeiArtProvider].
     *
     * @throws IllegalStateException if the Artwork was not retrieved from a
     * [MuzeiArtProvider]
     */
    val data get() = _data ?: throw IllegalStateException(
            "Only Artwork retrieved from a MuzeiArtProvider has a data File")

    /**
     * The date this artwork was initially added to its [MuzeiArtProvider].
     *
     * Note: this will only be available if the artwork is retrieved from a
     * [MuzeiArtProvider].
     *
     * @throws IllegalStateException if the Artwork was not retrieved from a
     * [MuzeiArtProvider]
     */
    val dateAdded get() = _dateAdded ?: throw IllegalStateException(
            "Only Artwork retrieved from a MuzeiArtProvider has a date added")

    /**
     * The date of the last modification of the artwork (i.e., the last time it was
     * updated). This will initially be equal to the [dateAdded].
     *
     * Note: this will only be available if the artwork is retrieved from a
     * [MuzeiArtProvider].
     *
     * @throws IllegalStateException if the Artwork was not retrieved from a
     * [MuzeiArtProvider]
     */
    @Suppress("unused")
    val dateModified get() = _dateModified ?: throw IllegalStateException(
            "Only Artwork retrieved from a MuzeiArtProvider has a date modified")

    /**
     * @suppress
     */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Artwork #")
        sb.append(id)

        if (token != null && token.isNotEmpty() && (persistentUri?.toString() != token)) {
            sb.append("+")
            sb.append(token)
        }
        sb.append(" (")
        sb.append(persistentUri)
        if (persistentUri != null && persistentUri != webUri) {
            sb.append(", ")
            sb.append(webUri)
        }
        sb.append(")")
        sb.append(": ")
        var appended = false
        if (!title.isNullOrEmpty()) {
            sb.append(title)
            appended = true
        }
        if (!byline.isNullOrEmpty()) {
            if (appended) {
                sb.append(" by ")
            }
            sb.append(byline)
            appended = true
        }
        if (!attribution.isNullOrEmpty()) {
            if (appended) {
                sb.append(", ")
            }
            sb.append(attribution)
            appended = true
        }
        if (metadata != null) {
            if (appended) {
                sb.append("; ")
            }
            sb.append("Metadata=")
            sb.append(metadata)
            appended = true
        }
        if (_dateAdded != null) {
            if (appended) {
                sb.append(", ")
            }
            sb.append("Added on ")
            sb.append(DATE_FORMAT.format(_dateAdded))
            appended = true
        }
        if (_dateModified != null && _dateModified != _dateAdded) {
            if (appended) {
                sb.append(", ")
            }
            sb.append("Last modified on ")
            sb.append(DATE_FORMAT.format(_dateModified))
        }

        return sb.toString()
    }

    internal fun toContentValues() = ContentValues().apply {
        put(TOKEN, token)
        put(TITLE, title)
        put(BYLINE, byline)
        put(ATTRIBUTION, attribution)
        if (persistentUri != null) {
            put(PERSISTENT_URI, persistentUri.toString())
        }
        if (webUri != null) {
            put(WEB_URI, webUri.toString())
        }
        put(METADATA, metadata)
    }

    /**
     * A [builder](http://en.wikipedia.org/wiki/Builder_pattern)-style,
     * [fluent interface](http://en.wikipedia.org/wiki/Fluent_interface) for creating
     * [Artwork] objects.
     *
     * For example:
     * ```
     * Artwork artwork = new Artwork.Builder()
     *   .persistentUri(Uri.parse("http://example.com/image.jpg"))
     *   .title("Example image")
     *   .byline("Unknown person, c. 1980")
     *   .attribution("Copyright (C) Unknown person, 1980")
     *   .build()
     * ```
     */
    class Builder {
        private var token: String? = null
        private var title: String? = null
        private var byline: String? = null
        private var attribution: String? = null
        private var persistentUri: Uri? = null
        private var webUri: Uri? = null
        private var metadata: String? = null

        /**
         * Sets the artwork's opaque application-specific identifier.
         *
         * @param token the artwork's opaque application-specific identifier.
         * @return this [Builder].
         */
        fun token(token: String?): Builder {
            this.token = token
            return this
        }

        /**
         * Sets the artwork's user-visible title.
         *
         * @param title the artwork's user-visible title.
         * @return this [Builder].
         */
        fun title(title: String?): Builder {
            this.title = title
            return this
        }

        /**
         * Sets the artwork's user-visible byline, usually containing the author and date.
         *
         * This is generally used as a secondary source of information after the [title].
         *
         * @param byline the artwork's user-visible byline.
         * @return this [Builder].
         */
        fun byline(byline: String?): Builder {
            this.byline = byline
            return this
        }

        /**
         * Sets the artwork's user-visible attribution text.
         *
         * This is generally used as a tertiary source of information after the
         * [title] and the [byline].
         *
         * @param attribution the artwork's user-visible attribution text.
         * @return this [Builder].
         */
        fun attribution(attribution: String?): Builder {
            this.attribution = attribution
            return this
        }

        /**
         * Sets the artwork's persistent URI, which must resolve to a JPEG or PNG image, ideally
         * under 5MB.
         *
         * When a persistent URI is present, your [MuzeiArtProvider] will store
         * downloaded images in the [cache directory][Context.getCacheDir] and automatically
         * re-download the image as needed. If it is not present, then you must write the image
         * directly to the [MuzeiArtProvider] with
         * [ContentResolver.openOutputStream][android.content.ContentResolver.openOutputStream]
         * and the images will be stored in the [Context.getFilesDir] as it assumed that
         * there is no way to re-download the artwork.
         *
         * @param persistentUri the artwork's persistent URI. Your app should have long-lived
         * access to this URI.
         * @return this [Builder].
         * @see MuzeiArtProvider.openFile
         */
        fun persistentUri(persistentUri: Uri?): Builder {
            this.persistentUri = persistentUri
            return this
        }

        /**
         * Sets the artwork's web URI. This is used by default in
         * [MuzeiArtProvider.getArtworkInfo]
         * to allow the user to view more details about the artwork.
         *
         * @param webUri a Uri to more details about the artwork.
         * @return this [Builder].
         * @see MuzeiArtProvider.getArtworkInfo
         */
        fun webUri(webUri: Uri?): Builder {
            this.webUri = webUri
            return this
        }

        /**
         * Sets the provider specific metadata about the artwork.
         *
         * This is not used by Muzei at all, so can contain any data that makes it easier to query
         * or otherwise work with your Artwork.
         *
         * @param metadata any provider specific data associated with the artwork
         * @return this [Builder].
         */
        fun metadata(metadata: String?): Builder {
            this.metadata = metadata
            return this
        }

        /**
         * Creates and returns the final Artwork object. Once this method is called, it is not
         * valid to further use this [Artwork.Builder] object.
         *
         * @return the final constructed [Artwork].
         */
        fun build(): Artwork {
            return Artwork(title, byline, attribution, token, persistentUri, webUri, metadata)
        }
    }
}
