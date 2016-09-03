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
import android.content.UriPermission;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Provides access to the Gallery's chosen photos and metadata
 */
public class GalleryProvider extends ContentProvider {
    private static final String TAG = "GalleryProvider";
    /**
     * The incoming URI matches the CHOSEN PHOTOS URI pattern
     */
    private static final int CHOSEN_PHOTOS = 1;
    /**
     * The incoming URI matches the CHOSEN PHOTOS ID URI pattern
     */
    private static final int CHOSEN_PHOTOS_ID = 2;
    /**
     * The incoming URI matches the METADATA CACHE URI pattern
     */
    private static final int METADATA_CACHE = 3;
    /**
     * The database that the provider uses as its underlying data store
     */
    private static final String DATABASE_NAME = "gallery_source.db";
    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 2;
    /**
     * A UriMatcher instance
     */
    private static final UriMatcher uriMatcher = GalleryProvider.buildUriMatcher();
    /**
     * An identity all column projection mapping for Chosen Photos
     */
    private final HashMap<String, String> allChosenPhotosColumnProjectionMap =
            GalleryProvider.buildAllChosenPhotosColumnProjectionMap();
    /**
     * An identity all column projection mapping for Metadata Cache
     */
    private final HashMap<String, String> allMetadataCacheColumnProjectionMap =
            GalleryProvider.buildAllMetadataCacheColumnProjectionMap();
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
     * Creates and initializes a column project for all columns for Chosen Photos
     *
     * @return The all column projection map for Chosen Photos
     */
    private static HashMap<String, String> buildAllChosenPhotosColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(GalleryContract.ChosenPhotos.COLUMN_NAME_URI,
                GalleryContract.ChosenPhotos.COLUMN_NAME_URI);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes a column project for all columns for Sources
     *
     * @return The all column projection map for Sources
     */
    private static HashMap<String, String> buildAllMetadataCacheColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(GalleryContract.MetadataCache.COLUMN_NAME_URI,
                GalleryContract.MetadataCache.COLUMN_NAME_URI);
        allColumnProjectionMap.put(GalleryContract.MetadataCache.COLUMN_NAME_DATETIME,
                GalleryContract.MetadataCache.COLUMN_NAME_DATETIME);
        allColumnProjectionMap.put(GalleryContract.MetadataCache.COLUMN_NAME_LOCATION,
                GalleryContract.MetadataCache.COLUMN_NAME_LOCATION);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes the URI matcher
     *
     * @return the URI Matcher
     */
    private static UriMatcher buildUriMatcher() {
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(GalleryContract.AUTHORITY, GalleryContract.ChosenPhotos.TABLE_NAME,
                GalleryProvider.CHOSEN_PHOTOS);
        matcher.addURI(GalleryContract.AUTHORITY, GalleryContract.ChosenPhotos.TABLE_NAME + "/#",
                GalleryProvider.CHOSEN_PHOTOS_ID);
        matcher.addURI(GalleryContract.AUTHORITY, GalleryContract.MetadataCache.TABLE_NAME,
                GalleryProvider.METADATA_CACHE);
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
            Context context = getContext();
            if (context != null) {
                ContentResolver contentResolver = context.getContentResolver();
                Iterator<Uri> iterator = pendingNotifyChange.iterator();
                while (iterator.hasNext()) {
                    Uri uri = iterator.next();
                    contentResolver.notifyChange(uri, null);
                    iterator.remove();
                }
            }
        }
    }

    private void notifyChange(Uri uri) {
        if (holdNotifyChange) {
            pendingNotifyChange.add(uri);
        } else {
            Context context = getContext();
            if (context == null) {
                return;
            }
            context.getContentResolver().notifyChange(uri, null);
        }
    }

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS) {
            return deleteChosenPhotos(uri, selection, selectionArgs);
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            throw new UnsupportedOperationException("Deletes are not supported");
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private int deleteChosenPhotos(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        // Opens the database object in "write" mode.
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        // We can't just simply delete the rows as that won't free up the space occupied by the
        // chosen image files for each row being deleted. Instead we have to query
        // and manually delete each chosen image file
        String[] projection = new String[] {
                GalleryContract.ChosenPhotos.COLUMN_NAME_URI};
        Cursor rowsToDelete = queryChosenPhotos(uri, projection, selection, selectionArgs, null);
        if (rowsToDelete == null) {
            return 0;
        }
        rowsToDelete.moveToFirst();
        while (!rowsToDelete.isAfterLast()) {
            String imageUri = rowsToDelete.getString(0);
            File file = getCacheFileForUri(getContext(), imageUri);
            if (file != null && file.exists()) {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete " + file);
                }
            } else if (getContext() != null) {
                // Try to release any persisted URI permission for the imageUri
                Uri uriToRelease = Uri.parse(imageUri);
                ContentResolver contentResolver = getContext().getContentResolver();
                List<UriPermission> persistedUriPermissions = contentResolver.getPersistedUriPermissions();
                for (UriPermission persistedUriPermission : persistedUriPermissions) {
                    if (persistedUriPermission.getUri().equals(uriToRelease)) {
                        contentResolver.releasePersistableUriPermission(
                                uriToRelease, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        break;
                    }
                }
            }
            rowsToDelete.moveToNext();
        }
        int count = db.delete(GalleryContract.ChosenPhotos.TABLE_NAME, selection, selectionArgs);
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
        switch (GalleryProvider.uriMatcher.match(uri)) {
            case CHOSEN_PHOTOS:
                // If the pattern is for chosen photos, returns the chosen photos content type.
                return GalleryContract.ChosenPhotos.CONTENT_TYPE;
            case CHOSEN_PHOTOS_ID:
                // If the pattern is for chosen photo id, returns the chosen photo content item type.
                return GalleryContract.ChosenPhotos.CONTENT_ITEM_TYPE;
            case METADATA_CACHE:
                // If the pattern is for metadata cache, returns the metadata cache content type.
                return GalleryContract.MetadataCache.CONTENT_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS) {
            return insertChosenPhotos(uri, values);
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            return insertMetadataCache(uri, values);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Uri insertChosenPhotos(@NonNull final Uri uri, final ContentValues values) {
        if (values == null) {
            throw new IllegalArgumentException("Invalid ContentValues: must not be null");
        }
        if (!values.containsKey(GalleryContract.ChosenPhotos.COLUMN_NAME_URI))
            throw new IllegalArgumentException("Initial values must contain URI " + values);
        String imageUri = values.getAsString(GalleryContract.ChosenPhotos.COLUMN_NAME_URI);
        boolean persistedPermission = false;
        Uri uriToTake = Uri.parse(imageUri);
        // Try to persist access to the URI, saving us from having to store a local copy
        if (getContext() != null && DocumentsContract.isDocumentUri(getContext(), uriToTake)) {
            try {
                getContext().getContentResolver().takePersistableUriPermission(uriToTake, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                persistedPermission = true;
                // If we have a persisted URI permission, we don't need a local copy
                File cachedFile = getCacheFileForUri(getContext(), imageUri);
                if (cachedFile != null && cachedFile.exists()) {
                    if (!cachedFile.delete()) {
                        Log.w(TAG, "Unable to delete " + cachedFile);
                    }
                }
            } catch (SecurityException ignored) {
                // If we don't have FLAG_GRANT_PERSISTABLE_URI_PERMISSION (such as when using ACTION_GET_CONTENT),
                // this will fail. It'll also fail for URIs originating from our own app.
                // These cases are handled below
            }
        }
        if (!persistedPermission &&
                !imageUri.startsWith(ContentResolver.SCHEME_CONTENT + "://" + getContext().getPackageName())) {
            // We only need to make a local copy if we weren't able to persist the permission
            // and the URI is not from our package (we always have access to those URIs)
            try {
                writeUriToFile(getContext(), imageUri, getCacheFileForUri(getContext(), imageUri));
            } catch (IOException e) {
                Log.e(TAG, "Error downloading gallery image " + imageUri, e);
                throw new SQLException("Error downloading gallery image " + imageUri);
            }
        }
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        long rowId = db.insert(GalleryContract.ChosenPhotos.TABLE_NAME,
                GalleryContract.ChosenPhotos.COLUMN_NAME_URI, values);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the chosen photos ID pattern and the new row ID appended to it.
            final Uri chosenPhotoUri = ContentUris.withAppendedId(GalleryContract.ChosenPhotos.CONTENT_URI, rowId);
            notifyChange(chosenPhotoUri);
            return chosenPhotoUri;
        }
        // If the insert didn't succeed, then the rowID is <= 0
        throw new SQLException("Failed to insert row into " + uri);
    }

    private static void writeUriToFile(Context context, String uri, File destFile) throws IOException {
        if (context == null) {
            return;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) {
                return;
            }
            out = new FileOutputStream(destFile);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (SecurityException e) {
            throw new IOException("Unable to read Uri: " + uri, e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch(IOException ignored) {}
            }
            if (out != null) {
                try {
                    out.close();
                } catch(IOException ignored) {}
            }
        }
    }

    private Uri insertMetadataCache(@NonNull final Uri uri, final ContentValues initialValues) {
        if (!initialValues.containsKey(GalleryContract.MetadataCache.COLUMN_NAME_URI))
            throw new IllegalArgumentException("Initial values must contain URI " + initialValues);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        final long rowId = db.insert(GalleryContract.MetadataCache.TABLE_NAME,
                GalleryContract.MetadataCache.COLUMN_NAME_URI, initialValues);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the metadata cache ID pattern and the new row ID appended to it.
            final Uri metadataCacheUri = ContentUris.withAppendedId(GalleryContract.MetadataCache.CONTENT_URI, rowId);
            notifyChange(metadataCacheUri);
            return metadataCacheUri;
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
        databaseHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS ||
                GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS_ID) {
            return queryChosenPhotos(uri, projection, selection, selectionArgs, sortOrder);
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            return queryMetadataCache(uri, projection, selection, selectionArgs, sortOrder);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Cursor queryChosenPhotos(@NonNull final Uri uri, final String[] projection, final String selection,
                               final String[] selectionArgs, final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(GalleryContract.ChosenPhotos.TABLE_NAME);
        qb.setProjectionMap(allChosenPhotosColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        if (GalleryProvider.uriMatcher.match(uri) == CHOSEN_PHOTOS_ID) {
            // If the incoming URI is for a single chosen photo identified by its ID, appends "_ID = <chosenPhotoId>"
            // to the where clause, so that it selects that single chosen photo
            qb.appendWhere(BaseColumns._ID + "=" + uri.getLastPathSegment());
        }
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = GalleryContract.ChosenPhotos.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    private Cursor queryMetadataCache(@NonNull final Uri uri, final String[] projection, final String selection,
                               final String[] selectionArgs, final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(GalleryContract.MetadataCache.TABLE_NAME);
        qb.setProjectionMap(allMetadataCacheColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = GalleryContract.MetadataCache.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS_ID) {
            return openFileChosenPhoto(uri, mode);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private ParcelFileDescriptor openFileChosenPhoto(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        if (!mode.equals("r")) {
            throw new IllegalArgumentException("Only reading chosen photos is allowed");
        }
        String[] projection = { GalleryContract.ChosenPhotos.COLUMN_NAME_URI };
        Cursor data = queryChosenPhotos(uri, projection, null, null, null);
        if (data == null) {
            return null;
        }
        if (!data.moveToFirst()) {
            throw new IllegalStateException("Invalid URI: " + uri);
        }
        String imageUri = data.getString(0);
        data.close();
        final File file = getCacheFileForUri(getContext(), imageUri);
        if ((file == null || !file.exists()) && getContext() != null) {
            // Assume we have persisted URI permission to the imageUri and can read the image directly from the imageUri
            try {
                return getContext().getContentResolver().openFileDescriptor(Uri.parse(imageUri), mode);
            } catch (SecurityException e) {
                Log.d(TAG, "Unable to load " + uri + ", deleting the row", e);
                deleteChosenPhotos(uri, null, null);
                throw new FileNotFoundException("No permission to load " + uri);
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    static File getLocalFileForUri(Context context, @NonNull Uri uri) {
        String imageUri = uri.toString();
        if (GalleryProvider.uriMatcher.match(uri) == CHOSEN_PHOTOS_ID) {
            String[] projection = { GalleryContract.ChosenPhotos.COLUMN_NAME_URI };
            Cursor data = context.getContentResolver().query(uri, projection, null, null, null);
            if (data == null) {
                return null;
            }
            if (!data.moveToFirst()) {
                throw new IllegalStateException("Invalid URI: " + uri);
            }
            imageUri = data.getString(0);
            data.close();
            // See if there's already a cached file for the image
            File cachedFile = getCacheFileForUri(context, imageUri);
            if (cachedFile != null && cachedFile.exists()) {
                return cachedFile;
            }
        }
        // Create a local file
        File tempFile = new File(context.getCacheDir(), "tempimage");
        try {
            writeUriToFile(context, imageUri, tempFile);
            return tempFile;
        } catch (IOException e) {
            Log.e(TAG, "Error downloading gallery image " + imageUri, e);
            return null;
        }
    }

    private static File getCacheFileForUri(Context context, @NonNull String imageUri) {
        File directory = new File(context.getExternalFilesDir(null), "gallery_images");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }

        // Create a unique filename based on the imageUri
        Uri uri = Uri.parse(imageUri);
        StringBuilder filename = new StringBuilder();
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
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(uri.toString().getBytes("UTF-8"));
            byte[] digest = md.digest();
            for (byte b : digest) {
                if ((0xff & b) < 0x10) {
                    filename.append("0").append(Integer.toHexString((0xFF & b)));
                } else {
                    filename.append(Integer.toHexString(0xFF & b));
                }
            }
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            filename.append(uri.toString().hashCode());
        }

        return new File(directory, filename.toString());
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS ||
                GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS_ID) {
            throw new UnsupportedOperationException("Updates are not allowed");
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            throw new UnsupportedOperationException("Updates are not allowed");
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with table name and column names taken from the GalleryContract class.
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            onCreateChosenPhotos(db);
            onCreateMetadataCache(db);
        }

        private void onCreateChosenPhotos(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + GalleryContract.ChosenPhotos.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + GalleryContract.ChosenPhotos.COLUMN_NAME_URI + " TEXT NOT NULL,"
                    + "UNIQUE (" + GalleryContract.ChosenPhotos.COLUMN_NAME_URI + ") ON CONFLICT REPLACE)");
        }

        private void onCreateMetadataCache(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + GalleryContract.MetadataCache.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + GalleryContract.MetadataCache.COLUMN_NAME_URI + " TEXT NOT NULL,"
                    + GalleryContract.MetadataCache.COLUMN_NAME_DATETIME + " INTEGER,"
                    + GalleryContract.MetadataCache.COLUMN_NAME_LOCATION + " TEXT,"
                    + "UNIQUE (" + GalleryContract.MetadataCache.COLUMN_NAME_URI + ") ON CONFLICT REPLACE)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS " + GalleryContract.MetadataCache.TABLE_NAME);
                onCreateMetadataCache(db);
            }
        }
    }

}