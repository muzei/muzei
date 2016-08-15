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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.androidclientcommon.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DocumentsProvider that allows users to view previous Muzei wallpapers
 */
public class MuzeiDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "MuzeiDocumentsProvider";
    /**
     * Default root projection
     */
    private final static String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID};
    /**
     * Default document projection
     */
    private final static String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SUMMARY,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE};

    private static final String[] ARTWORK_PROJECTION = new String[]{
            MuzeiContract.Artwork._ID,
            MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
            MuzeiContract.Artwork.COLUMN_NAME_TITLE,
            MuzeiContract.Artwork.COLUMN_NAME_BYLINE};

    private static final String[] SOURCE_PROJECTION = new String[]{
            MuzeiContract.Sources._ID,
            MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
            MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION};

    private static final String ROOT_DOCUMENT_ID = "root";
    private static final String BY_DATE_DOCUMENT_ID = "by_date";
    private static final String BY_SOURCE_DOCUMENT_ID = "by_source";

    private static final String ARTWORK_DOCUMENT_ID_PREFIX = "artwork_";
    private static final String SOURCE_DOCUMENT_ID_PREFIX = "source_";

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "Muzei");
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher);
        row.add(DocumentsContract.Root.COLUMN_TITLE, getContext().getString(R.string.app_name));
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY |
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH |
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*");
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        return result;
    }

    @Override
    public Cursor querySearchDocuments(final String rootId, final String query, final String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        String selection = MuzeiContract.Artwork.COLUMN_NAME_TITLE + " LIKE ? OR " +
                MuzeiContract.Artwork.COLUMN_NAME_BYLINE + " LIKE ? OR " +
                MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION + " LIKE ?";
        String likeAnyPositionQuery = "%" + query + "%";
        includeAllArtwork(result, getContext().getContentResolver().query(
                MuzeiContract.Artwork.CONTENT_URI, ARTWORK_PROJECTION,
                selection,
                new String[] { likeAnyPositionQuery, likeAnyPositionQuery, likeAnyPositionQuery },
                MuzeiContract.Artwork.DEFAULT_SORT_ORDER));
        return result;
    }

    @Override
    public boolean isChildDocument(final String parentDocumentId, final String documentId) {
        if (ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            // Everything is a child of ROOT_DOCUMENT_ID
            return true;
        } else if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            if (BY_DATE_DOCUMENT_ID.equals(parentDocumentId)) {
                // All artwork is a child of BY_DATE_DOCUMENT_ID
                return true;
            }
            if (BY_SOURCE_DOCUMENT_ID.equals(parentDocumentId)) {
                long sourceId = Long.parseLong(parentDocumentId.replace(SOURCE_DOCUMENT_ID_PREFIX, ""));
                long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
                String[] projection = new String[] { MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID };
                Cursor data = getContext().getContentResolver().query(
                        ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, artworkId),
                        projection, null, null, null);
                if (data == null) {
                    return false;
                }
                long artworkSourceId = data.getLong(0);
                data.close();
                // The source id of the parent must match the artwork's source id for it to be a child
                return sourceId == artworkSourceId;
            }
            return false;
        } else if (documentId != null && documentId.startsWith(SOURCE_DOCUMENT_ID_PREFIX)) {
            // Sources are children of BY_SOURCE_DOCUMENT_ID
            return BY_SOURCE_DOCUMENT_ID.equals(parentDocumentId);
        }
        return false;
    }

    @Override
    public String getDocumentType(final String documentId) throws FileNotFoundException {
        if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            return "image/*";
        }
        return DocumentsContract.Document.MIME_TYPE_DIR;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        if (ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            includeByDateRow(result.newRow());
            includeBySourceRow(result.newRow());
        } else if (BY_DATE_DOCUMENT_ID.equals(parentDocumentId)) {
            includeAllArtwork(result, getContext().getContentResolver().query(
                    MuzeiContract.Artwork.CONTENT_URI, ARTWORK_PROJECTION, null, null,
                    MuzeiContract.Artwork.DEFAULT_SORT_ORDER));
        } else if (BY_SOURCE_DOCUMENT_ID.equals(parentDocumentId)) {
            includeAllSources(result, getContext().getContentResolver().query(
                    MuzeiContract.Sources.CONTENT_URI, SOURCE_PROJECTION, null, null, null));
        } else if (parentDocumentId != null && parentDocumentId.startsWith(SOURCE_DOCUMENT_ID_PREFIX)) {
            long sourceId = Long.parseLong(parentDocumentId.replace(SOURCE_DOCUMENT_ID_PREFIX, ""));
            includeAllArtwork(result, getContext().getContentResolver().query(
                    MuzeiContract.Artwork.CONTENT_URI, ARTWORK_PROJECTION,
                    MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID + "=?",
                    new String[] { Long.toString(sourceId) },
                    null));
        }
        return result;
    }

    private void includeByDateRow(MatrixCursor.RowBuilder row) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, BY_DATE_DOCUMENT_ID);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                getContext().getString(R.string.document_by_date_display_name));
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID);
        row.add(DocumentsContract.Document.COLUMN_SIZE, null);
    }

    private void includeBySourceRow(MatrixCursor.RowBuilder row) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, BY_SOURCE_DOCUMENT_ID);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                getContext().getString(R.string.document_by_source_display_name));
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        row.add(DocumentsContract.Document.COLUMN_SIZE, null);
    }

    private void includeAllArtwork(MatrixCursor result, Cursor data) {
        if (data == null) {
            return;
        }
        Set<String> addedImageUris = new HashSet<>();
        data.moveToFirst();
        while (!data.isAfterLast()) {
            String imageUri = data.getString(data.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI));
            // We can't skip null image URIs (those are unique images), but we do want to skip
            // duplicates of the same underlying artwork as determined by a shared image URI
            if (TextUtils.isEmpty(imageUri) || !addedImageUris.contains(imageUri)) {
                if (!TextUtils.isEmpty(imageUri)) {
                    addedImageUris.add(imageUri);
                }
                final MatrixCursor.RowBuilder row = result.newRow();
                long id = data.getLong(data.getColumnIndex(BaseColumns._ID));
                row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        ARTWORK_DOCUMENT_ID_PREFIX + Long.toString(id));
                String title = data.getString(data.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_TITLE));
                row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, title);
                String byline = data.getString(data.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_BYLINE));
                row.add(DocumentsContract.Document.COLUMN_SUMMARY, byline);
                row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/*");
                row.add(DocumentsContract.Document.COLUMN_FLAGS,
                        DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL |
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
                row.add(DocumentsContract.Document.COLUMN_SIZE, null);
            }
            data.moveToNext();
        }
        data.close();
    }

    private void includeAllSources(MatrixCursor result, Cursor data) {
        if (data == null) {
            return;
        }
        data.moveToFirst();
        while (!data.isAfterLast()) {
            final MatrixCursor.RowBuilder row = result.newRow();
            long id = data.getLong(data.getColumnIndex(BaseColumns._ID));
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    SOURCE_DOCUMENT_ID_PREFIX + Long.toString(id));
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
            ComponentName componentName = ComponentName.unflattenFromString(
                    data.getString(data.getColumnIndex(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME)));
            Intent sourceIntent = new Intent();
            sourceIntent.setComponent(componentName);
            PackageManager packageManager = getContext().getPackageManager();
            List<ResolveInfo> resolveInfoList = packageManager.queryIntentServices(sourceIntent, 0);
            if (resolveInfoList.isEmpty()) {
                row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, componentName.getShortClassName());
                continue;
            }
            ResolveInfo resolveInfo = resolveInfoList.get(0);
            String title = resolveInfo.loadLabel(packageManager).toString();
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, title);
            String description = data.getString(data.getColumnIndex(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION));
            if (TextUtils.isEmpty(description)) {
                // Load the default description
                try {
                    Context packageContext = getContext().createPackageContext(
                            componentName.getPackageName(), 0);
                    Resources packageRes = packageContext.getResources();
                    description = packageRes.getString(resolveInfo.serviceInfo.descriptionRes);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (!TextUtils.isEmpty(description)) {
                row.add(DocumentsContract.Document.COLUMN_SUMMARY, description);
            }
            data.moveToNext();
        }
        data.close();
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    getContext().getString(R.string.app_name));
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
        } else if (BY_DATE_DOCUMENT_ID.equals(documentId)) {
            includeByDateRow(result.newRow());
        } else if (BY_SOURCE_DOCUMENT_ID.equals(documentId)) {
            includeBySourceRow(result.newRow());
        } else if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
            includeAllArtwork(result, getContext().getContentResolver().query(
                    ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, artworkId),
                    ARTWORK_PROJECTION, null, null, null));
        } else if (documentId != null && documentId.startsWith(SOURCE_DOCUMENT_ID_PREFIX)) {
            long sourceId = Long.parseLong(documentId.replace(SOURCE_DOCUMENT_ID_PREFIX, ""));
            includeAllSources(result, getContext().getContentResolver().query(
                    ContentUris.withAppendedId(MuzeiContract.Sources.CONTENT_URI, sourceId),
                    SOURCE_PROJECTION, null, null, null));
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            return null;
        }
        long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
        return getContext().getContentResolver().openFileDescriptor(
                ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, artworkId), mode, signal);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(final String documentId, final Point sizeHint, final CancellationSignal signal) throws FileNotFoundException {
        if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
            Uri artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, artworkId);
            return openArtworkThumbnail(artworkUri, sizeHint, signal);
        }
        return null;
    }

    private AssetFileDescriptor openArtworkThumbnail(final Uri artworkUri, final Point sizeHint, final CancellationSignal signal) throws FileNotFoundException {
        ContentResolver contentResolver = getContext().getContentResolver();
        File tempFile = getCacheFileForArtworkUri(artworkUri);
        if (tempFile != null && tempFile.exists() && tempFile.length() != 0) {
            // We already have a cached thumbnail
            return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(contentResolver.openInputStream(artworkUri), null, options);
        if (signal.isCanceled()) {
            // Canceled, so we'll stop here to save us the effort of actually decoding the image
            return null;
        }
        final int targetHeight = 2 * sizeHint.y;
        final int targetWidth = 2 * sizeHint.x;
        final int height = options.outHeight;
        final int width = options.outWidth;
        options.inSampleSize = 1;
        if (height > targetHeight || width > targetWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / options.inSampleSize) > targetHeight
                    || (halfWidth / options.inSampleSize) > targetWidth) {
                options.inSampleSize *= 2;
            }
        }
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(artworkUri), null, options);
        // Write out the thumbnail to a temporary file
        FileOutputStream out = null;
        try {
            if (tempFile == null) {
                tempFile = File.createTempFile("thumbnail", null, getContext().getCacheDir());
            }
            out = new FileOutputStream(tempFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
        } catch (IOException e) {
            Log.e(TAG, "Error writing thumbnail", e);
            return null;
        } finally {
            if (out != null)
                try {
                    out.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing thumbnail", e);
                }
        }
        return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    // This is very similar to MuzeiProvider.getCacheFileForArtworkUri, but uses the getCacheDir()
    private File getCacheFileForArtworkUri(@NonNull Uri artworkUri) {
        File directory = new File(getContext().getCacheDir(), "artwork_thumbnails");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        String[] projection = { BaseColumns._ID, MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI };
        Cursor data = getContext().getContentResolver().query(artworkUri, projection, null, null, null);
        if (data == null) {
            return null;
        }
        if (!data.moveToFirst()) {
            throw new IllegalStateException("Invalid URI: " + artworkUri);
        }
        // While normally we'd use data.getLong(), we later need this as a String so the automatic conversion helps here
        String id = data.getString(0);
        String imageUri = data.getString(1);
        data.close();
        if (TextUtils.isEmpty(imageUri)) {
            return new File(directory, id);
        }
        // Otherwise, create a unique filename based on the imageUri
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
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            return;
        }
        long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
        Uri artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI, artworkId);
        // Delete any thumbnail we have cached
        File thumbnail = getCacheFileForArtworkUri(artworkUri);
        if (thumbnail != null && thumbnail.exists()) {
            thumbnail.delete();
        }

        // Since we filter out subsequent artwork with the same non-null artwork URI,
        // this deleteDocument() really means 'delete all instances of that artwork URI'.
        // This forces us to do a little more work here to really delete all instances of the
        // artwork.

        // First we check the image URI
        Cursor data = getContext().getContentResolver().query(artworkUri,
                new String[] { MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI }, null, null, null);
        if (data == null) {
            return;
        }
        if (!data.moveToFirst()) {
            throw new IllegalStateException("Invalid URI: " + artworkUri);
        }
        String imageUri = data.getString(0);
        data.close();

        if (TextUtils.isEmpty(imageUri)) {
            // The easy case: this is a unique image URI and we can just delete the single row.
            revokeDocumentPermission(documentId);
            // Clear the calling identity to denote that this delete request is coming from
            // Muzei itself, even if it is on behalf of the user or an app the user has trusted
            long token = Binder.clearCallingIdentity();
            getContext().getContentResolver().delete(artworkUri, null, null);
            Binder.restoreCallingIdentity(token);
        } else {
            // The hard case: we're actually deleting every row with that image URI
            data = getContext().getContentResolver().query(MuzeiContract.Artwork.CONTENT_URI,
                    new String[] { BaseColumns._ID },
                    MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI + "=?",
                    new String[] { imageUri }, null);
            if (data == null) {
                return;
            }
            data.moveToFirst();
            while (!data.isAfterLast()) {
                // We want to make sure every document being deleted is revoked
                revokeDocumentPermission(ARTWORK_DOCUMENT_ID_PREFIX + data.getLong(0));
                data.moveToNext();
            }
            data.close();
            // Clear the calling identity to denote that this delete request is coming from
            // Muzei itself, even if it is on behalf of the user or an app the user has trusted
            long token = Binder.clearCallingIdentity();
            getContext().getContentResolver().delete(
                    MuzeiContract.Artwork.CONTENT_URI,
                    MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI + "=?",
                    new String[] { imageUri });
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }
}
