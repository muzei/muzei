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

package com.google.android.apps.muzei.shortcuts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.R;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

/**
 * Static controller for updating the Artwork Info Shortcut whenever the current artwork changes
 */
@RequiresApi(api = Build.VERSION_CODES.N_MR1)
public class ArtworkInfoShortcutController {
    private static final String ARTWORK_INFO_SHORTCUT_ID = "artwork_info";

    public static void updateShortcut(Context context) {
        Cursor data = context.getContentResolver().query(MuzeiContract.Artwork.CONTENT_URI,
                new String[] {MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT}, null, null, null);
        if (data == null) {
            return;
        }
        Intent artworkInfo = null;
        if (data.moveToFirst()) {
            try {
                String viewIntent = data.getString(0);
                if (!TextUtils.isEmpty(viewIntent)) {
                    artworkInfo = Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME);
                }
            } catch (URISyntaxException ignored) {
            }
        }
        data.close();
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        List<ShortcutInfo> dynamicShortcuts = shortcutManager.getDynamicShortcuts();
        ShortcutInfo artworkInfoShortcutInfo = null;
        for (ShortcutInfo shortcutInfo : dynamicShortcuts) {
            if (shortcutInfo.getId().equals(ARTWORK_INFO_SHORTCUT_ID)) {
                artworkInfoShortcutInfo = shortcutInfo;
            }
        }

        if (artworkInfo != null) {
            if (artworkInfoShortcutInfo != null && !artworkInfoShortcutInfo.isEnabled()) {
                // Re-enable a disabled Artwork Info Shortcut
                shortcutManager.enableShortcuts(
                        Collections.singletonList(ARTWORK_INFO_SHORTCUT_ID));
            }
            ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(
                    context, ARTWORK_INFO_SHORTCUT_ID)
                    .setIcon(Icon.createWithResource(context,
                            R.drawable.ic_shortcut_artwork_info))
                    .setShortLabel(context.getString(R.string.action_artwork_info))
                    .setIntent(artworkInfo.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    .build();
            shortcutManager.addDynamicShortcuts(
                    Collections.singletonList(shortcutInfo));
        } else {
            if (artworkInfoShortcutInfo != null) {
                if (artworkInfoShortcutInfo.isEnabled()) {
                    shortcutManager.disableShortcuts(
                            Collections.singletonList(ARTWORK_INFO_SHORTCUT_ID),
                            context.getString(R.string.action_artwork_info_disabled));
                }
            }
        }
    }

    private ArtworkInfoShortcutController() {
    }
}
