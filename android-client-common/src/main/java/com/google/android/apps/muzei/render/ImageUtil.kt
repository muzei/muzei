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

package com.google.android.apps.muzei.render

import android.graphics.Bitmap
import android.graphics.Color

fun Bitmap?.darkness(): Float {
    if (this == null || width == 0 || height == 0) {
        return 0f
    }

    var totalLum = 0
    var n = 0
    var x: Int
    var y: Int
    var color: Int
    y = 0
    while (y < height) {
        x = 0
        while (x < width) {
            ++n
            color = getPixel(x, y)
            totalLum += (0.21f * Color.red(color)
                    + 0.71f * Color.green(color)
                    + 0.07f * Color.blue(color)).toInt()
            x++
        }
        y++
    }

    return totalLum / n / 256f
}

fun Int.sampleSize(targetSize: Int): Int {
    var sampleSize = 1
    while (this / (sampleSize shl 1) > targetSize) {
        sampleSize = sampleSize shl 1
    }
    return sampleSize
}
