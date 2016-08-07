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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;

public class DemoRenderController extends RenderController {
    private static final String TAG = "DemoRenderController";

    private final Handler mHandler = new Handler();

    private static final long ANIMATION_CYCLE_TIME_MILLIS = 35000;
    private static final long FOCUS_DELAY_TIME_MILLIS = 2000;
    private static final long FOCUS_TIME_MILLIS = 6000;

    private Animator mCurrentScrollAnimator;
    private boolean mReverseDirection = false;
    private boolean mAllowFocus = true;

    public DemoRenderController(Context context, MuzeiBlurRenderer renderer,
            Callbacks callbacks, boolean allowFocus) {
        super(context, renderer, callbacks);
        mAllowFocus = allowFocus;
        runAnimation();
    }

    private void runAnimation() {
        if (mCurrentScrollAnimator != null) {
            mCurrentScrollAnimator.cancel();
        }

        mCurrentScrollAnimator = ObjectAnimator
                .ofFloat(mRenderer, "normalOffsetX",
                        mReverseDirection ? 1f : 0f, mReverseDirection ? 0f : 1f)
                .setDuration(ANIMATION_CYCLE_TIME_MILLIS);
        mCurrentScrollAnimator.start();
        mCurrentScrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mReverseDirection = !mReverseDirection;
                runAnimation();
            }
        });
        if (mAllowFocus) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(false, false);
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mRenderer.setIsBlurred(true, false);
                        }
                    }, FOCUS_TIME_MILLIS);
                }
            }, FOCUS_DELAY_TIME_MILLIS);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (mCurrentScrollAnimator != null) {
            mCurrentScrollAnimator.cancel();
        }
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected BitmapRegionLoader openDownloadedCurrentArtwork(boolean forceReload) {
        try {
            return BitmapRegionLoader.newInstance(mContext.getAssets().open("starrynight.jpg"));
        } catch (IOException e) {
            Log.e(TAG, "Error opening demo image.", e);
            return null;
        }
    }
}
