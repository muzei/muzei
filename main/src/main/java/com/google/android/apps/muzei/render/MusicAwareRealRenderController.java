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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.preference.PreferenceManager;

import com.google.android.apps.muzei.MusicListenerService;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.MusicArtworkChangedEvent;
import com.google.android.apps.muzei.event.MusicStateChangedEvent;
import com.google.android.apps.muzei.util.IOUtil;
import com.google.android.apps.muzei.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import de.greenrobot.event.EventBus;

import static com.google.android.apps.muzei.util.LogUtil.LOGE;

public class MusicAwareRealRenderController extends RealRenderController {
    private static final String TAG = LogUtil.makeLogTag(MusicAwareRealRenderController.class);

    private Bitmap mMusicArtwork;
    private boolean mArtDetailMode = false;
    private boolean mIsMusicPlaying = false;
    private boolean mIsMusicArtworkCached = false;
    private BitmapRegionLoader mQueuedMusicBitmapRegionLoader;
    private boolean mShowingMusicArtwork = false;

    public MusicAwareRealRenderController(Context context, MuzeiBlurRenderer renderer,
                                Callbacks callbacks) {
        super(context, renderer, callbacks);
        MusicStateChangedEvent msce = EventBus.getDefault().getStickyEvent(
                MusicStateChangedEvent.class);
        if (msce != null) {
            onEventMainThread(msce);
        }
        MusicArtworkChangedEvent mace = EventBus.getDefault().getStickyEvent(
                MusicArtworkChangedEvent.class);
        if (mace != null) {
            onEventMainThread(mace);
        }
    }

    public void destroy() {
        super.destroy();
        if (mQueuedMusicBitmapRegionLoader != null) {
            mQueuedMusicBitmapRegionLoader.destroy();
        }
    }

    public void onEventMainThread(final ArtDetailOpenedClosedEvent e) {
        if (e.isArtDetailOpened() == mArtDetailMode) {
            return;
        }

        mArtDetailMode = e.isArtDetailOpened();
        reloadCurrentArtwork(false);
    }

    public void onEventMainThread(MusicStateChangedEvent e) {
        mIsMusicPlaying = e.isMusicPlaying();
        reloadCurrentArtwork(false);
    }

    public void onEventMainThread(MusicArtworkChangedEvent e) {
        mMusicArtwork = e.getArtwork();
        mIsMusicArtworkCached = false;
        if (mQueuedMusicBitmapRegionLoader != null) {
            mQueuedMusicBitmapRegionLoader.destroy();
            mQueuedMusicBitmapRegionLoader = null;
        }
        if (mMusicArtwork == null) {
            reloadCurrentArtwork(false);
            return;
        }
        new AsyncTask<Void, Void, BitmapRegionLoader>() {
            @Override
            protected BitmapRegionLoader doInBackground(Void... voids) {
                File cacheArtworkFile = new File(IOUtil.getBestAvailableCacheRoot(mContext), "music_artwork.png");
                FileOutputStream outputStream = null;
                try {
                    outputStream = new FileOutputStream(cacheArtworkFile);
                    mMusicArtwork.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    mIsMusicArtworkCached = true;
                    return BitmapRegionLoader.newInstance(new FileInputStream(cacheArtworkFile));
                } catch (FileNotFoundException e) {
                    LOGE(TAG, "Error caching music artwork", e);
                } finally {
                    try {
                        if (outputStream != null) {
                            outputStream.close();
                        }
                    } catch (IOException e) {
                        LOGE(TAG, "Error caching music artwork", e);
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(final BitmapRegionLoader bitmapRegionLoader) {
                mQueuedMusicBitmapRegionLoader = bitmapRegionLoader;
                reloadCurrentArtwork(false);
            }
        }.execute();
    }

    private void reloadCachedMusicArtwork() {
        new AsyncTask<Void, Void, BitmapRegionLoader>() {
            @Override
            protected BitmapRegionLoader doInBackground(Void... voids) {
                File cacheArtworkFile = new File(IOUtil.getBestAvailableCacheRoot(mContext), "music_artwork.png");
                try {
                    return BitmapRegionLoader.newInstance(new FileInputStream(cacheArtworkFile));
                } catch (FileNotFoundException e) {
                    LOGE(TAG, "Error loading cached music artwork", e);
                    mIsMusicArtworkCached = false;
                }
                return null;
            }

            @Override
            protected void onPostExecute(final BitmapRegionLoader bitmapRegionLoader) {
                mQueuedMusicBitmapRegionLoader = bitmapRegionLoader;
                reloadCurrentArtwork(false);
            }
        }.execute();
    }

    @Override
    public void reloadCurrentArtwork(final boolean forceReload) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        final boolean prefEnabled = sp.getBoolean(MusicListenerService.PREF_ENABLED, false);
        if (prefEnabled && !mArtDetailMode && mIsMusicPlaying) {
            if (!mIsMusicArtworkCached) {
                // We'll receive another callback once the music artwork is cached
            } else if (mQueuedMusicBitmapRegionLoader == null) {
                reloadCachedMusicArtwork();
            } else {
                mCallbacks.queueEventOnGlThread(new Runnable() {
                    @Override
                    public void run() {
                        mShowingMusicArtwork = true;
                        if (mVisible) {
                            mRenderer.setAndConsumeBitmapRegionLoader(mQueuedMusicBitmapRegionLoader);
                        } else {
                            mQueuedBitmapRegionLoader = mQueuedMusicBitmapRegionLoader;
                        }
                        mQueuedMusicBitmapRegionLoader = null;
                    }
                });
            }
        } else if (mShowingMusicArtwork) {
            mShowingMusicArtwork = false;
            super.reloadCurrentArtwork(true);
        } else {
            super.reloadCurrentArtwork(forceReload);
        }
    }
}
