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

package com.google.android.apps.muzei.gallery;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Random;

/**
 * Draws a cool random highlighted cells animation.
 */
public class GalleryEmptyStateGraphicView extends View {
    private static final int[] BITMAP = new int[] {
            0, 0, 1, 1, 1, 1, 0, 0,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 0, 0, 1, 1, 1,
            1, 1, 0, 1, 1, 0, 1, 1,
            1, 1, 0, 1, 1, 0, 1, 1,
            1, 1, 1, 0, 0, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
    };

    private static final int COLS = 8;
    private static final int ROWS = BITMAP.length / COLS;

    private static final int CELL_SPACING_DIP = 2;
    private static final int CELL_ROUNDING_DIP = 1;
    private static final int CELL_SIZE_DIP = 8;

    private static final int ON_TIME_MILLIS = 400;
    private static final int FADE_TIME_MILLIS = 100;
    private static final int OFF_TIME_MILLIS = 50;

    private final Paint mOffPaint = new Paint();
    private final Paint mOnPaint = new Paint();
    private int mWidth, mHeight;
    private long mOnTime;
    private int mOnX;
    private int mOnY;
    private final Random mRandom = new Random();
    private final RectF mTempRectF = new RectF();
    private final int mCellSpacing;
    private final int mCellRounding;
    private final int mCellSize;

    public GalleryEmptyStateGraphicView(Context context) {
        this(context, null, 0);
    }

    public GalleryEmptyStateGraphicView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GalleryEmptyStateGraphicView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final Resources res = getResources();
        mOffPaint.setAntiAlias(true);
        mOffPaint.setColor(ContextCompat.getColor(context, R.color.gallery_empty_state_dark));
        mOnPaint.setAntiAlias(true);
        mOnPaint.setColor(ContextCompat.getColor(context, R.color.gallery_empty_state_light));

        mCellSpacing = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_SPACING_DIP, res.getDisplayMetrics());
        mCellSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_SIZE_DIP, res.getDisplayMetrics());
        mCellRounding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CELL_ROUNDING_DIP, res.getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(
                resolveSize(COLS * mCellSize + (COLS - 1) * mCellSpacing, widthMeasureSpec),
                resolveSize(ROWS * mCellSize + (ROWS - 1) * mCellSpacing, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isShown() || mWidth == 0 || mHeight == 0) {
            return;
        }

        // tick timer
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed > mOnTime + ON_TIME_MILLIS + FADE_TIME_MILLIS * 2 + OFF_TIME_MILLIS) {
            mOnTime = nowElapsed;
            while (true) {
                int x = mRandom.nextInt(COLS);
                int y = mRandom.nextInt(ROWS);
                if ((x != mOnX || y != mOnY) && BITMAP[y * COLS + x] == 1) {
                    mOnX = x;
                    mOnY = y;
                    break;
                }
            }
        }

        int t = (int) (nowElapsed - mOnTime);
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                if (BITMAP[y * COLS + x] != 1) {
                    continue;
                }

                mTempRectF.set(
                        x * (mCellSize + mCellSpacing),
                        y * (mCellSize + mCellSpacing),
                        x * (mCellSize + mCellSpacing) + mCellSize,
                        y * (mCellSize + mCellSpacing) + mCellSize);

                canvas.drawRoundRect(mTempRectF,
                        mCellRounding,
                        mCellRounding,
                        mOffPaint);

                if (nowElapsed <= mOnTime + ON_TIME_MILLIS + FADE_TIME_MILLIS * 2
                        && mOnX == x && mOnY == y) {
                    // draw items
                    if (t < FADE_TIME_MILLIS) {
                        mOnPaint.setAlpha(t * 255 / FADE_TIME_MILLIS);
                    } else if (t < FADE_TIME_MILLIS + ON_TIME_MILLIS) {
                        mOnPaint.setAlpha(255);
                    } else {
                        mOnPaint.setAlpha((255 -
                                (t - ON_TIME_MILLIS - FADE_TIME_MILLIS) * 255 / FADE_TIME_MILLIS));
                    }

                    canvas.drawRoundRect(mTempRectF,
                            mCellRounding,
                            mCellRounding,
                            mOnPaint);
                }
            }
        }

        postInvalidateOnAnimation();
    }
}

