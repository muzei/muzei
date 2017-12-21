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

package com.google.android.apps.muzei.wearable;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.media.ExifInterface;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.render.ImageUtil;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.AvailabilityException;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Controller for updating Android Wear devices with new wallpapers.
 */
public class WearableController implements LifecycleObserver {
    private static final String TAG = "WearableController";

    private final Context mContext;
    private HandlerThread mWearableHandlerThread;
    private ContentObserver mWearableContentObserver;

    public WearableController(Context context) {
        mContext = context;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void registerContentObserver() {
        // Set up a thread to update Android Wear whenever the artwork changes
        mWearableHandlerThread = new HandlerThread("MuzeiWallpaperService-Wearable");
        mWearableHandlerThread.start();
        mWearableContentObserver = new ContentObserver(new Handler(mWearableHandlerThread.getLooper())) {
            @Override
            public void onChange(final boolean selfChange, final Uri uri) {
                updateArtwork();
            }
        };
        mContext.getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, mWearableContentObserver);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void unregisterContentObserver() {
        mContext.getContentResolver().unregisterContentObserver(mWearableContentObserver);
        mWearableHandlerThread.quitSafely();
    }

    private void updateArtwork() {
        if (ConnectionResult.SUCCESS != GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(mContext)) {
            return;
        }
        DataClient dataClient = Wearable.getDataClient(mContext);
        try {
            Tasks.await(GoogleApiAvailability.getInstance()
                    .checkApiAvailability(dataClient), 5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AvailabilityException) {
                ConnectionResult connectionResult = ((AvailabilityException) e.getCause())
                        .getConnectionResult(dataClient);
                if (connectionResult.getErrorCode() != ConnectionResult.API_UNAVAILABLE) {
                    Log.w(TAG, "onConnectionFailed: " + connectionResult, e.getCause());
                }
            } else {
                Log.w(TAG, "Unable to check for Wear API availability", e);
            }
            return;
        } catch (InterruptedException|TimeoutException e) {
            Log.w(TAG, "Unable to check for Wear API availability", e);
            return;
        }
        ContentResolver contentResolver = mContext.getContentResolver();
        Bitmap image = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI), null, options);
            options.inJustDecodeBounds = false;
            if (options.outWidth > options.outHeight) {
                options.inSampleSize = ImageUtil.calculateSampleSize(options.outHeight, 320);
            } else {
                options.inSampleSize = ImageUtil.calculateSampleSize(options.outWidth, 320);
            }
            image = BitmapFactory.decodeStream(contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI), null, options);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Unable to read artwork to update Android Wear", e);
        }
        if (image != null) {
            int rotation = getRotation();
            if (rotation != 0) {
                // Rotate the image so that Wear always gets a right side up image
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                image = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(),
                        matrix, true);
            }
            final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            Asset asset = Asset.createFromBytes(byteStream.toByteArray());
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create("/artwork");
            Artwork artwork = MuzeiDatabase.getInstance(mContext).artworkDao().getCurrentArtworkBlocking();
            dataMapRequest.getDataMap().putDataMap("artwork", ArtworkTransfer.toDataMap(artwork));
            dataMapRequest.getDataMap().putAsset("image", asset);
            try {
                Tasks.await(dataClient.putDataItem(dataMapRequest.asPutDataRequest().setUrgent()));
            } catch (ExecutionException|InterruptedException e) {
                Log.w(TAG, "Error uploading artwork to Wear", e);
            }
        }
    }

    private int getRotation() {
        ContentResolver contentResolver = mContext.getContentResolver();
        int rotation = 0;
        try (InputStream in = contentResolver.openInputStream(
                MuzeiContract.Artwork.CONTENT_URI)) {
            if (in == null) {
                return 0;
            }
            ExifInterface exifInterface = new ExifInterface(in);
            int orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
            }
        } catch (IOException|NumberFormatException|StackOverflowError ignored) {
        }
        return rotation;
    }
}
