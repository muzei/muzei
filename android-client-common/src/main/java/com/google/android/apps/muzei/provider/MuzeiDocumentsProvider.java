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
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.androidclientcommon.R;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * DocumentsProvider that allows users to view previous Muzei wallpapers
 */
public class MuzeiDocumentsProvider extends DocumentsProvider {
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
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*");
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
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
                    MuzeiContract.Artwork.TABLE_NAME + "." + BaseColumns._ID));
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
        // TODO: Add Document.COLUMN_ICON
    }

    private void includeBySourceRow(MatrixCursor.RowBuilder row) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, BY_SOURCE_DOCUMENT_ID);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                getContext().getString(R.string.document_by_source_display_name));
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID);
        row.add(DocumentsContract.Document.COLUMN_SIZE, null);
        // TODO: Add Document.COLUMN_ICON
    }

    private void includeAllArtwork(MatrixCursor result, Cursor data) {
        if (data == null) {
            return;
        }
        data.moveToFirst();
        while (!data.isAfterLast()) {
            final MatrixCursor.RowBuilder row = result.newRow();
            long id = data.getLong(data.getColumnIndex(BaseColumns._ID));
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    ARTWORK_DOCUMENT_ID_PREFIX + Long.toString(id));
            String title = data.getString(data.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_TITLE));
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, title);
            String byline = data.getString(data.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_BYLINE));
            row.add(DocumentsContract.Document.COLUMN_SUMMARY, byline);
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/*");
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
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
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
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
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID);
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
    public boolean onCreate() {
        return true;
    }
}
