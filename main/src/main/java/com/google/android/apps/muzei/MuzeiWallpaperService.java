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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.DoubleTapActionChangedEvent;
import com.google.android.apps.muzei.event.TapAction;
import com.google.android.apps.muzei.event.ThreeFingerActionChangedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;
import com.google.android.apps.muzei.render.RealRenderController;
import com.google.android.apps.muzei.render.RenderController;

import net.rbgrn.android.glwallpaperservice.GLWallpaperService;

import de.greenrobot.event.EventBus;

public class MuzeiWallpaperService extends GLWallpaperService {

    public static final String PREF_DOUBLETAPACTION = "doubletap_action";
    public static final String PREF_THREEFINGERACTION = "threefinger_action";

    private static final int THREE_FINGER_ACTION_PAUSE_MS = 1000 * 5; //5s

    @Override
    public Engine onCreateEngine() {
        return new MuzeiWallpaperEngine();
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

        private TapAction mDoubleTapAction = TapAction.ShowOriginalArtwork;
        private TapAction mThreeFingerAction = TapAction.Nothing;
        private long mLastThreeFingerAction = 0;

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
            EventBus.getDefault().registerSticky(this);

            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            int doubleTapActionCode = sp.getInt(PREF_DOUBLETAPACTION, TapAction.ShowOriginalArtwork.getCode());
            mDoubleTapAction = TapAction.fromCode(doubleTapActionCode);

            int threeFingerActionCode = sp.getInt(PREF_THREEFINGERACTION, TapAction.Nothing.getCode());
            mThreeFingerAction = TapAction.fromCode(threeFingerActionCode);
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

        public void onEventMainThread(ArtDetailViewport e) {
            requestRender();
        }

        public void onEventMainThread(DoubleTapActionChangedEvent e) {
            mDoubleTapAction = e.getNewAction();
        }

        public void onEventMainThread(ThreeFingerActionChangedEvent e) {
            mThreeFingerAction = e.getNewAction();
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
            if (WallpaperManager.COMMAND_TAP.equals(action)) {
                // TODO: could we replace double tap with single tap using this?
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            // Delay blur from temporary refocus while touching the screen
            delayedBlur();
            long curTime = SystemClock.elapsedRealtime();
            long lastDiff = curTime - mLastThreeFingerAction;
            if(event.getPointerCount() == 3
                    && lastDiff > THREE_FINGER_ACTION_PAUSE_MS)
            {
                mLastThreeFingerAction = curTime;
                executeTapAction(mThreeFingerAction);
            }
        }

        private GestureDetector.OnGestureListener mGestureListener
                = new GestureDetector.SimpleOnGestureListener() {
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

                executeTapAction(mDoubleTapAction);

                return true;
            }
        };

        private void executeTapAction(TapAction action) {

            if(action == TapAction.NextArtwork)
                executeNextArtworkAction();
            else if(action == TapAction.ShowOriginalArtwork)
                executeShowOriginalArtworkAction();
        }

        private void executeShowOriginalArtworkAction() {
            // Temporarily toggle focused/blurred
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(!mRenderer.isBlurred(), false);
                }
            });

            // Schedule a re-blur
            delayedBlur();
        }

        private void executeNextArtworkAction() {
            SourceManager.getInstance(getApplicationContext()).sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
        }

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
