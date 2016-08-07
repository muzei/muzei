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

package com.google.android.apps.muzei.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.Matrix3f;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;
import android.support.v8.renderscript.ScriptIntrinsicColorMatrix;

public class ImageBlurrer {
    public static final int MAX_SUPPORTED_BLUR_PIXELS = 25;

    private final RenderScript mRS;
    private final ScriptIntrinsicBlur mSIBlur;
    private final ScriptIntrinsicColorMatrix mSIGrey;

    private Allocation mAllocationSrc;
    private Allocation mAllocationDest;

    public ImageBlurrer(Context context) {
        mRS = RenderScript.create(context);
        mSIBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS));
        mSIGrey = ScriptIntrinsicColorMatrix.create(mRS, Element.U8_4(mRS));
    }

    public Bitmap blurBitmap(Bitmap src, float radius, float desaturateAmount) {
        if (src == null) {
            return null;
        }

        Bitmap dest = src.copy(src.getConfig(), false);
        if (radius == 0f && desaturateAmount == 0f) {
            return dest;
        }

        if (mAllocationSrc == null) {
            mAllocationSrc = Allocation.createFromBitmap(mRS, src);
        } else {
            mAllocationSrc.copyFrom(src);
        }
        if (mAllocationDest == null) {
            mAllocationDest = Allocation.createFromBitmap(mRS, dest);
        } else {
            mAllocationDest.copyFrom(dest);
        }

        if (radius > 0f && desaturateAmount > 0f) {
            doBlur(radius, mAllocationSrc, mAllocationDest);
            doDesaturate(MathUtil.constrain(0, 1, desaturateAmount), mAllocationDest, mAllocationSrc);
            mAllocationSrc.copyTo(dest);
        } else if (radius > 0f) {
            doBlur(radius, mAllocationSrc, mAllocationDest);
            mAllocationDest.copyTo(dest);
        } else {
            doDesaturate(MathUtil.constrain(0, 1, desaturateAmount), mAllocationSrc, mAllocationDest);
            mAllocationDest.copyTo(dest);
        }
        return dest;
    }

    private void doBlur(float amount, Allocation input, Allocation output) {
        mSIBlur.setRadius(amount);
        mSIBlur.setInput(input);
        mSIBlur.forEach(output);
    }

    private void doDesaturate(float normalizedAmount, Allocation input, Allocation output) {
        Matrix3f m = new Matrix3f(new float[]{
                MathUtil.interpolate(1, 0.299f, normalizedAmount),
                MathUtil.interpolate(0, 0.299f, normalizedAmount),
                MathUtil.interpolate(0, 0.299f, normalizedAmount),

                MathUtil.interpolate(0, 0.587f, normalizedAmount),
                MathUtil.interpolate(1, 0.587f, normalizedAmount),
                MathUtil.interpolate(0, 0.587f, normalizedAmount),

                MathUtil.interpolate(0, 0.114f, normalizedAmount),
                MathUtil.interpolate(0, 0.114f, normalizedAmount),
                MathUtil.interpolate(1, 0.114f, normalizedAmount),
        });
        mSIGrey.setColorMatrix(m);
        mSIGrey.forEach(input, output);
    }

    public void destroy() {
        mSIBlur.destroy();
        if (mAllocationSrc != null) {
            mAllocationSrc.destroy();
        }
        if (mAllocationDest != null) {
            mAllocationDest.destroy();
        }
        mRS.destroy();
    }
}
