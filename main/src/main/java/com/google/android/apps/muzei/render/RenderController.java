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
import android.os.Handler;
import android.os.Message;

import com.google.android.apps.muzei.event.BlurAmountChangedEvent;
import com.google.android.apps.muzei.event.DimAmountChangedEvent;
import com.google.android.apps.muzei.event.GreyAmountChangedEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public abstract class RenderController {
    protected Context mContext;
    protected MuzeiBlurRenderer mRenderer;
    protected Callbacks mCallbacks;
    protected boolean mVisible;
    private BitmapRegionLoader mQueuedBitmapRegionLoader;

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

    @Subscribe
    public void onEventMainThread(BlurAmountChangedEvent e) {
        mRenderer.recomputeMaxPrescaledBlurPixels();
        throttledForceReloadCurrentArtwork();
    }

    @Subscribe
    public void onEventMainThread(DimAmountChangedEvent e) {
        mRenderer.recomputeMaxDimAmount();
        throttledForceReloadCurrentArtwork();
    }

    @Subscribe
    public void onEventMainThread(GreyAmountChangedEvent e) {
        mRenderer.recomputeGreyAmount();
        throttledForceReloadCurrentArtwork();
    }

    private void throttledForceReloadCurrentArtwork() {
        mThrottledForceReloadHandler.removeMessages(0);
        mThrottledForceReloadHandler.sendEmptyMessageDelayed(0, 250);
    }

    private Handler mThrottledForceReloadHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            reloadCurrentArtwork(true);
        }
    };

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

    public interface Callbacks {
        void queueEventOnGlThread(Runnable runnable);
        void requestRender();
    }
}
