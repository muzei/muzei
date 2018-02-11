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
import android.support.v4.content.ContextCompat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.*

/**
 * Draws a cool random highlighted cells animation.
 */
class GalleryEmptyStateGraphicView
    @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : View(context, attrs, defStyleAttr) {

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

    private val mOffPaint = Paint().apply { isAntiAlias = true }
    private val mOnPaint = Paint().apply { isAntiAlias = true }
    private var mWidth: Int = 0
    private var mHeight: Int = 0
    private var mOnTime: Long = 0
    private var mOnX: Int = 0
    private var mOnY: Int = 0
    private val mRandom = Random()
    private val mTempRectF = RectF()
    private val mCellSpacing: Int
    private val mCellRounding: Int
    private val mCellSize: Int

    init {
        mOffPaint.color = ContextCompat.getColor(context, R.color.gallery_empty_state_dark)
        mOnPaint.color = ContextCompat.getColor(context, R.color.gallery_empty_state_light)

        val displayMetrics = resources.displayMetrics
        mCellSpacing = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_SPACING_DIP.toFloat(), displayMetrics).toInt()
        mCellSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_SIZE_DIP.toFloat(), displayMetrics).toInt()
        mCellRounding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_ROUNDING_DIP.toFloat(), displayMetrics).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
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
                View.resolveSize(COLS * mCellSize + (COLS - 1) * mCellSpacing, widthMeasureSpec),
                View.resolveSize(ROWS * mCellSize + (ROWS - 1) * mCellSpacing, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isShown || mWidth == 0 || mHeight == 0) {
            return
        }

        // tick timer
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed > mOnTime + ON_TIME_MILLIS.toLong() + (FADE_TIME_MILLIS * 2).toLong() + OFF_TIME_MILLIS.toLong()) {
            mOnTime = nowElapsed
            while (true) {
                val x = mRandom.nextInt(COLS)
                val y = mRandom.nextInt(ROWS)
                if ((x != mOnX || y != mOnY) && BITMAP[y * COLS + x] == 1) {
                    mOnX = x
                    mOnY = y
                    break
                }
            }
        }

        val t = (nowElapsed - mOnTime).toInt()
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                if (BITMAP[y * COLS + x] != 1) {
                    continue
                }

                mTempRectF.set(
                        (x * (mCellSize + mCellSpacing)).toFloat(),
                        (y * (mCellSize + mCellSpacing)).toFloat(),
                        (x * (mCellSize + mCellSpacing) + mCellSize).toFloat(),
                        (y * (mCellSize + mCellSpacing) + mCellSize).toFloat())

                canvas.drawRoundRect(mTempRectF,
                        mCellRounding.toFloat(),
                        mCellRounding.toFloat(),
                        mOffPaint)

                if (nowElapsed <= mOnTime + ON_TIME_MILLIS.toLong() + (FADE_TIME_MILLIS * 2).toLong()
                        && mOnX == x && mOnY == y) {
                    // draw items
                    mOnPaint.alpha = when {
                        t < FADE_TIME_MILLIS -> t * 255 / FADE_TIME_MILLIS
                        t < FADE_TIME_MILLIS + ON_TIME_MILLIS -> 255
                        else -> 255 - (t - ON_TIME_MILLIS - FADE_TIME_MILLIS) * 255 / FADE_TIME_MILLIS
                    }

                    canvas.drawRoundRect(mTempRectF,
                            mCellRounding.toFloat(),
                            mCellRounding.toFloat(),
                            mOnPaint)
                }
            }
        }
        postInvalidateOnAnimation()
    }
}
