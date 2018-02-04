/*
 * Copyright 2017 Google Inc.
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
package com.google.android.apps.muzei.single;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link MuzeiArtSource} that displays just a single image
 */
public class SingleArtSource extends MuzeiArtSource {
    private static final String TAG = "SingleArtwork";
    private static final String ACTION_PUBLISH_NEW_ARTWORK = "publish_new_artwork";
    private static final String EXTRA_ARTWORK_TITLE = "title";
    private static final String EXTRA_ARTWORK_URI = "uri";
    private static ExecutorService sExecutor;

    public SingleArtSource() {
        super("SingleArtSource");
    }

    @NonNull
    static File getArtworkFile(@NonNull Context context) {
        return new File(context.getFilesDir(), "single");
    }

    @NonNull
    public static LiveData<Boolean> setArtwork(@NonNull Context context, @NonNull Uri artworkUri) {
        if (sExecutor == null) {
            sExecutor = Executors.newSingleThreadExecutor();
        }
        final MutableLiveData<Boolean> mutableLiveData = new MutableLiveData<>();
        sExecutor.submit(() -> {
            File tempFile = writeUriToFile(context, artworkUri, getArtworkFile(context));
            if (tempFile != null) {
                Intent intent = new Intent(context, SingleArtSource.class);
                intent.setAction(ACTION_PUBLISH_NEW_ARTWORK);
                String title = null;
                if (DocumentsContract.isDocumentUri(context, artworkUri)) {
                    try (Cursor data = context.getContentResolver().query(artworkUri,
                            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                            null, null, null)) {
                        if (data != null && data.moveToNext()) {
                            title = data.getString(
                                    data.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                        }
                    } catch (SecurityException e) {
                        // Whelp, I guess no title for us
                    }
                }
                intent.putExtra(EXTRA_ARTWORK_TITLE,
                        title != null ? title : context.getString(R.string.single_default_artwork_title));
                intent.putExtra(EXTRA_ARTWORK_URI, Uri.fromFile(tempFile));
                context.startService(intent);
            }
            mutableLiveData.postValue(tempFile != null);
        });
        return mutableLiveData;
    }

    @Nullable
    private static File writeUriToFile(@NonNull Context context, @NonNull Uri uri, @NonNull File destFile) {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = in != null ? new FileOutputStream(destFile) : null) {
            if (in == null) {
                return null;
            }
            File directory = new File(context.getCacheDir(), "single");
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
            for (File existingTempFile : directory.listFiles()) {
                //noinspection ResultOfMethodCallIgnored
                existingTempFile.delete();
            }
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
            File tempFile = new File(directory, filename.toString());
            try (OutputStream tempOut = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    out.write(buffer, 0, bytesRead);
                    tempOut.write(buffer, 0, bytesRead);
                }
                out.flush();
                tempOut.flush();
                return tempFile;
            }
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            Log.e(TAG, "Unable to read Uri: " + uri, e);
            return null;
        }
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_PUBLISH_NEW_ARTWORK.equals(intent.getAction())) {
            publishArtwork(new Artwork.Builder()
                    .title(intent.getStringExtra(EXTRA_ARTWORK_TITLE))
                    .imageUri(intent.getParcelableExtra(EXTRA_ARTWORK_URI))
                    .build());
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onUpdate(final int reason) {
    }
}