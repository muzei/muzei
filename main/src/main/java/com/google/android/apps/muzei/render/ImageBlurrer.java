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
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;

public class ImageBlurrer {
    public static final int MAX_SUPPORTED_BLUR_PIXELS = 25;

    private RenderScript mRS;
    private ScriptIntrinsicBlur mSIBlur;
    private Allocation mTmpIn;
    private Allocation mTmpOut;

    public ImageBlurrer(Context context) {
        mRS = RenderScript.create(context);
        mSIBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS));
    }

    public Bitmap blurBitmap(Bitmap src, float radius) {
        Bitmap dest = Bitmap.createBitmap(src);
        if ((int) radius == 0) {
            return dest;
        }

        if (mTmpIn != null) {
            mTmpIn.destroy();
        }
        if (mTmpOut != null) {
            mTmpOut.destroy();
        }

        mTmpIn = Allocation.createFromBitmap(mRS, src);
        mTmpOut = Allocation.createFromBitmap(mRS, dest);

        mSIBlur.setRadius((int) radius);
        mSIBlur.setInput(mTmpIn);
        mSIBlur.forEach(mTmpOut);
        mTmpOut.copyTo(dest);
        return dest;
    }

    public void destroy() {
        mSIBlur.destroy();
        if (mTmpIn != null) {
            mTmpIn.destroy();
        }
        if (mTmpOut != null) {
            mTmpOut.destroy();
        }
        mRS.destroy();
    }
}
