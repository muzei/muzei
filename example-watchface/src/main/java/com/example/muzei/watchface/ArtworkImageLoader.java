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

package com.example.muzei.watchface;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.io.FileNotFoundException;

/**
 * AsyncTaskLoader which provides access to the current Muzei artwork image. It also
 * registers a ContentObserver to ensure the image stays up to date
 */
public class ArtworkImageLoader extends AsyncTaskLoader<Bitmap> {
    private ContentObserver mContentObserver;

    public ArtworkImageLoader(Context context) {
        super(context);
    }

    @Override
    protected void onStartLoading() {
        if (mContentObserver == null) {
            mContentObserver = new ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange) {
                    onContentChanged();
                }
            };
            getContext().getContentResolver().registerContentObserver(
                    MuzeiContract.Artwork.CONTENT_URI, true, mContentObserver);
        }
        forceLoad();
    }

    @Override
    public Bitmap loadInBackground() {
        try {
            return MuzeiContract.Artwork.getCurrentArtworkBitmap(getContext());
        } catch (FileNotFoundException e) {
            Log.e("ArtworkImageLoader", "Error getting artwork image", e);
        }
        return null;
    }

    @Override
    protected void onReset() {
        if (mContentObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mContentObserver);
        }
    }
}
