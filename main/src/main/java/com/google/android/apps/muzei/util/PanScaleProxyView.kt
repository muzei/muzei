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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.view.GestureDetectorCompat

class PanScaleProxyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : View(context, attrs, defStyle) {
    /**
     * The current viewport. This rectangle represents the currently visible chart domain
     * and range. The currently visible chart X values are from this rectangle's left to its right.
     * The currently visible chart Y values are from this rectangle's top to its bottom.
     */
    var currentViewport = RectF(0f, 0f, 1f, 1f)
        private set
    var relativeAspectRatio = 1f
        set(value) {
            field = value
            constrainViewport()
            triggerViewportChangedListener()
        }
    var panScaleEnabled = true

    private val surfaceSizeBuffer = Point()
    private var currentWidth = 1
    private var currentHeight = 1
    private var minViewportWidthOrHeight = 0.01f

    // State objects and values related to gesture tracking.
    private var scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetectorCompat
    private val scroller = OverScroller(context)
    private val zoomer = Zoomer(context)
    private val zoomFocalPoint = PointF()
    private val scrollerStartViewport = RectF() // Used only for zooms and flings.
    private var dragZoomed = false
    private var motionEventDown = false

    var onViewportChanged: () -> Unit = {}
    var onSingleTapUp: () -> Unit = {}
    var onLongPress: () -> Unit = {}

    /**
     * The scale listener, used for handling multi-finger scale gestures.
     */
    private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        /**
         * This is the active focal point in terms of the viewport. Could be a local
         * variable but kept here to minimize per-frame allocations.
         */
        private val viewportFocus = PointF()

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!panScaleEnabled) {
                return false
            }

            dragZoomed = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!panScaleEnabled) {
                return false
            }

            val newWidth = 1 / detector.scaleFactor * currentViewport.width()
            val newHeight = 1 / detector.scaleFactor * currentViewport.height()

            val focusX = detector.focusX
            val focusY = detector.focusY
            hitTest(focusX, focusY, viewportFocus)

            currentViewport.set(
                    viewportFocus.x - newWidth * focusX / currentWidth,
                    viewportFocus.y - newHeight * focusY / currentHeight,
                    0f,
                    0f)
            currentViewport.right = currentViewport.left + newWidth
            currentViewport.bottom = currentViewport.top + newHeight
            constrainViewport()
            triggerViewportChangedListener()
            return true
        }
    }

    /**
     * The gesture listener, used for handling simple gestures such as double touches, scrolls,
     * and flings.
     */
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!panScaleEnabled) {
                return false
            }

            dragZoomed = false
            scrollerStartViewport.set(currentViewport)
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapUp.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress.invoke()
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (!panScaleEnabled || dragZoomed || e.actionMasked != MotionEvent.ACTION_UP) {
                return false
            }

            zoomer.forceFinished(true)
            hitTest(e.x, e.y, zoomFocalPoint)
            val startZoom = if (relativeAspectRatio > 1) {
                1 / currentViewport.height()
            } else {
                1 / currentViewport.width()
            }
            val zoomIn = startZoom < 1.5f
            zoomer.startZoom(startZoom, if (zoomIn) 2f else 1f)
            triggerViewportChangedListener()
            postAnimateTick()

            // Workaround for 11952668; blow away the entire scale gesture detector after
            // a double tap
            scaleGestureDetector = ScaleGestureDetector(getContext(), scaleGestureListener).apply {
                isQuickScaleEnabled = true
            }
            return true
        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!panScaleEnabled) {
                return false
            }

            // Scrolling uses math based on the viewport (as opposed to math using pixels).
            /*
              Pixel offset is the offset in screen pixels, while viewport offset is the
              offset within the current viewport. For additional information on surface sizes
              and pixel offsets, see the docs for {@link computeScrollSurfaceSize()}. For
              additional information about the viewport, see the comments for
              {@link currentViewport}.
             */
            val viewportOffsetX = distanceX * currentViewport.width() / currentWidth
            val viewportOffsetY = distanceY * currentViewport.height() / currentHeight
            computeScrollSurfaceSize(surfaceSizeBuffer)
            setViewportTopLeft(
                    currentViewport.left + viewportOffsetX,
                    currentViewport.top + viewportOffsetY)
            return true
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!panScaleEnabled) {
                return false
            }

            fling((-velocityX).toInt(), (-velocityY).toInt())
            return true
        }
    }

    private val animateTickRunnable = Runnable {
        var needsInvalidate = false

        if (scroller.computeScrollOffset()) {
            // The scroller isn't finished, meaning a fling or programmatic pan operation is
            // currently active.

            computeScrollSurfaceSize(surfaceSizeBuffer)
            val currX = scroller.currX
            val currY = scroller.currY

            val currXRange = currX * 1f / surfaceSizeBuffer.x
            val currYRange = currY * 1f / surfaceSizeBuffer.y
            setViewportTopLeft(currXRange, currYRange)
            needsInvalidate = true
        }

        if (zoomer.computeZoom()) {
            // Performs the zoom since a zoom is in progress.
            val newWidth: Float
            val newHeight: Float
            if (relativeAspectRatio > 1) {
                newHeight = 1 / zoomer.currZoom
                newWidth = newHeight / relativeAspectRatio
            } else {
                newWidth = 1 / zoomer.currZoom
                newHeight = newWidth * relativeAspectRatio
            }
            // focalPointOnScreen... 0 = left/top edge of screen, 1 = right/bottom edge of sreen
            val focalPointOnScreenX = (zoomFocalPoint.x - scrollerStartViewport.left) / scrollerStartViewport.width()
            val focalPointOnScreenY = (zoomFocalPoint.y - scrollerStartViewport.top) / scrollerStartViewport.height()
            currentViewport.set(
                    zoomFocalPoint.x - newWidth * focalPointOnScreenX,
                    zoomFocalPoint.y - newHeight * focalPointOnScreenY,
                    zoomFocalPoint.x + newWidth * (1 - focalPointOnScreenX),
                    zoomFocalPoint.y + newHeight * (1 - focalPointOnScreenY))
            constrainViewport()
            needsInvalidate = true
        }

        if (needsInvalidate) {
            triggerViewportChangedListener()
            postAnimateTick()
        }
    }

    init {
        setWillNotDraw(true)

        // Sets up interactions
        scaleGestureDetector = ScaleGestureDetector(context, scaleGestureListener).apply {
            isQuickScaleEnabled = true
        }
        gestureDetector = GestureDetectorCompat(context, gestureListener)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        currentWidth = Math.max(1, w)
        currentHeight = Math.max(1, h)
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and objects related to gesture handling
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Finds the chart point (i.e. within the chart's domain and range) represented by the
     * given pixel coordinates. The "dest" argument is set to the point and
     * this function returns true.
     */
    private fun hitTest(x: Float, y: Float, dest: PointF) {
        dest.set(currentViewport.left + currentViewport.width() * x / currentWidth,
                currentViewport.top + currentViewport.height() * y / currentHeight)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            motionEventDown = true
        }
        var retVal = scaleGestureDetector.onTouchEvent(event)
        retVal = gestureDetector.onTouchEvent(event) || retVal
        if (motionEventDown && event.actionMasked == MotionEvent.ACTION_UP) {
            motionEventDown = false
        }
        return retVal || super.onTouchEvent(event)
    }

    /**
     * Ensures that current viewport is inside the viewport extremes and original
     * aspect ratio is kept.
     */
    private fun constrainViewport() {
        currentViewport.run {
            if (relativeAspectRatio > 1) {
                if (top < 0) {
                    offset(0f, -top)
                }
                if (bottom > 1) {
                    val requestedHeight = height()
                    bottom = 1f
                    top = Math.max(0f, bottom - requestedHeight)
                }
                if (height() < minViewportWidthOrHeight) {
                    bottom = (bottom + top) / 2 + minViewportWidthOrHeight / 2
                    top = bottom - minViewportWidthOrHeight
                }
                val halfWidth = height() / relativeAspectRatio / 2f
                val centerX = ((right + left) / 2).constrain(halfWidth, 1 - halfWidth)
                left = centerX - halfWidth
                right = centerX + halfWidth
            } else {
                if (left < 0) {
                    offset(-left, 0f)
                }
                if (right > 1) {
                    val requestedWidth = width()
                    right = 1f
                    left = Math.max(0f, right - requestedWidth)
                }
                if (width() < minViewportWidthOrHeight) {
                    right = (right + left) / 2 + minViewportWidthOrHeight / 2
                    left = right - minViewportWidthOrHeight
                }
                val halfHeight = width() * relativeAspectRatio / 2
                val centerY = ((bottom + top) / 2).constrain(halfHeight, 1 - halfHeight)
                top = centerY - halfHeight
                bottom = centerY + halfHeight
            }
        }
    }

    private fun fling(velocityX: Int, velocityY: Int) {
        // Flings use math in pixels (as opposed to math based on the viewport).
        computeScrollSurfaceSize(surfaceSizeBuffer)
        scrollerStartViewport.set(currentViewport)
        val startX = (surfaceSizeBuffer.x * scrollerStartViewport.left).toInt()
        val startY = (surfaceSizeBuffer.y * scrollerStartViewport.top).toInt()
        scroller.forceFinished(true)
        scroller.fling(
                startX,
                startY,
                velocityX,
                velocityY,
                0, surfaceSizeBuffer.x - currentWidth,
                0, surfaceSizeBuffer.y - currentHeight,
                currentWidth / 2,
                currentHeight / 2)
        postAnimateTick()
        triggerViewportChangedListener()
    }

    private fun postAnimateTick() {
        handler?.run {
            removeCallbacks(animateTickRunnable)
            post(animateTickRunnable)
        }
    }

    /**
     * Computes the current scrollable surface size, in pixels. For example, if the entire chart
     * area is visible, this is simply the current view width and height. If the chart
     * is zoomed in 200% in both directions, the returned size will be twice as large horizontally
     * and vertically.
     */
    private fun computeScrollSurfaceSize(out: Point) {
        out.set(
                (currentWidth / currentViewport.width()).toInt(),
                (currentHeight / currentViewport.height()).toInt())
    }

    /**
     * Sets the current viewport (defined by [currentViewport]) to the given
     * X and Y positions. Note that the Y value represents the topmost pixel position, and thus
     * the bottom of the [currentViewport] rectangle. For more details on why top and
     * bottom are flipped, see [currentViewport].
     */
    private fun setViewportTopLeft(newX: Float, newY: Float) {
        var x = newX
        var y = newY
        /*
          Constrains within the scroll range. The scroll range is simply the viewport extremes
          (AXIS_X_MAX, etc.) minus the viewport size. For example, if the extrema were 0 and 10,
          and the viewport size was 2, the scroll range would be 0 to 8.
         */
        val curWidth = currentViewport.width()
        val curHeight = currentViewport.height()
        x = Math.max(0f, Math.min(x, 1 - curWidth))
        y = Math.max(0f, Math.min(y, 1 - curHeight))

        currentViewport.set(x, y, x + curWidth, y + curHeight)
        triggerViewportChangedListener()
    }

    private fun triggerViewportChangedListener() {
        onViewportChanged.invoke()
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and classes related to view state persistence.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        return SavedState(superState).apply { viewport = currentViewport }
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)

        currentViewport = state.viewport
    }

    fun setViewport(viewport: RectF) {
        currentViewport.set(viewport)
        triggerViewportChangedListener()
    }

    fun setMaxZoom(maxZoom: Int) {
        minViewportWidthOrHeight = 1f / maxZoom
    }

    /**
     * Persistent state that is saved by PanScaleProxyView.
     */
    internal class SavedState : View.BaseSavedState {

        companion object {
            @Suppress("unused")
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {

                override fun createFromParcel(source: Parcel): SavedState {
                    return SavedState(source)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }

        internal lateinit var viewport: RectF

        constructor(superState: Parcelable?) : super(superState)

        internal constructor(source: Parcel) : super(source) {
            viewport = RectF(source.readFloat(), source.readFloat(), source.readFloat(), source.readFloat())
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeFloat(viewport.left)
            out.writeFloat(viewport.top)
            out.writeFloat(viewport.right)
            out.writeFloat(viewport.bottom)
        }

        override fun toString(): String {
            return ("PanScaleProxyView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " viewport=" + viewport.toString() + "}")
        }
    }
}
