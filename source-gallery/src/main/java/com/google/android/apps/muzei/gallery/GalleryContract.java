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

package com.google.android.apps.muzei.gallery;

import android.net.Uri;
import android.provider.BaseColumns;

/**
 * Contract for the photos loaded by the Gallery and their cached metadata
 */
public class GalleryContract {
    /**
     * Base authority for this content provider
     */
    static final String AUTHORITY = BuildConfig.GALLERY_AUTHORITY;
    /**
     * The scheme part for this provider's URI
     */
    private static final String SCHEME = "content://";

    /**
     * This class cannot be instantiated
     */
    private GalleryContract() {
    }


    public static final class ChosenPhotos implements BaseColumns {
        /**
         * Column name of the chosen photo's URI.
         * <p>Type: TEXT (URI)
         */
        public static final String COLUMN_NAME_URI = "uri";
        /**
         * The MIME type of {@link #CONTENT_URI} providing chosen photos.
         */
        static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.gallery.chosen_photos";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a single chosen photo.
         */
        static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.android.apps.muzei.gallery.chosen_photos";
        /**
         * The default sort order for this table
         */
        static final String DEFAULT_SORT_ORDER = BaseColumns._ID;
        /**
         * The table name offered by this provider.
         */
        static final String TABLE_NAME = "chosen_photos";

        /**
         * This class cannot be instantiated
         */
        private ChosenPhotos() {
        }

        /**
         * The content:// style URL for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse(GalleryContract.SCHEME + GalleryContract.AUTHORITY
                + "/" + ChosenPhotos.TABLE_NAME);
    }

    static final class MetadataCache implements BaseColumns {
        /**
         * Column name of the photo's URI.
         * <p>Type: TEXT (URI)
         */
        static final String COLUMN_NAME_URI = "uri";
        /**
         * Column name for when this photo was taken
         * <p>Type: LONG (in milliseconds)
         */
        static final String COLUMN_NAME_DATETIME = "datetime";
        /**
         * Column name for the photo's location.
         * <p>Type: TEXT
         */
        static final String COLUMN_NAME_LOCATION = "location";
        /**
         * The MIME type of {@link #CONTENT_URI} providing metadata.
         */
        static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.gallery.metadata_cache";
        /**
         * The default sort order for this table
         */
        static final String DEFAULT_SORT_ORDER = BaseColumns._ID + " DESC";
        /**
         * The table name offered by this provider.
         */
        static final String TABLE_NAME = "metadata_cache";

        /**
         * This class cannot be instantiated
         */
        private MetadataCache() {
        }

        /**
         * The content:// style URL for this table.
         */
        static final Uri CONTENT_URI = Uri.parse(GalleryContract.SCHEME + GalleryContract.AUTHORITY
                + "/" + MetadataCache.TABLE_NAME);
    }
}
