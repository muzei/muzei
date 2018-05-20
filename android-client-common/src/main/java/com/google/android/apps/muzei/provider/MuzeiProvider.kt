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

import android.arch.persistence.db.SupportSQLiteQueryBuilder
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.support.v4.os.UserManagerCompat
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList

/**
 * Provides access to a the most recent artwork
 */
class MuzeiProvider : ContentProvider() {

    companion object {
        private const val TAG = "MuzeiProvider"
        /**
         * Maximum number of previous artwork to keep per source, with the exception of artwork that
         * has a persisted permission.
         * @see [cleanupCachedFiles]
         */
        private const val MAX_CACHE_SIZE = 10
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

        suspend fun getCacheFileForArtworkUri(context: Context, artworkId: Long) : File? {
            val artwork = async {
                MuzeiDatabase.getInstance(context)
                        .artworkDao()
                        .getArtworkById(artworkId)
            }.await() ?: return null
            return getCacheFileForArtworkUri(context, artwork)
        }

        fun getCacheFileForArtworkUri(context: Context, artwork: Artwork): File? {
            val directory = File(context.filesDir, "artwork")
            if (!directory.exists() && !directory.mkdirs()) {
                return null
            }
            if (artwork.imageUri == null && artwork.token.isNullOrEmpty()) {
                return File(directory, artwork.id.toString())
            }
            // Otherwise, create a unique filename based on the imageUri and token
            val filename = StringBuilder()
            artwork.imageUri?.run {
                filename.append(scheme).append("_")
                        .append(host).append("_")
                encodedPath?.take(60)?.replace('/', '_')?.run {
                    filename.append(this).append("_")
                }
            }
            // Use the imageUri if available, otherwise use the token
            val unique = artwork.imageUri?.toString() ?: artwork.token ?: ""
            try {
                val md = MessageDigest.getInstance("MD5")
                md.update(unique.toByteArray(charset("UTF-8")))
                val digest = md.digest()
                for (b in digest) {
                    if (0xff and b.toInt() < 0x10) {
                        filename.append("0").append(Integer.toHexString(0xFF and b.toInt()))
                    } else {
                        filename.append(Integer.toHexString(0xFF and b.toInt()))
                    }
                }
            } catch (e: NoSuchAlgorithmException) {
                filename.append(unique.hashCode())
            } catch (e: UnsupportedEncodingException) {
                filename.append(unique.hashCode())
            }

            return File(directory, filename.toString())
        }

        /**
         * Limit the number of cached files per art source to [.MAX_CACHE_SIZE].
         * @see [MAX_CACHE_SIZE]
         */
        fun cleanupCachedFiles(context: Context) {
            object : Thread() {
                override fun run() {
                    val database = MuzeiDatabase.getInstance(context)
                    val currentArtwork = database.artworkDao().currentArtworkBlocking ?: return
                    val sources = database.sourceDao().sourcesBlocking

                    // Loop through each source, cleaning up old artwork
                    for (source in sources) {
                        val componentName = source.componentName
                        val artworkCount = database.artworkDao().getArtworkCountForSourceBlocking(source.componentName)
                        if (artworkCount > MAX_CACHE_SIZE * 5) {
                            // Woah, that's way, way more than the allowed size
                            // Delete them all (except the current artwork)
                            // to get us back into a sane state
                            database.artworkDao().deleteNonMatching(context, componentName,
                                    listOf(currentArtwork.id))
                            continue
                        }
                        // Now use that ComponentName to look through the past artwork from that source
                        val artworkList = database.artworkDao()
                                .getArtworkForSourceBlocking(source.componentName)
                        if (artworkList.isEmpty()) {
                            continue
                        }
                        val artworkIdsToKeep = ArrayList<Long>()
                        val artworkToKeep = ArrayList<String>()
                        // Go through the artwork from this source and find the most recent artwork
                        // and mark them as artwork to keep
                        var count = 0
                        val mostRecentArtworkIds = ArrayList<Long>()
                        val mostRecentArtwork = ArrayList<String>()
                        for (artwork in artworkList) {
                            val unique = artwork.imageUri?.toString() ?: artwork.token ?: ""
                            if (mostRecentArtworkIds.size < MAX_CACHE_SIZE && !mostRecentArtwork.contains(unique)) {
                                mostRecentArtwork.add(unique)
                                mostRecentArtworkIds.add(artwork.id)
                            }
                            if (artworkToKeep.contains(unique)) {
                                // This ensures we aren't double counting the same artwork in our count
                                continue
                            }
                            if (count++ < MAX_CACHE_SIZE) {
                                // Keep artwork below the MAX_CACHE_SIZE
                                artworkIdsToKeep.add(artwork.id)
                                artworkToKeep.add(unique)
                            }
                        }
                        // Now delete all artwork not in the keep list
                        try {
                            database.artworkDao().deleteNonMatching(context,
                                    componentName, artworkIdsToKeep)
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "Unable to read all artwork for $componentName, " +
                                    "deleting everything but the latest artwork to get back to a good state", e)
                            database.artworkDao().deleteNonMatching(context,
                                    componentName, mostRecentArtworkIds)
                        } catch (e: SQLiteException) {
                            Log.e(TAG, "Unable to read all artwork for $componentName, " +
                                    "deleting everything but the latest artwork to get back to a good state", e)
                            database.artworkDao().deleteNonMatching(context, componentName, mostRecentArtworkIds)
                        }
                    }
                }
            }.start()
        }
    }

    /**
     * An identity all column projection mapping for Artwork
     */
    private val allArtworkColumnProjectionMap = mapOf(
            BaseColumns._ID to "artwork._id",
            "${MuzeiContract.Artwork.TABLE_NAME}.${BaseColumns._ID}" to "artwork._id",
            MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME to "sourceComponentName",
            MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI to "imageUri",
            MuzeiContract.Artwork.COLUMN_NAME_TITLE to "title",
            MuzeiContract.Artwork.COLUMN_NAME_BYLINE to "byline",
            MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION to "attribution",
            MuzeiContract.Artwork.COLUMN_NAME_TOKEN to "token",
            MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT to "viewIntent",
            MuzeiContract.Artwork.COLUMN_NAME_META_FONT to "metaFont",
            MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED to "date_added",
            "${MuzeiContract.Sources.TABLE_NAME}.${BaseColumns._ID}" to
                    "0 AS \"sources._id\"",
            MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME to
                    "sourceComponentName AS component_name",
            MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED to "1 AS selected",
            MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION to "\"\" AS description",
            MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE to "0 AS network",
            MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND to
                    "1 AS supports_next_artwork",
            MuzeiContract.Sources.COLUMN_NAME_COMMANDS to "NULL AS commands"
    )
    private lateinit var openFileHandler: Handler

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
        openFileHandler = Handler()
        // Schedule a job that will update the latest artwork in the Direct Boot cache directory
        // whenever the artwork changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context?.run { DirectBootCacheJobService.scheduleDirectBootCacheJob(this) }
        }
        return true
    }

    override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor? {
        if (!UserManagerCompat.isUserUnlocked(context)) {
            Log.w(TAG, "Queries are not supported until the user is unlocked")
            return null
        }
        return runBlocking {
            if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                    MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
                queryArtwork(uri, projection, selection, selectionArgs, sortOrder)
            } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                    MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
                querySource(uri, projection)
            } else {
                throw IllegalArgumentException("Unknown URI $uri")
            }
        }
    }

    private suspend fun queryArtwork(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        val qb = SupportSQLiteQueryBuilder.builder("artwork")
        qb.columns(computeColumns(projection, allArtworkColumnProjectionMap))
        val source = async {
            MuzeiDatabase.getInstance(context).sourceDao()
                    .currentSourceBlocking
        }.await()
        var finalSelection = source?.run {
            DatabaseUtils.concatenateWhere(selection,
                    "sourceComponentName = \"${source.componentName.flattenToShortString()}\"")
        } ?: selection
        if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
            // If the incoming URI is for a single artwork identified by its ID, appends "_ID = <artworkId>"
            // to the where clause, so that it selects that single piece of artwork
            finalSelection = DatabaseUtils.concatenateWhere(selection,
                    "${BaseColumns._ID} = ${uri.lastPathSegment}")
        }
        qb.selection(finalSelection, selectionArgs)
        qb.orderBy(sortOrder ?: "date_added DESC")
        return async {
            MuzeiDatabase.getInstance(context).query(qb.create()).apply {
                setNotificationUri(context.contentResolver, uri)
            }
        }.await()
    }

    private suspend fun querySource(uri: Uri, projection: Array<String>?): Cursor? {
        val context = context ?: return null
        val c = MatrixCursor(projection)
        val currentSource = async {
            MuzeiDatabase.getInstance(context).sourceDao()
                    .currentSourceBlocking
        }.await()
        currentSource?.let { source ->
            c.newRow().apply {
                add(BaseColumns._ID, 0L)
                add(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, source.componentName)
                add(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, true)
                add(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                        source.displayDescription)
                add(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE, false)
                add(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                        source.supportsNextArtwork)
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
        return runBlocking {
            if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                    MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
                openFileArtwork(uri, mode)
            } else {
                throw IllegalArgumentException("Unknown URI $uri")
            }
        }
    }

    @Throws(FileNotFoundException::class)
    private suspend fun openFileArtwork(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val isWriteOperation = mode.contains("w")
        val file = when {
            !UserManagerCompat.isUserUnlocked(context) -> {
                if (isWriteOperation) {
                    Log.w(TAG, "Wallpaper is read only until the user is unlocked")
                    return null
                }
                DirectBootCacheJobService.getCachedArtwork(context)
            }
            !isWriteOperation && MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK -> {
                // If it isn't a write operation, then we should attempt to find the latest artwork
                // that does have a cached artwork file. This prevents race conditions where
                // an external app attempts to load the latest artwork while an art source is inserting a
                // new artwork
                val artworkList = async {
                    MuzeiDatabase.getInstance(context).artworkDao().artworkBlocking
                }.await()
                if (artworkList.isEmpty()) {
                    if (BuildConfig.DEBUG || context.packageName != callingPackage) {
                        Log.w(TAG, "You must insert at least one row to read or write artwork")
                    }
                    return null
                }
                artworkList.asSequence().map { artwork -> getCacheFileForArtworkUri(context, artwork) }
                        .find { it?.exists() == true }
            }
            else -> getCacheFileForArtworkUri(context, ContentUris.parseId(uri))
        } ?: throw FileNotFoundException("Could not create artwork file for $uri for mode $mode")

        if (file.exists() && file.length() > 0 && isWriteOperation) {
            if (BuildConfig.DEBUG || context.packageName != callingPackage) {
                Log.w(TAG, "Writing to an existing artwork file ($file) for $uri is not allowed: " +
                        "insert a new row")
            }
            return null
        }
        Log.v(TAG, "Opening artwork for $uri doing a ${if (isWriteOperation) "write" else "read"}")
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), openFileHandler) { e ->
                if (BuildConfig.DEBUG) {
                    Log.v(TAG, "Artwork for $uri doing a ${if (isWriteOperation) "write" else "read"} operation is now closed.", e)
                }
                if (isWriteOperation) {
                    if (e != null) {
                        Log.e(TAG, "Error closing $file for $uri", e)
                        if (file.exists()) {
                            if (!file.delete()) {
                                Log.w(TAG, "Unable to delete $file")
                            }
                        }
                    } else {
                        // The file was successfully written, notify listeners of the new artwork
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Artwork was successfully written to $file for $uri")
                        }
                        context.contentResolver
                                .notifyChange(MuzeiContract.Artwork.CONTENT_URI, null)
                        context.sendBroadcast(
                                Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED))
                        cleanupCachedFiles(context)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error opening artwork $uri", e)
            throw FileNotFoundException("Error opening artwork $uri")
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Updates are not supported")
    }
}
