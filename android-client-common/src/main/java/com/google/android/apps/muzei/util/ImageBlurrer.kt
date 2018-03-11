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
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.Matrix3f
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.renderscript.ScriptIntrinsicColorMatrix

fun Bitmap?.blur(context: Context, radius: Float = ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS.toFloat())
        : Bitmap? {
    val blurrer = ImageBlurrer(context, this)
    val blurred = blurrer.blurBitmap(radius)
    blurrer.destroy()
    return blurred
}

class ImageBlurrer(context: Context, private val sourceBitmap: Bitmap?) {

    companion object {
        const val MAX_SUPPORTED_BLUR_PIXELS = 25
    }

    private val renderScript: RenderScript = RenderScript.create(context)
    private val scriptIntrinsicBlur: ScriptIntrinsicBlur
    private val scriptIntrinsicGrey: ScriptIntrinsicColorMatrix
    private val allocationSrc: Allocation?

    init {
        scriptIntrinsicBlur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
        scriptIntrinsicGrey = ScriptIntrinsicColorMatrix.create(renderScript)
        allocationSrc = if (sourceBitmap != null) Allocation.createFromBitmap(renderScript, sourceBitmap) else null
    }

    @JvmOverloads
    fun blurBitmap(radius: Float = MAX_SUPPORTED_BLUR_PIXELS.toFloat(), desaturateAmount: Float = 0f): Bitmap? {
        if (sourceBitmap == null || allocationSrc == null) {
            return null
        }

        val dest = sourceBitmap.copy(sourceBitmap.config, true)
        if (radius == 0f && desaturateAmount == 0f) {
            return dest
        }

        val allocationDest = Allocation.createFromBitmap(renderScript, dest)

        if (radius > 0f && desaturateAmount > 0f) {
            doBlur(radius, allocationSrc, allocationDest)
            doDesaturate(desaturateAmount.constrain(0f, 1f), allocationDest, allocationSrc)
            allocationSrc.copyTo(dest)
        } else if (radius > 0f) {
            doBlur(radius, allocationSrc, allocationDest)
            allocationDest.copyTo(dest)
        } else {
            doDesaturate(desaturateAmount.constrain(0f, 1f), allocationSrc, allocationDest)
            allocationDest.copyTo(dest)
        }
        allocationDest.destroy()
        return dest
    }

    private fun doBlur(amount: Float, input: Allocation, output: Allocation) {
        scriptIntrinsicBlur.setRadius(amount)
        scriptIntrinsicBlur.setInput(input)
        scriptIntrinsicBlur.forEach(output)
    }

    private fun doDesaturate(normalizedAmount: Float, input: Allocation, output: Allocation) {
        val m = Matrix3f(floatArrayOf(
                interpolate(1f, 0.299f, normalizedAmount),
                interpolate(0f, 0.299f, normalizedAmount),
                interpolate(0f, 0.299f, normalizedAmount),

                interpolate(0f, 0.587f, normalizedAmount),
                interpolate(1f, 0.587f, normalizedAmount),
                interpolate(0f, 0.587f, normalizedAmount),

                interpolate(0f, 0.114f, normalizedAmount),
                interpolate(0f, 0.114f, normalizedAmount),
                interpolate(1f, 0.114f, normalizedAmount)))
        scriptIntrinsicGrey.setColorMatrix(m)
        scriptIntrinsicGrey.forEach(input, output)
    }

    fun destroy() {
        scriptIntrinsicBlur.destroy()
        scriptIntrinsicGrey.destroy()
        allocationSrc?.destroy()
        renderScript.destroy()
    }
}
