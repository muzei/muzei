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

package com.google.android.apps.muzei.util

import android.animation.TimeInterpolator
import android.os.SystemClock
import android.view.animation.AccelerateDecelerateInterpolator

// Non thread-safe
class TickingFloatAnimator(private val duration: Int) {

    private var startValue: Int = 0
    private var endValue: Int = 0
    private var onEnd: () -> Unit = { }

    private val interpolator: TimeInterpolator = AccelerateDecelerateInterpolator()
    private var startTime: Long = 0
    var currentValue: Float = 0f
    var isRunning = false
        private set

    fun start(startValue: Int = currentValue.toInt(), endValue: Int, onEnd: () -> Unit) {
        this.startValue = startValue
        this.endValue = endValue
        this.onEnd = onEnd
        // Start the TickingFloatAnimator
        isRunning = true
        startTime = SystemClock.elapsedRealtime()
        tick()
    }

    fun tick(): Boolean {
        if (!isRunning) {
            return false
        }

        val t = Math.min((SystemClock.elapsedRealtime() - startTime).toFloat() / duration, 1f)

        isRunning = t < 1f
        currentValue = if (isRunning) {
            startValue + interpolator.getInterpolation(t) * (endValue - startValue)
        } else {
            endValue.toFloat().also { onEnd() }
        }
        return isRunning
    }
}
