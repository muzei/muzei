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

package com.google.android.apps.muzei;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.google.android.apps.muzei.gallery.GalleryArtSource;
import com.google.android.apps.muzei.gallery.GalleryContract;

import java.util.ArrayList;
import java.util.Arrays;

public class PhotoSetAsTargetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() == null || getIntent().getData() == null) {
            finish();
            return;
        }

        Uri photoUri = getIntent().getData();

        // Select the gallery source
        SourceManager sourceManager = SourceManager.getInstance(this);
        sourceManager.selectSource(new ComponentName(this, GalleryArtSource.class));

        // Add and publish the chosen photo
        ContentValues values = new ContentValues();
        values.put(GalleryContract.ChosenPhotos.COLUMN_NAME_URI, photoUri.toString());
        getContentResolver().insert(GalleryContract.ChosenPhotos.CONTENT_URI, values);
        startService(new Intent(this, GalleryArtSource.class)
                .setAction(GalleryArtSource.ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                .putExtra(GalleryArtSource.EXTRA_FORCE_URI, photoUri));

        // Launch main activity
        startActivity(Intent.makeMainActivity(new ComponentName(this, MuzeiActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
