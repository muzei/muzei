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
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix3f;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicColorMatrix;

public class ImageBlurrer {
    public static final int MAX_SUPPORTED_BLUR_PIXELS = 25;

    private final RenderScript mRS;
    private final ScriptIntrinsicBlur mSIBlur;
    private final ScriptIntrinsicColorMatrix mSIGrey;
    private final Bitmap mSourceBitmap;
    private final Allocation mAllocationSrc;

    public ImageBlurrer(Context context, Bitmap src) {
        mRS = RenderScript.create(context);
        mSIBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS));
        mSIGrey = ScriptIntrinsicColorMatrix.create(mRS);

        mSourceBitmap = src;
        mAllocationSrc = src != null ? Allocation.createFromBitmap(mRS, src) : null;
    }

    public Bitmap blurBitmap(float radius, float desaturateAmount) {
        if (mSourceBitmap == null) {
            return null;
        }

        Bitmap dest = mSourceBitmap.copy(mSourceBitmap.getConfig(), true);
        if (radius == 0f && desaturateAmount == 0f) {
            return dest;
        }

        Allocation allocationDest = Allocation.createFromBitmap(mRS, dest);

        if (radius > 0f && desaturateAmount > 0f) {
            doBlur(radius, mAllocationSrc, allocationDest);
            doDesaturate(MathUtil.constrain(0, 1, desaturateAmount), allocationDest, mAllocationSrc);
            mAllocationSrc.copyTo(dest);
        } else if (radius > 0f) {
            doBlur(radius, mAllocationSrc, allocationDest);
            allocationDest.copyTo(dest);
        } else {
            doDesaturate(MathUtil.constrain(0, 1, desaturateAmount), mAllocationSrc, allocationDest);
            allocationDest.copyTo(dest);
        }
        allocationDest.destroy();
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
        mSIGrey.destroy();
        if (mAllocationSrc != null) {
            mAllocationSrc.destroy();
        }
        mRS.destroy();
    }
}
