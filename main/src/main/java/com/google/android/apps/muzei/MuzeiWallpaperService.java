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

package com.google.android.apps.muzei;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.LockScreenVisibleChangedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.android.apps.muzei.wearable.WearableController;

import net.rbgrn.android.glwallpaperservice.GLWallpaperService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class MuzeiWallpaperService extends GLWallpaperService {
    private LockScreenVisibleReceiver mLockScreenVisibleReceiver;
    private NetworkChangeReceiver mNetworkChangeReceiver;
    private HandlerThread mNotificationHandlerThread;
    private ContentObserver mNotificationContentObserver;
    private HandlerThread mWearableHandlerThread;
    private ContentObserver mWearableContentObserver;

    @Override
    public Engine onCreateEngine() {
        return new MuzeiWallpaperEngine();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mLockScreenVisibleReceiver = new LockScreenVisibleReceiver();
        mLockScreenVisibleReceiver.setupRegisterDeregister(this);
        SourceManager.getInstance(MuzeiWallpaperService.this).subscribeToSelectedSource();
        mNetworkChangeReceiver = new NetworkChangeReceiver();
        IntentFilter networkChangeFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mNetworkChangeReceiver, networkChangeFilter);
        // Ensure we retry loading the artwork if the network changed while the wallpaper was disabled
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(this);
        if (retryIntent != null && connectivityManager.getActiveNetworkInfo().isConnected()) {
            startService(retryIntent);
        }

        // Set up a thread to update notifications whenever the artwork changes
        mNotificationHandlerThread = new HandlerThread("MuzeiWallpaperService-Notification");
        mNotificationHandlerThread.start();
        mNotificationContentObserver = new ContentObserver(new Handler(mNotificationHandlerThread.getLooper())) {
            @Override
            public void onChange(final boolean selfChange, final Uri uri) {
                NewWallpaperNotificationReceiver.maybeShowNewArtworkNotification(MuzeiWallpaperService.this);
            }
        };
        getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, mNotificationContentObserver);

        // Set up a thread to update Android Wear whenever the artwork changes
        mWearableHandlerThread = new HandlerThread("MuzeiWallpaperService-Wearable");
        mWearableHandlerThread.start();
        mWearableContentObserver = new ContentObserver(new Handler(mWearableHandlerThread.getLooper())) {
            @Override
            public void onChange(final boolean selfChange, final Uri uri) {
                WearableController.updateArtwork(MuzeiWallpaperService.this);
            }
        };
        getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, mWearableContentObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(mWearableContentObserver);
        mWearableHandlerThread.quitSafely();
        getContentResolver().unregisterContentObserver(mNotificationContentObserver);
        mNotificationHandlerThread.quitSafely();
        if (mNetworkChangeReceiver != null) {
            unregisterReceiver(mNetworkChangeReceiver);
            mNetworkChangeReceiver = null;
        }
        SourceManager.getInstance(MuzeiWallpaperService.this).unsubscribeToSelectedSource();
        if (mLockScreenVisibleReceiver != null) {
            mLockScreenVisibleReceiver.destroy();
            mLockScreenVisibleReceiver = null;
        }
    }

    private class MuzeiWallpaperEngine extends GLEngine implements
            RenderController.Callbacks,
            MuzeiBlurRenderer.Callbacks {

        private static final long TEMPORARY_FOCUS_DURATION_MILLIS = 3000;

        private Handler mMainThreadHandler = new Handler();

        private RenderController mRenderController;
        private GestureDetector mGestureDetector;
        private MuzeiBlurRenderer mRenderer;

        private boolean mArtDetailMode = false;
        private boolean mVisible = true;
        private boolean mValidDoubleTap;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            mRenderer = new MuzeiBlurRenderer(MuzeiWallpaperService.this, this);
            mRenderer.setIsPreview(isPreview());
            mRenderController = new RealRenderController(MuzeiWallpaperService.this,
                    mRenderer, this);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 0, 0, 0);
            setRenderer(mRenderer);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            requestRender();

            mGestureDetector = new GestureDetector(MuzeiWallpaperService.this, mGestureListener);
            if (!isPreview()) {
                EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(true));
            }
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(true);
            EventBus.getDefault().register(this);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (!isPreview()) {
                EventBus.getDefault().postSticky(new WallpaperSizeChangedEvent(width, height));
            }
            mRenderController.reloadCurrentArtwork(true);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            EventBus.getDefault().unregister(this);
            if (!isPreview()) {
                EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(false));
            }
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (mRenderer != null) {
                        mRenderer.destroy();
                    }
                }
            });
            mRenderController.destroy();
        }

        @Subscribe
        public void onEventMainThread(final ArtDetailOpenedClosedEvent e) {
            if (e.isArtDetailOpened() == mArtDetailMode) {
                return;
            }

            mArtDetailMode = e.isArtDetailOpened();
            cancelDelayedBlur();
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(!e.isArtDetailOpened(), true);
                }
            });
        }

        @Subscribe
        public void onEventMainThread(ArtDetailViewport e) {
            requestRender();
        }

        @Subscribe
        public void onEventMainThread(LockScreenVisibleChangedEvent e) {
            final boolean blur = !e.isLockScreenVisible();
            cancelDelayedBlur();
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(blur, false);
                }
            });
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            mRenderController.setVisible(visible);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset,
                    yPixelOffset);
            mRenderer.setNormalOffsetX(xOffset);
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                boolean resultRequested) {
            // mValidDoubleTap previously set in the gesture listener
            if (WallpaperManager.COMMAND_TAP.equals(action) && mValidDoubleTap) {
                // Temporarily toggle focused/blurred
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.setIsBlurred(!mRenderer.isBlurred(), false);
                        // Schedule a re-blur
                        delayedBlur();
                    }
                });
                // Reset the flag
                mValidDoubleTap = false;
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            // Delay blur from temporary refocus while touching the screen
            delayedBlur();
        }

        private final Runnable mDoubleTapTimeout = new Runnable() {

            @Override
            public void run() {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mValidDoubleTap = false;
                    }
                });
            }
        };

        private final GestureDetector.OnGestureListener mGestureListener = new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mArtDetailMode) {
                    // The main activity is visible, so discard any double touches since focus
                    // should be forced on
                    return true;
                }

                mValidDoubleTap = true; // processed in onCommand/COMMAND_TAP

                mMainThreadHandler.removeCallbacks(mDoubleTapTimeout);
                final int timeout = ViewConfiguration.getDoubleTapTimeout();
                mMainThreadHandler.postDelayed(mDoubleTapTimeout, timeout);
                return true;
            }
        };

        private void cancelDelayedBlur() {
            mMainThreadHandler.removeCallbacks(mBlurRunnable);
        }

        private void delayedBlur() {
            if (mArtDetailMode || mRenderer.isBlurred()) {
                return;
            }

            cancelDelayedBlur();
            mMainThreadHandler.postDelayed(mBlurRunnable, TEMPORARY_FOCUS_DURATION_MILLIS);
        }

        private Runnable mBlurRunnable = new Runnable() {
            @Override
            public void run() {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.setIsBlurred(true, false);
                    }
                });
            }
        };

        @Override
        public void requestRender() {
            if (mVisible) {
                super.requestRender();
            }
        }

        @Override
        public void queueEventOnGlThread(Runnable runnable) {
            queueEvent(runnable);
        }
    }
}
