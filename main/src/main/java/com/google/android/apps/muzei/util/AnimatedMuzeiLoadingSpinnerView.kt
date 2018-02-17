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
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import java.text.ParseException

class AnimatedMuzeiLoadingSpinnerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : View(context, attrs, defStyle) {

    companion object {
        private const val TAG = "AnimatedLoadingSpinner"

        private const val TRACE_TIME = 1000
        private const val MARKER_LENGTH_DIP = 16
        private val TRACE_RESIDUE_COLOR = Color.argb(50, 255, 255, 255)
        private const val TRACE_COLOR = Color.WHITE
        private val VIEWPORT = RectF(0f, 88f, 318f, 300f)

        private val INTERPOLATOR = LinearInterpolator()
    }

    private var mGlyphData: GlyphData? = null
    private val mMarkerLength: Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            MARKER_LENGTH_DIP.toFloat(), resources.displayMetrics)
    private var mWidth: Int = 0
    private var mHeight: Int = 0
    private var mStartTime: Long = -1

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
        mStartTime = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    fun stop() {
        mStartTime = -1
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
        rebuildGlyphData()
    }

    private fun rebuildGlyphData() {
        val parser = object : SvgPathParser() {
            override fun transformX(x: Float): Float {
                return x * mWidth / VIEWPORT.width()
            }

            override fun transformY(y: Float): Float {
                return y * mHeight / VIEWPORT.height()
            }
        }

        val path = try {
            parser.parsePath(LogoPaths.GLYPHS[0])
        } catch (e: ParseException) {
            Log.e(TAG, "Couldn't parse path", e)
            Path()
        }
        mGlyphData = GlyphData(path, Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
            color = Color.WHITE
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f,
                    resources.displayMetrics)
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mStartTime < 0 || mGlyphData == null) {
            return
        }

        val t = (System.currentTimeMillis() - mStartTime) % (TRACE_TIME * 2)

        val sc = canvas.save()
        canvas.translate(
                -VIEWPORT.left * mWidth / VIEWPORT.width(),
                -VIEWPORT.top * mHeight / VIEWPORT.height())

        mGlyphData?.let { glyphData ->
            // Draw outlines (starts as traced)
            val phase = MathUtil.constrain(0f, 1f, t % TRACE_TIME * 1f / TRACE_TIME)
            val distance = INTERPOLATOR.getInterpolation(phase) * glyphData.length

            glyphData.paint.apply {
                color = TRACE_RESIDUE_COLOR
                @SuppressLint("DrawAllocation")
                pathEffect = DashPathEffect(if (t < TRACE_TIME) {
                    floatArrayOf(distance, glyphData.length)
                } else {
                    floatArrayOf(0f, distance, glyphData.length, 0f)
                }, 0f)
            }
            canvas.drawPath(glyphData.path, glyphData.paint)

            glyphData.paint.apply {
                color = TRACE_COLOR
                @SuppressLint("DrawAllocation")
                pathEffect = DashPathEffect(
                        floatArrayOf(0f, distance, if (phase > 0) mMarkerLength else 0f, glyphData.length), 0f)
            }
            canvas.drawPath(glyphData.path, glyphData.paint)
            canvas.restoreToCount(sc)

            postInvalidateOnAnimation()
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
}
