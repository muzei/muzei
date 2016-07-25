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
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.BaseColumns;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.ArtworkLoadingStateChangedEvent;
import com.google.android.apps.muzei.util.IOUtil;
import com.google.android.apps.muzei.util.LogUtil;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DownloadArtworkTask extends AsyncTask<Void, Void, Boolean> {
    private static final String TAG = LogUtil.makeLogTag(DownloadArtworkTask.class);

    private final Context mApplicationContext;

    public DownloadArtworkTask(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    @Override
    protected void onPreExecute() {
        EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(true, false));
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        ContentResolver resolver = mApplicationContext.getContentResolver();
        String[] projection = {BaseColumns._ID, MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI };
        Cursor data = resolver.query(MuzeiContract.Artwork.CONTENT_URI, projection, null, null, null);
        if (data == null || !data.moveToFirst()) {
            if (data != null) {
                data.close();
            }
            return false;
        }
        Uri artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI,
                data.getLong(0));
        Uri imageUri = Uri.parse(data.getString(1));
        data.close();
        OutputStream out = null;
        InputStream in = null;
        try {
            out = resolver.openOutputStream(artworkUri);
            if (out == null) {
                // We've already downloaded the file
                return true;
            }
            in = IOUtil.openUri(mApplicationContext, imageUri, null);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (IOUtil.OpenUriException | IOException e) {
            Log.e(TAG, "Error downloading artwork", e);
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing artwork input stream", e);
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing artwork output stream", e);
            }
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
}
