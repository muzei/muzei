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
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import net.nurik.roman.muzei.androidclientcommon.R;

public class ShadowDipsTextView extends TextView {
    public ShadowDipsTextView(Context context) {
        super(context);
        init(context, null, 0);
    }

    public ShadowDipsTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public ShadowDipsTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs, defStyle);
    }

    private void init(Context context, AttributeSet attrs, int defStyle) {
        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.ShadowDipsTextView, defStyle, 0);
        int shadowDx = a.getDimensionPixelSize(R.styleable.ShadowDipsTextView_shadowDx, 0);
        int shadowDy = a.getDimensionPixelSize(R.styleable.ShadowDipsTextView_shadowDy, 0);
        int shadowRadius = a.getDimensionPixelSize(R.styleable.ShadowDipsTextView_shadowRadius, 0);
        int shadowColor = a.getColor(R.styleable.ShadowDipsTextView_shadowColor, 0);
        if (shadowColor != 0) {
            setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);
        }
        a.recycle();
    }
}

