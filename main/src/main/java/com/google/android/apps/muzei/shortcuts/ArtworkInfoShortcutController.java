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

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;

import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;

import net.nurik.roman.muzei.R;

import java.util.Collections;
import java.util.List;

/**
 * Controller responsible for updating the Artwork Info Shortcut whenever the current artwork changes
 */
@RequiresApi(api = Build.VERSION_CODES.N_MR1)
public class ArtworkInfoShortcutController implements LifecycleObserver {
    private static final String ARTWORK_INFO_SHORTCUT_ID = "artwork_info";
    private final Context mContext;
    private final LifecycleOwner mLifecycleOwner;
    private HandlerThread mArtworkInfoShortcutHandlerThread;
    private Handler mArtworkInfoShortcutHandler;

    public ArtworkInfoShortcutController(Context context, LifecycleOwner lifecycleOwner) {
        mContext = context;
        mLifecycleOwner = lifecycleOwner;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void registerContentObserver() {
        mArtworkInfoShortcutHandlerThread = new HandlerThread("MuzeiWallpaperService-ArtworkInfoShortcut");
        mArtworkInfoShortcutHandlerThread.start();
        mArtworkInfoShortcutHandler = new Handler(mArtworkInfoShortcutHandlerThread.getLooper());
        MuzeiDatabase.getInstance(mContext).artworkDao().getCurrentArtwork().observe(mLifecycleOwner,
                new Observer<Artwork>() {
                    @Override
                    public void onChanged(@Nullable final Artwork artwork) {
                        mArtworkInfoShortcutHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateShortcut(artwork);
                            }
                        });
                    }
                });
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void unregisterContentObserver() {
        mArtworkInfoShortcutHandlerThread.quitSafely();
    }

    private void updateShortcut(Artwork artwork) {
        if (artwork == null) {
            return;
        }
        ShortcutManager shortcutManager = mContext.getSystemService(ShortcutManager.class);
        List<ShortcutInfo> dynamicShortcuts = shortcutManager.getDynamicShortcuts();
        ShortcutInfo artworkInfoShortcutInfo = null;
        for (ShortcutInfo shortcutInfo : dynamicShortcuts) {
            if (shortcutInfo.getId().equals(ARTWORK_INFO_SHORTCUT_ID)) {
                artworkInfoShortcutInfo = shortcutInfo;
            }
        }

        if (artwork.viewIntent != null) {
            if (artworkInfoShortcutInfo != null && !artworkInfoShortcutInfo.isEnabled()) {
                // Re-enable a disabled Artwork Info Shortcut
                shortcutManager.enableShortcuts(
                        Collections.singletonList(ARTWORK_INFO_SHORTCUT_ID));
            }
            ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(
                    mContext, ARTWORK_INFO_SHORTCUT_ID)
                    .setIcon(Icon.createWithResource(mContext,
                            R.drawable.ic_shortcut_artwork_info))
                    .setShortLabel(mContext.getString(R.string.action_artwork_info))
                    .setIntent(artwork.viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    .build();
            shortcutManager.addDynamicShortcuts(
                    Collections.singletonList(shortcutInfo));
        } else {
            if (artworkInfoShortcutInfo != null) {
                if (artworkInfoShortcutInfo.isEnabled()) {
                    shortcutManager.disableShortcuts(
                            Collections.singletonList(ARTWORK_INFO_SHORTCUT_ID),
                            mContext.getString(R.string.action_artwork_info_disabled));
                }
            }
        }
    }
}
