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

package com.google.android.apps.muzei.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.util.Log
import androidx.core.os.UserManagerCompat
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.ProviderManager
import kotlinx.coroutines.runBlocking
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.FileNotFoundException

/**
 * Provides access to a the most recent artwork
 */
class MuzeiProvider : ContentProvider() {

    companion object {
        private const val TAG = "MuzeiProvider"
        /**
         * The incoming URI matches the ARTWORK URI pattern
         */
        private const val ARTWORK = 1
        /**
         * The incoming URI matches the ARTWORK ID URI pattern
         */
        private const val ARTWORK_ID = 2
        /**
         * The incoming URI matches the SOURCE URI pattern
         */
        private const val SOURCES = 3
        /**
         * The incoming URI matches the SOURCE ID URI pattern
         */
        private const val SOURCE_ID = 4
        /**
         * A UriMatcher instance
         */
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(MuzeiContract.AUTHORITY, MuzeiContract.Artwork.TABLE_NAME,
                    MuzeiProvider.ARTWORK)
            addURI(MuzeiContract.AUTHORITY, "${MuzeiContract.Artwork.TABLE_NAME}/#",
                    MuzeiProvider.ARTWORK_ID)
            addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME,
                    MuzeiProvider.SOURCES)
            addURI(MuzeiContract.AUTHORITY, "${MuzeiContract.Sources.TABLE_NAME}/#",
                    MuzeiProvider.SOURCE_ID)
        }
    }

    /**
     * An identity all column projection mapping for Artwork
     */
    private val allArtworkColumnProjectionMap = mapOf(
            BaseColumns._ID to "artwork._id",
            "${MuzeiContract.Artwork.TABLE_NAME}.${BaseColumns._ID}" to "artwork._id",
            MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME to
                    "providerAuthority AS sourceComponentName",
            MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI to "imageUri",
            MuzeiContract.Artwork.COLUMN_NAME_TITLE to "title",
            MuzeiContract.Artwork.COLUMN_NAME_BYLINE to "byline",
            MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION to "attribution",
            MuzeiContract.Artwork.COLUMN_NAME_TOKEN to "NULL AS token",
            MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT to "NULL AS viewIntent",
            MuzeiContract.Artwork.COLUMN_NAME_META_FONT to "metaFont",
            MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED to "date_added",
            "${MuzeiContract.Sources.TABLE_NAME}.${BaseColumns._ID}" to
                    "0 AS \"sources._id\"",
            MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME to
                    "providerAuthority AS component_name",
            MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED to "1 AS selected",
            MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION to "\"\" AS description",
            MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE to "0 AS network",
            MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND to
                    "1 AS supports_next_artwork",
            MuzeiContract.Sources.COLUMN_NAME_COMMANDS to "NULL AS commands"
    )

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Deletes are not supported")
    }

    override fun getType(uri: Uri): String? {
        // Chooses the MIME type based on the incoming URI pattern
        return when (MuzeiProvider.uriMatcher.match(uri)) {
            ARTWORK ->
                // If the pattern is for artwork, returns the artwork content type.
                MuzeiContract.Artwork.CONTENT_TYPE
            ARTWORK_ID ->
                // If the pattern is for artwork id, returns the artwork content item type.
                MuzeiContract.Artwork.CONTENT_ITEM_TYPE
            SOURCES ->
                // If the pattern is for sources, returns the sources content type.
                MuzeiContract.Sources.CONTENT_TYPE
            SOURCE_ID ->
                // If the pattern is for source id, returns the sources content item type.
                MuzeiContract.Sources.CONTENT_ITEM_TYPE
            else -> throw IllegalArgumentException("Unknown URI $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Inserts are not supported")
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider.onCreate
     */
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        if (!UserManagerCompat.isUserUnlocked(context)) {
            Log.w(TAG, "Queries are not supported until the user is unlocked")
            return null
        }
        return if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            queryArtwork(uri, projection, selection, selectionArgs, sortOrder)
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            querySource(uri, projection)
        } else {
            throw IllegalArgumentException("Unknown URI $uri")
        }
    }

    private fun queryArtwork(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        val qb = SupportSQLiteQueryBuilder.builder("artwork")
        qb.columns(computeColumns(projection, allArtworkColumnProjectionMap))
        val provider = ensureBackground {
            MuzeiDatabase.getInstance(context).providerDao()
                    .currentProviderBlocking
        }
        var finalSelection = provider?.run {
            DatabaseUtils.concatenateWhere(selection,
                    "providerAuthority = \"${provider.authority}\"")
        } ?: selection
        if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
            // If the incoming URI is for a single artwork identified by its ID, appends "_ID = <artworkId>"
            // to the where clause, so that it selects that single piece of artwork
            finalSelection = DatabaseUtils.concatenateWhere(selection,
                    "${BaseColumns._ID} = ${uri.lastPathSegment}")
        }
        qb.selection(finalSelection, selectionArgs)
        qb.orderBy(sortOrder ?: "date_added DESC")
        return ensureBackground {
            MuzeiDatabase.getInstance(context).query(qb.create())
        }.apply {
            setNotificationUri(context.contentResolver, uri)
        }
    }

    private fun querySource(uri: Uri, projection: Array<String>?): Cursor? {
        val context = context ?: return null
        val c = MatrixCursor(projection)
        val currentProvider = ensureBackground {
            MuzeiDatabase.getInstance(context).providerDao()
                    .currentProviderBlocking
        }
        currentProvider?.let { provider ->
            c.newRow().apply {
                add(BaseColumns._ID, 0L)
                add(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, provider.authority)
                add(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, true)
                add(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION, runBlocking {
                    ProviderManager.getDescription(context, provider.authority)
                })
                add(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE, false)
                add(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                        provider.supportsNextArtwork)
                add(MuzeiContract.Sources.COLUMN_NAME_COMMANDS, null)
            }
        }
        return c.apply { setNotificationUri(context.contentResolver, uri) }
    }

    private fun computeColumns(projectionIn: Array<String>?, projectionMap: Map<String, String>): Array<String> {
        if (projectionIn != null && projectionIn.isNotEmpty()) {
            return projectionIn.map { userColumn ->
                val column = projectionMap[userColumn]
                when {
                    column != null -> column
                    userColumn.contains(" AS ") || userColumn.contains(" as ") -> userColumn
                    else -> throw IllegalArgumentException("Invalid column $userColumn")
                }
            }.toTypedArray()
        }
        // Return all columns in projection map.
        return projectionMap.values.toTypedArray()
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            openFileArtwork(uri, mode)
        } else {
            throw IllegalArgumentException("Unknown URI $uri")
        }
    }

    @Throws(FileNotFoundException::class)
    private fun openFileArtwork(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        if (!UserManagerCompat.isUserUnlocked(context)) {
            val file = DirectBootCache.getCachedArtwork(context)
                    ?: throw FileNotFoundException("No wallpaper was cached for Direct Boot")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val artworkDao = MuzeiDatabase.getInstance(context).artworkDao()
        val artwork = ensureBackground {
            when {
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK -> artworkDao.currentArtworkBlocking
                else -> artworkDao.getArtworkByIdBlocking(ContentUris.parseId(uri))
            }
        } ?: throw FileNotFoundException("Could not get artwork file for $uri")
        val token = Binder.clearCallingIdentity()
        try {
            return context.contentResolver.openFileDescriptor(artwork.imageUri, mode)
        } catch (e: FileNotFoundException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Artwork ${artwork.imageUri} with id ${artwork.id} from request for $uri " +
                        "is no longer valid, deleting: ${e.message}")
            }
            ensureBackground {
                artworkDao.deleteById(artwork.id)
            }
            throw e
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Updates are not supported")
    }
}
