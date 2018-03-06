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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;

import net.nurik.roman.muzei.androidclientcommon.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

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
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED};

    private static final String ROOT_DOCUMENT_ID = "root";

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "Muzei");
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher);
        row.add(DocumentsContract.Root.COLUMN_TITLE, context.getString(R.string.app_name));
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY |
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH |
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/png");
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        return result;
    }

    @Override
    public Cursor querySearchDocuments(final String rootId, final String query, final String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }

        String likeAnyPositionQuery = "%" + query + "%";
        includeAllArtwork(result, MuzeiDatabase.getInstance(context).artworkDao()
                .searchArtworkBlocking(likeAnyPositionQuery));
        return result;
    }

    @Override
    public boolean isChildDocument(final String parentDocumentId, final String documentId) {
        // The only parent is the Root and it is the parent of everything
        return ROOT_DOCUMENT_ID.equals(parentDocumentId);
    }

    @Override
    public String getDocumentType(final String documentId) throws FileNotFoundException {
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        return "image/png";
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        if (ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            includeAllArtwork(result, MuzeiDatabase.getInstance(context).artworkDao()
                    .getArtworkBlocking());
        }
        return result;
    }

    private void includeAllArtwork(MatrixCursor result, @NonNull List<Artwork> artworkList) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        for (Artwork artwork : artworkList) {
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    Long.toString(artwork.id));
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, artwork.title);
            row.add(DocumentsContract.Document.COLUMN_SUMMARY, artwork.byline);
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/png");
            // Don't allow deleting the currently displayed artwork
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, artwork.dateAdded.getTime());
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        Context context = getContext();
        if (context == null) {
            return result;
        }
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    getContext().getString(R.string.app_name));
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID |
                    DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
        } else {
            long artworkId = Long.parseLong(documentId);
            final Artwork artwork =
                    MuzeiDatabase.getInstance(context).artworkDao().getArtworkById(artworkId);
            if (artwork != null) {
                includeAllArtwork(result, Collections.singletonList(artwork));
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        long artworkId = Long.parseLong(documentId);
        return contentResolver.openFileDescriptor(Artwork.getContentUri(artworkId), mode, signal);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(final String documentId, final Point sizeHint,
            @Nullable final CancellationSignal signal) throws FileNotFoundException {
        long artworkId = Long.parseLong(documentId);
        return openArtworkThumbnail(Artwork.getContentUri(artworkId), sizeHint, signal);
    }

    private AssetFileDescriptor openArtworkThumbnail(final Uri artworkUri, final Point sizeHint,
            @Nullable final CancellationSignal signal) throws FileNotFoundException {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        long artworkId = ContentUris.parseId(artworkUri);
        File tempFile = getCacheFileForArtworkUri(artworkId);
        if (tempFile.exists() && tempFile.length() != 0) {
            // We already have a cached thumbnail
            return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = contentResolver.openInputStream(artworkUri)) {
            BitmapFactory.decodeStream(input, null, options);
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to decode artwork");
        }
        if (signal != null && signal.isCanceled()) {
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
        Bitmap bitmap;
        try (InputStream input = contentResolver.openInputStream(artworkUri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to decode artwork");
        }
        // Write out the thumbnail to a temporary file
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
        } catch (IOException e) {
            Log.e(TAG, "Error writing thumbnail", e);
            return null;
        }
        return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @NonNull
    private File getCacheFileForArtworkUri(long artworkId) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Unable to create cache directory");
        }
        File directory = new File(context.getCacheDir(), "artwork_thumbnails");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new FileNotFoundException("Unable to create cache directory");
        }
        Artwork artwork = MuzeiDatabase.getInstance(context).artworkDao().getArtworkById(artworkId);
        if (artwork == null) {
            throw new FileNotFoundException("Unable to get artwork for id " + artworkId);
        }
        return new File(directory, Long.toString(artwork.id));
    }

    @Override
    public boolean onCreate() {
        return true;
    }
}
