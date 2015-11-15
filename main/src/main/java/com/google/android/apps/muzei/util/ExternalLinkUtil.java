/*
 * Copyright 2015 Google Inc.
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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.customtabs.CustomTabsIntent;

import net.nurik.roman.muzei.R;

public class ExternalLinkUtil {
    public static void openLinkInBrowser(Context context, Uri uri) {
        CustomTabsIntent cti = new CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setToolbarColor(
                        context.getResources().getColor(R.color.theme_primary))
                .build();
        Intent intent = cti.intent;
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    public static void openLinkInBrowser(Context context, String url) {
        openLinkInBrowser(context, Uri.parse(url));
    }
}
