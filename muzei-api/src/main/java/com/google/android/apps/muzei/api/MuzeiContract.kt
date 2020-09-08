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

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.provider.BaseColumns
import androidx.annotation.RequiresPermission
import androidx.annotation.WorkerThread
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.CONTENT_URI
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.getCurrentArtwork
import com.google.android.apps.muzei.api.MuzeiContract.Artwork.getCurrentArtworkBitmap
import com.google.android.apps.muzei.api.provider.ProviderClient
import java.io.FileNotFoundException

/**
 * Contract between Muzei and applications, containing the definitions for all supported URIs and
 * columns as well as helper methods to make it easier to work with the provided data.
 */
public object MuzeiContract {
    /**
     * Only Muzei can write to the Muzei ContentProvider - this is a signature permission that no
     * other app can hold.
     */
    public const val WRITE_PERMISSION: String = "com.google.android.apps.muzei.WRITE_PROVIDER"
    /**
     * Base authority for this content provider
     */
    public const val AUTHORITY: String = "com.google.android.apps.muzei"
    /**
     * The scheme part for this provider's URI
     */
    private const val SCHEME = "content://"

    /**
     * Constants and helper methods for the Artwork table, providing access to the current artwork.
     *
     * The Artwork table contains the details of the artwork that has been loaded by Muzei.
     * It also provides direct access to the cached image already downloaded by Muzei,
     * ensuring that you do not need to do additional networks requests or have internet access
     * when retrieving previously loaded artwork.
     *
     * ### Working with the Artwork table
     *
     * Querying [CONTENT_URI] will return either zero rows (in cases where the user has never
     * activated Muzei before) or a row for each previously loaded artwork with all of the
     * details needed to create an [Artwork][com.google.android.apps.muzei.api.Artwork] object.
     * The helper method [getCurrentArtwork] builds an Artwork object for the most recent
     * artwork, although you can certainly query the [CONTENT_URI] directly and either
     * use the columns directly or use
     * [Artwork.fromCursor][com.google.android.apps.muzei.api.Artwork.fromCursor]
     * to parse the Cursor for you.
     *
     * If instead you use [ContentResolver.openInputStream], you'll get an InputStream for the
     * cached image Bitmap. This can then be passed to [BitmapFactory.decodeStream] or
     * similar methods to retrieve the Bitmap itself. The helper method [getCurrentArtworkBitmap]
     * does this operation, although note that this may return a very large Bitmap so following the
     * [Handling Bitmaps documentation](https://developer.android.com/topic/performance/graphics)
     * advice is highly suggested.
     *
     * ### Listening for changes
     *
     * Just like any [ContentProvider], listening for changes can be done by implementing a
     * [ContentObserver] on [CONTENT_URI] to listen for updates.
     *
     * On API 24+ devices, it is strongly recommended to use `WorkManager` or
     * `JobScheduler` to listen for content URI changes in the background without
     * maintaining a constantly running [ContentObserver].
     *
     * To support earlier versions of Android, you can listen for the
     * [ACTION_ARTWORK_CHANGED] broadcast, sent out immediately after an update is made:
     *
     * ```
     * <receiver android:name=".ExampleArtworkUpdateReceiver">
     *   <intent-filter>
     *     <action android:name="com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED" />
     *   </intent-filter>
     * </receiver>
     * ```
     *
     * No data is sent alongside the broadcast, but this can be used to kick off
     * background processing to retrieve the latest artwork or start other processing.
     */
    public object Artwork {
        /**
         * The unique ID of the Artwork, suitable for use with [ContentUris.withAppendedId].
         */
        @Suppress("unused", "ObjectPropertyName")
        public const val _ID: String = BaseColumns._ID
        /**
         * Column name for the authority of the provider for this artwork.
         *
         * Type: TEXT
         */
        public const val COLUMN_NAME_PROVIDER_AUTHORITY: String = "sourceComponentName"
        /**
         * Column name of the artwork image URI. In almost all cases you should use
         * [ContentResolver.openInputStream(CONTENT_URI)][ContentResolver.openInputStream]
         * to retrieve the already downloaded artwork.
         *
         * Type: TEXT (URI)
         */
        public const val COLUMN_NAME_IMAGE_URI: String = "imageUri"
        /**
         * Column name for the artwork's title.
         *
         * Type: TEXT
         */
        public const val COLUMN_NAME_TITLE: String = "title"
        /**
         * Column name for the artwork's byline (such as author and date).
         *
         * Type: TEXT
         */
        public const val COLUMN_NAME_BYLINE: String = "byline"
        /**
         * Column name for the artwork's attribution info.
         *
         * Type: TEXT
         */
        public const val COLUMN_NAME_ATTRIBUTION: String = "attribution"
        /**
         * Column name for when this artwork was added.
         *
         * Type: LONG (in milliseconds)
         */
        public const val COLUMN_NAME_DATE_ADDED: String = "date_added"
        /**
         * The MIME type of [CONTENT_URI] providing artwork.
         */
        public const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.artwork"
        /**
         * The MIME type of [CONTENT_URI] providing a single piece of artwork.
         */
        public const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.google.android.apps.muzei.artwork"
        /**
         * The default sort order for this table
         */
        @Suppress("unused")
        public const val DEFAULT_SORT_ORDER: String = "$COLUMN_NAME_DATE_ADDED DESC"
        /**
         * The table name offered by this provider.
         */
        public const val TABLE_NAME: String = "artwork"

        /**
         * The content:// style URL for this table. This is the main entry point for queries and for
         * opening an [InputStream][java.io.InputStream] to the current artwork's image.
         *
         * Apps can only [query][ContentResolver.query] for artwork; only Muzei can write
         * to the ContentProvider.
         *
         * @see getCurrentArtwork
         * @see getCurrentArtworkBitmap
         */
        @JvmStatic
        @get:JvmName("getContentUri")
        @RequiresPermission.Write(RequiresPermission(WRITE_PERMISSION))
        public val CONTENT_URI: Uri = Uri.parse("$SCHEME$AUTHORITY/$TABLE_NAME")
        /**
         * Intent action that will be broadcast when the artwork is changed. This happens
         * immediately after the ContentProvider is updated with data and should be considered
         * the signal that you can retrieve the new artwork.
         */
        @Deprecated("This broadcast cannot be received on Build.VERSION_CODES.O " +
                "and higher devices. Use WorkManager or JobScheduler to listen for " +
                "artwork change events in the background on API 24+ devices.")
        public const val ACTION_ARTWORK_CHANGED: String = "com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED"

        /**
         * Returns the current Muzei [Artwork][com.google.android.apps.muzei.api.Artwork].
         *
         * @param context the context to retrieve a ContentResolver.
         *
         * @return the current [Artwork][com.google.android.apps.muzei.api.Artwork]
         * or null if one could not be found.
         */
        @JvmStatic
        public fun getCurrentArtwork(context: Context): com.google.android.apps.muzei.api.Artwork? {
            val contentResolver = context.contentResolver
            return contentResolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    com.google.android.apps.muzei.api.Artwork.fromCursor(cursor)
                } else {
                    null
                }
            }
        }

        /**
         * Naively gets the current artwork image without any subsampling or optimization for output size
         *
         * Note that this may return a very large Bitmap so following the
         * [Displaying Bitmaps Efficiently training](http://developer.android.com/training/displaying-bitmaps/index.html)
         * is highly recommended.
         *
         * @param context the context to retrieve a ContentResolver.
         *
         * @return A Bitmap of the current artwork or null if the image could not be decoded.
         *
         * @throws FileNotFoundException If no cached artwork image was found.
         */
        @JvmStatic
        @WorkerThread
        @Throws(FileNotFoundException::class)
        public fun getCurrentArtworkBitmap(context: Context): Bitmap? {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "getCurrentArtworkBitmap cannot be called on the main thread"
            }
            val contentResolver = context.contentResolver
            return BitmapFactory.decodeStream(contentResolver.openInputStream(CONTENT_URI))
        }
    }

    /**
     * Constants and helper methods for the Sources table, providing access to sources' information.
     */
    public object Sources {
        /**
         * The unique ID of the Source, suitable for use with [ContentUris.withAppendedId].
         */
        @Suppress("unused", "ObjectPropertyName")
        public const val _ID: String = BaseColumns._ID
        /**
         * Column name for the authority of the provider for this artwork.
         *
         * Type: TEXT
         */
        public const val COLUMN_NAME_AUTHORITY: String = "component_name"
        /**
         * Column name for the source's description.
         *
         * Type: TEXT
         */
        public const val COLUMN_NAME_DESCRIPTION: String = "description"
        /**
         * Column name for the flag indicating if the source supports a 'Next Artwork' action
         *
         * Type: INTEGER (boolean)
         */
        public const val COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND: String = "supports_next_artwork"
        /**
         * The MIME type of [CONTENT_URI] providing sources.
         */
        public const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.source"
        /**
         * The MIME type of [CONTENT_URI] providing a single source.
         */
        public const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.google.android.apps.muzei.source"
        /**
         * The default sort order for this table
         */
        @Suppress("unused")
        public const val DEFAULT_SORT_ORDER: String = COLUMN_NAME_AUTHORITY
        /**
         * The table name offered by this provider.
         */
        public const val TABLE_NAME: String = "sources"

        /**
         * The content:// style URL for this table.
         *
         * Apps can only [query][ContentResolver.query] for source info; only Muzei can write
         * to the ContentProvider.
         */
        @JvmStatic
        @get:JvmName("getContentUri")
        @RequiresPermission.Write(RequiresPermission(WRITE_PERMISSION))
        public val CONTENT_URI: Uri = Uri.parse("$SCHEME$AUTHORITY/$TABLE_NAME")
        /**
         * Intent action that will be broadcast when the source info is changed. This happens
         * immediately after the ContentProvider is updated with data and should be considered
         * the signal that you can retrieve the new source info.
         */
        @Deprecated("This broadcast cannot be received on Build.VERSION_CODES.O " +
                "and higher devices. Use WorkManager or JobScheduler to listen for " +
                "source change events in the background on API 24+ devices.")
        public const val ACTION_SOURCE_CHANGED: String = "com.google.android.apps.muzei.ACTION_SOURCE_CHANGED"

        /**
         * Checks the [Sources] table provided by Muzei to determine whether the
         * [com.google.android.apps.muzei.api.provider.MuzeiArtProvider] associated with the
         * given [authority] has been selected by the user.
         *
         * If Muzei is not installed or Muzei has never been activated, this will return
         * `false`.
         */
        @JvmStatic
        public fun isProviderSelected(context: Context, authority: String): Boolean = context.contentResolver.query(
                CONTENT_URI, arrayOf(COLUMN_NAME_AUTHORITY),
                "$COLUMN_NAME_AUTHORITY=?", arrayOf(authority),
                null)?.use { data ->
            val authorityColumn = data.getColumnIndex(COLUMN_NAME_AUTHORITY)
            while (data.moveToNext()) {
                val selectedAuthority = data.getString(authorityColumn)
                if (selectedAuthority == authority) {
                    return true
                }
            }
            false
        } ?: false

        /**
         * Deep link into Muzei's Sources screen, automatically scrolling
         * to the [com.google.android.apps.muzei.api.provider.MuzeiArtProvider] associated
         * with the given [authority]. If Muzei is not yet activated, users will be asked
         * to activate Muzei before continuing onto the Sources screen.
         *
         * Note that users must still manually select your `MuzeiArtProvider`.
         *
         * It is strongly recommended in cases where you receive an
         * [android.content.ActivityNotFoundException] (due to the user having an older version
         * of Muzei installed or if Muzei is not installed at all) to fall back on:
         *
         * 1. [android.content.pm.PackageManager.getLaunchIntentForPackage] with Muzei's
         * package name, `net.nurik.roman.muzei` to open Muzei and navigate to the
         * Sources screen themselves.
         * 2. A link to the Google Play Store with Muzei's package name so that user can
         * install Muzei.
         */
        @JvmStatic
        public fun createChooseProviderIntent(authority: String): Intent = Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse(BuildConfig.CHOOSE_PROVIDER_URI_PREFIX + authority))
    }
}

/**
 * Checks the [MuzeiContract.Sources] table provided by Muzei to determine whether the
 * [com.google.android.apps.muzei.api.provider.MuzeiArtProvider] associated with
 * this [ProviderClient] has been selected by the user.
 *
 * If Muzei is not installed or Muzei has never been activated, this will return
 * `false`.
 */
@Suppress("unused")
public fun ProviderClient.isSelected(context: Context): Boolean =
        MuzeiContract.Sources.isProviderSelected(context, contentUri.authority!!)

/**
 * Deep link into Muzei's Sources screen, automatically scrolling
 * to the [com.google.android.apps.muzei.api.provider.MuzeiArtProvider] associated
 * with this [ProviderClient]. If Muzei is not yet activated, users will be asked
 * to activate Muzei before continuing onto the Sources screen.
 *
 * Note that users must still manually select your `MuzeiArtProvider`.
 *
 * It is strongly recommended in cases where you receive an
 * [android.content.ActivityNotFoundException] (due to the user having an older version
 * of Muzei installed or if Muzei is not installed at all) to fall back on:
 *
 * 1. [android.content.pm.PackageManager.getLaunchIntentForPackage] with Muzei's
 * package name, `net.nurik.roman.muzei` to open Muzei and navigate to the
 * Sources screen themselves.
 * 2. A link to the Google Play Store with Muzei's package name so that user can
 * install Muzei.
 */
@Suppress("unused")
public fun ProviderClient.createChooseProviderIntent(): Intent =
        MuzeiContract.Sources.createChooseProviderIntent(contentUri.authority!!)
