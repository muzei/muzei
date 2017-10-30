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

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.os.UserManagerCompat;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.notifications.NotificationUpdater;
import com.google.android.apps.muzei.render.ImageUtil;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;
import com.google.android.apps.muzei.shortcuts.ArtworkInfoShortcutController;
import com.google.android.apps.muzei.wallpaper.LockscreenObserver;
import com.google.android.apps.muzei.wallpaper.NetworkChangeObserver;
import com.google.android.apps.muzei.wallpaper.WallpaperAnalytics;
import com.google.android.apps.muzei.wearable.WearableController;
import com.google.android.apps.muzei.widget.WidgetUpdater;

import net.rbgrn.android.glwallpaperservice.GLWallpaperService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.io.FileNotFoundException;

public class MuzeiWallpaperService extends GLWallpaperService implements LifecycleOwner {
    private static final String TAG = "MuzeiWallpaperService";

    private LifecycleRegistry mLifecycle;
    private BroadcastReceiver mUnlockReceiver;

    @Override
    public Engine onCreateEngine() {
        return new MuzeiWallpaperEngine();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mLifecycle = new LifecycleRegistry(this);
        mLifecycle.addObserver(new WallpaperAnalytics(this));
        mLifecycle.addObserver(new SourceManager(this));
        mLifecycle.addObserver(new NetworkChangeObserver(this));
        mLifecycle.addObserver(new NotificationUpdater(this));
        mLifecycle.addObserver(new WearableController(this));
        mLifecycle.addObserver(new WidgetUpdater(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            mLifecycle.addObserver(new ArtworkInfoShortcutController(this, this));
        }
        if (UserManagerCompat.isUserUnlocked(this)) {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mUnlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
                    unregisterReceiver(this);
                    mUnlockReceiver = null;
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            registerReceiver(mUnlockReceiver, filter);
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onDestroy() {
        if (mUnlockReceiver != null) {
            unregisterReceiver(mUnlockReceiver);
        }
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }

    public class MuzeiWallpaperEngine extends GLEngine implements
            LifecycleOwner,
            LifecycleObserver,
            RenderController.Callbacks,
            MuzeiBlurRenderer.Callbacks {

        private static final long TEMPORARY_FOCUS_DURATION_MILLIS = 3000;
        private static final int MAX_ARTWORK_SIZE = 110; // px

        private Handler mMainThreadHandler = new Handler();

        private RenderController mRenderController;
        private GestureDetector mGestureDetector;
        private MuzeiBlurRenderer mRenderer;
        private Bitmap mCurrentArtwork;

        private boolean mArtDetailMode = false;
        private boolean mVisible = true;
        private boolean mValidDoubleTap;

        private LifecycleRegistry mEngineLifecycle;

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

            mEngineLifecycle = new LifecycleRegistry(this);
            mEngineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
            mEngineLifecycle.addObserver(new WallpaperAnalytics(MuzeiWallpaperService.this));
            mEngineLifecycle.addObserver(new LockscreenObserver(MuzeiWallpaperService.this, this));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                mEngineLifecycle.addObserver(new LifecycleObserver() {
                    private ContentObserver mContentObserver;

                    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
                    public void registerArtworkObserver() {
                        mContentObserver = new ContentObserver(new Handler()) {
                            @Override
                            public void onChange(boolean selfChange, Uri uri) {
                                updateCurrentArtwork();
                            }
                        };
                        getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                                true, mContentObserver);
                        mContentObserver.onChange(true, MuzeiContract.Artwork.CONTENT_URI);
                    }

                    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                    public void unregisterArtworkObserver() {
                        getContentResolver().unregisterContentObserver(mContentObserver);
                    }
                });
            }

            if (!isPreview()) {
                // Use the MuzeiWallpaperService's lifecycle to wait for the user to unlock
                mLifecycle.addObserver(this);
            }
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(true);
            EventBus.getDefault().register(mEventBusSubscriber);
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return mEngineLifecycle;
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_START)
        public void onUserUnlocked() {
            // The MuzeiWallpaperService only gets to ON_START when the user is unlocked
            // At that point, we can proceed with the engine's lifecycle
            mEngineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        }

        private void updateCurrentArtwork() {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(
                        getContentResolver().openInputStream(MuzeiContract.Artwork.CONTENT_URI),
                        null, options);
                options.inSampleSize = Math.max(
                        ImageUtil.calculateSampleSize(options.outHeight, MAX_ARTWORK_SIZE / 2),
                        ImageUtil.calculateSampleSize(options.outWidth, MAX_ARTWORK_SIZE / 2));
                options.inJustDecodeBounds = false;
                mCurrentArtwork = BitmapFactory.decodeStream(
                        getContentResolver().openInputStream(MuzeiContract.Artwork.CONTENT_URI),
                        null, options);
                notifyColorsChanged();
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Error reading current artwork", e);
            }
        }

        @RequiresApi(api = 27)
        @Override
        public WallpaperColors onComputeColors() {
            return mCurrentArtwork != null
                    ? WallpaperColors.fromBitmap(mCurrentArtwork)
                    : super.onComputeColors();
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
            EventBus.getDefault().unregister(mEventBusSubscriber);
            if (!isPreview()) {
                mLifecycle.removeObserver(this);
            }
            mEngineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (mRenderer != null) {
                        mRenderer.destroy();
                    }
                }
            });
            mRenderController.destroy();
            super.onDestroy();
        }

        class EventBusSubscriber {
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
        }
        private Object mEventBusSubscriber = new EventBusSubscriber();

        public void lockScreenVisibleChanged(final boolean isLockScreenVisible) {
            cancelDelayedBlur();
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(!isLockScreenVisible, false);
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
