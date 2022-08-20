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

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.provider.BaseColumns
import androidx.annotation.RequiresApi
import java.util.ArrayList

/**
 * Contract between Muzei and Muzei Art Providers, containing the definitions for all supported
 * URIs and columns as well as helper methods to make it easier to work with the provided data.
 */
public object ProviderContract {
    /**
     * Retrieve the content URI for the given [MuzeiArtProvider], allowing you to build
     * custom queries, inserts, updates, and deletes using a [ContentResolver].
     *
     * This **does not** check for the validity of the MuzeiArtProvider. You can
     * use [PackageManager.resolveContentProvider] passing in the
     * authority if you need to confirm the authority is valid.
     *
     * @param authority The [MuzeiArtProvider] you need a content URI for
     * @return The content URI for the [MuzeiArtProvider]
     * @see MuzeiArtProvider.contentUri
     */
    @JvmStatic
    public fun getContentUri(authority: String): Uri {
        return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .build()
    }

    /**
     * Creates a new [ProviderClient] for accessing the given [MuzeiArtProvider]
     * from anywhere in your application.
     *
     * @param context Context used to construct the ProviderClient.
     * @param provider The [MuzeiArtProvider] you need a ProviderClient for.
     * @return a [ProviderClient] suitable for accessing the [MuzeiArtProvider].
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    public fun getProviderClient(
            context: Context,
            provider: Class<out MuzeiArtProvider>
    ): ProviderClient {
        val componentName = ComponentName(context, provider)
        val pm = context.packageManager
        val authority: String
        try {
            @Suppress("DEPRECATION")
            val info = pm.getProviderInfo(componentName, 0)
            authority = info.authority
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException(
                    "Invalid MuzeiArtProvider: $componentName, is your provider disabled?", e)
        }

        return getProviderClient(context, authority)
    }

    /**
     * Creates a new [ProviderClient] for accessing the given [MuzeiArtProvider]
     * from anywhere in your application.
     *
     * @param context Context used to construct the ProviderClient.
     * @param Provider The subclass of [MuzeiArtProvider] you need a ProviderClient for.
     * @return a [ProviderClient] suitable for accessing the [MuzeiArtProvider].
     */
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    public inline fun <reified Provider : MuzeiArtProvider> getProviderClient(
            context: Context): ProviderClient {
        return getProviderClient(context, Provider::class.java)
    }

    /**
     * Creates a new [ProviderClient] for accessing the given [MuzeiArtProvider]
     * from anywhere in your application.
     *
     * @param context Context used to construct the ProviderClient.
     * @param authority The [MuzeiArtProvider] you need a ProviderClient for.
     * @return a [ProviderClient] suitable for accessing the [MuzeiArtProvider].
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    public fun getProviderClient(
            context: Context,
            authority: String): ProviderClient {
        val contentUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .build()
        return object : ProviderClient {
            override val contentUri: Uri
                get() = contentUri

            override val lastAddedArtwork: com.google.android.apps.muzei.api.provider.Artwork?
                get() = context.contentResolver.query(
                        contentUri,
                        null, null, null,
                        "${BaseColumns._ID} DESC")?.use { data ->
                    return if (data.moveToFirst())
                        com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data)
                    else
                        null
                }

            override fun addArtwork(
                    artwork: com.google.android.apps.muzei.api.provider.Artwork
            ): Uri? {
                val contentResolver = context.contentResolver
                return contentResolver.insert(contentUri, artwork.toContentValues())
            }

            override fun addArtwork(
                    artwork: Iterable<com.google.android.apps.muzei.api.provider.Artwork>
            ): List<Uri> {
                val contentResolver = context.contentResolver
                val operations = ArrayList<ContentProviderOperation>()
                for (art in artwork) {
                    operations.add(ContentProviderOperation.newInsert(contentUri)
                            .withValues(art.toContentValues())
                            .build())
                }
                return try {
                    val results = contentResolver.applyBatch(authority, operations)
                    results.mapNotNull { result -> result.uri }
                } catch (ignored: OperationApplicationException) {
                    emptyList()
                } catch (ignored: RemoteException) {
                    emptyList()
                }
            }

            override fun setArtwork(
                    artwork: com.google.android.apps.muzei.api.provider.Artwork
            ): Uri? {
                val contentResolver = context.contentResolver
                val operations = ArrayList<ContentProviderOperation>()
                operations.add(ContentProviderOperation.newInsert(contentUri)
                        .withValues(artwork.toContentValues())
                        .build())
                operations.add(ContentProviderOperation.newDelete(contentUri)
                        .withSelection("${BaseColumns._ID} != ?", arrayOfNulls(1))
                        .withSelectionBackReference(0, 0)
                        .build())
                return try {
                    val results = contentResolver.applyBatch(
                            authority, operations)
                    results[0].uri
                } catch (e: Exception) {
                    null
                }
            }

            override fun setArtwork(
                    artwork: Iterable<com.google.android.apps.muzei.api.provider.Artwork>
            ): List<Uri> {
                val contentResolver = context.contentResolver
                val operations = ArrayList<ContentProviderOperation>()
                for (art in artwork) {
                    operations.add(ContentProviderOperation.newInsert(contentUri)
                            .withValues(art.toContentValues())
                            .build())
                }
                val artworkCount = operations.size
                val resultUris = ArrayList<Uri>(artworkCount)

                // Delete any artwork that was not inserted/update in the above operations
                val currentTime = System.currentTimeMillis()
                operations.add(ContentProviderOperation.newDelete(contentUri)
                        .withSelection("${Artwork.DATE_MODIFIED} < ?",
                                arrayOf(currentTime.toString()))
                        .build())
                try {
                    val results = contentResolver.applyBatch(authority, operations)
                    resultUris.addAll(results.take(artworkCount).mapNotNull { result -> result.uri })
                } catch (ignored: OperationApplicationException) {
                } catch (ignored: RemoteException) {
                }

                return resultUris
            }
        }
    }

    /**
     * Constants and helper methods for working with the
     * [Artwork][com.google.android.apps.muzei.api.provider.Artwork] associated
     * with a [MuzeiArtProvider].
     */
    public object Artwork {
        /**
         * The unique ID of the Artwork, suitable for use with [ContentUris.withAppendedId].
         */
        @Suppress("unused", "ObjectPropertyName")
        public const val _ID: String = BaseColumns._ID
        /**
         * The token that uniquely defines the artwork. Any inserts using the same non-null token
         * will be considered updates to the existing artwork. Therefore there will always be at
         * most one artwork with the same non-null token.
         *
         * This field **cannot** be changed after the artwork is inserted.
         *
         * Type: TEXT
         */
        public const val TOKEN: String = "token"
        /**
         * The user-visible title of the artwork
         *
         * Type: TEXT
         */
        public const val TITLE: String = "title"
        /**
         * The artwork's byline (such as author and date). This is generally used as a secondary
         * source of information after the [TITLE].
         *
         * Type: TEXT
         */
        public const val BYLINE: String = "byline"
        /**
         * The attribution info for the artwork
         *
         * Type: TEXT
         */
        public const val ATTRIBUTION: String = "attribution"
        /**
         * The persistent URI of the artwork
         *
         * Type: TEXT (Uri)
         */
        public const val PERSISTENT_URI: String = "persistent_uri"
        /**
         * The web URI of the artwork
         *
         * Type: TEXT (Uri)
         */
        public const val WEB_URI: String = "web_uri"
        /**
         * The provider specific metadata about the artwork
         *
         * Type: TEXT
         */
        public const val METADATA: String = "metadata"
        /**
         * Path to the file on disk.
         *
         * Note that apps may not have filesystem permissions to directly access
         * this path. Instead of trying to open this path directly, apps should use
         * [ContentResolver.openFileDescriptor][android.content.ContentResolver.openFileDescriptor]
         * to gain access.
         *
         * Type: TEXT
         */
        public const val DATA: String = "_data"
        /**
         * The time the file was added to the provider.
         *
         * Type: LONG (in milliseconds)
         */
        public const val DATE_ADDED: String = "date_added"
        /**
         * The time the file was last modified.
         *
         * Type: LONG (in milliseconds)
         */
        public const val DATE_MODIFIED: String = "date_modified"
    }
}