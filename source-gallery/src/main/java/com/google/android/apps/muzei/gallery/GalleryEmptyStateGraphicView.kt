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

package com.google.android.apps.muzei.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Random

/**
 * Draws a cool random highlighted cells animation.
 */
class GalleryEmptyStateGraphicView
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private val BITMAP = intArrayOf(
                0, 0, 1, 1, 1, 1, 0, 0,
                1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 0, 0, 1, 1, 1,
                1, 1, 0, 1, 1, 0, 1, 1,
                1, 1, 0, 1, 1, 0, 1, 1,
                1, 1, 1, 0, 0, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1
        )

        private const val COLS = 8
        private val ROWS = BITMAP.size / COLS

        private const val CELL_SPACING_DIP = 2
        private const val CELL_ROUNDING_DIP = 1
        private const val CELL_SIZE_DIP = 8

        private const val ON_TIME_MILLIS = 400
        private const val FADE_TIME_MILLIS = 100
        private const val OFF_TIME_MILLIS = 50
    }

    private val offPaint = Paint().apply { isAntiAlias = true }
    private val onPaint = Paint().apply { isAntiAlias = true }
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var onTime: Long = 0
    private var onX: Int = 0
    private var onY: Int = 0
    private val random = Random()
    private val tempRectF = RectF()
    private val cellSpacing: Int
    private val cellRounding: Int
    private val cellSize: Int

    init {
        offPaint.color = ContextCompat.getColor(context, R.color.gallery_empty_state_dark)
        onPaint.color = ContextCompat.getColor(context, R.color.gallery_empty_state_light)

        val displayMetrics = resources.displayMetrics
        cellSpacing = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_SPACING_DIP.toFloat(), displayMetrics).toInt()
        cellSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_SIZE_DIP.toFloat(), displayMetrics).toInt()
        cellRounding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_ROUNDING_DIP.toFloat(), displayMetrics).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        currentWidth = w
        currentHeight = h
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) {
            postInvalidateOnAnimation()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
                View.resolveSize(COLS * cellSize + (COLS - 1) * cellSpacing, widthMeasureSpec),
                View.resolveSize(ROWS * cellSize + (ROWS - 1) * cellSpacing, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isShown || currentWidth == 0 || currentHeight == 0) {
            return
        }

        // tick timer
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed > onTime + ON_TIME_MILLIS.toLong() + (FADE_TIME_MILLIS * 2).toLong() + OFF_TIME_MILLIS.toLong()) {
            onTime = nowElapsed
            while (true) {
                val x = random.nextInt(COLS)
                val y = random.nextInt(ROWS)
                if ((x != onX || y != onY) && BITMAP[y * COLS + x] == 1) {
                    onX = x
                    onY = y
                    break
                }
            }
        }

        val t = (nowElapsed - onTime).toInt()
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                if (BITMAP[y * COLS + x] != 1) {
                    continue
                }

                tempRectF.set(
                        (x * (cellSize + cellSpacing)).toFloat(),
                        (y * (cellSize + cellSpacing)).toFloat(),
                        (x * (cellSize + cellSpacing) + cellSize).toFloat(),
                        (y * (cellSize + cellSpacing) + cellSize).toFloat())

                canvas.drawRoundRect(tempRectF,
                        cellRounding.toFloat(),
                        cellRounding.toFloat(),
                        offPaint)

                if (nowElapsed <= onTime + ON_TIME_MILLIS.toLong() + (FADE_TIME_MILLIS * 2).toLong()
                        && onX == x && onY == y) {
                    // draw items
                    onPaint.alpha = when {
                        t < FADE_TIME_MILLIS -> t * 255 / FADE_TIME_MILLIS
                        t < FADE_TIME_MILLIS + ON_TIME_MILLIS -> 255
                        else -> 255 - (t - ON_TIME_MILLIS - FADE_TIME_MILLIS) * 255 / FADE_TIME_MILLIS
                    }

                    canvas.drawRoundRect(tempRectF,
                            cellRounding.toFloat(),
                            cellRounding.toFloat(),
                            onPaint)
                }
            }
        }
        postInvalidateOnAnimation()
    }
}
