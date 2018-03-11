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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.content.res.use
import net.nurik.roman.muzei.R

class Scrollbar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : View(context, attrs, defStyle) {

    companion object {
        private const val DEFAULT_BACKGROUND_COLOR = -0x80000000
        private const val DEFAULT_INDICATOR_COLOR = -0x1000000
    }

    private var hidden = true

    private val animationDuration: Long = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
    private val backgroundPaint: Paint = Paint().apply { isAntiAlias = true }
    private val indicatorPaint: Paint = Paint().apply { isAntiAlias = true }

    private val tempPath = Path()
    private val tempRectF = RectF()

    private var currentWidth: Int = 0
    private var currentHeight: Int = 0

    private var scrollRange: Int = 0
    private var viewportWidth: Int = 0
    private var indicatorWidth: Float = 0f

    private var scrollPosition: Float = 0f

    init {
        context.obtainStyledAttributes(
                attrs, R.styleable.Scrollbar).use {
            backgroundPaint.color =
                    it.getColor(R.styleable.Scrollbar_backgroundColor, DEFAULT_BACKGROUND_COLOR)
            indicatorPaint.color =
                    it.getColor(R.styleable.Scrollbar_indicatorColor, DEFAULT_INDICATOR_COLOR)
        }

        alpha = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scrollRange <= viewportWidth) {
            return
        }

        tempRectF.apply {
            top = 0f
            bottom = currentHeight.toFloat()
            left = 0f
            right = currentWidth.toFloat()
        }

        drawPill(canvas, tempRectF, backgroundPaint)

        tempRectF.apply {
            top = 0f
            bottom = currentHeight.toFloat()
            left = (scrollPosition * 1f / (scrollRange - viewportWidth)
                    * currentWidth.toFloat() * (1 - indicatorWidth))
            right = tempRectF.left + indicatorWidth * currentWidth
        }

        drawPill(canvas, tempRectF, indicatorPaint)
    }

    private fun drawPill(canvas: Canvas, rectF: RectF, paint: Paint) {
        val radius = rectF.height() / 2
        var temp: Float

        tempPath.reset()
        tempPath.moveTo(rectF.left + radius, rectF.top)
        tempPath.lineTo(rectF.right - radius, rectF.top)

        temp = rectF.left
        rectF.left = rectF.right - 2 * radius
        tempPath.arcTo(rectF, 270f, 180f)
        rectF.left = temp

        tempPath.lineTo(rectF.left + radius, rectF.bottom)

        temp = rectF.right
        rectF.right = rectF.left + rectF.height()
        tempPath.arcTo(rectF, 90f, 180f)
        rectF.right = temp

        tempPath.close()
        canvas.drawPath(tempPath, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
                View.resolveSize(0, widthMeasureSpec),
                View.resolveSize(0, heightMeasureSpec))
    }

    fun setScrollPosition(position: Int) {
        scrollPosition = position.toFloat().constrain(0f, scrollRange.toFloat())
        postInvalidateOnAnimation()
    }

    fun setScrollRangeAndViewportWidth(scrollRange: Int, viewportWidth: Int) {
        this.scrollRange = scrollRange
        this.viewportWidth = viewportWidth
        indicatorWidth = if (scrollRange > 0) {
            (viewportWidth * 1f / scrollRange).constrain(indicatorWidth, 1f)
        } else {
            0.1f
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        currentHeight = h
        currentWidth = w
    }

    fun show() {
        if (!hidden) {
            return
        }

        hidden = false
        animate().cancel()
        animate().alpha(1f).duration = animationDuration
    }

    fun hide() {
        if (hidden) {
            return
        }

        hidden = true
        animate().cancel()
        animate().alpha(0f).duration = animationDuration
    }
}
