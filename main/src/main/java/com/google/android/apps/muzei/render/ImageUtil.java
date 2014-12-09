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
import android.graphics.Color;

public class ImageUtil {
    // Make sure input images are very small!
    public static float calculateDarkness(Bitmap bitmap) {
        if (bitmap == null) {
            return 0;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int totalLum = 0;
        int n = 0;
        int x, y, color;
        for (y = 0; y < height; y++) {
            for (x = 0; x < width; x++) {
                ++n;
                color = bitmap.getPixel(x, y);
                totalLum += (0.21f * Color.red(color)
                        + 0.71f * Color.green(color)
                        + 0.07f * Color.blue(color));
            }
        }

        return (totalLum / n) / 256f;
    }

    private ImageUtil() {
    }

    public static int calculateSampleSize(int rawSize, int targetSize) {
        int sampleSize = 1;
        while (rawSize / (sampleSize << 1) > targetSize) {
            sampleSize <<= 1;
        }
        return sampleSize;
    }
}
