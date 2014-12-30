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

package com.google.android.apps.muzei.util;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

// See https://code.google.com/p/android/issues/detail?id=9904
public class TypefaceUtil {
    private static final Map<String, Typeface> sTypefaceCache = new HashMap<>();

    public static Typeface getAndCache(Context context, String assetPath) {
        synchronized (sTypefaceCache) {
            if (!sTypefaceCache.containsKey(assetPath)) {
                Typeface tf = Typeface.createFromAsset(
                        context.getApplicationContext().getAssets(), assetPath);
                sTypefaceCache.put(assetPath, tf);
            }
            return sTypefaceCache.get(assetPath);
        }
    }
}
