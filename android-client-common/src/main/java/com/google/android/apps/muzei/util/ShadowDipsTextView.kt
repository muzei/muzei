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
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.content.res.use
import net.nurik.roman.muzei.androidclientcommon.R

class ShadowDipsTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : TextView(context, attrs, defStyle) {

    init {
        context.obtainStyledAttributes(attrs,
                R.styleable.ShadowDipsTextView, defStyle, 0).use {
            val shadowDx = it.getDimensionPixelSize(R.styleable.ShadowDipsTextView_shadowDx, 0)
            val shadowDy = it.getDimensionPixelSize(R.styleable.ShadowDipsTextView_shadowDy, 0)
            val shadowRadius = it.getDimensionPixelSize(R.styleable.ShadowDipsTextView_shadowRadius, 0)
            val shadowColor = it.getColor(R.styleable.ShadowDipsTextView_shadowColor, 0)
            if (shadowColor != 0) {
                setShadowLayer(shadowRadius.toFloat(), shadowDx.toFloat(), shadowDy.toFloat(), shadowColor)
            }
        }
    }
}
