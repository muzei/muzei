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

package com.google.android.apps.muzei.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;

/**
 * Provides access to a the most recent artwork
 */
public class MuzeiProvider extends ContentProvider {
    private static final String TAG = MuzeiProvider.class.getSimpleName();
    /**
     * Shared Preference key for the current artwork location used in openFile
     */
    private static final String CURRENT_ARTWORK_LOCATION = "CURRENT_ARTWORK_LOCATION";
    /**
     * The incoming URI matches the ARTWORK URI pattern
     */
    private static final int ARTWORK = 1;
    /**
     * The database that the provider uses as its underlying data store
     */
    private static final String DATABASE_NAME = "muzei.db";
    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 1;
    /**
     * A UriMatcher instance
     */
    private static final UriMatcher uriMatcher = MuzeiProvider.buildUriMatcher();
    /**
     * An identity all column projection mapping
     */
    private final HashMap<String, String> allColumnProjectionMap = MuzeiProvider.buildAllColumnProjectionMap();
    /**
     * Handle to a new DatabaseHelper.
     */
    private DatabaseHelper databaseHelper;

    /**
     * Save the current artwork's local location so that third parties can use openFile to retrieve the already
     * downloaded artwork rather than re-download it
     *
     * @param context        Any valid Context
     * @param currentArtwork File pointing to the current artwork
     */
    public static boolean saveCurrentArtworkLocation(Context context, File currentArtwork) {
        if (currentArtwork == null || !currentArtwork.exists()) {
            Log.w(TAG, "File " + currentArtwork + " is not valid");
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putString(CURRENT_ARTWORK_LOCATION, currentArtwork.getAbsolutePath()).commit();
        return true;
    }

    /**
     * Creates and initializes a column project for all columns
     *
     * @return The all column projection map
     */
    private static HashMap<String, String> buildAllColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TITLE,
                MuzeiContract.Artwork.COLUMN_NAME_TITLE);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_BYLINE,
                MuzeiContract.Artwork.COLUMN_NAME_BYLINE);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TOKEN,
                MuzeiContract.Artwork.COLUMN_NAME_TOKEN);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT,
                MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes the URI matcher
     *
     * @return the URI Matcher
     */
    private static UriMatcher buildUriMatcher() {
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Artwork.TABLE_NAME,
                MuzeiProvider.ARTWORK);
        return matcher;
    }

    @Override
    public int delete(final Uri uri, final String where, final String[] whereArgs) {
        throw new UnsupportedOperationException("Deletes are not supported");
    }

    @Override
    public String getType(final Uri uri) {
        /**
         * Chooses the MIME type based on the incoming URI pattern
         */
        switch (MuzeiProvider.uriMatcher.match(uri)) {
            case ARTWORK:
                // If the pattern is for artwork, returns the general content type.
                return MuzeiContract.Artwork.CONTENT_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        // Validates the incoming URI. Only the full provider URI is allowed for inserts.
        if (MuzeiProvider.uriMatcher.match(uri) != MuzeiProvider.ARTWORK)
            throw new IllegalArgumentException("Unknown URI " + uri);
        if (values == null) {
            throw new IllegalArgumentException("Invalid ContentValues: must not be null");
        }
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        final int countUpdated = db.update(MuzeiContract.Artwork.TABLE_NAME,
                values, BaseColumns._ID + "=1", null);
        if (countUpdated != 1) {
            long rowId = db.insert(MuzeiContract.Artwork.TABLE_NAME,
                    MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI, values);
            if (rowId <= 0) {
                throw new SQLException("Failed to insert row into " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(MuzeiContract.Artwork.CONTENT_URI, null);
        getContext().sendBroadcast(new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
        return MuzeiContract.Artwork.CONTENT_URI;
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider#onCreate()
     */
    @Override
    public boolean onCreate() {
        databaseHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs,
                        final String sortOrder) {
        // Validates the incoming URI. Only the full provider URI is allowed for queries.
        if (MuzeiProvider.uriMatcher.match(uri) != MuzeiProvider.ARTWORK)
            throw new IllegalArgumentException("Unknown URI " + uri);
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(MuzeiContract.Artwork.TABLE_NAME);
        qb.setProjectionMap(allColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder, null);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(final Uri uri, final String mode) throws FileNotFoundException {
        // Validates the incoming URI. Only the full provider URI is allowed for openFile
        if (MuzeiProvider.uriMatcher.match(uri) != MuzeiProvider.ARTWORK) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("Invalid mode for opening file: " + mode + ". Only 'r' is valid");
        }
        String currentArtworkLocation = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(CURRENT_ARTWORK_LOCATION, null);
        if (currentArtworkLocation == null) {
            throw new FileNotFoundException("No artwork image is set");
        }
        File file = new File(currentArtworkLocation);
        if (!file.exists()) {
            throw new FileNotFoundException("File " + currentArtworkLocation + " does not exist");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Updates are not allowed: insert does an insert or update operation");
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    static class DatabaseHelper extends SQLiteOpenHelper {
        /**
         * Creates a new DatabaseHelper
         *
         * @param context context of this database
         */
        DatabaseHelper(final Context context) {
            super(context, MuzeiProvider.DATABASE_NAME, null, MuzeiProvider.DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with table name and column names taken from the MuzeiContract class.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + MuzeiContract.Artwork.TABLE_NAME + " (" + BaseColumns._ID
                    + " INTEGER PRIMARY KEY AUTOINCREMENT," + MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI
                    + " TEXT," + MuzeiContract.Artwork.COLUMN_NAME_TITLE + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_BYLINE + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_TOKEN + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT + " TEXT);");
        }

        /**
         * Demonstrates that the provider must consider what happens when the underlying database is changed. Note that
         * this currently just destroys and recreates the database - should upgrade in place
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + MuzeiContract.Artwork.TABLE_NAME);
            onCreate(db);
        }
    }
}