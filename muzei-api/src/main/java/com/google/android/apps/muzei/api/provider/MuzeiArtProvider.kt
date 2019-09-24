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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.util.Log
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_GET_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_LAST_LOADED_TIME
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_MAX_LOADED_ARTWORK_ID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_OPEN_ARTWORK_INFO_SUCCESS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_RECENT_ARTWORK_IDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_VERSION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_VERSION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_INVALID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_OPEN_ARTWORK_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND
import com.google.android.apps.muzei.api.internal.getRecentIds
import com.google.android.apps.muzei.api.internal.putRecentIds
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider.Companion.ACCESS_PERMISSION
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider.Companion.ACTION_MUZEI_ART_PROVIDER
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider.Companion.EXTRA_FROM_MUZEI
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.HashSet

/**
 * Base class for a Muzei Live Wallpaper artwork provider. Art providers are a way for other apps to
 * feed wallpapers (called [artworks][Artwork]) to Muzei Live Wallpaper.
 *
 * ### Subclassing [MuzeiArtProvider]
 *
 * Subclasses must implement at least the [onLoadRequested] callback
 * method, which is called whenever Muzei has displayed all available artwork from your provider.
 * It is strongly recommended to load new artwork at this point and add it via
 * [addArtwork] so that users can continue to move to the next artwork.
 *
 *  All artwork added via [addArtwork] is available to Muzei. Muzei controls how
 * often the artwork changes and the order that it proceeds through the artwork. As a
 * convenience, you can use [setArtwork] to remove all artwork and set just a
 * single new [Artwork]. You can use [ContentResolver.delete] with [contentUri] to delete
 * specific artwork based on criteria of your choosing.
 *
 * Many operations are also available in [ProviderContract.Artwork], allowing you to add,
 * update, delete, and query for artwork from anywhere in your app.
 *
 * ### Registering your provider
 *
 * Each provider must be added to your application's `AndroidManifest.xml` file via a
 * `<provider>` element.
 *
 * The Muzei app discover available providers using Android's [Intent] mechanism. Ensure
 * that your `provider` definition includes an `<intent-filter>` with
 * an action of [ACTION_MUZEI_ART_PROVIDER]. It is strongly recommended to protect access
 * to your provider's data by adding the [ACCESS_PERMISSION], which will
 * ensure that only your app and Muzei can access your data.
 *
 * Lastly, there are a few `<meta-data>` elements that you should add to your
 * provider definition:
 *
 *  * `settingsActivity` (optional): if present, should be the qualified
 * component name for a configuration activity in the provider's package that Muzei can offer
 * to the user for customizing the extension. This activity must be exported.
 *  * `setupActivity` (optional): if present, should be the qualified
 * component name for an initial setup activity that must be ran before the provider can be
 * activated. It will be started with [android.app.Activity.startActivityForResult] and must
 * return [android.app.Activity.RESULT_OK] for the provider to be activated. This activity
 * must be exported.
 *
 * ### Example
 *
 * Below is an example provider declaration in the manifest:
 * ```
 * <provider android:name=".ExampleArtProvider"
 *   android:authority="com.example.artprovider"
 *   android:label="@string/source_title"
 *   android:description="@string/source_description"
 *   android:permission="com.google.android.apps.muzei.api.ACCESS_PROVIDER">
 *   <intent-filter>
 *     <action android:name="com.google.android.apps.muzei.api.MuzeiArtProvider" />
 *   </intent-filter>
 *   <!-- A settings activity is optional -->
 *   <meta-data android:name="settingsActivity"
 *     android:value=".ExampleSettingsActivity" />
 * </provider>
 * ```
 *
 * If a `settingsActivity` meta-data element is present, an activity with the given
 * component name should be defined and exported in the application's manifest as well. Muzei
 * will set the [EXTRA_FROM_MUZEI] extra to true in the launch intent for this
 * activity. An example is shown below:
 * ```
 * <activity android:name=".ExampleSettingsActivity"
 *   android:label="@string/title_settings"
 *   android:exported="true" />
 * ```
 *
 * Finally, below is a simple example [MuzeiArtProvider] subclass that publishes a single,
 * static artwork:
 * ```
 * class ExampleArtProvider : MuzeiArtProvider() {
 *   override fun onLoadRequested(initial: Boolean) {
 *     if (initial) {
 *       setArtwork(Artwork(
 *           title = "Example image",
 *           byline = "Unknown person, c. 1980",
 *           persistentUri = Uri.parse("http://example.com/image.jpg"),
 *           webUri = Uri.parse("http://example.com/imagedetails.html")))
 *     }
 *   }
 * }
 * ```
 *
 * As onLoadRequested can be called at any time (including when offline), it is
 * strongly recommended to use the callback of onLoadRequested to kick off
 * a load operation using `WorkManager`, `JobScheduler`, or a comparable API. These
 * other components can then use a [ProviderClient] and
 * [ProviderClient.addArtwork] to add Artwork to the MuzeiArtProvider.
 *
 * ### Additional notes
 * Providers can also expose additional user-facing commands (such as 'Share artwork') by
 * returning one or more [commands][UserCommand] from [getCommands]. To handle
 * commands, override the [onCommand] callback method.
 *
 * Providers can provide a dynamic description of the current configuration (e.g.
 * 'Popular photos tagged "landscape"'), by overriding [getDescription]. By default,
 * the `android:description` element of the provider element in the manifest will be
 * used.
 *
 * All artwork should support opening an Activity to view more details about the artwork.
 * You can provider your own functionality by overriding [getArtworkInfo].
 *
 * If custom behavior is needed to retrieve the artwork's binary data (for example,
 * authentication with a remote server), this behavior can be added to
 * [openFile]. If you already have binary data available locally for your
 * artwork, you can also write it directly via [ContentResolver.openOutputStream].
 *
 * MuzeiArtProvider respects [Log.isLoggable] for debug logging, allowing you to
 * use `adb shell setprop log.tag.MuzeiArtProvider VERBOSE` to enable logging of the
 * communications between Muzei and your MuzeiArtProvider.
 *
 * @constructor Constructs a `MuzeiArtProvider`.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
abstract class MuzeiArtProvider : ContentProvider(), ProviderClient {

    companion object {
        private const val TAG = "MuzeiArtProvider"
        private const val MAX_RECENT_ARTWORK = 100
        /**
         * Permission that can be used with your [MuzeiArtProvider] to ensure that only your app
         * and Muzei can read and write its data.
         *
         * This is a signature permission that only Muzei can hold.
         */
        @Suppress("unused")
        const val ACCESS_PERMISSION = "com.google.android.apps.muzei.api.ACCESS_PROVIDER"
        /**
         * The [Intent] action representing a Muzei art provider. This provider should
         * declare an `<intent-filter>` for this action in order to register with
         * Muzei.
         */
        const val ACTION_MUZEI_ART_PROVIDER = "com.google.android.apps.muzei.api.MuzeiArtProvider"
        /**
         * Boolean extra that will be set to true when Muzei starts provider settings and setup
         * activities.
         *
         * Check for this extra in your activity if you need to adjust your UI depending on
         * whether or not the user came from Muzei.
         */
        const val EXTRA_FROM_MUZEI = "com.google.android.apps.muzei.api.extra.FROM_MUZEI_SETTINGS"
        private const val PREF_MAX_LOADED_ARTWORK_ID = "maxLoadedArtworkId"
        private const val PREF_LAST_LOADED_TIME = "lastLoadTime"
        private const val PREF_RECENT_ARTWORK_IDS = "recentArtworkIds"

        private const val TABLE_NAME = "artwork"
    }

    /**
     * An identity all column projection mapping for artwork
     */
    private val allArtworkColumnProjectionMap = mapOf(
            BaseColumns._ID to BaseColumns._ID,
            ProviderContract.Artwork.TOKEN to ProviderContract.Artwork.TOKEN,
            ProviderContract.Artwork.TITLE to ProviderContract.Artwork.TITLE,
            ProviderContract.Artwork.BYLINE to ProviderContract.Artwork.BYLINE,
            ProviderContract.Artwork.ATTRIBUTION to ProviderContract.Artwork.ATTRIBUTION,
            ProviderContract.Artwork.PERSISTENT_URI to ProviderContract.Artwork.PERSISTENT_URI,
            ProviderContract.Artwork.WEB_URI to ProviderContract.Artwork.WEB_URI,
            ProviderContract.Artwork.METADATA to ProviderContract.Artwork.METADATA,
            ProviderContract.Artwork.DATA to ProviderContract.Artwork.DATA,
            ProviderContract.Artwork.DATE_ADDED to ProviderContract.Artwork.DATE_ADDED,
            ProviderContract.Artwork.DATE_MODIFIED to ProviderContract.Artwork.DATE_MODIFIED)

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var authority: String
    final override val contentUri: Uri by lazy {
        val context = context
                ?: throw IllegalStateException("getContentUri() should not be called before onCreate()")
        ProviderContract.getProviderClient(context, javaClass).contentUri
    }

    private val applyingBatch = ThreadLocal<Boolean>()
    private val changedUris = ThreadLocal<MutableSet<Uri>>()

    private fun applyingBatch(): Boolean {
        return applyingBatch.get() != null && applyingBatch.get()!!
    }

    private fun onOperationComplete() {
        val context = context ?: return
        val contentResolver = context.contentResolver
        for (uri in changedUris.get()!!) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Notified for batch change on $uri")
            }
            contentResolver.notifyChange(uri, null)
        }
    }

    final override val lastAddedArtwork: Artwork? get() = query(contentUri, null, null, null,
            "${BaseColumns._ID} DESC").use { data ->
        return if (data.moveToFirst()) Artwork.fromCursor(data) else null
    }

    final override fun addArtwork(artwork: Artwork): Uri? {
        return insert(contentUri, artwork.toContentValues())
    }

    final override fun addArtwork(artwork: Iterable<Artwork>): List<Uri> {
        val operations = ArrayList<ContentProviderOperation>()
        for (art in artwork) {
            operations.add(ContentProviderOperation.newInsert(contentUri)
                    .withValues(art.toContentValues())
                    .build())
        }
        val resultUris = ArrayList<Uri>(operations.size)
        try {
            val results = applyBatch(operations)
            for (result in results) {
                resultUris.add(result.uri)
            }
        } catch (e: OperationApplicationException) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "addArtwork failed", e)
            }
        }

        return resultUris
    }

    final override fun setArtwork(artwork: Artwork): Uri? {
        val operations = ArrayList<ContentProviderOperation>()
        operations.add(ContentProviderOperation.newInsert(contentUri)
                .withValues(artwork.toContentValues())
                .build())
        operations.add(ContentProviderOperation.newDelete(contentUri)
                .withSelection(BaseColumns._ID + " != ?", arrayOfNulls(1))
                .withSelectionBackReference(0, 0)
                .build())
        return try {
            val results = applyBatch(operations)
            results[0].uri
        } catch (e: OperationApplicationException) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "setArtwork failed", e)
            }
            null
        }
    }

    final override fun setArtwork(artwork: Iterable<Artwork>): List<Uri> {
        val operations = ArrayList<ContentProviderOperation>()
        for (art in artwork) {
            operations.add(ContentProviderOperation.newInsert(contentUri)
                    .withValues(art.toContentValues())
                    .build())
        }
        val currentTime = System.currentTimeMillis()
        val resultUris = ArrayList<Uri>(operations.size)
        try {
            val results = applyBatch(operations)
            for (result in results) {
                resultUris.add(result.uri)
            }
            val deleteOperations = ArrayList<ContentProviderOperation>()
            query(
                    contentUri,
                    arrayOf(BaseColumns._ID),
                    ProviderContract.Artwork.DATE_MODIFIED + "<?",
                    arrayOf(currentTime.toString()), null).use { data ->
                while (data.moveToNext()) {
                    val artworkUri = ContentUris.withAppendedId(contentUri,
                            data.getLong(0))
                    if (!resultUris.contains(artworkUri)) {
                        deleteOperations.add(ContentProviderOperation
                                .newDelete(artworkUri)
                                .build())
                    }
                }
            }
            if (deleteOperations.isNotEmpty()) {
                applyBatch(deleteOperations)
            }
        } catch (e: OperationApplicationException) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "setArtwork failed", e)
            }
        }

        return resultUris
    }

    /**
     * @suppress
     */
    @CallSuper
    override fun call(
            method: String,
            arg: String?,
            extras: Bundle?
    ): Bundle? {
        val context = context ?: return null
        val token = Binder.clearCallingIdentity()
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Received command $method with arg \"$arg\" and extras $extras")
        }
        try {
            when (method) {
                METHOD_GET_VERSION -> {
                    return Bundle().apply {
                        putInt(KEY_VERSION, com.google.android.apps.muzei.api.BuildConfig.API_VERSION)
                    }
                }
                METHOD_REQUEST_LOAD -> databaseHelper.readableDatabase.query(TABLE_NAME,
                        null, null, null, null, null, null, "1").use { data ->
                    onLoadRequested(data == null || data.count == 0)
                }
                METHOD_MARK_ARTWORK_INVALID -> query(Uri.parse(arg), null, null, null, null).use { data ->
                    if (data.moveToNext()) {
                        onInvalidArtwork(Artwork.fromCursor(data))
                    }
                }
                METHOD_MARK_ARTWORK_LOADED -> query(contentUri, null, null, null, null).use { data ->
                    val prefs = context.getSharedPreferences(authority, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    // See if we need to update the maxLoadedArtworkId
                    val currentMaxId = prefs.getLong(PREF_MAX_LOADED_ARTWORK_ID, 0L)
                    val loadedId = ContentUris.parseId(Uri.parse(arg))
                    if (loadedId > currentMaxId) {
                        editor.putLong(PREF_MAX_LOADED_ARTWORK_ID, loadedId)
                    }
                    // Update the last loaded time
                    editor.putLong(PREF_LAST_LOADED_TIME, System.currentTimeMillis())
                    // Update the list of recent artwork ids
                    val recentArtworkIds = prefs.getRecentIds(PREF_RECENT_ARTWORK_IDS)
                    // Remove the loadedId if it exists in the list already
                    recentArtworkIds.remove(loadedId)
                    // Then add the loadedId to the end of the list
                    recentArtworkIds.addLast(loadedId)
                    val maxSize = data.count.coerceIn(1, MAX_RECENT_ARTWORK)
                    while (recentArtworkIds.size > maxSize) {
                        removeAutoCachedFile(recentArtworkIds.removeFirst())
                    }
                    editor.putRecentIds(PREF_RECENT_ARTWORK_IDS, recentArtworkIds)
                    editor.apply()
                }
                METHOD_GET_LOAD_INFO -> {
                    val prefs = context.getSharedPreferences(authority, Context.MODE_PRIVATE)
                    return Bundle().apply {
                        putLong(KEY_MAX_LOADED_ARTWORK_ID, prefs.getLong(PREF_MAX_LOADED_ARTWORK_ID, 0L))
                        putLong(KEY_LAST_LOADED_TIME, prefs.getLong(PREF_LAST_LOADED_TIME, 0L))
                        putString(KEY_RECENT_ARTWORK_IDS, prefs.getString(PREF_RECENT_ARTWORK_IDS, ""))
                    }.also {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "For $METHOD_GET_LOAD_INFO returning $it")
                        }
                    }
                }
                METHOD_GET_DESCRIPTION -> {
                    return Bundle().apply {
                        putString(KEY_DESCRIPTION, getDescription())
                    }
                }
                METHOD_GET_COMMANDS -> query(Uri.parse(arg), null, null, null, null).use { data ->
                    if (data.moveToNext()) {
                        return Bundle().apply {
                            val userCommands = getCommands(Artwork.fromCursor(data))
                            val commandsSerialized = JSONArray()
                            for (command in userCommands) {
                                commandsSerialized.put(command.serialize())
                            }
                            putString(KEY_COMMANDS, commandsSerialized.toString())
                        }.also {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "For $METHOD_GET_COMMANDS returning $it")
                            }
                        }
                    }
                }
                METHOD_TRIGGER_COMMAND -> if (extras != null) {
                    query(Uri.parse(arg), null, null, null, null).use { data ->
                        if (data.moveToNext()) {
                            onCommand(Artwork.fromCursor(data), extras.getInt(KEY_COMMAND))
                        }
                    }
                }
                METHOD_OPEN_ARTWORK_INFO -> query(Uri.parse(arg), null, null, null, null).use { data ->
                    if (data.moveToNext()) {
                        return Bundle().apply {
                            @Suppress("DEPRECATION")
                            val success = openArtworkInfo(Artwork.fromCursor(data))
                            putBoolean(KEY_OPEN_ARTWORK_INFO_SUCCESS, success)
                        }.also {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "For $METHOD_OPEN_ARTWORK_INFO returning $it")
                            }
                        }
                    }
                }
                METHOD_GET_ARTWORK_INFO -> query(Uri.parse(arg), null, null, null, null).use { data ->
                    if (data.moveToNext()) {
                        return Bundle().apply {
                            val artworkInfo = getArtworkInfo(Artwork.fromCursor(data))
                            putParcelable(KEY_GET_ARTWORK_INFO, artworkInfo)
                        }.also {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "For $METHOD_GET_ARTWORK_INFO returning $it")
                            }
                        }
                    }
                }
            }
            return null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Callback method when the user has viewed all of the available artwork. This should be used
     * as a cue to load more artwork so that the user has a constant stream of new artwork.
     *
     * Muzei will always prefer to show unseen artwork, but will automatically cycle through all
     * of the available artwork if no new artwork is found (i.e., if you don't load new artwork
     * after receiving this callback).
     *
     * @param initial true when there is no artwork available, such as is the case when this is
     * the initial load of this MuzeiArtProvider.
     */
    abstract fun onLoadRequested(initial: Boolean)

    /**
     * Called when Muzei failed to load the given artwork, usually due to an incompatibility
     * in supported image format. The default behavior is to delete the artwork.
     *
     * If you only support a single artwork, you should use this callback as an opportunity
     * to provide an alternate version of the artwork or a backup image to avoid repeatedly
     * loading the same artwork, just to mark it as invalid and be left with no valid artwork.
     *
     * @param artwork Artwork that Muzei has failed to load
     */
    open fun onInvalidArtwork(artwork: Artwork) {
        val artworkUri = ContentUris.withAppendedId(contentUri, artwork.id)
        delete(artworkUri, null, null)
    }

    /**
     * The longer description for the current state of this MuzeiArtProvider. For example,
     * 'Popular photos tagged "landscape"'). The default implementation returns the
     * `android:description` element of the provider element in the manifest.
     *
     * @return A longer description to be displayed alongside the label of the provider.
     */
    open fun getDescription(): String {
        val context = context ?: return ""
        return try {
            @SuppressLint("InlinedApi")
            val info = context.packageManager.getProviderInfo(
                    ComponentName(context, javaClass),
                    PackageManager.MATCH_DISABLED_COMPONENTS)
            if (info.descriptionRes != 0) context.getString(info.descriptionRes) else ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /**
     * Retrieve the list of commands available for the given artwork.
     *
     * @param artwork The associated artwork that can be used to customize the list of available
     * commands.
     * @return A List of [commands][UserCommand] that the user can trigger.
     * @see onCommand
     */
    open fun getCommands(artwork: Artwork): List<UserCommand> {
        return ArrayList()
    }

    /**
     * Callback method indicating that the user has selected a command.
     *
     * @param artwork The artwork at the time when this command was triggered.
     * @param id the ID of the command the user has chosen.
     * @see getCommands
     */
    open fun onCommand(artwork: Artwork, id: Int) {
    }

    /**
     * Callback when the user wishes to see more information about the given artwork. The default
     * implementation opens the [web uri][ProviderContract.Artwork.WEB_URI] of the artwork.
     *
     * @param artwork The artwork the user wants to see more information about.
     * @return True if the artwork info was successfully opened.
     */
    @Deprecated("Override getArtworkInfo to return a PendingIntent that starts " +
            "your artwork info. This method will still be called on devices that " +
            "have an older version of Muzei installed.")
    open fun openArtworkInfo(artwork: Artwork): Boolean {
        val context = context ?: return false
        if (artwork.webUri != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, artwork.webUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "Could not open ${artwork.webUri}, artwork info for " +
                            ContentUris.withAppendedId(contentUri, artwork.id), e)
                }
            }
        }
        return false
    }

    /**
     * Callback when the user wishes to see more information about the given artwork. The default
     * implementation constructs a [PendingIntent] to the
     * [web uri][ProviderContract.Artwork.WEB_URI] of the artwork.
     *
     * @param artwork The artwork the user wants to see more information about.
     * @return A [PendingIntent] generally constructed with
     * [PendingIntent.getActivity].
     */
    open fun getArtworkInfo(artwork: Artwork): PendingIntent? {
        if (artwork.webUri != null && context != null) {
            val intent = Intent(Intent.ACTION_VIEW, artwork.webUri)
            return PendingIntent.getActivity(context, 0, intent, 0)
        }
        return null
    }

    /**
     * @suppress
     */
    @CallSuper
    override fun onCreate(): Boolean {
        authority = contentUri.authority!!
        val databaseName = authority.substring(authority.lastIndexOf('.') + 1)
        databaseHelper = DatabaseHelper(context!!, databaseName)
        return true
    }

    /**
     * @suppress
     */
    override fun query(uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor {
        val contentResolver = context?.contentResolver
                ?: throw IllegalStateException("Called query() before onCreate()")
        val qb = SQLiteQueryBuilder().apply {
            tables = TABLE_NAME
            projectionMap = allArtworkColumnProjectionMap
            isStrict = true
        }
        val db = databaseHelper.readableDatabase
        if (uri != contentUri) {
            // Appends "_ID = <id>" to the where clause, so that it selects the single artwork
            qb.appendWhere("${BaseColumns._ID}=${uri.lastPathSegment}")
        }
        val orderBy = if (sortOrder.isNullOrEmpty())
            "${ProviderContract.Artwork.DATE_ADDED} DESC"
        else
            sortOrder
        val c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null)
        c.setNotificationUri(contentResolver, uri)
        return c
    }

    /**
     * @suppress
     */
    override fun getType(uri: Uri): String {
        return if (uri == contentUri) {
            "vnd.android.cursor.dir/vnd.$authority.$TABLE_NAME"
        } else {
            "vnd.android.cursor.item/vnd.$authority.$TABLE_NAME"
        }
    }

    /**
     * @suppress
     */
    @Throws(OperationApplicationException::class)
    override fun applyBatch(
            operations: ArrayList<ContentProviderOperation>
    ): Array<ContentProviderResult> {
        changedUris.set(HashSet())
        val db = databaseHelper.readableDatabase
        val results: Array<ContentProviderResult>
        db.beginTransaction()
        try {
            applyingBatch.set(true)
            results = super.applyBatch(operations)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            applyingBatch.set(false)
            onOperationComplete()
        }
        return results
    }

    /**
     * @suppress
     */
    override fun bulkInsert(uri: Uri, values: Array<ContentValues>): Int {
        changedUris.set(HashSet())
        val db = databaseHelper.readableDatabase
        val numberInserted: Int
        db.beginTransaction()
        try {
            applyingBatch.set(true)
            numberInserted = super.bulkInsert(uri, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            applyingBatch.set(false)
            onOperationComplete()
        }
        return numberInserted
    }

    /**
     * @suppress
     */
    override fun insert(uri: Uri, initialValues: ContentValues?): Uri? {
        val values = initialValues ?: ContentValues()
        val context = context ?: throw IllegalStateException("Called insert() before onCreate()")
        if (values.containsKey(ProviderContract.Artwork.TOKEN)) {
            val token = values.getAsString(ProviderContract.Artwork.TOKEN)
            if (token.isNullOrEmpty()) {
                // Treat empty strings as null
                if (token != null) {
                    if (Log.isLoggable(TAG, Log.INFO)) {
                        Log.i(TAG, "${ProviderContract.Artwork.TOKEN} must be non-empty if included")
                    }
                }
                values.remove(token)
            } else {
                query(contentUri, null,
                        "${ProviderContract.Artwork.TOKEN}=?",
                        arrayOf(token), null).use { existingData ->
                    if (existingData.moveToFirst()) {
                        // If there's already a row with the same token, update it rather than
                        // inserting a new row

                        // But first check whether there's actually anything changing
                        val title = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.TITLE))
                        val byline = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.BYLINE))
                        val attribution = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.ATTRIBUTION))
                        val persistentUri = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.PERSISTENT_URI))
                        val webUri = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.WEB_URI))
                        val metadata = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.METADATA))
                        val noChange =
                                title == values.getAsString(ProviderContract.Artwork.TITLE) &&
                                byline == values.getAsString(ProviderContract.Artwork.BYLINE) &&
                                attribution == values.getAsString(ProviderContract.Artwork.ATTRIBUTION) &&
                                persistentUri == values.getAsString(ProviderContract.Artwork.PERSISTENT_URI) &&
                                webUri == values.getAsString(ProviderContract.Artwork.WEB_URI) &&
                                metadata == values.getAsString(ProviderContract.Artwork.METADATA)
                        val id = existingData.getLong(existingData.getColumnIndex(BaseColumns._ID))
                        val updateUri = ContentUris.withAppendedId(contentUri, id)
                        if (noChange) {
                            // Just update the DATE_MODIFIED and don't send a notifyChange()
                            values.clear()
                            values.put(ProviderContract.Artwork.DATE_MODIFIED,
                                    System.currentTimeMillis())
                            val db = databaseHelper.writableDatabase
                            db.update(TABLE_NAME, values, "${BaseColumns._ID}=?",
                                    arrayOf(id.toString()))
                        } else {
                            // Do a full update
                            update(updateUri, values, null, null)
                        }
                        return updateUri
                    }
                }
            }
        }
        val now = System.currentTimeMillis()
        values.put(ProviderContract.Artwork.DATE_ADDED, now)
        values.put(ProviderContract.Artwork.DATE_MODIFIED, now)
        val db = databaseHelper.writableDatabase
        db.beginTransaction()
        val rowId = db.insert(TABLE_NAME,
                ProviderContract.Artwork.DATE_ADDED, values)
        if (rowId <= 0) {
            // Insert failed, not much we can do about that
            db.endTransaction()
            return null
        }
        // Add the DATA column pointing at the correct location
        val hasPersistentUri = values.containsKey(ProviderContract.Artwork.PERSISTENT_URI) &&
                !values.getAsString(ProviderContract.Artwork.PERSISTENT_URI).isNullOrEmpty()
        val directory = if (hasPersistentUri) {
            File(context.cacheDir, "muzei_$authority")
        } else {
            File(context.filesDir, "muzei_$authority")
        }

        directory.mkdirs()
        val artwork = File(directory, rowId.toString())
        db.update(TABLE_NAME, ContentValues().apply {
            put(ProviderContract.Artwork.DATA, artwork.absolutePath)
        }, "${BaseColumns._ID}=$rowId", null)
        db.setTransactionSuccessful()
        db.endTransaction()
        // Creates a URI with the artwork ID pattern and the new row ID appended to it.
        val artworkUri = ContentUris.withAppendedId(contentUri, rowId)
        if (applyingBatch()) {
            changedUris.get()!!.add(artworkUri)
        } else {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Notified for insert on $artworkUri")
            }
            context.contentResolver.notifyChange(artworkUri, null)
        }
        return artworkUri
    }

    /**
     * @suppress
     */
    override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<String>?
    ): Int {
        val db = databaseHelper.writableDatabase
        val count: Int
        var finalWhere = selection
        if (contentUri != uri) {
            finalWhere = "${BaseColumns._ID} = ${uri.lastPathSegment}"
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null) {
                finalWhere = "$finalWhere AND $selection"
            }
        }
        // Delete all of the files associated with the rows being deleted
        query(contentUri, arrayOf(ProviderContract.Artwork.DATA),
                finalWhere, selectionArgs, null).use { rowsToDelete ->
            while (rowsToDelete.moveToNext()) {
                val fileName = rowsToDelete.getString(0)
                val file = if (fileName != null) File(fileName) else null
                if (file != null && file.exists()) {
                    if (!file.delete()) {
                        if (Log.isLoggable(TAG, Log.INFO)) {
                            Log.i(TAG, "Unable to delete $file")
                        }
                    }
                }
            }
        }
        // Then delete the rows themselves
        count = db.delete(TABLE_NAME, finalWhere, selectionArgs)
        val context = context ?: return count
        if (count > 0) {
            if (applyingBatch()) {
                changedUris.get()!!.add(uri)
            } else {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Notified for delete on $uri")
                }
                context.contentResolver.notifyChange(uri, null)
            }
        }
        return count
    }

    /**
     * @suppress
     */
    override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
    ): Int {
        if (values == null) {
            return 0
        }
        val db = databaseHelper.writableDatabase
        val count: Int
        var finalWhere = selection
        if (contentUri != uri) {
            finalWhere = "${BaseColumns._ID} = ${uri.lastPathSegment}"
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null) {
                finalWhere = "$finalWhere AND $selection"
            }
        }
        // TOKEN, DATA and DATE_ADDED cannot be changed
        values.remove(ProviderContract.Artwork.TOKEN)
        values.remove(ProviderContract.Artwork.DATA)
        values.remove(ProviderContract.Artwork.DATE_ADDED)
        // Update the DATE_MODIFIED
        values.put(ProviderContract.Artwork.DATE_MODIFIED, System.currentTimeMillis())
        count = db.update(TABLE_NAME, values, finalWhere, selectionArgs)
        val context = context ?: return count
        if (count > 0) {
            if (applyingBatch()) {
                changedUris.get()!!.add(uri)
            } else {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Notified for update on $uri")
                }
                context.contentResolver.notifyChange(uri, null)
            }
        }
        return count
    }

    private fun removeAutoCachedFile(artworkId: Long) {
        val artworkUri = ContentUris.withAppendedId(contentUri, artworkId)
        query(artworkUri, null, null, null, null).use { data ->
            if (!data.moveToFirst()) {
                return
            }
            val artwork = Artwork.fromCursor(data)

            if (artwork.persistentUri != null && artwork.data.exists()) {
                artwork.data.delete()
            }
        }
    }

    /**
     * Called every time an image is loaded (even if there is a cached
     * image available). This gives you an opportunity to circumvent the
     * typical loading process and remove previously cached artwork on
     * demand. The default implementation always returns `true`.
     *
     * In most cases, you should proactively delete Artwork that you know
     * is not valid rather than wait for this callback since at this point
     * the user is specifically waiting for the image to appear.
     *
     * The MuzeiArtProvider will call [onInvalidArtwork] for you
     * if you return `false` - there is no need to call this
     * manually from within this method.
     * @param artwork The Artwork to confirm
     * @return Whether the Artwork is valid and should be loaded
     */
    open fun isArtworkValid(artwork: Artwork): Boolean {
        return true
    }

    /**
     * Provide an InputStream to the binary data associated with artwork that has not yet been
     * cached. The default implementation retrieves the image from the
     * [persistent URI][Artwork.persistentUri] and supports URI schemes in the following
     * formats:
     *
     *  * `content://...`.
     *  * `android.resource://...`.
     *  * `file://...`.
     *  * `file:///android_asset/...`.
     *  * `http://...` or `https://...`.
     *
     * Throwing any exception other than an [IOException] will be considered a permanent
     * error that will result in a call to [onInvalidArtwork].
     *
     * @param artwork The Artwork to open
     * @return A valid [InputStream] for the artwork's image
     * @throws IOException if an error occurs while opening the image. The request will be retried
     * automatically.
     */
    @Throws(IOException::class)
    open fun openFile(artwork: Artwork): InputStream {
        val context = context ?: throw IOException()
        val persistentUri = artwork.persistentUri
                ?: throw IllegalStateException("Got null persistent URI for $artwork. " +
                        "The default implementation of openFile() requires a persistent URI. " +
                        "You must override this method or write the binary data directly to " +
                        "the artwork's data file.")
        val scheme = persistentUri.scheme ?: throw IOException("Uri had no scheme")
        return (if (ContentResolver.SCHEME_CONTENT == scheme || ContentResolver.SCHEME_ANDROID_RESOURCE == scheme) {
            context.contentResolver.openInputStream(persistentUri)
        } else if (ContentResolver.SCHEME_FILE == scheme) {
            val segments = persistentUri.pathSegments
            if (segments != null && segments.size > 1
                    && "android_asset" == segments[0]) {
                val assetPath = StringBuilder()
                for (i in 1 until segments.size) {
                    if (i > 1) {
                        assetPath.append("/")
                    }
                    assetPath.append(segments[i])
                }
                context.assets.open(assetPath.toString())
            } else {
                FileInputStream(File(persistentUri.path!!))
            }
        } else if ("http" == scheme || "https" == scheme) {
            val url = URL(persistentUri.toString())
            val urlConnection = url.openConnection() as HttpURLConnection
            val responseCode = urlConnection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP error response $responseCode")
            }
            urlConnection.inputStream
        } else {
            throw FileNotFoundException("Unsupported scheme $scheme for $persistentUri")
        }) ?: throw FileNotFoundException("Null input stream for URI: $persistentUri")
    }

    /**
     * @suppress
     */
    @Throws(FileNotFoundException::class)
    override fun openFile(
            uri: Uri,
            mode: String
    ): ParcelFileDescriptor? {
        val artwork = query(uri, null, null, null, null).use { data ->
            if (!data.moveToFirst()) {
                throw FileNotFoundException("Could not get persistent uri for $uri")
            }
            Artwork.fromCursor(data)
        }
        if (!isArtworkValid(artwork)) {
            onInvalidArtwork(artwork)
            throw SecurityException("Artwork $artwork was marked as invalid")
        }
        if (!artwork.data.exists() && mode == "r") {
            // Download the image from the persistent URI for read-only operations
            // rather than throw a FileNotFoundException
            val directory = artwork.data.parentFile
            // Ensure that the parent directory of the artwork exists
            // as otherwise FileOutputStream will fail
            if (!directory!!.exists() && !directory.mkdirs()) {
                throw FileNotFoundException("Unable to create directory $directory for $artwork")
            }
            try {
                openFile(artwork).use { input ->
                    FileOutputStream(artwork.data).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                if (e !is IOException) {
                    if (Log.isLoggable(TAG, Log.INFO)) {
                        Log.i(TAG, "Unable to open artwork $artwork for $uri", e)
                    }
                    onInvalidArtwork(artwork)
                }
                // Delete the file in cases of an error so that we will try again from scratch next time.
                if (artwork.data.exists() && !artwork.data.delete()) {
                    if (Log.isLoggable(TAG, Log.INFO)) {
                        Log.i(TAG, "Error deleting partially downloaded file after error", e)
                    }
                }
                throw FileNotFoundException("Could not download artwork $artwork for $uri: ${e.message}")
            }
        }
        return ParcelFileDescriptor.open(artwork.data, ParcelFileDescriptor.parseMode(mode))
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    internal class DatabaseHelper(
            context: Context,
            databaseName: String
    ) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
        companion object {
            private const val DATABASE_VERSION = 1
        }

        /**
         * Creates the underlying database with table name and column names taken from the
         * MuzeiContract class.
         */
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + ProviderContract.Artwork.TOKEN + " TEXT,"
                    + ProviderContract.Artwork.TITLE + " TEXT,"
                    + ProviderContract.Artwork.BYLINE + " TEXT,"
                    + ProviderContract.Artwork.ATTRIBUTION + " TEXT,"
                    + ProviderContract.Artwork.PERSISTENT_URI + " TEXT,"
                    + ProviderContract.Artwork.WEB_URI + " TEXT,"
                    + ProviderContract.Artwork.METADATA + " TEXT,"
                    + ProviderContract.Artwork.DATA + " TEXT,"
                    + ProviderContract.Artwork.DATE_ADDED + " INTEGER NOT NULL,"
                    + ProviderContract.Artwork.DATE_MODIFIED + " INTEGER NOT NULL);")
        }

        /**
         * Upgrades the database.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}
