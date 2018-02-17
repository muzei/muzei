/*
 * Copyright 2017 Google Inc.
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
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * A vertical [LinearLayout] that transforms fitsSystemWindows insets into vertical padding
 * for only the top and bottom child.
 */
class SmartInsetLinearLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = LinearLayout.VERTICAL
    }

    @Suppress("OverridingDeprecatedMember")
    override fun fitSystemWindows(insets: Rect): Boolean {
        val horizontalInsets = Rect(insets.left, 0, insets.right, 0)
        @Suppress("DEPRECATION")
        super.fitSystemWindows(horizontalInsets)
        val childCount = childCount
        if (childCount > 0) {
            val firstChild = getChildAt(0)
            firstChild.setPadding(firstChild.paddingLeft,
                    insets.top,
                    firstChild.paddingRight,
                    if (childCount == 1) insets.bottom else 0)
            if (childCount > 1) {
                val lastChild = getChildAt(childCount - 1)
                lastChild.setPadding(lastChild.paddingLeft,
                        lastChild.paddingTop,
                        lastChild.paddingRight,
                        insets.bottom)
            }
        }
        return true
    }
}
