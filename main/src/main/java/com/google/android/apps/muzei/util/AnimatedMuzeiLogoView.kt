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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

import java.text.ParseException

class AnimatedMuzeiLogoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : View(context, attrs, defStyle) {

    companion object {
        private const val TAG = "AnimatedMuzeiLogoView"

        private const val TRACE_TIME = 2000
        private const val TRACE_TIME_PER_GLYPH = 1000
        private const val FILL_START = 1200
        private const val FILL_TIME = 2000
        private const val MARKER_LENGTH_DIP = 16
        private val TRACE_RESIDUE_COLOR = Color.argb(50, 255, 255, 255)
        private const val TRACE_COLOR = Color.WHITE
        private val VIEWPORT = PointF(1000f, 300f)

        private val INTERPOLATOR = DecelerateInterpolator()

        const val STATE_NOT_STARTED = 0
        const val STATE_TRACE_STARTED = 1
        const val STATE_FILL_STARTED = 2
        const val STATE_FINISHED = 3
    }

    private val fillPaint: Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private var glyphData: Array<GlyphData>? = null
    private val markerLength: Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            MARKER_LENGTH_DIP.toFloat(), resources.displayMetrics)
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var startTime: Long = 0

    private var state = STATE_NOT_STARTED
        set(value) {
            if (field != value) {
                field = value
                onStateChange.invoke(value)
            }
        }
    var onStateChange: (state: Int) -> Unit = {}

    init {

        // See https://github.com/romainguy/road-trip/blob/master/application/src/main/java/org/curiouscreature/android/roadtrip/IntroView.java
        // Note: using a software layer here is an optimization. This view works with
        // hardware accelerated rendering but every time a path is modified (when the
        // dash path effect is modified), the graphics pipeline will rasterize the path
        // again in a new texture. Since we are dealing with dozens of paths, it is much
        // more efficient to rasterize the entire view into a single re-usable texture
        // instead. Ideally this should be toggled using a heuristic based on the number
        // and or dimensions of paths to render.
        // Note that PathDashPathEffects can lead to clipping issues with hardware rendering.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun start() {
        startTime = System.currentTimeMillis()
        state = STATE_TRACE_STARTED
        postInvalidateOnAnimation()
    }

    fun reset() {
        startTime = 0
        state = STATE_NOT_STARTED
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        currentWidth = w
        currentHeight = h
        rebuildGlyphData()
    }

    private fun rebuildGlyphData() {
        val parser = SvgPathParser(
                transformX = { x -> x * currentWidth / VIEWPORT.x },
                transformY = { y -> y * currentHeight / VIEWPORT.y })

        glyphData = LogoPaths.GLYPHS.map { glyph ->
            val path = try {
                parser.parsePath(glyph)
            } catch (e: ParseException) {
                Log.e(TAG, "Couldn't parse path", e)
                Path()
            }
            GlyphData(path, Paint().apply {
                style = Paint.Style.STROKE
                isAntiAlias = true
                color = Color.WHITE
                strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f,
                        resources.displayMetrics)
            })
        }.toTypedArray()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == STATE_NOT_STARTED || glyphData == null) {
            return
        }

        val t = System.currentTimeMillis() - startTime

        glyphData?.let { glyphData ->
            // Draw outlines (starts as traced)
            val size = glyphData.size
            glyphData.forEachIndexed { index, glyph ->
                val phase = ((t - (TRACE_TIME - TRACE_TIME_PER_GLYPH).toFloat() *
                        index.toFloat() * 1f / size)
                        * 1f / TRACE_TIME_PER_GLYPH).constrain(0f, 1f)
                val distance = INTERPOLATOR.getInterpolation(phase) * glyph.length
                glyph.paint.color = TRACE_RESIDUE_COLOR
                glyph.paint.pathEffect = DashPathEffect(
                        floatArrayOf(distance, glyph.length), 0f)
                canvas.drawPath(glyph.path, glyph.paint)

                glyph.paint.color = TRACE_COLOR
                glyph.paint.pathEffect = DashPathEffect(
                        floatArrayOf(0f, distance, if (phase > 0) markerLength else 0f, glyph.length), 0f)
                canvas.drawPath(glyph.path, glyph.paint)
            }

            if (t > FILL_START) {
                if (state < STATE_FILL_STARTED) {
                    state = STATE_FILL_STARTED
                }

                // If after fill start, draw fill
                val phase = ((t - FILL_START) * 1f / FILL_TIME).constrain(0f, 1f)
                fillPaint.setARGB((phase * 255).toInt(), 255, 255, 255)
                for (glyph in glyphData) {
                    canvas.drawPath(glyph.path, fillPaint)
                }
            }

            if (t < FILL_START + FILL_TIME) {
                // draw next frame if animation isn't finished
                postInvalidateOnAnimation()
            } else {
                state = STATE_FINISHED
            }
        }
    }

    private data class GlyphData(val path: Path, val paint: Paint) {
        internal val length: Float

        init {
            val pm = PathMeasure(path, true)
            var len = pm.length
            while (true) {
                len = Math.max(len, pm.length)
                if (!pm.nextContour()) {
                    break
                }
            }
            length = len
        }
    }

    public override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        return SavedState(superState).apply {
            state = this@AnimatedMuzeiLogoView.state
            startTime = this@AnimatedMuzeiLogoView.startTime
        }
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        val ss = state as SavedState
        super.onRestoreInstanceState(ss.superState)
        this.state = ss.state
        startTime = ss.startTime
        postInvalidateOnAnimation()
    }

    internal class SavedState : View.BaseSavedState {

        companion object {
            @Suppress("unused")
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState {
                    return SavedState(source)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }

        var state: Int = 0
        var startTime: Long = 0

        constructor(superState: Parcelable?) : super(superState)

        private constructor(source: Parcel) : super(source) {
            state = source.readInt()
            startTime = source.readLong()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(state)
            out.writeLong(startTime)
        }
    }
}
