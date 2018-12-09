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

import android.content.Context
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

/**
 * A simple class that animates double-touch zoom gestures. Functionally similar to a [ ].
 */
internal class Zoomer(context: Context) {
    /**
     * The interpolator, used for making zooms animate 'naturally.'
     */
    private val interpolator: Interpolator = DecelerateInterpolator()

    /**
     * The total animation duration for a zoom.
     */
    private val animationDurationMillis: Int = context.resources.getInteger(
            android.R.integer.config_shortAnimTime)

    /**
     * Whether or not the current zoom has finished.
     */
    private var finished = true

    /**
     * The starting zoom value.
     */
    private var startZoom = 1f

    /**
     * The current zoom value; computed by [.computeZoom].
     */
    /**
     * Returns the current zoom level.
     *
     * @see android.widget.Scroller.getCurrX
     */
    var currZoom: Float = 0f
        private set

    /**
     * The time the zoom started, computed using [android.os.SystemClock.elapsedRealtime].
     */
    private var startRTC: Long = 0

    /**
     * The destination zoom factor.
     */
    private var endZoom: Float = 0f

    /**
     * Forces the zoom finished state to the given value. Unlike [.abortAnimation], the
     * current zoom value isn't set to the ending value.
     *
     * @see android.widget.Scroller.forceFinished
     */
    fun forceFinished(finished: Boolean) {
        this.finished = finished
    }

    /**
     * Starts a zoom from startZoom to endZoom. That is, to zoom from 100% to 125%, endZoom should
     * by 0.25f.
     *
     * @see android.widget.Scroller.startScroll
     */
    fun startZoom(startZoom: Float, endZoom: Float) {
        startRTC = SystemClock.elapsedRealtime()
        this.endZoom = endZoom

        finished = false
        this.startZoom = startZoom
        currZoom = startZoom
    }

    /**
     * Computes the current zoom level, returning true if the zoom is still active and false if the
     * zoom has finished.
     *
     * @see android.widget.Scroller.computeScrollOffset
     */
    fun computeZoom(): Boolean {
        if (finished) {
            return false
        }

        val tRTC = SystemClock.elapsedRealtime() - startRTC
        if (tRTC >= animationDurationMillis) {
            finished = true
            currZoom = endZoom
            return false
        }

        val t = tRTC * 1f / animationDurationMillis
        currZoom = interpolate(
                startZoom, endZoom, interpolator.getInterpolation(t))
        return true
    }
}
