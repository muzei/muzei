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
     * Artwork table contract
     */
    public static final class Artwork implements BaseColumns {
        /**
         * Column name of the artwork image URI. In almost all cases you should use ContentProvider.openFile to
         * retrieve the already downloaded artwork
         * <p/>
         * Type: TEXT (URI)
         * </P>
         */
        public static final String COLUMN_NAME_IMAGE_URI = "imageUri";
        /**
         * Column name for the artwork's title
         * <p/>
         * Type: TEXT
         * </P>
         */
        public static final String COLUMN_NAME_TITLE = "title";
        /**
         * Column name for the artwork's byline (e.g. author and date)
         * <p/>
         * Type: TEXT
         * </P>
         */
        public static final String COLUMN_NAME_BYLINE = "byline";
        /**
         * Column name for the artwork's opaque application-specific identifier
         * <p/>
         * Type: TEXT
         * </P>
         */
        public static final String COLUMN_NAME_TOKEN = "token";
        /**
         * Column name for the artwork's view Intent, encoded via Intent.toUri(Intent.URI_INTENT_SCHEME) and can
         * be decoded via Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME)
         * <p/>
         * Type: TEXT (Intent encoded URI)
         * </P>
         */
        public static final String COLUMN_NAME_VIEW_INTENT = "viewIntent";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of contractions.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.artwork";
        /**
         * The table name offered by this provider
         */
        public static final String TABLE_NAME = "artwork";

        /**
         * This class cannot be instantiated
         */
        private Artwork() {
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse(MuzeiContract.SCHEME + MuzeiContract.AUTHORITY
                + "/" + Artwork.TABLE_NAME);
        /**
         * Intent action that will be broadcast when the artwork is changed. This happens immediately after the
         * ContentProvider is updated with data and should be considered the signal that you can retrieve the new
         * artwork
         */
        public static final String ACTION_ARTWORK_CHANGED = "com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED";

        /**
         * Returns the current Muzei Artwork
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
         * @param context Context to retrieve a ContentResolver
         * @return A Bitmap of the current artwork or possibly null
         * @throws FileNotFoundException
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
