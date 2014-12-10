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

import android.content.Context;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;

/**
 * Loader which provides a scaled version of the current Muzei artwork suitable for full screen
 * display on a watchface. Note: this doesn't crop the image, only scale it so that the shortest
 * dimension of the image is equal to the dimension of the screen (i.e., for wide images, the
 * height would be equal to the screen height and the width would be greater than the screen width
 * to maintain the aspect ratio)
 * <p>
 *     Note: if you are using this without a LoaderManager, you must manually call
 *     registerListener(Loader.OnLoadCompleteListener<Bitmap>)
 *     and startLoading() when creating the loader and
 *     unregisterListener(Loader.OnLoadCompleteListener<Bitmap>)
 *     and reset() when destroying the loader
 * </p>
 */
public class WatchfaceArtworkImageLoader extends ArtworkImageLoader {
    public WatchfaceArtworkImageLoader(Context context) {
        super(context);
    }

    @Override
    public Bitmap loadInBackground() {
        Bitmap image = super.loadInBackground();
        if (image == null) {
            return null;
        }
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int width = image.getWidth();
        int height = image.getHeight();
        if (width > height) {
            float scalingFactor = metrics.heightPixels * 1f / height;
            return Bitmap.createScaledBitmap(image, (int)(scalingFactor * width),
                    metrics.heightPixels, true);
        } else {
            float scalingFactor = metrics.widthPixels * 1f / width;
            return Bitmap.createScaledBitmap(image, metrics.widthPixels,
                    (int)(scalingFactor * height), true);
        }
    }
}
