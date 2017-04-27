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

import android.annotation.SuppressLint;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.media.ExifInterface;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GalleryArtSource extends MuzeiArtSource {
    private static final String TAG = "GalleryArtSource";
    private static final String SOURCE_NAME = "GalleryArtSource";

    public static final String PREF_ROTATE_INTERVAL_MIN = "rotate_interval_min";

    public static final int DEFAULT_ROTATE_INTERVAL_MIN = 60 * 6;

    static final String ACTION_BIND_GALLERY
            = "com.google.android.apps.muzei.gallery.BIND_GALLERY";
    public static final String ACTION_PUBLISH_NEXT_GALLERY_ITEM
            = "com.google.android.apps.muzei.gallery.action.PUBLISH_NEXT_GALLERY_ITEM";
    public static final String EXTRA_FORCE_URI
            = "com.google.android.apps.muzei.gallery.extra.FORCE_URI";

    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat sExifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

    private static final Set<String> sOmitCountryCodes = new HashSet<>();
    static {
        sOmitCountryCodes.add("US");
    }

    private Geocoder mGeocoder;
    private ContentObserver mContentObserver;

    public GalleryArtSource() {
        super(SOURCE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mGeocoder = new Geocoder(this);
        mContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                // Update the metadata
                updateMeta();

                // See if we've just added the very first image
                Artwork currentArtwork = getCurrentArtwork();
                if (currentArtwork == null) {
                    publishNextArtwork(null);
                    return;
                }

                // See if the current artwork was removed
                Uri currentArtworkUri = currentArtwork.getToken() != null
                        ? Uri.parse(currentArtwork.getToken())
                        : null;
                if (uri.equals(currentArtworkUri)) {
                    // We're showing a removed URI
                    publishNextArtwork(null);
                }
            }
        };
        // Make any changes since the last time the GalleryArtSource was created
        mContentObserver.onChange(false, GalleryContract.ChosenPhotos.CONTENT_URI);
        getContentResolver().registerContentObserver(GalleryContract.ChosenPhotos.CONTENT_URI, true, mContentObserver);
    }

    @Override
    public IBinder onBind(final Intent intent) {
        if (intent != null && TextUtils.equals(intent.getAction(), ACTION_BIND_GALLERY)) {
            return new Binder();
        }
        return super.onBind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(mContentObserver);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        super.onHandleIntent(intent);
        if (intent != null && ACTION_PUBLISH_NEXT_GALLERY_ITEM.equals(intent.getAction())) {
            Uri forceUri = null;
            if (intent.hasExtra(EXTRA_FORCE_URI)) {
                forceUri = intent.getParcelableExtra(EXTRA_FORCE_URI);
            }

            publishNextArtwork(forceUri);
        }
    }

    @Override
    protected void onUpdate(@UpdateReason int reason) {
        if (reason == UPDATE_REASON_INITIAL) {
            updateMeta();
        }
        publishNextArtwork(null);
    }

    static SharedPreferences getSharedPreferences(Context context) {
        return MuzeiArtSource.getSharedPreferences(context, SOURCE_NAME);
    }

    private void publishNextArtwork(Uri forceUri) {
        // schedule next
        scheduleNext();

        Cursor chosenUris = getContentResolver().query(GalleryContract.ChosenPhotos.CONTENT_URI,
                new String[] { BaseColumns._ID, GalleryContract.ChosenPhotos.COLUMN_NAME_URI,
                        GalleryContract.ChosenPhotos.COLUMN_NAME_IS_TREE_URI },
                null, null, null);
        int numChosenUris = (chosenUris != null) ? chosenUris.getCount() : 0;

        Artwork currentArtwork = getCurrentArtwork();
        String lastToken = (currentArtwork != null) ? currentArtwork.getToken() : null;

        Uri imageUri;
        Random random = new Random();
        if (forceUri != null) {
            imageUri = forceUri;

        } else if (numChosenUris > 0) {
            // First build a list of all image URIs, recursively exploring any tree URIs that were added
            List<Uri> allImages = new ArrayList<>(numChosenUris);
            while (chosenUris.moveToNext()) {
                Uri chosenUri = ContentUris.withAppendedId(GalleryContract.ChosenPhotos.CONTENT_URI,
                        chosenUris.getLong(chosenUris.getColumnIndex(BaseColumns._ID)));
                boolean isTreeUri = chosenUris.getInt(
                        chosenUris.getColumnIndex(GalleryContract.ChosenPhotos.COLUMN_NAME_IS_TREE_URI)) != 0;
                if (isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Uri treeUri = Uri.parse(chosenUris.getString(
                            chosenUris.getColumnIndex(GalleryContract.ChosenPhotos.COLUMN_NAME_URI)));
                    addAllImagesFromTree(allImages, treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                } else {
                    allImages.add(chosenUri);
                }
            }
            int numImages = allImages.size();
            if (numImages == 0) {
                Log.e(TAG, "No photos in the selected directories.");
                return;
            }
            while (true) {
                imageUri = allImages.get(random.nextInt(numImages));
                if (numImages <= 1 || !imageUri.toString().equals(lastToken)) {
                    break;
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing read external storage permission.");
                return;
            }
            Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] { MediaStore.MediaColumns._ID },
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " NOT LIKE '%Screenshots%'",
                    null, null);
            if (cursor == null) {
                Log.w(TAG, "Empty cursor.");
                return;
            }

            int count = cursor.getCount();
            if (count == 0) {
                Log.e(TAG, "No photos in the gallery.");
                return;
            }

            while (true) {
                cursor.moveToPosition(random.nextInt(count));
                imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0));
                if (!imageUri.toString().equals(lastToken)) {
                    break;
                }
            }

            cursor.close();
        }
        if (chosenUris != null) {
            chosenUris.close();
        }

        String token = imageUri.toString();

        // Retrieve metadata for item
        ensureMetadataExists(imageUri);
        String[] projection = {
                GalleryContract.MetadataCache.COLUMN_NAME_DATETIME,
                GalleryContract.MetadataCache.COLUMN_NAME_LOCATION};
        Cursor metadata = getContentResolver().query(GalleryContract.MetadataCache.CONTENT_URI,
                projection,
                GalleryContract.MetadataCache.COLUMN_NAME_URI + "=?",
                new String[] { imageUri.toString() },
                null);
        long datetime = 0;
        String location = null;
        if (metadata != null && metadata.moveToFirst()) {
            datetime = metadata.getLong(metadata.getColumnIndex(GalleryContract.MetadataCache.COLUMN_NAME_DATETIME));
            location = metadata.getString(metadata.getColumnIndex(GalleryContract.MetadataCache.COLUMN_NAME_LOCATION));
        }
        if (metadata != null) {
            metadata.close();
        }

        // Publish the actual artwork
        String title;
        if (datetime > 0) {
            title = DateUtils.formatDateTime(this, datetime,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                            | DateUtils.FORMAT_SHOW_WEEKDAY);
        } else {
            title = getString(R.string.gallery_from_gallery);
        }

        String byline;
        if (!TextUtils.isEmpty(location)) {
            byline = location;
        } else {
            byline = getString(R.string.gallery_touch_to_view);
        }

        publishArtwork(new Artwork.Builder()
                .imageUri(imageUri)
                .title(title)
                .byline(byline)
                .token(token)
                .viewIntent(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(imageUri, "image/jpeg"))
                .build());
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private int addAllImagesFromTree(final List<Uri> allImages, final Uri treeUri, final String parentDocumentId) {
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                parentDocumentId);
        Cursor children = null;
        try {
            children = getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error reading " + childrenUri, e);
        }
        if (children == null) {
            return 0;
        }
        int numImagesAdded = 0;
        while (children.moveToNext()) {
            String documentId = children.getString(
                    children.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
            String mimeType = children.getString(
                    children.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                // Recursively explore all directories
                numImagesAdded += addAllImagesFromTree(allImages, treeUri, documentId);
            } else if (mimeType != null && mimeType.startsWith("image/")) {
                // Add images to the list
                if (allImages != null) {
                    allImages.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
                }
                numImagesAdded++;
            }
        }
        children.close();
        return numImagesAdded;
    }

    private void updateMeta() {
        Cursor chosenUris = getContentResolver().query(GalleryContract.ChosenPhotos.CONTENT_URI,
                new String[] {
                        BaseColumns._ID,
                        GalleryContract.ChosenPhotos.COLUMN_NAME_IS_TREE_URI,
                        GalleryContract.ChosenPhotos.COLUMN_NAME_URI },
                null, null, null);
        int numImages = 0;
        ArrayList<ContentProviderOperation> rowsToDelete = new ArrayList<>();
        while (chosenUris != null && chosenUris.moveToNext()) {
            boolean isTreeUri = chosenUris.getInt(
                    chosenUris.getColumnIndex(GalleryContract.ChosenPhotos.COLUMN_NAME_IS_TREE_URI)) != 0;
            if (isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Uri treeUri = Uri.parse(chosenUris.getString(
                        chosenUris.getColumnIndex(GalleryContract.ChosenPhotos.COLUMN_NAME_URI)));
                try {
                    numImages += addAllImagesFromTree(null, treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                } catch (SecurityException e) {
                    Log.w(TAG, "Unable to load images from " + treeUri + ", deleting row", e);
                    rowsToDelete.add(ContentProviderOperation.newDelete(
                            ContentUris.withAppendedId(GalleryContract.ChosenPhotos.CONTENT_URI,
                                    chosenUris.getLong(chosenUris.getColumnIndex(BaseColumns._ID)))).build());
                }
            } else {
                numImages++;
            }
        }
        if (chosenUris != null) {
            chosenUris.close();
        }
        if (!rowsToDelete.isEmpty()) {
            try {
                getContentResolver().applyBatch(GalleryContract.AUTHORITY, rowsToDelete);
            } catch (RemoteException | OperationApplicationException e) {
                Log.e(TAG, "Error deleting invalid rows", e);
            }
        }
        setDescription(numImages > 0
                ? getResources().getQuantityString(
                R.plurals.gallery_description_choice_template,
                numImages, numImages)
                : getString(R.string.gallery_description));
        if (numImages != 1) {
            setUserCommands(BUILTIN_COMMAND_ID_NEXT_ARTWORK);
        } else {
            removeAllUserCommands();
        }
    }

    private void scheduleNext() {
        int rotateIntervalMinutes = getSharedPreferences().getInt(PREF_ROTATE_INTERVAL_MIN,
                DEFAULT_ROTATE_INTERVAL_MIN);
        if (rotateIntervalMinutes > 0) {
            scheduleUpdate(System.currentTimeMillis() + rotateIntervalMinutes * 60 * 1000);
        }
    }

    private void ensureMetadataExists(@NonNull Uri imageUri) {
        Cursor existingMetadata = getContentResolver().query(GalleryContract.MetadataCache.CONTENT_URI,
                new String[] {BaseColumns._ID},
                GalleryContract.MetadataCache.COLUMN_NAME_URI + "=?",
                new String[] { imageUri.toString() },
                null);
        if (existingMetadata == null) {
            return;
        }
        boolean metadataExists = existingMetadata.moveToFirst();
        existingMetadata.close();
        if (!metadataExists) {
            // No cached metadata or it's stale, need to pull it separately using Exif
            ContentValues values = new ContentValues();
            values.put(GalleryContract.MetadataCache.COLUMN_NAME_URI, imageUri.toString());

            try (InputStream in = getContentResolver().openInputStream(imageUri)) {
                if (in == null) {
                    return;
                }
                ExifInterface exifInterface = new ExifInterface(in);
                String dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
                if (!TextUtils.isEmpty(dateString)) {
                    Date date = sExifDateFormat.parse(dateString);
                    values.put(GalleryContract.MetadataCache.COLUMN_NAME_DATETIME, date.getTime());
                }

                double[] latlong = exifInterface.getLatLong();
                if (latlong != null) {
                    // Reverse geocode
                    List<Address> addresses = null;
                    try {
                        addresses = mGeocoder.getFromLocation(latlong[0], latlong[1], 1);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Invalid latitude/longitude, skipping location metadata", e);
                    }
                    if (addresses != null && addresses.size() > 0) {
                        Address addr = addresses.get(0);
                        String locality = addr.getLocality();
                        String adminArea = addr.getAdminArea();
                        String countryCode = addr.getCountryCode();
                        StringBuilder sb = new StringBuilder();
                        if (!TextUtils.isEmpty(locality)) {
                            sb.append(locality);
                        }
                        if (!TextUtils.isEmpty(adminArea)) {
                            if (sb.length() > 0) {
                                sb.append(", ");
                            }
                            sb.append(adminArea);
                        }
                        if (!TextUtils.isEmpty(countryCode)
                                && !sOmitCountryCodes.contains(countryCode)) {
                            if (sb.length() > 0) {
                                sb.append(", ");
                            }
                            sb.append(countryCode);
                        }
                        values.put(GalleryContract.MetadataCache.COLUMN_NAME_LOCATION, sb.toString());
                    }
                }

                getContentResolver().insert(GalleryContract.MetadataCache.CONTENT_URI, values);
            } catch (ParseException|IOException|NumberFormatException|StackOverflowError e) {
                Log.w(TAG, "Couldn't read image metadata.", e);
            }
        }
    }
}
