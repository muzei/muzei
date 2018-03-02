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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/**
 * A custom ScrollView that can accept a scroll listener.
 */
class ObservableHorizontalScrollView(context: Context, attrs: AttributeSet)
    : HorizontalScrollView(context, attrs) {

    var callbacks: Callbacks? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        callbacks?.onScrollChanged(l)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        callbacks?.takeIf { ev.actionMasked == MotionEvent.ACTION_DOWN }?.onDownMotionEvent()
        return super.onTouchEvent(ev)
    }

    /**
     * Overriden to make the method public
     */
    public override fun computeHorizontalScrollRange(): Int {
        return super.computeHorizontalScrollRange()
    }

    interface Callbacks {
        fun onScrollChanged(scrollX: Int)
        fun onDownMotionEvent()
    }
}
