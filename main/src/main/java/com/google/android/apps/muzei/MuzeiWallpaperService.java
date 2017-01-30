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

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.RequiresApi;
import android.support.v4.os.UserManagerCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;
import com.google.android.apps.muzei.settings.Prefs;
import com.google.android.apps.muzei.shortcuts.ArtworkInfoShortcutController;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.android.apps.muzei.wearable.WearableController;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;
import net.rbgrn.android.glwallpaperservice.GLWallpaperService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class MuzeiWallpaperService extends GLWallpaperService {
    private boolean mInitialized = false;
    private BroadcastReceiver mUnlockReceiver;
    private NetworkChangeReceiver mNetworkChangeReceiver;
    private HandlerThread mNotificationHandlerThread;
    private ContentObserver mNotificationContentObserver;
    private HandlerThread mWearableHandlerThread;
    private ContentObserver mWearableContentObserver;
    private HandlerThread mArtworkInfoShortcutHandlerThread;
    private ContentObserver mArtworkInfoShortcutContentObserver;

    @Override
    public Engine onCreateEngine() {
        return new MuzeiWallpaperEngine();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserManagerCompat.isUserUnlocked(this)) {
            initialize();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mUnlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    initialize();
                    unregisterReceiver(this);
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            registerReceiver(mUnlockReceiver, filter);
        }
    }

    private void initialize() {
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE);
        SourceManager.subscribeToSelectedSource(MuzeiWallpaperService.this);
        mNetworkChangeReceiver = new NetworkChangeReceiver();
        IntentFilter networkChangeFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mNetworkChangeReceiver, networkChangeFilter);
        // Ensure we retry loading the artwork if the network changed while the wallpaper was disabled
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(this);
        if (retryIntent != null && connectivityManager.getActiveNetworkInfo() != null &&
                connectivityManager.getActiveNetworkInfo().isConnected()) {
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

        // Set up a thread to update the Artwork Info shortcut whenever the artwork changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            mArtworkInfoShortcutHandlerThread = new HandlerThread("MuzeiWallpaperService-ArtworkInfoShortcut");
            mArtworkInfoShortcutHandlerThread.start();
            mArtworkInfoShortcutContentObserver = new ContentObserver(new Handler(
                    mArtworkInfoShortcutHandlerThread.getLooper())) {
                @RequiresApi(api = Build.VERSION_CODES.N_MR1)
                @Override
                public void onChange(final boolean selfChange, final Uri uri) {
                    ArtworkInfoShortcutController.updateShortcut(MuzeiWallpaperService.this);
                }
            };
            getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                    true, mArtworkInfoShortcutContentObserver);
        }
        mInitialized = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mInitialized) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                getContentResolver().unregisterContentObserver(mArtworkInfoShortcutContentObserver);
                mArtworkInfoShortcutHandlerThread.quitSafely();
            }
            getContentResolver().unregisterContentObserver(mWearableContentObserver);
            mWearableHandlerThread.quitSafely();
            getContentResolver().unregisterContentObserver(mNotificationContentObserver);
            mNotificationHandlerThread.quitSafely();
            if (mNetworkChangeReceiver != null) {
                unregisterReceiver(mNetworkChangeReceiver);
                mNetworkChangeReceiver = null;
            }
            SourceManager.unsubscribeToSelectedSource(MuzeiWallpaperService.this);
        } else {
            unregisterReceiver(mUnlockReceiver);
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

        private boolean mIsLockScreenVisibleReceiverRegistered = false;
        private SharedPreferences.OnSharedPreferenceChangeListener
                mLockScreenPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(final SharedPreferences sp, final String key) {
                if (Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED.equals(key)) {
                    if (sp.getBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, false)) {
                        IntentFilter intentFilter = new IntentFilter();
                        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
                        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
                        registerReceiver(mLockScreenVisibleReceiver, intentFilter);
                        mIsLockScreenVisibleReceiverRegistered = true;
                        // If the user is not yet unlocked (i.e., using Direct Boot), we should
                        // immediately send the lock screen visible callback
                        if (!UserManagerCompat.isUserUnlocked(MuzeiWallpaperService.this)) {
                            lockScreenVisibleChanged(true);
                        }
                    } else if (mIsLockScreenVisibleReceiverRegistered) {
                        unregisterReceiver(mLockScreenVisibleReceiver);
                        mIsLockScreenVisibleReceiverRegistered = false;
                    }
                }
            }
        };
        private BroadcastReceiver mLockScreenVisibleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                if (intent != null) {
                    if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        lockScreenVisibleChanged(false);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        lockScreenVisibleChanged(true);
                    } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        KeyguardManager kgm = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                        if (!kgm.inKeyguardRestrictedInputMode()) {
                            lockScreenVisibleChanged(false);
                        }
                    }
                }
            }
        };

        private boolean mWallpaperActivated = false;
        private BroadcastReceiver mEngineUnlockReceiver;

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

            SharedPreferences sp = Prefs.getSharedPreferences(MuzeiWallpaperService.this);
            sp.registerOnSharedPreferenceChangeListener(mLockScreenPreferenceChangeListener);
            // Trigger the initial registration if needed
            mLockScreenPreferenceChangeListener.onSharedPreferenceChanged(sp,
                    Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED);

            if (!isPreview()) {
                if (UserManagerCompat.isUserUnlocked(MuzeiWallpaperService.this)) {
                    activateWallpaper();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mEngineUnlockReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            activateWallpaper();
                            unregisterReceiver(this);
                        }
                    };
                    IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                    registerReceiver(mEngineUnlockReceiver, filter);
                }
            }
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(true);
            EventBus.getDefault().register(this);
        }

        private void activateWallpaper() {
            mWallpaperActivated = true;
            FirebaseAnalytics.getInstance(MuzeiWallpaperService.this).logEvent("wallpaper_created", null);
            EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(true));
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
            EventBus.getDefault().unregister(this);
            if (mWallpaperActivated) {
                FirebaseAnalytics.getInstance(MuzeiWallpaperService.this).logEvent("wallpaper_destroyed", null);
                EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(false));
            } else if (!isPreview()) {
                unregisterReceiver(mEngineUnlockReceiver);
            }
            if (mIsLockScreenVisibleReceiverRegistered) {
                unregisterReceiver(mLockScreenVisibleReceiver);
            }
            Prefs.getSharedPreferences(MuzeiWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(mLockScreenPreferenceChangeListener);
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

        private void lockScreenVisibleChanged(final boolean isLockScreenVisible) {
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
