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
import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.provider.BaseColumns;

import com.google.android.apps.muzei.api.MuzeiContract;

public class DownloadArtworkTask extends AsyncTask<Void, Void, Void> {
    private final Context mApplicationContext;

    public DownloadArtworkTask(Context context) {
        mApplicationContext = context.getApplicationContext();
    }

    @Override
    protected Void doInBackground(Void... voids) {
        ContentResolver resolver = mApplicationContext.getContentResolver();
        String[] projection = {BaseColumns._ID, MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI };
        Cursor data = resolver.query(MuzeiContract.Artwork.CONTENT_URI, projection, null, null, null);

        return null;
    }
}
