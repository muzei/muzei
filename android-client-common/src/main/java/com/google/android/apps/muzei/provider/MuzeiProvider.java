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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.v4.os.UserManagerCompat;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides access to a the most recent artwork
 */
public class MuzeiProvider extends ContentProvider {
    private static final String TAG = "MuzeiProvider";
    /**
     * Maximum number of previous artwork to keep per source, with the exception of artwork that
     * has a persisted permission.
     * @see #cleanupCachedFiles
     */
    private static final int MAX_CACHE_SIZE = 10;
    /**
     * The incoming URI matches the ARTWORK URI pattern
     */
    private static final int ARTWORK = 1;
    /**
     * The incoming URI matches the ARTWORK ID URI pattern
     */
    private static final int ARTWORK_ID = 2;
    /**
     * The incoming URI matches the SOURCE URI pattern
     */
    private static final int SOURCES = 3;
    /**
     * The incoming URI matches the SOURCE ID URI pattern
     */
    private static final int SOURCE_ID = 4;
    /**
     * The database that the provider uses as its underlying data store
     */
    private static final String DATABASE_NAME = "muzei.db";
    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 3;
    /**
     * A UriMatcher instance
     */
    private static final UriMatcher uriMatcher = MuzeiProvider.buildUriMatcher();
    /**
     * An identity all column projection mapping for Artwork
     */
    private final HashMap<String, String> allArtworkColumnProjectionMap =
            MuzeiProvider.buildAllArtworkColumnProjectionMap();
    /**
     * An identity all column projection mapping for Sources
     */
    private final HashMap<String, String> allSourcesColumnProjectionMap =
            MuzeiProvider.buildAllSourcesColumnProjectionMap();
    private Handler openFileHandler;
    /**
     * Handle to a new DatabaseHelper.
     */
    private DatabaseHelper databaseHelper;
    /**
     * Whether we should hold notifyChange() calls due to an ongoing applyBatch operation
     */
    private boolean holdNotifyChange = false;
    /**
     * Set of Uris that should be applied when the ongoing applyBatch operation finishes
     */
    private final LinkedHashSet<Uri> pendingNotifyChange = new LinkedHashSet<>();

    /**
     * Creates and initializes a column project for all columns for Artwork
     *
     * @return The all column projection map for Artwork
     */
    private static HashMap<String, String> buildAllArtworkColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID,
                MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID,
                MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME,
                MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TITLE,
                MuzeiContract.Artwork.COLUMN_NAME_TITLE);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_BYLINE,
                MuzeiContract.Artwork.COLUMN_NAME_BYLINE);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION,
                MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TOKEN,
                MuzeiContract.Artwork.COLUMN_NAME_TOKEN);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT,
                MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_META_FONT,
                MuzeiContract.Artwork.COLUMN_NAME_META_FONT);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED,
                MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED);
        allColumnProjectionMap.put(MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID,
                MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                MuzeiContract.Sources.COLUMN_NAME_COMMANDS);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes a column project for all columns for Sources
     *
     * @return The all column projection map for Sources
     */
    private static HashMap<String, String> buildAllSourcesColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                MuzeiContract.Sources.COLUMN_NAME_COMMANDS);
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
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Artwork.TABLE_NAME + "/#",
                MuzeiProvider.ARTWORK_ID);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME,
                MuzeiProvider.SOURCES);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME + "/#",
                MuzeiProvider.SOURCE_ID);
        return matcher;
    }

    @NonNull
    @Override
    public ContentProviderResult[] applyBatch(@NonNull final ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        holdNotifyChange = true;
        try {
            return super.applyBatch(operations);
        } finally {
            holdNotifyChange = false;
            boolean broadcastSourceChanged = false;
            Context context = getContext();
            if (context != null) {
                ContentResolver contentResolver = context.getContentResolver();
                synchronized (pendingNotifyChange) {
                    Iterator<Uri> iterator = pendingNotifyChange.iterator();
                    while (iterator.hasNext()) {
                        Uri uri = iterator.next();
                        contentResolver.notifyChange(uri, null);
                        if (MuzeiContract.Artwork.CONTENT_URI.equals(uri)) {
                            context.sendBroadcast(new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
                        } else if (MuzeiProvider.uriMatcher.match(uri) == SOURCES ||
                                MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
                            broadcastSourceChanged = true;
                        }
                        iterator.remove();
                    }
                }
                if (broadcastSourceChanged) {
                    context.sendBroadcast(new Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED));
                }
            }
        }
    }

    private void notifyChange(Uri uri) {
        if (holdNotifyChange) {
            synchronized (pendingNotifyChange) {
                pendingNotifyChange.add(uri);
            }
        } else {
            Context context = getContext();
            if (context == null) {
                return;
            }
            context.getContentResolver().notifyChange(uri, null);
            if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK ||
                    MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
                context.sendBroadcast(new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
            } else if (MuzeiProvider.uriMatcher.match(uri) == SOURCES ||
                    MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
                context.sendBroadcast(new Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED));
            }
        }
    }

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        Context context = getContext();
        if (context == null) {
            return 0;
        }
        if (!UserManagerCompat.isUserUnlocked(context)) {
            Log.w(TAG, "Deletes are not supported until the user is unlocked");
            return 0;
        }
        // Only allow Muzei to delete content
        if (!context.getPackageName().equals(getCallingPackage())) {
            throw new UnsupportedOperationException("Deletes are not supported");
        }
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            return deleteArtwork(uri, selection, selectionArgs);
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return deleteSource(uri, selection, selectionArgs);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private int deleteArtwork(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        // Opens the database object in "write" mode.
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        String finalWhere = selection;
        if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
            finalWhere = MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID + " = " + uri.getLastPathSegment();
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null)
                finalWhere = finalWhere + " AND " + selection;
        }
        // We can't just simply delete the rows as that won't free up the space occupied by the
        // artwork image files associated with each row being deleted. Instead we have to query
        // and manually delete each artwork file
        String[] projection = new String[] {
                MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID,
                MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                MuzeiContract.Artwork.COLUMN_NAME_TOKEN};
        Cursor rowsToDelete = queryArtwork(uri, projection, finalWhere, selectionArgs,
                MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI);
        if (rowsToDelete == null) {
            return 0;
        }
        // First we build a list of IDs to be deleted. This will be used if we need to determine
        // if a given image URI needs to be deleted
        List<String> idsToDelete = new ArrayList<>();
        rowsToDelete.moveToFirst();
        while (!rowsToDelete.isAfterLast()) {
            idsToDelete.add(Long.toString(rowsToDelete.getLong(0)));
            rowsToDelete.moveToNext();
        }
        String notInDeleteIds = MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID +
                " NOT IN (" + TextUtils.join(",", idsToDelete) + ")";
        // Now we actually go through the list of rows to be deleted
        // and check if we can delete the artwork image file associated with each one
        rowsToDelete.moveToFirst();
        while (!rowsToDelete.isAfterLast()) {
            Uri artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI,
                    rowsToDelete.getLong(0));
            String imageUri = rowsToDelete.getString(1);
            String token = rowsToDelete.getString(2);
            if (TextUtils.isEmpty(imageUri) && TextUtils.isEmpty(token)) {
                // An empty image URI and token means the artwork is unique to this specific row
                // so we can always delete it when the associated row is deleted
                File artwork = getCacheFileForArtworkUri(artworkUri);
                if (artwork != null && artwork.exists()) {
                    artwork.delete();
                }
            } else if (TextUtils.isEmpty(imageUri)) {
                // Check if there are other rows using this same token that aren't
                // in the list of ids to delete
                Cursor otherArtwork = queryArtwork(MuzeiContract.Artwork.CONTENT_URI,
                        new String[] {MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID},
                        MuzeiContract.Artwork.COLUMN_NAME_TOKEN + "=? AND " + notInDeleteIds,
                        new String[] { token }, null);
                if (otherArtwork == null) {
                    continue;
                }
                if (otherArtwork.getCount() == 0) {
                    // There's no non-deleted rows that reference this same artwork URI
                    // so we can delete the artwork
                    File artwork = getCacheFileForArtworkUri(artworkUri);
                    if (artwork != null && artwork.exists()) {
                        artwork.delete();
                    }
                }
                otherArtwork.close();
            } else {
                // Check if there are other rows using this same image URI that aren't
                // in the list of ids to delete
                Cursor otherArtwork = queryArtwork(MuzeiContract.Artwork.CONTENT_URI,
                        new String[] {MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID},
                        MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI + "=? AND " + notInDeleteIds,
                        new String[] { imageUri }, null);
                if (otherArtwork == null) {
                    continue;
                }
                if (otherArtwork.getCount() == 0) {
                    // There's no non-deleted rows that reference this same artwork URI
                    // so we can delete the artwork
                    File artwork = getCacheFileForArtworkUri(artworkUri);
                    if (artwork != null && artwork.exists()) {
                        artwork.delete();
                    }
                }
                otherArtwork.close();
            }
            rowsToDelete.moveToNext();
        }
        rowsToDelete.close();
        int count = db.delete(MuzeiContract.Artwork.TABLE_NAME, finalWhere, selectionArgs);
        if (count > 0) {
            notifyChange(uri);
        }
        return count;
    }

    private int deleteSource(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        // Opens the database object in "write" mode.
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;
        // Does the delete based on the incoming URI pattern.
        switch (MuzeiProvider.uriMatcher.match(uri)) {
            case SOURCES:
                // If the incoming pattern matches the general pattern for
                // sources, does a delete based on the incoming "where"
                // column and arguments.
                count = db.delete(MuzeiContract.Sources.TABLE_NAME, selection, selectionArgs);
                break;
            case SOURCE_ID:
                // If the incoming URI matches a single source ID, does the
                // delete based on the incoming data, but modifies the where
                // clause to restrict it to the particular source ID.
                String finalWhere = BaseColumns._ID + " = " + uri.getLastPathSegment();
                // If there were additional selection criteria, append them to the final WHERE clause
                if (selection != null)
                    finalWhere = finalWhere + " AND " + selection;
                count = db.delete(MuzeiContract.Sources.TABLE_NAME, finalWhere, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            notifyChange(uri);
        }
        return count;
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        /**
         * Chooses the MIME type based on the incoming URI pattern
         */
        switch (MuzeiProvider.uriMatcher.match(uri)) {
            case ARTWORK:
                // If the pattern is for artwork, returns the artwork content type.
                return MuzeiContract.Artwork.CONTENT_TYPE;
            case ARTWORK_ID:
                // If the pattern is for artwork id, returns the artwork content item type.
                return MuzeiContract.Artwork.CONTENT_ITEM_TYPE;
            case SOURCES:
                // If the pattern is for sources, returns the sources content type.
                return MuzeiContract.Sources.CONTENT_TYPE;
            case SOURCE_ID:
                // If the pattern is for source id, returns the sources content item type.
                return MuzeiContract.Sources.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!UserManagerCompat.isUserUnlocked(context)) {
            Log.w(TAG, "Inserts are not supported until the user is unlocked");
            return null;
        }
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            return insertArtwork(uri, values);
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES) {
            // Ensure the app inserting the source is Muzei
            if (!context.getPackageName().equals(getCallingPackage())) {
                throw new UnsupportedOperationException("Inserting sources is not supported, use update");
            }
            return insertSource(uri, values);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Uri insertArtwork(@NonNull final Uri uri, final ContentValues values) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (values == null) {
            throw new IllegalArgumentException("Invalid ContentValues: must not be null");
        }
        if (!values.containsKey(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME) ||
                TextUtils.isEmpty(values.getAsString(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME))) {
            throw new IllegalArgumentException("Initial values must contain component name: " + values);
        }

        // Check to make sure the component name is valid
        ComponentName componentName = ComponentName.unflattenFromString(
                values.getAsString(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME));
        if (componentName == null) {
            throw new IllegalArgumentException("Invalid component name: " +
                    values.getAsString(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME));
        }
        // Make sure they are using the short string format
        values.put(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME, componentName.flattenToShortString());

        // Ensure the app inserting the artwork is either Muzei or the same app as the source
        String callingPackageName = getCallingPackage();
        if (!context.getPackageName().equals(callingPackageName) &&
                !TextUtils.equals(callingPackageName, componentName.getPackageName())) {
            throw new IllegalArgumentException("Calling package name (" + callingPackageName +
                    ") must match the source's package name (" + componentName.getPackageName() + ")");
        }

        if (values.containsKey(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT)) {
            String viewIntentString = values.getAsString(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
            Intent viewIntent;
            try {
                if (!TextUtils.isEmpty(viewIntentString)) {
                    // Make sure it is a valid Intent URI
                    viewIntent = Intent.parseUri(viewIntentString, Intent.URI_INTENT_SCHEME);
                    // Make sure we can construct a PendingIntent for the Intent
                    PendingIntent.getActivity(context, 0, viewIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                }
            } catch (URISyntaxException e) {
                Log.w(TAG, "Removing invalid View Intent: " + viewIntentString, e);
                values.remove(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
            } catch (RuntimeException e) {
                // This is actually meant to catch a FileUriExposedException, but you can't
                // have catch statements for exceptions that don't exist at your minSdkVersion
                Log.w(TAG, "Removing invalid View Intent that contains a file:// URI: " +
                        viewIntentString, e);
                values.remove(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
            }
        }

        // Ensure the related source has been added to the database.
        // This should be true in 99.9% of cases, but the insert will fail if this isn't true
        Cursor sourceQuery = querySource(MuzeiContract.Sources.CONTENT_URI,
                new String[] { BaseColumns._ID },
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                new String[] { componentName.flattenToShortString() },
                null);
        if (sourceQuery == null || sourceQuery.getCount() == 0) {
            ContentValues initialValues = new ContentValues();
            initialValues.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                    componentName.flattenToShortString());
            insertSource(MuzeiContract.Sources.CONTENT_URI, initialValues);
        }
        if (sourceQuery != null) {
            sourceQuery.close();
        }

        values.put(MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED, System.currentTimeMillis());
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        long rowId = db.insert(MuzeiContract.Artwork.TABLE_NAME,
                MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI, values);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the artwork ID pattern and the new row ID appended to it.
            final Uri artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, rowId);
            File artwork = getCacheFileForArtworkUri(artworkUri);
            if (artwork != null && artwork.exists()) {
                // The image already exists so we'll notifyChange() to say the new artwork is ready
                // Otherwise, this will be called when the file is written with openFile()
                // using this Uri and the actual artwork is written successfully
                notifyChange(artworkUri);
            }
            return artworkUri;
        }
        // If the insert didn't succeed, then the rowID is <= 0
        throw new SQLException("Failed to insert row into " + uri);
    }

    private Uri insertSource(@NonNull final Uri uri, final ContentValues initialValues) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!initialValues.containsKey(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME) ||
                TextUtils.isEmpty(initialValues.getAsString(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME))) {
            throw new IllegalArgumentException(
                    "Initial values must contain component name " + initialValues);
        }
        ComponentName componentName = ComponentName.unflattenFromString(
                initialValues.getAsString(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME));
        if (componentName == null) {
            throw new IllegalArgumentException("Invalid component name: " +
                    initialValues.getAsString(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME));
        }
        ApplicationInfo info;
        try {
            // Ensure the service is valid and extract the application info
            info = context.getPackageManager().getServiceInfo(componentName, 0).applicationInfo;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Invalid component name " +
                    initialValues.getAsString(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME), e);
        }
        // Make sure they are using the short string format
        initialValues.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, componentName.flattenToShortString());


        // Only Muzei can set the IS_SELECTED field
        if (initialValues.containsKey(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED)) {
            if (!context.getPackageName().equals(getCallingPackage())) {
                Log.w(TAG, "Only Muzei can set the " + MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED +
                        " column. Ignoring the value in " + initialValues);
                initialValues.remove(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED);
            }
        }

        // Disable network access callbacks if we're running on an API 24 device and the source app
        // targets API 24. This is to be consistent with the Behavior Changes in Android N
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                initialValues.containsKey(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE) &&
                initialValues.getAsBoolean(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE)) {
            if (info.targetSdkVersion >= Build.VERSION_CODES.N) {
                Log.w(TAG, "Sources targeting API 24 cannot receive network access callbacks. Changing " +
                        componentName + " to false for " + MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE);
                initialValues.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE, false);
            }
        }
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        final long rowId = db.insert(MuzeiContract.Sources.TABLE_NAME,
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, initialValues);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the source ID pattern and the new row ID appended to it.
            final Uri sourceUri = ContentUris.withAppendedId(MuzeiContract.Sources.CONTENT_URI, rowId);
            notifyChange(sourceUri);
            return sourceUri;
        }
        // If the insert didn't succeed, then the rowID is <= 0
        throw new SQLException("Failed to insert row into " + uri);
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider#onCreate()
     */
    @Override
    public boolean onCreate() {
        openFileHandler = new Handler();
        databaseHelper = new DatabaseHelper(getContext());
        // Schedule a job that will update the latest artwork in the Direct Boot cache directory
        // whenever the artwork changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            DirectBootCacheJobService.scheduleDirectBootCacheJob(getContext());
        }
        return true;
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        if (!UserManagerCompat.isUserUnlocked(getContext())) {
            Log.w(TAG, "Queries are not supported until the user is unlocked");
            return null;
        }
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            return queryArtwork(uri, projection, selection, selectionArgs, sortOrder);
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return querySource(uri, projection, selection, selectionArgs, sortOrder);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Cursor queryArtwork(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(MuzeiContract.Artwork.TABLE_NAME + " INNER JOIN " +
                MuzeiContract.Sources.TABLE_NAME + " ON " +
                MuzeiContract.Artwork.TABLE_NAME + "." +
                MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + "=" +
                MuzeiContract.Sources.TABLE_NAME + "." +
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME);
        qb.setProjectionMap(allArtworkColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
            // If the incoming URI is for a single source identified by its ID, appends "_ID = <artworkId>"
            // to the where clause, so that it selects that single piece of artwork
            qb.appendWhere(MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID + "=" + uri.getLastPathSegment());
        }
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + " DESC, " +
                    MuzeiContract.Artwork.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    private Cursor querySource(@NonNull final Uri uri, final String[] projection, final String selection,
                                final String[] selectionArgs, final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(MuzeiContract.Sources.TABLE_NAME);
        qb.setProjectionMap(allSourcesColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        if (MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
            // If the incoming URI is for a single source identified by its ID, appends "_ID = <sourceId>"
            // to the where clause, so that it selects that single source
            qb.appendWhere(BaseColumns._ID + "=" + uri.getLastPathSegment());
        }
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = MuzeiContract.Sources.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            return openFileArtwork(uri, mode);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private ParcelFileDescriptor openFileArtwork(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        String[] projection = { BaseColumns._ID, MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI };
        final boolean isWriteOperation = mode.contains("w");
        final File file;
        if (!UserManagerCompat.isUserUnlocked(getContext())) {
            if (isWriteOperation) {
                Log.w(TAG, "Wallpaper is read only until the user is unlocked");
                return null;
            }
            file = DirectBootCacheJobService.getCachedArtwork(getContext());
        } else if (!isWriteOperation && MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            // If it isn't a write operation, then we should attempt to find the latest artwork
            // that does have a cached artwork file. This prevents race conditions where
            // an external app attempts to load the latest artwork while an art source is inserting a
            // new artwork
            Cursor data = queryArtwork(MuzeiContract.Artwork.CONTENT_URI, projection, null, null, null);
            if (data == null) {
                return null;
            }
            if (!data.moveToFirst()) {
                if (!getContext().getPackageName().equals(getCallingPackage())) {
                    Log.w(TAG, "You must insert at least one row to read or write artwork");
                }
                return null;
            }
            File foundFile = null;
            while (!data.isAfterLast()) {
                Uri possibleArtworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, data.getLong(0));
                File possibleFile = getCacheFileForArtworkUri(possibleArtworkUri);
                if (possibleFile != null && possibleFile.exists()) {
                    foundFile = possibleFile;
                    break;
                }
                data.moveToNext();
            }
            file = foundFile;
        } else {
            file = getCacheFileForArtworkUri(uri);
        }
        if (file == null) {
            throw new FileNotFoundException("Could not create artwork file");
        }
        if (file.exists() && file.length() > 0 && isWriteOperation) {
            Context context = getContext();
            if (context == null) {
                return null;
            }
            if (!context.getPackageName().equals(getCallingPackage())) {
                Log.w(TAG, "Writing to an existing artwork file is not allowed: insert a new row");
            }
            cleanupCachedFiles();
            return null;
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), openFileHandler,
                    new ParcelFileDescriptor.OnCloseListener() {
                        @Override
                        public void onClose(final IOException e) {
                            if (isWriteOperation) {
                                if (e != null) {
                                    Log.e(TAG, "Error closing " + file + " for " + uri, e);
                                    if (file.exists()) {
                                        if (!file.delete()) {
                                            Log.w(TAG, "Unable to delete " + file);
                                        }
                                    }
                                } else {
                                    // The file was successfully written, notify listeners of the new artwork
                                    notifyChange(uri);
                                    cleanupCachedFiles();
                                }
                            }

                        }
                    });
        } catch (IOException e) {
            Log.e(TAG, "Error opening artwork " + uri, e);
            throw new FileNotFoundException("Error opening artwork " + uri);
        }
    }

    private File getCacheFileForArtworkUri(Uri artworkUri) {
        Context context = getContext();
        if (context == null || artworkUri == null) {
            return null;
        }
        File directory = new File(context.getFilesDir(), "artwork");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        String[] projection = { BaseColumns._ID, MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                MuzeiContract.Artwork.COLUMN_NAME_TOKEN };
        Cursor data = queryArtwork(artworkUri, projection, null, null, null);
        if (data == null) {
            return null;
        }
        if (!data.moveToFirst()) {
            Log.e(TAG, "Invalid artwork URI " + artworkUri);
            return null;
        }
        // While normally we'd use data.getLong(), we later need this as a String so the automatic conversion helps here
        String id = data.getString(0);
        String imageUri = data.getString(1);
        String token = data.getString(2);
        data.close();
        if (TextUtils.isEmpty(imageUri) && TextUtils.isEmpty(token)) {
            return new File(directory, id);
        }
        // Otherwise, create a unique filename based on the imageUri and token
        StringBuilder filename = new StringBuilder();
        if (!TextUtils.isEmpty(imageUri)) {
            Uri uri = Uri.parse(imageUri);
            filename.append(uri.getScheme()).append("_")
                    .append(uri.getHost()).append("_");
            String encodedPath = uri.getEncodedPath();
            if (!TextUtils.isEmpty(encodedPath)) {
                int length = encodedPath.length();
                if (length > 60) {
                    encodedPath = encodedPath.substring(length - 60);
                }
                encodedPath = encodedPath.replace('/', '_');
                filename.append(encodedPath).append("_");
            }
        }
        // Use the imageUri if available, otherwise use the token
        String unique = !TextUtils.isEmpty(imageUri) ? imageUri : token;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(unique.getBytes("UTF-8"));
            byte[] digest = md.digest();
            for (byte b : digest) {
                if ((0xff & b) < 0x10) {
                    filename.append("0").append(Integer.toHexString((0xFF & b)));
                } else {
                    filename.append(Integer.toHexString(0xFF & b));
                }
            }
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            filename.append(unique.hashCode());
        }
        return new File(directory, filename.toString());
    }

    /**
     * Limit the number of cached files per art source to {@link #MAX_CACHE_SIZE}.
     * @see #MAX_CACHE_SIZE
     */
    private void cleanupCachedFiles() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        Cursor sources = querySource(MuzeiContract.Sources.CONTENT_URI,
                new String[] {MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME},
                null, null, null);
        if (sources == null) {
            return;
        }
        // Access to certain artwork can be persisted through MuzeiDocumentsProvider
        // We never want to delete these artwork as that would break other apps
        Set<Uri> persistedUris = MuzeiDocumentsProvider.getPersistedArtworkUris(context);
        // Loop through each source, cleaning up old artwork
        while (sources.moveToNext()) {
            String componentName = sources.getString(0);
            // Now use that ComponentName to look through the past artwork from that source
            Cursor artworkBySource = queryArtwork(MuzeiContract.Artwork.CONTENT_URI,
                    new String[] {BaseColumns._ID, MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                        MuzeiContract.Artwork.COLUMN_NAME_TOKEN},
                    MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + "=?",
                    new String[] {componentName},
                    MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED + " DESC");
            if (artworkBySource == null) {
                continue;
            }
            List<String> artworkIdsToKeep = new ArrayList<>();
            List<String> artworkToKeep = new ArrayList<>();
            // First find all of the persisted artwork from this source and mark them as artwork to keep
            while (artworkBySource.moveToNext()) {
                long id = artworkBySource.getLong(0);
                Uri uri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, id);
                String artworkUri = artworkBySource.getString(1);
                String artworkToken = artworkBySource.getString(2);
                String unique = !TextUtils.isEmpty(artworkUri) ? artworkUri : artworkToken;
                if (persistedUris.contains(uri)) {
                    // Always keep artwork that is persisted
                    artworkIdsToKeep.add(Long.toString(id));
                    artworkToKeep.add(unique);
                }
            }
            // Now go through the artwork from this source and find the most recent artwork
            // and mark them as artwork to keep
            int count = 0;
            artworkBySource.moveToPosition(-1);
            while (artworkBySource.moveToNext()) {
                // BaseColumns._ID is a long, but we need it as a String later anyways
                String id = artworkBySource.getString(0);
                String artworkUri = artworkBySource.getString(1);
                String artworkToken = artworkBySource.getString(2);
                String unique = !TextUtils.isEmpty(artworkUri) ? artworkUri : artworkToken;
                if (artworkToKeep.contains(unique)) {
                    // This ensures we are double counting the same artwork in our count
                    artworkIdsToKeep.add(id);
                    continue;
                }
                if (count++ < MAX_CACHE_SIZE) {
                    // Keep artwork below the MAX_CACHE_SIZE
                    artworkIdsToKeep.add(id);
                    artworkToKeep.add(unique);
                }
            }
            // Now delete all artwork not in the keep list
            int numDeleted = deleteArtwork(MuzeiContract.Artwork.CONTENT_URI,
                    MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + "=?"
                    + " AND " + MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID
                    + " NOT IN (" + TextUtils.join(",", artworkIdsToKeep) + ")",
                    new String[] {componentName});
            if (numDeleted > 0) {
                Log.d(TAG, "For " + componentName + " kept " + artworkToKeep.size()
                        + " artwork, deleted " + numDeleted);
            }
            artworkBySource.close();
        }
        sources.close();
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK_ID) {
            throw new UnsupportedOperationException("Updates are not allowed: insert a new row");
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return updateSource(uri, values, selection, selectionArgs);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private int updateSource(@NonNull final Uri uri, final ContentValues values, final String selection,
                             final String[] selectionArgs) {
        Context context = getContext();
        if (context == null) {
            return 0;
        }

        // Only Muzei can set the IS_SELECTED field
        if (values.containsKey(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED)) {
            if (!context.getPackageName().equals(getCallingPackage())) {
                Log.w(TAG, "Only Muzei can set the " + MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED +
                        " column. Ignoring the value in " + values);
                values.remove(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED);
            }
        }

        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        String finalWhere = selection;
        String[] finalSelectionArgs = selectionArgs;
        if (MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
            // If the incoming URI matches a single source ID, does the update based on the incoming data, but
            // modifies the where clause to restrict it to the particular source ID.
            finalWhere = DatabaseUtils.concatenateWhere(finalWhere,
                    BaseColumns._ID + " = " + uri.getLastPathSegment());
        }
        String callingPackageName = getCallingPackage();
        if (!context.getPackageName().equals(callingPackageName)) {
            // Only allow other apps to update their own source
            finalWhere = DatabaseUtils.concatenateWhere(finalWhere,
                    MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + " LIKE ?");
            finalSelectionArgs = DatabaseUtils.appendSelectionArgs(finalSelectionArgs,
                    new String[] {callingPackageName +"/%"});
        }
        int count = db.update(MuzeiContract.Sources.TABLE_NAME, values, finalWhere, finalSelectionArgs);
        if (count > 0) {
            notifyChange(uri);
        } else if (values.containsKey(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME)) {
            insertSource(MuzeiContract.Sources.CONTENT_URI, values);
            count = 1;
        }
        return count;
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
            db.execSQL("CREATE TABLE " + MuzeiContract.Sources.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + " TEXT,"
                    + MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + " INTEGER,"
                    + MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION + " TEXT,"
                    + MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE + " INTEGER,"
                    + MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND + " INTEGER,"
                    + MuzeiContract.Sources.COLUMN_NAME_COMMANDS + " TEXT);");
            db.execSQL("CREATE TABLE " + MuzeiContract.Artwork.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_TITLE + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_BYLINE + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_TOKEN + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_META_FONT + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED + " INTEGER,"
                    + MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT + " TEXT,"
                    + " CONSTRAINT fk_source_artwork FOREIGN KEY ("
                    + MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + ") REFERENCES "
                    + MuzeiContract.Sources.TABLE_NAME + " ("
                    + MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + ") ON DELETE CASCADE);");
        }

        /**
         * Upgrades the database.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
            if (oldVersion < 3) {
                // We can't ALTER TABLE to add a foreign key and we wouldn't know what the FK should be
                // at this point anyways so we'll wipe and recreate the artwork table
                db.execSQL("DROP TABLE " + MuzeiContract.Artwork.TABLE_NAME);
                onCreate(db);
            }
        }
    }
}