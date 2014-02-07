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

package com.google.android.apps.muzei.util;

import android.animation.TimeInterpolator;
import android.os.SystemClock;
import android.view.animation.AccelerateDecelerateInterpolator;

// Non thread-safe
public class TickingFloatAnimator {
    private float mStartValue = 0;
    private float mCurrentValue;
    private float mEndValue;
    private boolean mRunning = false;
    private long mStartTime;
    private int mDuration = 1000;
    private Runnable mEndCallback;
    private TimeInterpolator mInterpolator = new AccelerateDecelerateInterpolator();

    public static TickingFloatAnimator create() {
        return new TickingFloatAnimator();
    }

    public TickingFloatAnimator from(float startValue) {
        cancel();
        mStartValue = startValue;
        mCurrentValue = startValue;
        return this;
    }

    public TickingFloatAnimator to(float endValue) {
        mEndValue = endValue;
        return this;
    }

    public TickingFloatAnimator withDuration(int duration) {
        mDuration = Math.max(0, duration);
        return this;
    }

    public TickingFloatAnimator withInterpolator(TimeInterpolator interpolator) {
        mInterpolator = interpolator;
        return this;
    }

    public TickingFloatAnimator withEndListener(Runnable listener) {
        mEndCallback = listener;
        return this;
    }

    public void cancel() {
        mRunning = false;
        mEndValue = mCurrentValue;
    }

    public boolean tick() {
        if (!mRunning) {
            return false;
        }

        float t;
        if (mDuration <= 0) {
            t = 1;
        } else {
            t = (float) (SystemClock.elapsedRealtime() - mStartTime) * 1f / mDuration;
            if (t >= 1) {
                t = 1;
            }
        }

        if (t >= 1) {
            // Ended
            mRunning = false;
            mCurrentValue = mEndValue;
            if (mEndCallback != null) {
                mEndCallback.run();
            }
            return false;
        }

        // Still running; compute value
        mCurrentValue = mStartValue + mInterpolator.getInterpolation(t) * (mEndValue - mStartValue);
        mRunning = true;
        return true;
    }

    public void start() {
        mRunning = true;
        mStartValue = mCurrentValue;
        mStartTime = SystemClock.elapsedRealtime();
        tick();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public float currentValue() {
        return mCurrentValue;
    }

    private TickingFloatAnimator() {
    }
}
