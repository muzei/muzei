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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
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
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.ArtworkDao;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

import net.nurik.roman.muzei.androidclientcommon.BuildConfig;
import net.nurik.roman.muzei.androidclientcommon.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * DocumentsProvider that allows users to view previous Muzei wallpapers
 */
public class MuzeiDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "MuzeiDocumentsProvider";
    /**
     * Method to manually persist a URI permission, required on API 25 or lower
     * devices when attempting to persist URIs from our own package.
     * @see #call(String, String, Bundle)
     */
    private static final String TAKE_PERSISTABLE_URI_PERMISSION = "takePersistableUriPermission";
    /**
     * Method to manually release a persisted a URI permission, required on API 25 or lower
     * devices when attempting to persist URIs from our own package.
     * @see #call(String, String, Bundle)
     */
    private static final String RELEASE_PERSISTABLE_URI_PERMISSION = "releasePersistableUriPermission";
    /**
     * On API 25 or lower devices, we don't get URI permissions to URIs
     * from our own package so we store them manually with this key.
     */
    private static final String KEY_PERSISTED_URIS = "KEY_PERSISTED_URIS";
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
    private static final String BY_DATE_DOCUMENT_ID = "by_date";
    private static final String BY_SOURCE_DOCUMENT_ID = "by_source";

    private static final String ARTWORK_DOCUMENT_ID_PREFIX = "artwork_";
    private static final String SOURCE_DOCUMENT_ID_PREFIX = "source_";

    /**
     * Transform a Document URI into the underlying Artwork URI
     */
    private static Uri getArtworkUriForDocumentUri(Uri documentUri) {
        try {
            String documentId = DocumentsContract.getDocumentId(documentUri);
            if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
                long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
                return Artwork.getContentUri(artworkId);
            }
        } catch (IllegalArgumentException e) {
            // We'll get an IllegalArgumentException on tree URIs, but these don't correspond with
            // individual artwork, so we can ignore them
        }
        return null;
    }

    /**
     * Gets the set of artwork that other apps (or other parts of Muzei) have persistent access to.
     * This artwork should be preserved for as long as possible to avoid breaking others that are relying
     * on the artwork being persistently available.
     *
     * @param context Context used to retrieve the persisted URIs
     * @return A Set of {@link MuzeiContract.Artwork artwork} URIs that have persisted access
     */
    static Set<Uri> getPersistedArtworkUris(@NonNull Context context) {
        // Get the set of persisted URIs - we never want to delete persisted artwork
        List<UriPermission> persistedUriPermissions = context.getContentResolver().getPersistedUriPermissions();
        TreeSet<Uri> persistedUris = new TreeSet<>();
        for (UriPermission persistedUriPermission : persistedUriPermissions) {
            Uri persistedUri = persistedUriPermission.getUri();
            // Translate MuzeiDocumentsProvider URIs to artwork URIs
            if (persistedUri.getAuthority().equals(BuildConfig.DOCUMENTS_AUTHORITY)) {
                persistedUri = getArtworkUriForDocumentUri(persistedUri);
                if (persistedUri != null) {
                    persistedUris.add(persistedUri);
                }
            }
        }
        // On API 25 or lower devices, we don't get URI permissions to URIs
        // from our own package so we need to store them manually.
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        for (String persistedUri : preferences.getStringSet(KEY_PERSISTED_URIS, new TreeSet<String>())) {
            persistedUris.add(Uri.parse(persistedUri));
        }
        return persistedUris;
    }

    /**
     * On API 25 or lower devices, we don't get URI permissions to URIs
     * from our own package so we manage those URI permissions manually by
     * passing through requests here.
     *
     * {@inheritDoc}
     */
    @Override
    public Bundle call(@NonNull final String method, final String arg, final Bundle extras) {
        if (TAKE_PERSISTABLE_URI_PERMISSION.equals(method)){
            Uri artworkUri = getArtworkUriForDocumentUri(Uri.parse(arg));
            if (artworkUri != null) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                Set<String> persistedUris = preferences.getStringSet(KEY_PERSISTED_URIS, new TreeSet<String>());
                if (persistedUris.add(artworkUri.toString())) {
                    preferences.edit().putStringSet(KEY_PERSISTED_URIS, persistedUris).apply();
                }
            }
            return new Bundle();
        } else if (RELEASE_PERSISTABLE_URI_PERMISSION.equals(method)) {
            Uri artworkUri = getArtworkUriForDocumentUri(Uri.parse(arg));
            if (artworkUri != null) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                Set<String> persistedUris = preferences.getStringSet(KEY_PERSISTED_URIS, new TreeSet<String>());
                if (persistedUris.remove(artworkUri.toString())) {
                    preferences.edit().putStringSet(KEY_PERSISTED_URIS, persistedUris).apply();
                }
            }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

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
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*");
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
        if (ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            // Everything is a child of ROOT_DOCUMENT_ID
            return true;
        } else if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            if (BY_DATE_DOCUMENT_ID.equals(parentDocumentId) || BY_SOURCE_DOCUMENT_ID.equals(parentDocumentId)) {
                // All artwork is a child of BY_DATE_DOCUMENT_ID and a child of BY_SOURCE_DOCUMENT_ID
                return true;
            }
            if (parentDocumentId != null && parentDocumentId.startsWith(SOURCE_DOCUMENT_ID_PREFIX)) {
                long sourceId = Long.parseLong(parentDocumentId.replace(SOURCE_DOCUMENT_ID_PREFIX, ""));
                long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
                String[] projection = new String[] { MuzeiContract.Sources.TABLE_NAME + "." + BaseColumns._ID };
                Context context = getContext();
                if (context == null) {
                    return false;
                }
                long artworkSourceId = MuzeiDatabase.getInstance(context).sourceDao()
                        .getSourceIdForArtworkId(artworkId);
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
        Context context = getContext();
        if (context == null) {
            return result;
        }
        if (ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            includeByDateRow(result.newRow());
            includeBySourceRow(result.newRow());
        } else {
            MuzeiDatabase database = MuzeiDatabase.getInstance(context);
            if (BY_DATE_DOCUMENT_ID.equals(parentDocumentId)) {
                includeAllArtwork(result, database.artworkDao().getArtworkBlocking());
            } else if (BY_SOURCE_DOCUMENT_ID.equals(parentDocumentId)) {
                includeAllSources(result, database.sourceDao().getSourcesBlocking());
            } else if (parentDocumentId != null && parentDocumentId.startsWith(SOURCE_DOCUMENT_ID_PREFIX)) {
                long sourceId = Long.parseLong(parentDocumentId.replace(SOURCE_DOCUMENT_ID_PREFIX, ""));
                includeAllArtwork(result, database.artworkDao().getArtworkForSourceIdBlocking(sourceId));
            }
        }
        return result;
    }

    private void includeByDateRow(MatrixCursor.RowBuilder row) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, BY_DATE_DOCUMENT_ID);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                context.getString(R.string.document_by_date_display_name));
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        row.add(DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_DIR_PREFERS_GRID |
                DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
        row.add(DocumentsContract.Document.COLUMN_SIZE, null);
    }

    private void includeBySourceRow(MatrixCursor.RowBuilder row) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, BY_SOURCE_DOCUMENT_ID);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                context.getString(R.string.document_by_source_display_name));
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        row.add(DocumentsContract.Document.COLUMN_SIZE, null);
    }

    private void includeAllArtwork(MatrixCursor result, List<Artwork> artworkList) {
        if (artworkList == null) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        long currentArtworkId = MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtworkBlocking().id;
        // Add the artwork from the given List
        Set<Uri> addedImageUris = new HashSet<>();
        for (Artwork artwork : artworkList) {
            // We can't skip null image URIs (those are unique images), but we do want to skip
            // duplicates of the same underlying artwork as determined by a shared image URI
            if (artwork.imageUri == null || !addedImageUris.contains(artwork.imageUri)) {
                if (artwork.imageUri != null) {
                    addedImageUris.add(artwork.imageUri);
                }
                final MatrixCursor.RowBuilder row = result.newRow();
                row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        ARTWORK_DOCUMENT_ID_PREFIX + Long.toString(artwork.id));
                row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, artwork.title);
                row.add(DocumentsContract.Document.COLUMN_SUMMARY, artwork.byline);
                row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/*");
                // Don't allow deleting the currently displayed artwork
                row.add(DocumentsContract.Document.COLUMN_FLAGS,
                        DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL |
                        (artwork.id != currentArtworkId ? DocumentsContract.Document.FLAG_SUPPORTS_DELETE : 0));
                row.add(DocumentsContract.Document.COLUMN_SIZE, null);
                row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, artwork.dateAdded.getTime());
            }
        }
    }

    private void includeAllSources(MatrixCursor result, List<Source> sources) {
        if (sources == null) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        for (Source source : sources) {
            final MatrixCursor.RowBuilder row = result.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    SOURCE_DOCUMENT_ID_PREFIX + Long.toString(source.id));
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            row.add(DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.FLAG_DIR_PREFERS_GRID |
                    DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
            Intent sourceIntent = new Intent();
            sourceIntent.setComponent(source.componentName);
            PackageManager packageManager = context.getPackageManager();
            List<ResolveInfo> resolveInfoList = packageManager.queryIntentServices(sourceIntent, 0);
            if (resolveInfoList.isEmpty()) {
                row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, source.componentName.getShortClassName());
                continue;
            }
            ResolveInfo resolveInfo = resolveInfoList.get(0);
            String title = resolveInfo.loadLabel(packageManager).toString();
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, title);
            String description = source.description;
            if (TextUtils.isEmpty(description) && resolveInfo.serviceInfo.descriptionRes != 0) {
                // Load the default description
                try {
                    Context packageContext = context.createPackageContext(
                            source.componentName.getPackageName(), 0);
                    Resources packageRes = packageContext.getResources();
                    description = packageRes.getString(resolveInfo.serviceInfo.descriptionRes);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (!TextUtils.isEmpty(description)) {
                row.add(DocumentsContract.Document.COLUMN_SUMMARY, description);
            }
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
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
            row.add(DocumentsContract.Document.COLUMN_SIZE, null);
        } else if (BY_DATE_DOCUMENT_ID.equals(documentId)) {
            includeByDateRow(result.newRow());
        } else if (BY_SOURCE_DOCUMENT_ID.equals(documentId)) {
            includeBySourceRow(result.newRow());
        } else if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
            includeAllArtwork(result, Collections.singletonList(
                    MuzeiDatabase.getInstance(context).artworkDao().getArtworkById(artworkId)));
        } else if (documentId != null && documentId.startsWith(SOURCE_DOCUMENT_ID_PREFIX)) {
            long sourceId = Long.parseLong(documentId.replace(SOURCE_DOCUMENT_ID_PREFIX, ""));
            includeAllSources(result, Collections.singletonList(
                    MuzeiDatabase.getInstance(context).sourceDao().getSourceById(sourceId)));
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            return null;
        }
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
        return contentResolver.openFileDescriptor(Artwork.getContentUri(artworkId), mode, signal);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(final String documentId, final Point sizeHint, final CancellationSignal signal) throws FileNotFoundException {
        if (documentId != null && documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
            return openArtworkThumbnail(Artwork.getContentUri(artworkId), sizeHint, signal);
        }
        return null;
    }

    private AssetFileDescriptor openArtworkThumbnail(final Uri artworkUri, final Point sizeHint, final CancellationSignal signal) throws FileNotFoundException {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        long artworkId = ContentUris.parseId(artworkUri);
        File tempFile = getCacheFileForArtworkUri(artworkId);
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
        if (tempFile == null) {
            try {
                tempFile = File.createTempFile("thumbnail", null, getContext().getCacheDir());
            } catch (IOException e) {
                Log.e(TAG, "Error writing thumbnail", e);
                return null;
            }
        }
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
        } catch (IOException e) {
            Log.e(TAG, "Error writing thumbnail", e);
            return null;
        }
        return new AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    // This is very similar to MuzeiProvider.getCacheFileForArtworkUri, but uses the getCacheDir()
    private File getCacheFileForArtworkUri(long artworkId) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        File directory = new File(context.getCacheDir(), "artwork_thumbnails");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        Artwork artwork = MuzeiDatabase.getInstance(context).artworkDao().getArtworkById(artworkId);
        if (artwork == null) {
            return null;
        }
        if (artwork.imageUri == null) {
            return new File(directory, Long.toString(artwork.id));
        }
        // Otherwise, create a unique filename based on the imageUri
        StringBuilder filename = new StringBuilder();
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
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(artwork.imageUri.toString().getBytes("UTF-8"));
            byte[] digest = md.digest();
            for (byte b : digest) {
                if ((0xff & b) < 0x10) {
                    filename.append("0").append(Integer.toHexString((0xFF & b)));
                } else {
                    filename.append(Integer.toHexString(0xFF & b));
                }
            }
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            filename.append(artwork.imageUri.toString().hashCode());
        }
        return new File(directory, filename.toString());
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(ARTWORK_DOCUMENT_ID_PREFIX)) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        long artworkId = Long.parseLong(documentId.replace(ARTWORK_DOCUMENT_ID_PREFIX, ""));
        // Delete any thumbnail we have cached
        File thumbnail = getCacheFileForArtworkUri(artworkId);
        if (thumbnail != null && thumbnail.exists()) {
            thumbnail.delete();
        }

        // Since we filter out subsequent artwork with the same non-null artwork URI,
        // this deleteDocument() really means 'delete all instances of that artwork URI'.
        // This forces us to do a little more work here to really delete all instances of the
        // artwork.

        // First we check the image URI
        ArtworkDao artworkDao = MuzeiDatabase.getInstance(context).artworkDao();
        Artwork artwork = artworkDao.getArtworkById(artworkId);
        if (artwork == null) {
            return;
        }

        if (artwork.imageUri == null) {
            // The easy case: this is a unique image URI and we can just delete the single row.
            revokeDocumentPermission(documentId);
            artworkDao.delete(context, artwork);
        } else {
            // The hard case: we're actually deleting every row with that image URI
            List<Artwork> artworkToDelete = artworkDao.getArtworkByImageUri(artwork.imageUri);
            if (artworkToDelete == null) {
                return;
            }
            for (Artwork art : artworkToDelete) {
                // We want to make sure every document being deleted is revoked
                revokeDocumentPermission(ARTWORK_DOCUMENT_ID_PREFIX + art.id);
            }
            artworkDao.deleteByImageUri(context, artwork.imageUri);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }
}
