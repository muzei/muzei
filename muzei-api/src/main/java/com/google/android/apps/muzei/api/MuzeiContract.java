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

package com.google.android.apps.muzei.api;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Looper;
import android.provider.BaseColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.annotation.StringDef;
import androidx.annotation.WorkerThread;

/**
 * Contract between Muzei and applications, containing the definitions for all supported URIs and
 * columns as well as helper methods to make it easier to work with the provided data.
 */
public class MuzeiContract {
    /**
     * Only Muzei can write to the Muzei ContentProvider - this is a signature permission that no
     * other app can hold.
     */
    public static final String WRITE_PERMISSION = "com.google.android.apps.muzei.WRITE_PROVIDER";
    /**
     * Base authority for this content provider
     */
    public static final String AUTHORITY = "com.google.android.apps.muzei";
    /**
     * The scheme part for this provider's URI
     */
    private static final String SCHEME = "content://";

    /**
     * This class cannot be instantiated
     */
    private MuzeiContract() {
    }

    /**
     * Constants and helper methods for the Artwork table, providing access to the current artwork.
     *
     * <p>The Artwork table contains the details of the artwork that has been loaded by Muzei.
     * It also provides direct access to the cached image already downloaded by Muzei,
     * ensuring that you do not need to do additional networks requests or have internet access
     * when retrieving previously loaded artwork.
     *
     * <h3>Working with the Artwork table</h3>
     *
     * Querying {@link #CONTENT_URI} will return either zero rows (in cases where the user has never
     * activated Muzei before) or a row for each previously loaded artwork with all of the details needed to create an
     * {@link com.google.android.apps.muzei.api.Artwork Artwork} object. The helper method
     * {@link #getCurrentArtwork(Context)} does builds an Artwork object for the most recent artwork, although
     * you can certainly query the {@link #CONTENT_URI} directly and either use the columns directly or
     * use {@link com.google.android.apps.muzei.api.Artwork#fromCursor(android.database.Cursor) Artwork.fromCursor(Cursor)}
     * to parse the Cursor for you.
     *
     * <p>If instead you use {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream(Uri)},
     * you'll get an InputStream for the cached image Bitmap. This can
     * then be passed to {@link BitmapFactory#decodeStream(java.io.InputStream) BitmapFactory.decodeStream(InputStream)} or
     * similar methods to retrieve the Bitmap itself. The helper method
     * {@link #getCurrentArtworkBitmap(Context)} does this operation, although note
     * that this may return a very large Bitmap so following the
     * <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently training</a>
     * advice is highly suggested.
     *
     * <h3>Listening for changes</h3>
     *
     * Just like any {@link android.content.ContentProvider ContentProvider}, listening for changes can be done by
     * implementing a {@link android.database.ContentObserver ContentObserver} on {@link #CONTENT_URI} or by using
     * any of the helper classes such as {@link android.content.CursorLoader CursorLoader} to listen for updates.
     *
     * <p>However, if you want to receive updates while in the background and do not want to maintain
     * a constantly running {@link android.database.ContentObserver ContentObserver}, you can instead listen for the
     * {@link #ACTION_ARTWORK_CHANGED} broadcast, sent out immediately after an update is made:
     *
     * <pre class="prettyprint">
     * &lt;receiver android:name=".ExampleArtworkUpdateReceiver"&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action android:name="com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED" /&gt;
     *     &lt;/intent-filter&gt;
     * &lt;/receiver&gt;
     * </pre>
     *
     * No data is sent alongside the broadcast, but this can be used to kick off an
     * {@link android.app.IntentService IntentService} to retrieve the latest artwork or start
     * other processing.
     */
    public static final class Artwork implements BaseColumns {
        /**
         * The set of valid font types to use to display artwork meta info.
         *
         * @see #COLUMN_NAME_META_FONT
         * @see #META_FONT_TYPE_DEFAULT
         * @see #META_FONT_TYPE_ELEGANT
         */
        @Retention(RetentionPolicy.SOURCE)
        @StringDef({META_FONT_TYPE_DEFAULT, META_FONT_TYPE_ELEGANT})
        public @interface MetaFontType {}
        /**
         * The default font type for {@link #COLUMN_NAME_META_FONT}
         */
        public static final String META_FONT_TYPE_DEFAULT = "";
        /**
         * An elegant alternate font type for {@link #COLUMN_NAME_META_FONT}
         */
        public static final String META_FONT_TYPE_ELEGANT = "elegant";
        /**
         * Column name for the authority of the provider for this artwork.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_SOURCE_COMPONENT_NAME = "sourceComponentName";
        /**
         * Column name of the artwork image URI. In almost all cases you should use
         * {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream(CONTENT_URI)}
         * to retrieve the already downloaded artwork.
         * <p>Type: TEXT (URI)
         */
        public static final String COLUMN_NAME_IMAGE_URI = "imageUri";
        /**
         * Column name for the artwork's title.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_TITLE = "title";
        /**
         * Column name for the artwork's byline (such as author and date).
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_BYLINE = "byline";
        /**
         * Column name for the artwork's attribution info.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_ATTRIBUTION = "attribution";
        /**
         * Column name for the artwork's opaque application-specific identifier.
         * This is generally only useful to the app that published the artwork and should
         * not be relied upon by other apps.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_TOKEN = "token";
        /**
         * Column name for the artwork's view Intent, encoded via
         * {@link android.content.Intent#toUri(int) Intent.toUri(Intent.URI_INTENT_SCHEME)} and can
         * be decoded via
         * {@link android.content.Intent#parseUri(String, int) Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME)}
         * <p>Type: TEXT (Intent encoded URI)
         */
        public static final String COLUMN_NAME_VIEW_INTENT = "viewIntent";
        /**
         * Column name for the font type to use to display artwork meta info.
         * <p>Type: TEXT (one of {@link #META_FONT_TYPE_DEFAULT} or {@link #META_FONT_TYPE_ELEGANT})
         */
        public static final String COLUMN_NAME_META_FONT = "metaFont";
        /**
         * Column name for when this artwork was added.
         * This will be automatically added for you by Muzei.
         * <p>Type: LONG (in milliseconds)
         */
        public static final String COLUMN_NAME_DATE_ADDED = "date_added";
        /**
         * The MIME type of {@link #CONTENT_URI} providing artwork.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.artwork";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a single piece of artwork.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.android.apps.muzei.artwork";
        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = COLUMN_NAME_DATE_ADDED + " DESC";
        /**
         * The table name offered by this provider.
         */
        public static final String TABLE_NAME = "artwork";

        /**
         * This class cannot be instantiated
         */
        private Artwork() {
        }

        /**
         * The content:// style URL for this table. This is the main entry point for queries and for
         * opening an {@link java.io.InputStream InputStream} to the current artwork's image.
         * <p>
         * Apps can only {@link ContentResolver#query query} for artwork; only Muzei can write to the ContentProvider.
         *
         * @see #getCurrentArtwork
         * @see #getCurrentArtworkBitmap
         */
        @RequiresPermission.Write(@RequiresPermission(WRITE_PERMISSION))
        public static final Uri CONTENT_URI = Uri.parse(MuzeiContract.SCHEME + MuzeiContract.AUTHORITY
                + "/" + Artwork.TABLE_NAME);
        /**
         * Intent action that will be broadcast when the artwork is changed. This happens immediately after the
         * ContentProvider is updated with data and should be considered the signal that you can retrieve the new
         * artwork.
         */
        public static final String ACTION_ARTWORK_CHANGED = "com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED";

        /**
         * Returns the current Muzei {@link com.google.android.apps.muzei.api.Artwork Artwork}.
         *
         * @param context the context to retrieve a ContentResolver.
         *
         * @return the current {@link com.google.android.apps.muzei.api.Artwork Artwork}
         * or null if one could not be found.
         */
        public static com.google.android.apps.muzei.api.Artwork getCurrentArtwork(Context context) {
            ContentResolver contentResolver = context.getContentResolver();
            Cursor cursor = contentResolver.query(CONTENT_URI, null, null, null, null);
            try {
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                return com.google.android.apps.muzei.api.Artwork.fromCursor(cursor);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        /**
         * Naively gets the current artwork image without any subsampling or optimization for output size
         * <p>Note that this may return a very large Bitmap so following the
         * <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently training</a>
         * is highly recommended.
         *
         * @param context the context to retrieve a ContentResolver.
         *
         * @return A Bitmap of the current artwork or null if the image could not be decoded.
         *
         * @throws FileNotFoundException If no cached artwork image was found.
         */
        @WorkerThread
        public static Bitmap getCurrentArtworkBitmap(Context context) throws FileNotFoundException {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw new IllegalStateException("getCurrentArtworkBitmap cannot be called on the main thread");
            }
            ContentResolver contentResolver = context.getContentResolver();
            return BitmapFactory.decodeStream(contentResolver.openInputStream(CONTENT_URI));
        }
    }

    /**
     * Constants and helper methods for the Sources table, providing access to sources' information.
     */
    public static final class Sources implements BaseColumns {
        /**
         * Column name for the authority of the provider.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_COMPONENT_NAME = "component_name";
        /**
         * Column name for the flag indicating if the source is currently selected
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_NAME_IS_SELECTED = "selected";
        /**
         * Column name for the source's description.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_DESCRIPTION = "description";
        /**
         * Column name for the flag indicating if the source wants callbacks for network connectivity changes
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_NAME_WANTS_NETWORK_AVAILABLE = "network";
        /**
         * Column name for the flag indicating if the source supports a 'Next Artwork' action
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND = "supports_next_artwork";
        /**
         * Column name for the commands the source supports
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_COMMANDS = "commands";
        /**
         * The MIME type of {@link #CONTENT_URI} providing sources.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.source";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a single source.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.android.apps.muzei.source";
        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = Sources.COLUMN_NAME_IS_SELECTED + " DESC," +
                Sources.COLUMN_NAME_COMPONENT_NAME;
        /**
         * The table name offered by this provider.
         */
        public static final String TABLE_NAME = "sources";

        /**
         * This class cannot be instantiated
         */
        private Sources() {
        }

        /**
         * The content:// style URL for this table.
         * <p>
         * Apps can only {@link ContentResolver#query query} for source info; only Muzei can write to the
         * ContentProvider.
         */
        @RequiresPermission.Write(@RequiresPermission(WRITE_PERMISSION))
        public static final Uri CONTENT_URI = Uri.parse(MuzeiContract.SCHEME + MuzeiContract.AUTHORITY
                + "/" + Sources.TABLE_NAME);
        /**
         * Intent action that will be broadcast when the source info is changed. This happens immediately after the
         * ContentProvider is updated with data and should be considered the signal that you can retrieve the new
         * source info.
         */
        public static final String ACTION_SOURCE_CHANGED = "com.google.android.apps.muzei.ACTION_SOURCE_CHANGED";

        /**
         * Parse the commands found in the {@link #COLUMN_NAME_COMMANDS} field into a List of {@link UserCommand}s.
         *
         * @param commandsString The serialized commands found in {@link #COLUMN_NAME_COMMANDS}.
         *
         * @return A deserialized List of {@link UserCommand}s.
         */
        @NonNull
        public static List<UserCommand> parseCommands(String commandsString) {
            ArrayList<UserCommand> commands = new ArrayList<>();
            if (commandsString == null) {
                return commands;
            }
            try {
                JSONArray commandArray = new JSONArray(commandsString);
                for (int h=0; h<commandArray.length(); h++) {
                    commands.add(UserCommand.deserialize(commandArray.getString(h)));
                }
            } catch (JSONException e) {
                Log.e(MuzeiContract.Sources.class.getSimpleName(), "Error parsing commands from " + commandsString, e);
            }
            return commands;
        }
    }
}
