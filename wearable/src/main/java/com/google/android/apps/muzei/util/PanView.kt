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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EdgeEffect
import android.widget.OverScroller
import androidx.annotation.Keep

/**
 * View which supports panning around an image larger than the screen size. Supports both scrolling
 * and flinging
 */
class PanView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : View(context, attrs, defStyle) {

    companion object {
        private const val TAG = "PanView"
    }

    private var image: Bitmap? = null
    private var scaledImage: Bitmap? = null
    private var blurredImage: Bitmap? = null
    private var blurAmount = 0f
    private val drawBlurredPaint = Paint().apply {
        isDither = true
    }

    /**
     * Horizontal offset for painting the image. As this is used in a canvas.drawBitmap it ranges
     * from a negative value currentWidth-image.getWidth() (remember the view is smaller than the image)
     * to zero. If it is zero that means the offsetX side of the image is visible otherwise it is
     * off screen and we are farther to the right.
     */
    private var offsetX: Float = 0f
    /**
     * Vertical offset for painting the image. As this is used in a canvas.drawBitmap it ranges
     * from a negative value currentHeight-image.getHeight() (remember the view is smaller than the image)
     * to zero. If it is zero that means the offsetY side of the image is visible otherwise it is
     * off screen and we are farther down.
     */
    private var offsetY: Float = 0f
    /**
     * View width
     */
    private var currentWidth = 1
    /**
     * View height
     */
    private var currentHeight = 1

    // State objects and values related to gesture tracking.
    private val gestureDetector = GestureDetector(context, ScrollFlingGestureListener())
    private val scroller = OverScroller(context)

    // Edge effect / overscroll tracking objects.
    private val edgeEffectTop = EdgeEffect(context)
    private val edgeEffectBottom = EdgeEffect(context)
    private val edgeEffectLeft = EdgeEffect(context)
    private val edgeEffectRight = EdgeEffect(context)

    private var edgeEffectTopActive = false
    private var edgeEffectBottomActive = false
    private var edgeEffectLeftActive = false
    private var edgeEffectRightActive = false

    private val animateTickRunnable = Runnable {
        val scaledImage = scaledImage ?: return@Runnable

        if (scroller.computeScrollOffset()) {
            // The scroller isn't finished, meaning a fling is currently active.
            setOffset(scroller.currX.toFloat(), scroller.currY.toFloat())

            if (currentWidth != scaledImage.width && offsetX < scroller.currX
                    && edgeEffectLeft.isFinished
                    && !edgeEffectLeftActive) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Left edge absorbing ${scroller.currVelocity}")
                }
                edgeEffectLeft.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectLeftActive = true
            } else if (currentWidth != scaledImage.width && offsetX > scroller.currX
                    && edgeEffectRight.isFinished
                    && !edgeEffectRightActive) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Right edge absorbing ${scroller.currVelocity}")
                }
                edgeEffectRight.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectRightActive = true
            }

            if (currentHeight != scaledImage.height && offsetY < scroller.currY
                    && edgeEffectTop.isFinished
                    && !edgeEffectTopActive) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Top edge absorbing ${scroller.currVelocity}")
                }
                edgeEffectTop.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectTopActive = true
            } else if (currentHeight != scaledImage.height && offsetY > scroller.currY
                    && edgeEffectBottom.isFinished
                    && !edgeEffectBottomActive) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Bottom edge absorbing ${scroller.currVelocity}")
                }
                edgeEffectBottom.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectBottomActive = true
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Flinging to $offsetX, $offsetY")
            }
            invalidate()
            postAnimateTick()
        }
    }

    /**
     * Sets an image to be displayed. Preferably this image should be larger than this view's size
     * to allow scrolling. Note that the image will be centered on first display
     * @param image Image to display
     */
    fun setImage(image: Bitmap?) {
        this.image = image
        updateScaledImage()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        currentWidth = Math.max(1, w)
        currentHeight = Math.max(1, h)
        updateScaledImage()
    }

    private fun updateScaledImage() {
        val image = image?.takeUnless { it.width == 0 || it.height == 0 } ?: return

        val width = image.width
        val height = image.height
        scaledImage = if (width > height) {
            val scalingFactor = currentHeight * 1f / height
            val scaledWidth = Math.max(1, (scalingFactor * width).toInt())
            Bitmap.createScaledBitmap(image, scaledWidth, currentHeight, true)
        } else {
            val scalingFactor = currentWidth * 1f / width
            val scaledHeight = Math.max(1, (scalingFactor * height).toInt())
            Bitmap.createScaledBitmap(image, currentWidth, scaledHeight, true)
        }
        blurredImage = scaledImage.blur(context)
        scaledImage?.let {
            // Center the image
            offsetX = ((currentWidth - it.width) / 2).toFloat()
            offsetY = ((currentHeight - it.height) / 2).toFloat()
        }
        invalidate()
    }

    @Suppress("unused")
    @Keep
    fun setBlurAmount(blurAmount: Float) {
        this.blurAmount = blurAmount
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (blurAmount < 1f) {
            scaledImage?.run {
                canvas.drawBitmap(this, offsetX, offsetY, null)
            }
        }
        if (blurAmount > 0f) {
            blurredImage?.run {
                drawBlurredPaint.alpha = (blurAmount * 255).toInt()
                canvas.drawBitmap(this, offsetX, offsetY, drawBlurredPaint)
            }
        }
        drawEdgeEffects(canvas)
    }

    /**
     * Draws the overscroll "glow" at the four edges, if necessary
     *
     * @see EdgeEffect
     */
    private fun drawEdgeEffects(canvas: Canvas) {
        // The methods below rotate and translate the canvas as needed before drawing the glow,
        // since EdgeEffect always draws a top-glow at 0,0.

        var needsInvalidate = false

        if (!edgeEffectTop.isFinished) {
            val restoreCount = canvas.save()
            edgeEffectTop.setSize(currentWidth, currentHeight)
            if (edgeEffectTop.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeEffectBottom.isFinished) {
            val restoreCount = canvas.save()
            canvas.translate((-currentWidth).toFloat(), currentHeight.toFloat())
            canvas.rotate(180f, currentWidth.toFloat(), 0f)
            edgeEffectBottom.setSize(currentWidth, currentHeight)
            if (edgeEffectBottom.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeEffectLeft.isFinished) {
            val restoreCount = canvas.save()
            canvas.translate(0f, currentHeight.toFloat())
            canvas.rotate(-90f, 0f, 0f)

            edgeEffectLeft.setSize(currentHeight, currentWidth)
            if (edgeEffectLeft.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeEffectRight.isFinished) {
            val restoreCount = canvas.save()
            canvas.translate(currentWidth.toFloat(), 0f)
            canvas.rotate(90f, 0f, 0f)

            edgeEffectRight.setSize(currentHeight, currentWidth)
            if (edgeEffectRight.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (needsInvalidate) {
            invalidate()
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and objects related to gesture handling
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun setOffset(offsetX: Float, offsetY: Float) {
        val scaledImage = scaledImage ?: return

        // Constrain between currentWidth - scaledImage.getWidth() and 0
        // currentWidth - scaledImage.getWidth() -> right edge visible
        // 0 -> left edge visible
        this.offsetX = Math.min(0f, Math.max((currentWidth - scaledImage.width).toFloat(), offsetX))
        // Constrain between currentHeight - scaledImage.getHeight() and 0
        // currentHeight - scaledImage.getHeight() -> bottom edge visible
        // 0 -> top edge visible
        this.offsetY = Math.min(0f, Math.max((currentHeight - scaledImage.height).toFloat(), offsetY))
    }

    /**
     * The gesture listener, used for handling simple gestures such as scrolls and flings.
     */
    private inner class ScrollFlingGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            releaseEdgeEffects()
            scroller.forceFinished(true)
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val scaledImage = scaledImage ?: return true

            val oldOffsetX = offsetX
            val oldOffsetY = offsetY
            setOffset(offsetX - distanceX, offsetY - distanceY)
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Scrolling to $offsetX, $offsetY")
            }
            if (currentWidth != scaledImage.width && offsetX < oldOffsetX - distanceX) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Left edge pulled " + -distanceX)
                }
                edgeEffectLeft.onPull(-distanceX * 1f / currentWidth)
                edgeEffectLeftActive = true
            }
            if (currentHeight != scaledImage.height && offsetY < oldOffsetY - distanceY) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Top edge pulled $distanceY")
                }
                edgeEffectTop.onPull(-distanceY * 1f / currentHeight)
                edgeEffectTopActive = true
            }
            if (currentHeight != scaledImage.height && offsetY > oldOffsetY - distanceY) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Bottom edge pulled " + -distanceY)
                }
                edgeEffectBottom.onPull(distanceY * 1f / currentHeight)
                edgeEffectBottomActive = true
            }
            if (currentWidth != scaledImage.width && offsetX > oldOffsetX - distanceX) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Right edge pulled $distanceX")
                }
                edgeEffectRight.onPull(distanceX * 1f / currentWidth)
                edgeEffectRightActive = true
            }
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val scaledImage = scaledImage ?: return true

            releaseEdgeEffects()
            scroller.forceFinished(true)
            scroller.fling(
                    offsetX.toInt(),
                    offsetY.toInt(),
                    velocityX.toInt(),
                    velocityY.toInt(),
                    currentWidth - scaledImage.width, 0, // currentWidth - scaledImage.getWidth() is negative
                    currentHeight - scaledImage.height, 0, // currentHeight - scaledImage.getHeight() is negative
                    scaledImage.width / 2,
                    scaledImage.height / 2)
            postAnimateTick()
            invalidate()
            return true
        }

        private fun releaseEdgeEffects() {
            edgeEffectBottomActive = false
            edgeEffectRightActive = edgeEffectBottomActive
            edgeEffectTopActive = edgeEffectRightActive
            edgeEffectLeftActive = edgeEffectTopActive
            edgeEffectLeft.onRelease()
            edgeEffectTop.onRelease()
            edgeEffectRight.onRelease()
            edgeEffectBottom.onRelease()
        }
    }

    private fun postAnimateTick() {
        handler?.run {
            removeCallbacks(animateTickRunnable)
            post(animateTickRunnable)
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and classes related to view state persistence.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val ss = SavedState(superState)
        ss.offsetX = offsetX
        ss.offsetY = offsetY
        return ss
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)

        offsetX = state.offsetX
        offsetY = state.offsetY
    }

    /**
     * Persistent state that is saved by PanView.
     */
    class SavedState : View.BaseSavedState {

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

        var offsetX = 0f
        var offsetY = 0f

        constructor(superState: Parcelable?) : super(superState)

        internal constructor(source: Parcel) : super(source) {
            offsetX = source.readFloat()
            offsetY = source.readFloat()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeFloat(offsetX)
            out.writeFloat(offsetY)
        }

        override fun toString(): String {
            return ("PanView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " offset=$offsetX, $offsetY}")
        }
    }
}
