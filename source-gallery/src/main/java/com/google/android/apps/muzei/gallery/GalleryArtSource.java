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
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.Observer;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GalleryArtSource extends MuzeiArtSource implements LifecycleOwner {
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

    private static final Random sRandom = new Random();

    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat sExifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

    private static final Set<String> sOmitCountryCodes = new HashSet<>();
    static {
        sOmitCountryCodes.add("US");
    }

    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
    private Geocoder mGeocoder;

    public GalleryArtSource() {
        super(SOURCE_NAME);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        mGeocoder = new Geocoder(this);

        GalleryDatabase.getInstance(this).chosenPhotoDao().getChosenPhotos().observe(this,
                new Observer<List<ChosenPhoto>>() {
                    private int numImages = -1;

                    @Override
                    public void onChanged(@Nullable final List<ChosenPhoto> chosenPhotos) {
                        int oldCount = numImages;
                        // Update the metadata
                        numImages = updateMeta(chosenPhotos);

                        Artwork currentArtwork = getCurrentArtwork();
                        Uri currentArtworkToken = currentArtwork != null && currentArtwork.getToken() != null
                                ? Uri.parse(currentArtwork.getToken())
                                : null;
                        boolean foundCurrentArtwork = false;
                        if (chosenPhotos != null) {
                            for (ChosenPhoto chosenPhoto : chosenPhotos) {
                                Uri chosenPhotoUri = chosenPhoto.getContentUri();
                                if (chosenPhotoUri.equals(currentArtworkToken)) {
                                    foundCurrentArtwork = true;
                                    break;
                                }
                            }
                        }
                        if (!foundCurrentArtwork) {
                            // We're showing a removed URI
                            startService(new Intent(GalleryArtSource.this, GalleryArtSource.class)
                                    .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM));
                        } else if (oldCount == 0 && numImages > 0) {
                            // If we've transitioned from a count of zero to a count greater than zero
                            startService(new Intent(GalleryArtSource.this, GalleryArtSource.class)
                                    .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                                    .putExtra(EXTRA_FORCE_URI, chosenPhotos.get(0).getContentUri()));
                        }
                    }
                });
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
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
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
            updateMeta(GalleryDatabase.getInstance(this).chosenPhotoDao().getChosenPhotosBlocking());
        }
        publishNextArtwork(null);
    }

    static SharedPreferences getSharedPreferences(Context context) {
        return MuzeiArtSource.getSharedPreferences(context, SOURCE_NAME);
    }

    private void publishNextArtwork(Uri forceUri) {
        // schedule next
        scheduleNext();

        List<ChosenPhoto> chosenPhotos = GalleryDatabase.getInstance(this)
                .chosenPhotoDao().getChosenPhotosBlocking();
        int numChosenUris = (chosenPhotos != null) ? chosenPhotos.size() : 0;

        Artwork currentArtwork = getCurrentArtwork();
        Uri lastImageUri = (currentArtwork != null) ? currentArtwork.getImageUri() : null;

        Uri imageUri;
        Uri token;
        if (forceUri != null) {
            // Assume the forceUri is to a single image
            imageUri = token = forceUri;
            // But if it is a tree URI, pick a random image to show from that tree
            ChosenPhoto chosenPhoto = GalleryDatabase.getInstance(this)
                    .chosenPhotoDao().getChosenPhotoBlocking(ContentUris.parseId(forceUri));
            if (chosenPhoto != null)  {
                if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Uri treeUri = chosenPhoto.uri;
                    List<Uri> photoUris = new ArrayList<>();
                    addAllImagesFromTree(photoUris, treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                    imageUri = photoUris.get(new Random().nextInt(photoUris.size()));
                }
            }
        } else if (numChosenUris > 0) {
            // First build a list of all image URIs, recursively exploring any tree URIs that were added
            List<Uri> allImages = new ArrayList<>(numChosenUris);
            List<Uri> tokens = new ArrayList<>(numChosenUris);
            for (ChosenPhoto chosenPhoto : chosenPhotos) {
                Uri chosenUri = chosenPhoto.getContentUri();
                if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Uri treeUri = chosenPhoto.uri;
                    int numAdded = addAllImagesFromTree(allImages, treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri));
                    for (int h=0; h<numAdded; h++) {
                        tokens.add(chosenUri);
                    }
                } else {
                    allImages.add(chosenUri);
                    tokens.add(chosenUri);
                }
            }
            int numImages = allImages.size();
            if (numImages == 0) {
                Log.e(TAG, "No photos in the selected directories.");
                return;
            }
            while (true) {
                int index = sRandom.nextInt(numImages);
                imageUri = allImages.get(index);
                if (numImages <= 1 || !imageUri.equals(lastImageUri)) {
                    token = tokens.get(index);
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
                cursor.moveToPosition(sRandom.nextInt(count));
                imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0));
                if (!imageUri.equals(lastImageUri)) {
                    token = imageUri;
                    break;
                }
            }

            cursor.close();
        }

        // Retrieve metadata for item
        Metadata metadata = ensureMetadataExists(imageUri);

        // Publish the actual artwork
        String title;
        if (metadata != null && metadata.date != null) {
            title = DateUtils.formatDateTime(this, metadata.date.getTime(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                            | DateUtils.FORMAT_SHOW_WEEKDAY);
        } else {
            title = getString(R.string.gallery_from_gallery);
        }

        String byline;
        if (metadata != null && !TextUtils.isEmpty(metadata.location)) {
            byline = metadata.location;
        } else {
            byline = getString(R.string.gallery_touch_to_view);
        }

        publishArtwork(new Artwork.Builder()
                .imageUri(imageUri)
                .title(title)
                .byline(byline)
                .token(token.toString())
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
        } catch (NullPointerException|IllegalArgumentException|SecurityException e) {
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

    private int updateMeta(List<ChosenPhoto> chosenPhotos) {
        int numImages = 0;
        final ArrayList<Long> idsToDelete = new ArrayList<>();
        for (ChosenPhoto chosenPhoto : chosenPhotos) {
            if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Uri treeUri = chosenPhoto.uri;
                try {
                    numImages += addAllImagesFromTree(null, treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                } catch (SecurityException e) {
                    Log.w(TAG, "Unable to load images from " + treeUri + ", deleting row", e);
                    idsToDelete.add(chosenPhoto.id);
                }
            } else {
                numImages++;
            }
        }
        if (!idsToDelete.isEmpty()) {
            final Context applicationContext = getApplicationContext();
            new Thread() {
                @Override
                public void run() {
                    GalleryDatabase.getInstance(applicationContext).chosenPhotoDao()
                            .delete(applicationContext, idsToDelete);
                }
            }.start();
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
        return numImages;
    }

    private void scheduleNext() {
        int rotateIntervalMinutes = getSharedPreferences().getInt(PREF_ROTATE_INTERVAL_MIN,
                DEFAULT_ROTATE_INTERVAL_MIN);
        if (rotateIntervalMinutes > 0) {
            scheduleUpdate(System.currentTimeMillis() + rotateIntervalMinutes * 60 * 1000);
        }
    }

    private Metadata ensureMetadataExists(@NonNull Uri imageUri) {
        MetadataDao metadataDao = GalleryDatabase.getInstance(this).metadataDao();
        Metadata existingMetadata = metadataDao.getMetadataForUri(imageUri);
        if (existingMetadata != null) {
            return existingMetadata;
        }
        // No cached metadata or it's stale, need to pull it separately using Exif
        Metadata metadata = new Metadata(imageUri);

        try (InputStream in = getContentResolver().openInputStream(imageUri)) {
            if (in == null) {
                return null;
            }
            ExifInterface exifInterface = new ExifInterface(in);
            String dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
            if (!TextUtils.isEmpty(dateString)) {
                metadata.date = sExifDateFormat.parse(dateString);
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
                    metadata.location = sb.toString();
                }
            }

            metadataDao.insert(metadata);
            return metadata;
        } catch (ParseException|IOException|IllegalArgumentException|StackOverflowError
                |NullPointerException|SecurityException e) {
            Log.w(TAG, "Couldn't read image metadata.", e);
        }
        return null;
    }
}
