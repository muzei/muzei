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

package com.google.android.apps.muzei.render;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;

import java.io.IOException;
import java.io.InputStream;

import static android.graphics.BitmapFactory.Options;

/**
 * Wrapper for {@link BitmapRegionDecoder} with some extra functionality.
 */
public class BitmapRegionLoader {
    private boolean mValid = false;
    private int mRotation = 0;
    private int mOriginalWidth;
    private int mOriginalHeight;
    private Rect mTempRect = new Rect();
    private InputStream mInputStream;
    private volatile BitmapRegionDecoder mBitmapRegionDecoder;
    private Matrix mRotateMatrix;

    public static BitmapRegionLoader newInstance(InputStream in) throws IOException {
        return newInstance(in, 0);
    }

    public static BitmapRegionLoader newInstance(InputStream in, int rotation) throws IOException {
        if (in == null) {
            return null;
        }

        BitmapRegionLoader loader = new BitmapRegionLoader(in);
        if (loader.mValid) {
            loader.mRotation = rotation;
            if (loader.mRotation != 0) {
                loader.mRotateMatrix = new Matrix();
                loader.mRotateMatrix.postRotate(rotation);
            }
            return loader;
        }

        return null;
    }

    private BitmapRegionLoader(InputStream in) throws IOException {
        mInputStream = in;
        mBitmapRegionDecoder = BitmapRegionDecoder.newInstance(in, false);
        if (mBitmapRegionDecoder != null) {
            mOriginalWidth = mBitmapRegionDecoder.getWidth();
            mOriginalHeight = mBitmapRegionDecoder.getHeight();
            mValid = true;
        }
    }

    /**
     * Key difference, aside from support for rotation, from
     * {@link BitmapRegionDecoder#decodeRegion(Rect, Options)} in this implementation is that even
     * if <code>inBitmap</code> is given, a sub-bitmap might be returned.
     */
    public synchronized Bitmap decodeRegion(Rect rect, Options options) {
        int unsampledInBitmapWidth = -1;
        int unsampledInBitmapHeight = -1;
        int sampleSize = Math.max(1, options != null ? options.inSampleSize : 1);
        if (options != null && options.inBitmap != null) {
            unsampledInBitmapWidth = options.inBitmap.getWidth() * sampleSize;
            unsampledInBitmapHeight = options.inBitmap.getHeight() * sampleSize;
        }

        // Decode with rotation
        switch (mRotation) {
            case 90:
                mTempRect.set(
                        rect.top, mOriginalHeight - rect.right,
                        rect.bottom, mOriginalHeight - rect.left);
                break;

            case 180:
                mTempRect.set(
                        mOriginalWidth - rect.right, mOriginalHeight - rect.bottom,
                        mOriginalWidth - rect.left, mOriginalHeight - rect.top);
                break;

            case 270:
                mTempRect.set(
                        mOriginalWidth - rect.bottom, rect.left,
                        mOriginalWidth - rect.top, rect.right);
                break;

            default:
                mTempRect.set(rect);
        }

        Bitmap bitmap = mBitmapRegionDecoder.decodeRegion(mTempRect, options);
        if (bitmap == null) {
            return null;
        }

        if (options != null && options.inBitmap != null &&
                ((mTempRect.width() != unsampledInBitmapWidth
                        || mTempRect.height() != unsampledInBitmapHeight))) {
            // Need to extract the sub-bitmap
            Bitmap subBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0,
                    mTempRect.width() / sampleSize,
                    mTempRect.height() / sampleSize);
            if (bitmap != options.inBitmap && bitmap != subBitmap) {
                bitmap.recycle();
            }
            bitmap = subBitmap;
        }

        if (mRotateMatrix != null) {
            // Rotate decoded bitmap
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(),
                    mRotateMatrix, true);
            if ((options == null || bitmap != options.inBitmap) && bitmap != rotatedBitmap) {
                bitmap.recycle();
            }
            bitmap = rotatedBitmap;
        }

        return bitmap;
    }

    public synchronized int getWidth() {
        return (mRotation == 90 || mRotation == 270) ? mOriginalHeight : mOriginalWidth;
    }

    public synchronized int getHeight() {
        return (mRotation == 90 || mRotation == 270) ? mOriginalWidth : mOriginalHeight;
    }

    public synchronized void destroy() {
        mBitmapRegionDecoder.recycle();
        mBitmapRegionDecoder = null;
        try {
            mInputStream.close();
        } catch (IOException ignored) {
        }
    }
}
