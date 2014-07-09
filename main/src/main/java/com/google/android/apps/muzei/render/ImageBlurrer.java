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

package com.google.android.apps.muzei.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix3f;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicColorMatrix;

import com.google.android.apps.muzei.util.MathUtil;

public class ImageBlurrer {
    public static final int MAX_SUPPORTED_BLUR_PIXELS = 25;
    private RenderScript mRS;

    private ScriptIntrinsicBlur mSIBlur;
    private ScriptIntrinsicColorMatrix mSIGrey;
    private Allocation mTmp1;
    private Allocation mTmp2;

    public ImageBlurrer(Context context) {
        mRS = RenderScript.create(context);
        mSIBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS));
        mSIGrey = ScriptIntrinsicColorMatrix.create(mRS);
    }

    public Bitmap blurBitmap(Bitmap src, float radius, float desaturateAmount) {
        Bitmap dest = Bitmap.createBitmap(src);
        if ((int) radius == 0) {
            return dest;
        }

        if (mTmp1 != null) {
            mTmp1.destroy();
        }
        if (mTmp2 != null) {
            mTmp2.destroy();
        }

        mTmp1 = Allocation.createFromBitmap(mRS, src);
        mTmp2 = Allocation.createFromBitmap(mRS, dest);

        mSIBlur.setRadius((int) radius);
        mSIBlur.setInput(mTmp1);
        mSIBlur.forEach(mTmp2);

        if (desaturateAmount > 0) {
            desaturateAmount = MathUtil.constrain(0, 1, desaturateAmount);
            Matrix3f m = new Matrix3f(new float[]{
                    MathUtil.interpolate(1, 0.299f, desaturateAmount),
                    MathUtil.interpolate(0, 0.299f, desaturateAmount),
                    MathUtil.interpolate(0, 0.299f, desaturateAmount),

                    MathUtil.interpolate(0, 0.587f, desaturateAmount),
                    MathUtil.interpolate(1, 0.587f, desaturateAmount),
                    MathUtil.interpolate(0, 0.587f, desaturateAmount),

                    MathUtil.interpolate(0, 0.114f, desaturateAmount),
                    MathUtil.interpolate(0, 0.114f, desaturateAmount),
                    MathUtil.interpolate(1, 0.114f, desaturateAmount),
            });
            mSIGrey.setColorMatrix(m);
            mSIGrey.forEach(mTmp2, mTmp1);
            mTmp1.copyTo(dest);
        } else {
            mTmp2.copyTo(dest);
        }
        return dest;
    }

    public void destroy() {
        mSIBlur.destroy();
        if (mTmp1 != null) {
            mTmp1.destroy();
        }
        if (mTmp2 != null) {
            mTmp2.destroy();
        }
        mRS.destroy();
    }
}
