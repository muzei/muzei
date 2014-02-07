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
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.Checkable;
import android.widget.ImageView;

import net.nurik.roman.muzei.R;

/**
 * A {@link Checkable} {@link ImageView}. When this is the root view for an item in a
 * {@link android.widget.ListView} and the list view has a choice mode of
 * {@link android.widget.ListView#CHOICE_MODE_MULTIPLE_MODAL }, the list view will
 * set the item's state to CHECKED and not ACTIVATED. This is important to ensure that we can
 * use the ACTIVATED state on tablet devices to represent the currently-viewed detail screen, while
 * allowing multiple-selection.
 */
public class CheckableImageView extends ImageView implements Checkable {
    private boolean mChecked;

    private Drawable mCheckedOverlayDrawable;

    private static final int[] CheckedStateSet = {
            android.R.attr.state_checked
    };

    public CheckableImageView(Context context) {
        super(context);
        init(context, null, 0);
    }

    public CheckableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public CheckableImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs, defStyle);
    }

    private void init(Context context, AttributeSet attrs, int defStyle) {
        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CheckableImageView, defStyle, 0);
        mCheckedOverlayDrawable =
                a.getDrawable(R.styleable.CheckableImageView_checkedOverlayDrawable);
        if (mCheckedOverlayDrawable != null) {
            mCheckedOverlayDrawable.setCallback(this);
        }

        a.recycle();
    }

    @Override
    public boolean isChecked() {
        return mChecked;
    }

    @Override
    public void setChecked(boolean checked) {
        mChecked = checked;
        refreshDrawableState();
        postInvalidateOnAnimation();
    }

    @Override
    public void toggle() {
        setChecked(!mChecked);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mChecked && mCheckedOverlayDrawable != null) {
            mCheckedOverlayDrawable.setBounds(0, 0, getWidth(), getHeight());
            mCheckedOverlayDrawable.draw(canvas);
        }
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        if (isChecked()) {
            mergeDrawableStates(drawableState, CheckedStateSet);
        }
        return drawableState;
    }
}
