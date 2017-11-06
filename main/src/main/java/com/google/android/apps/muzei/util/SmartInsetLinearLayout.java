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

package com.google.android.apps.muzei.util;

import android.content.Context;
import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A vertical {@link LinearLayout} that transforms fitsSystemWindows insets into vertical padding
 * for only the top and bottom child.
 */
public class SmartInsetLinearLayout extends LinearLayout {
    public SmartInsetLinearLayout(final Context context) {
        super(context);
        init();
    }

    public SmartInsetLinearLayout(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SmartInsetLinearLayout(final Context context, @Nullable final AttributeSet attrs,
            final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(LinearLayout.VERTICAL);
    }

    @Override
    protected boolean fitSystemWindows(final Rect insets) {
        Rect horizontalInsets = new Rect(insets.left, 0, insets.right, 0);
        super.fitSystemWindows(horizontalInsets);
        int childCount = getChildCount();
        if (childCount > 0) {
            View firstChild = getChildAt(0);
            firstChild.setPadding(firstChild.getPaddingLeft(),
                    insets.top,
                    firstChild.getPaddingRight(),
                    (childCount == 1 ? insets.bottom : 0));
            if (childCount > 1) {
                View lastChild = getChildAt(childCount - 1);
                lastChild.setPadding(lastChild.getPaddingLeft(),
                        lastChild.getPaddingTop(),
                        lastChild.getPaddingRight(),
                        insets.bottom);
            }

        }
        return true;
    }
}
