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

import android.arch.persistence.db.SupportSQLiteQueryBuilder;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.os.UserManagerCompat;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
     * Creates and initializes a column project for all columns for Artwork
     *
     * @return The all column projection map for Artwork
     */
    private static HashMap<String, String> buildAllArtworkColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID,
                "artwork._id");
        allColumnProjectionMap.put(MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID,
                "artwork._id");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME,
                "sourceComponentName");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                "imageUri");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TITLE,
                "title");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_BYLINE,
                "byline");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION,
                "attribution");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TOKEN,
                "token");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT,
                "viewIntent");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_META_FONT,
                "metaFont");
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED,
                "date_added");
        allColumnProjectionMap.put(MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID,
                "sources._id");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                "component_name");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                "selected");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                "description");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                "network");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                "supports_next_artwork");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                "commands");
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
                "component_name");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                "selected");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                "description");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                "network");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                "supports_next_artwork");
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                "commands");
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

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Deletes are not supported");
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        // Chooses the MIME type based on the incoming URI pattern
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
        throw new UnsupportedOperationException("Inserts are not supported");
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider#onCreate()
     */
    @Override
    public boolean onCreate() {
        openFileHandler = new Handler();
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
        Context context = getContext();
        if (context == null) {
            return null;
        }
        SupportSQLiteQueryBuilder qb = SupportSQLiteQueryBuilder.builder(
                "artwork INNER JOIN sources ON " +
                "artwork.sourceComponentName=sources.component_name");
        qb.columns(computeColumns(projection, allArtworkColumnProjectionMap));
        String finalSelection = selection;
        if (MuzeiProvider.uriMatcher.match(uri) == ARTWORK_ID) {
            // If the incoming URI is for a single source identified by its ID, appends "_ID = <artworkId>"
            // to the where clause, so that it selects that single piece of artwork
            finalSelection = DatabaseUtils.concatenateWhere(selection,
                    "artwork._id = " + uri.getLastPathSegment());
        }
        qb.selection(finalSelection, selectionArgs);
        String orderBy;
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = "selected DESC, date_added DESC";
        } else {
            orderBy = sortOrder;
        }
        qb.orderBy(orderBy);
        final Cursor c = MuzeiDatabase.getInstance(context).query(qb.create());
        c.setNotificationUri(context.getContentResolver(), uri);
        return c;
    }

    private Cursor querySource(@NonNull final Uri uri, final String[] projection, final String selection,
                                final String[] selectionArgs, final String sortOrder) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        SupportSQLiteQueryBuilder qb = SupportSQLiteQueryBuilder.builder("sources");
        qb.columns(computeColumns(projection, allSourcesColumnProjectionMap));
        String finalSelection = selection;
        if (MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
            // If the incoming URI is for a single source identified by its ID, appends "_ID = <sourceId>"
            // to the where clause, so that it selects that single source
            finalSelection = DatabaseUtils.concatenateWhere(selection,
                    "_id = " + uri.getLastPathSegment());
        }
        qb.selection(finalSelection, selectionArgs);
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = "selected DESC, component_name";
        else
            orderBy = sortOrder;
        qb.orderBy(orderBy);
        final Cursor c = MuzeiDatabase.getInstance(context).query(qb.create());
        c.setNotificationUri(context.getContentResolver(), uri);
        return c;
    }

    private String[] computeColumns(String[] projectionIn, @NonNull HashMap<String, String> projectionMap) {
        if (projectionIn != null && projectionIn.length > 0) {
            String[] projection = new String[projectionIn.length];
            int length = projectionIn.length;
            for (int i = 0; i < length; i++) {
                String userColumn = projectionIn[i];
                String column = projectionMap.get(userColumn);
                if (column != null) {
                    projection[i] = column;
                    continue;
                }
                if (userColumn.contains(" AS ") || userColumn.contains(" as ")) {
                    /* A column alias already exist */
                    projection[i] = userColumn;
                    continue;
                }
                throw new IllegalArgumentException("Invalid column "
                        + projectionIn[i]);
            }
            return projection;
        }
        // Return all columns in projection map.
        Set<Map.Entry<String, String>> entrySet = projectionMap.entrySet();
        String[] projection = new String[entrySet.size()];
        Iterator<Map.Entry<String, String>> entryIter = entrySet.iterator();
        int i = 0;
        while (entryIter.hasNext()) {
            Map.Entry<String, String> entry = entryIter.next();
            // Don't include the _count column when people ask for no projection.
            if (entry.getKey().equals(BaseColumns._COUNT)) {
                continue;
            }
            projection[i++] = entry.getValue();
        }
        return projection;
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

    @Nullable
    private ParcelFileDescriptor openFileArtwork(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        final Context context = getContext();
        if (context == null) {
            return null;
        }
        final boolean isWriteOperation = mode.contains("w");
        final File file;
        if (!UserManagerCompat.isUserUnlocked(context)) {
            if (isWriteOperation) {
                Log.w(TAG, "Wallpaper is read only until the user is unlocked");
                return null;
            }
            file = DirectBootCacheJobService.getCachedArtwork(context);
        } else if (!isWriteOperation && MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            // If it isn't a write operation, then we should attempt to find the latest artwork
            // that does have a cached artwork file. This prevents race conditions where
            // an external app attempts to load the latest artwork while an art source is inserting a
            // new artwork
            List<Artwork> artworkList = MuzeiDatabase.getInstance(context).artworkDao().getArtworkBlocking();
            if (artworkList == null || artworkList.isEmpty()) {
                if (!context.getPackageName().equals(getCallingPackage())) {
                    Log.w(TAG, "You must insert at least one row to read or write artwork");
                }
                return null;
            }
            File foundFile = null;
            for (Artwork artwork : artworkList) {
                File possibleFile = getCacheFileForArtworkUri(context, artwork.id);
                if (possibleFile != null && possibleFile.exists()) {
                    foundFile = possibleFile;
                    break;
                }
            }
            file = foundFile;
        } else {
            file = getCacheFileForArtworkUri(context, ContentUris.parseId(uri));
        }
        if (file == null) {
            throw new FileNotFoundException("Could not create artwork file for " + uri + " for mode " + mode);
        }
        if (file.exists() && file.length() > 0 && isWriteOperation) {
            if (!context.getPackageName().equals(getCallingPackage())) {
                Log.w(TAG, "Writing to an existing artwork file is not allowed: insert a new row");
            }
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
                                    context.getContentResolver()
                                            .notifyChange(MuzeiContract.Artwork.CONTENT_URI, null);
                                    context.sendBroadcast(
                                            new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
                                    cleanupCachedFiles(context);
                                }
                            }

                        }
                    });
        } catch (IOException e) {
            Log.e(TAG, "Error opening artwork " + uri, e);
            throw new FileNotFoundException("Error opening artwork " + uri);
        }
    }

    @Nullable
    public static File getCacheFileForArtworkUri(Context context, long artworkId) {
        File directory = new File(context.getFilesDir(), "artwork");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        Artwork artwork = MuzeiDatabase.getInstance(context).artworkDao().getArtworkById(artworkId);
        if (artwork == null) {
            return null;
        }
        if (artwork.imageUri == null && TextUtils.isEmpty(artwork.token)) {
            return new File(directory, Long.toString(artwork.id));
        }
        // Otherwise, create a unique filename based on the imageUri and token
        StringBuilder filename = new StringBuilder();
        if (artwork.imageUri != null) {
            filename.append(artwork.imageUri.getScheme()).append("_")
                    .append(artwork.imageUri.getHost()).append("_");
            String encodedPath = artwork.imageUri.getEncodedPath();
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
        String unique = artwork.imageUri != null ? artwork.imageUri.toString() : artwork.token;
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
    public static void cleanupCachedFiles(final Context context) {
        new Thread() {
            @Override
            public void run() {
                final MuzeiDatabase database = MuzeiDatabase.getInstance(context);
                final List<Source> sources = database.sourceDao().getSourcesBlocking();
                if (sources == null) {
                    return;
                }
                // Access to certain artwork can be persisted through MuzeiDocumentsProvider
                // We never want to delete these artwork as that would break other apps
                final Set<Uri> persistedUris = MuzeiDocumentsProvider.getPersistedArtworkUris(context);
                // Loop through each source, cleaning up old artwork
                for (Source source : sources) {
                    final ComponentName componentName = source.componentName;
                    // Now use that ComponentName to look through the past artwork from that source
                    final List<Artwork> artworkList = database.artworkDao()
                            .getArtworkForSourceIdBlocking(source.id);
                    if (artworkList == null || artworkList.isEmpty()) {
                        continue;
                    }
                    List<Long> artworkIdsToKeep = new ArrayList<>();
                    List<String> artworkToKeep = new ArrayList<>();
                    // First find all of the persisted artwork from this source and mark them as artwork to keep
                    for (Artwork artwork : artworkList) {
                        Uri uri = artwork.getContentUri();
                        String unique = artwork.imageUri != null ? artwork.imageUri.toString() : artwork.token;
                        if (persistedUris.contains(uri)) {
                            // Always keep artwork that is persisted
                            artworkIdsToKeep.add(artwork.id);
                            artworkToKeep.add(unique);
                        }
                    }
                    // Now go through the artwork from this source and find the most recent artwork
                    // and mark them as artwork to keep
                    int count = 0;
                    List<Long> mostRecentArtworkIds = new ArrayList<>();
                    List<String> mostRecentArtwork = new ArrayList<>();
                    for (Artwork artwork : artworkList) {
                        String unique = artwork.imageUri != null ? artwork.imageUri.toString() : artwork.token;
                        if (mostRecentArtworkIds.size() < MAX_CACHE_SIZE && !mostRecentArtwork.contains(unique)) {
                            mostRecentArtwork.add(unique);
                            mostRecentArtworkIds.add(artwork.id);
                        }
                        if (artworkToKeep.contains(unique)) {
                            // This ensures we aren't double counting the same artwork in our count
                            artworkIdsToKeep.add(artwork.id);
                            continue;
                        }
                        if (count++ < MAX_CACHE_SIZE) {
                            // Keep artwork below the MAX_CACHE_SIZE
                            artworkIdsToKeep.add(artwork.id);
                            artworkToKeep.add(unique);
                        }
                    }
                    // Now delete all artwork not in the keep list
                    try {
                        database.artworkDao().deleteNonMatching(context,
                                componentName, artworkIdsToKeep);
                    } catch (IllegalStateException|SQLiteException e) {
                        Log.e(TAG, "Unable to read all artwork for " + componentName +
                                ", deleting everything but the latest artwork to get back to a good state", e);
                        database.artworkDao().deleteNonMatching(context,
                                componentName, mostRecentArtworkIds);
                    }
                }
            }
        }.start();
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Updates are not supported");
    }
}