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
import android.os.AsyncTask;

import com.google.android.apps.muzei.event.BlurAmountChangedEvent;
import com.google.android.apps.muzei.event.DimAmountChangedEvent;

import de.greenrobot.event.EventBus;

public abstract class RenderController {
    protected Context mContext;
    protected MuzeiBlurRenderer mRenderer;
    protected Callbacks mCallbacks;
    protected boolean mVisible;
    protected BitmapRegionLoader mQueuedBitmapRegionLoader;

    public RenderController(Context context, MuzeiBlurRenderer renderer, Callbacks callbacks) {
        mRenderer = renderer;
        mContext = context;
        mCallbacks = callbacks;
        EventBus.getDefault().register(this);
    }

    public void destroy() {
        if (mQueuedBitmapRegionLoader != null) {
            mQueuedBitmapRegionLoader.destroy();
        }
        EventBus.getDefault().unregister(this);
    }

    public void onEventMainThread(BlurAmountChangedEvent e) {
        mRenderer.recomputeMaxPrescaledBlurPixels();
        reloadCurrentArtwork(true);
    }

    public void onEventMainThread(DimAmountChangedEvent e) {
        mRenderer.recomputeMaxDimAmount();
        reloadCurrentArtwork(true);
    }

    protected abstract BitmapRegionLoader openDownloadedCurrentArtwork(boolean forceReload);

    public void reloadCurrentArtwork(final boolean forceReload) {
        new AsyncTask<Void, Void, BitmapRegionLoader>() {
            @Override
            protected BitmapRegionLoader doInBackground(Void... voids) {
                // openDownloadedCurrentArtwork should be called on a background thread
                return openDownloadedCurrentArtwork(forceReload);
            }

            @Override
            protected void onPostExecute(final BitmapRegionLoader bitmapRegionLoader) {
                if (bitmapRegionLoader == null) {
                    return;
                }

                mCallbacks.queueEventOnGlThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mVisible) {
                            mRenderer.setAndConsumeBitmapRegionLoader(bitmapRegionLoader);
                        } else {
                            mQueuedBitmapRegionLoader = bitmapRegionLoader;
                        }
                    }
                });
            }
        }.execute((Void) null);
    }

    public void setVisible(boolean visible) {
        mVisible = visible;
        if (visible) {
            mCallbacks.queueEventOnGlThread(new Runnable() {
                @Override
                public void run() {
                    if (mQueuedBitmapRegionLoader != null) {
                        mRenderer.setAndConsumeBitmapRegionLoader(mQueuedBitmapRegionLoader);
                        mQueuedBitmapRegionLoader = null;
                    }
                }
            });
            mCallbacks.requestRender();
        }
    }

    public static interface Callbacks {
        void queueEventOnGlThread(Runnable runnable);
        void requestRender();
    }
}
