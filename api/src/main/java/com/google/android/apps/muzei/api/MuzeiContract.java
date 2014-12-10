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

import java.io.FileNotFoundException;

/**
 * Contract between Muzei and applications, containing the definitions for all supported URIs and
 * columns as well as helper methods to make it easier to work with the provided data.
 */
public class MuzeiContract {
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
     * <p>The Artwork table contains at most a single row with the details of the most recent
     * artwork. It also provides direct access to the cached image already downloaded by Muzei,
     * ensuring that you do not need to do additional networks requests or have internet access
     * when retrieving the latest artwork.
     *
     * <h3>Working with the Artwork table</h3>
     *
     * There's only one URI you need to care about: {@link #CONTENT_URI}.
     *
     * <p>Querying this URI will return either zero rows (in cases where the user has never
     * activated Muzei before) or one row containing all of the details needed to create an
     * {@link com.google.android.apps.muzei.api.Artwork Artwork} object. The helper method
     * {@link #getCurrentArtwork(Context)} does exactly this work for you, although
     * you can certainly query the {@link #CONTENT_URI} directly and either use the columns directly or
     * use {@link com.google.android.apps.muzei.api.Artwork#fromCursor(android.database.Cursor) Artwork.fromCursor(Cursor)}
     * to parse the Cursor for you.
     *
     * <p>If instead you use {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream(Uri)}
     * on the {@link #CONTENT_URI}, you'll get an InputStream for the cached image Bitmap. This can
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
         * Column name for the artwork's opaque application-specific identifier.
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
         * The MIME type of {@link #CONTENT_URI} providing artwork.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.artwork";
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
         */
        public static final Uri CONTENT_URI = Uri.parse(MuzeiContract.SCHEME + MuzeiContract.AUTHORITY
                + "/" + Artwork.TABLE_NAME);
        /**
         * Intent action that will be broadcast when the artwork is changed. This happens immediately after the
         * ContentProvider is updated with data and should be considered the signal that you can retrieve the new
         * artwork.
         */
        public static final String ACTION_ARTWORK_CHANGED = "com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED";

        /**
         * Returns the current Muzei {@link com.google.android.apps.muzei.api.Artwork Artwork}
         * @param context Context to retrieve a ContentResolver
         * @return the current Artwork or null if one could not be found
         */
        public static com.google.android.apps.muzei.api.Artwork getCurrentArtwork(Context context) {
            ContentResolver contentResolver = context.getContentResolver();
            Cursor cursor = contentResolver.query(CONTENT_URI, null, null, null, null);
            if (cursor == null) {
                return null;
            }
            try {
                if (!cursor.moveToFirst()) {
                    return null;
                }
                return com.google.android.apps.muzei.api.Artwork.fromCursor(cursor);
            } finally {
                cursor.close();
            }
        }

        /**
         * Naively gets the current artwork image without any subsampling or optimization for output size
         * <p>Note that this may return a very large Bitmap so following the
         * <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently training</a>
         * is highly recommended.
         * @param context Context to retrieve a ContentResolver
         * @return A Bitmap of the current artwork or null if the image could not be decoded
         * @throws FileNotFoundException If no cached artwork image was found
         */
        public static Bitmap getCurrentArtworkBitmap(Context context) throws FileNotFoundException {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw new IllegalStateException("getCurrentArtworkBitmap cannot be called on the main thread");
            }
            ContentResolver contentResolver = context.getContentResolver();
            return BitmapFactory.decodeStream(contentResolver.openInputStream(CONTENT_URI));
        }
    }
}
