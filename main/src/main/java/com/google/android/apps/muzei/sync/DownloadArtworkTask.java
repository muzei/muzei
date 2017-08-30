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

package com.google.android.apps.muzei.sync;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.ArtworkLoadingStateChangedEvent;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DownloadArtworkTask extends AsyncTask<Void, Void, Boolean> {
    private static final String TAG = "DownloadArtworkTask";
    private final Context mApplicationContext;

    public DownloadArtworkTask(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    @Override
    protected void onProgressUpdate(final Void... values) {
        EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(true, false));
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        Artwork artwork = MuzeiDatabase.getInstance(mApplicationContext).artworkDao().getCurrentArtworkBlocking();
        ContentResolver resolver = mApplicationContext.getContentResolver();
        if (artwork == null) {
            return false;
        }
        Uri artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI,
                artwork.id);
        if (artwork.imageUri == null) {
            // There's nothing else we can do here so declare success
            return true;
        }
        try (OutputStream out = resolver.openOutputStream(artworkUri);
             InputStream in = out != null ? openUri(mApplicationContext, artwork.imageUri) : null) {
            if (out == null) {
                // We've already downloaded the file
                return true;
            }
            // Only publish progress (i.e., say we've started loading the artwork)
            // if we actually need to download the artwork
            publishProgress();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (IOException|IllegalArgumentException e) {
            Log.e(TAG, "Error downloading artwork", e);
            return false;
        }
        return true;
    }

    @Override
    protected void onPostExecute(Boolean success) {
        if (success) {
            EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(false, false));
        } else {
            EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(false, true));
        }
    }

    private InputStream openUri(Context context, Uri uri)
            throws IOException {

        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be empty");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException("Uri had no scheme");
        }

        InputStream in = null;
        if ("content".equals(scheme) || "android.resource".equals(scheme)) {
            try {
                in = context.getContentResolver().openInputStream(uri);
            } catch (SecurityException e) {
                throw new FileNotFoundException("No access to " + uri + ": " + e.toString());
            }

        } else if ("file".equals(scheme)) {
            List<String> segments = uri.getPathSegments();
            if (segments != null && segments.size() > 1
                    && "android_asset".equals(segments.get(0))) {
                AssetManager assetManager = context.getAssets();
                StringBuilder assetPath = new StringBuilder();
                for (int i = 1; i < segments.size(); i++) {
                    if (i > 1) {
                        assetPath.append("/");
                    }
                    assetPath.append(segments.get(i));
                }
                in = assetManager.open(assetPath.toString());
            } else {
                in = new FileInputStream(new File(uri.getPath()));
            }

        } else if ("http".equals(scheme) || "https".equals(scheme)) {
            OkHttpClient client = OkHttpClientFactory.getNewOkHttpsSafeClient();
            Request request;
            request = new Request.Builder().url(new URL(uri.toString())).build();

            Response response = client.newCall(request).execute();
            int responseCode = response.code();
            if (!(responseCode >= 200 && responseCode < 300)) {
                throw new IOException("HTTP error response " + responseCode);
            }
            in = response.body().byteStream();
        }

        if (in == null) {
            throw new FileNotFoundException("Null input stream for URI: " + uri);
        }

        return in;
    }
}
