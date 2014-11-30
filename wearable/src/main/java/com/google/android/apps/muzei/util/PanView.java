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
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Scroller;

import net.nurik.roman.muzei.BuildConfig;

/**
 * View which supports panning around an image larger than the screen size. Supports both scrolling
 * and flinging
 */
public class PanView extends View {
    private static final String TAG = PanView.class.getSimpleName();

    private Bitmap mImage;
    /**
     * Horizontal offset for painting the image. As this is used in a canvas.drawBitmap it ranges
     * from a negative value mWidth-image.getWidth() (remember the view is smaller than the image)
     * to zero. If it is zero that means the offsetX side of the image is visible otherwise it is
     * off screen and we are farther to the right.
     */
    private float mOffsetX;
    /**
     * Vertical offset for painting the image. As this is used in a canvas.drawBitmap it ranges
     * from a negative value mHeight-image.getHeight() (remember the view is smaller than the image)
     * to zero. If it is zero that means the offsetY side of the image is visible otherwise it is
     * off screen and we are farther down.
     */
    private float mOffsetY;
    /**
     * View width
     */
    private int mWidth = 1;
    /**
     * View height
     */
    private int mHeight = 1;

    // State objects and values related to gesture tracking.
    private GestureDetector mGestureDetector;
    private Scroller mScroller;
    /**
     * Handler for posting fling animation updates
     */
    private Handler mHandler = new Handler();

    public PanView(Context context) {
        this(context, null, 0);
    }

    public PanView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PanView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // Sets up interactions
        mGestureDetector = new GestureDetector(context, new ScrollFlingGestureListener());
        mScroller = new Scroller(context);
    }

    /**
     * Sets an image to be displayed. Preferably this image should be larger than this view's size
     * to allow scrolling. Note that the image will be centered on first display
     * @param image Image to display
     */
    public void setImage(Bitmap image) {
        mImage = image;
        if (mImage == null) {
            return;
        }
        // Center the image
        mOffsetX = (mWidth - mImage.getWidth()) / 2;
        mOffsetY = (mHeight - mImage.getHeight()) / 2;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = Math.max(1, w);
        mHeight = Math.max(1, h);
        if (mImage != null) {
            // Center the image
            mOffsetX = (mWidth - mImage.getWidth()) / 2;
            mOffsetY = (mHeight - mImage.getHeight()) / 2;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mImage != null) {
            canvas.drawBitmap(mImage, mOffsetX, mOffsetY, null);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and objects related to gesture handling
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        return mGestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    /**
     * The gesture listener, used for handling simple gestures such as scrolls and flings.
     */
    private class ScrollFlingGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            mScroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // Constrain between mWidth - mImage.getWidth() and 0
            // mWidth - mImage.getWidth() -> right edge visible
            // 0 -> left edge visible
            mOffsetX = Math.min(0, Math.max(mWidth - mImage.getWidth(), mOffsetX - distanceX));
            // Constrain between mHeight - mImage.getHeight() and 0
            // mHeight - mImage.getHeight() -> bottom edge visible
            // 0 -> top edge visible
            mOffsetY = Math.min(0, Math.max(mHeight - mImage.getHeight(), mOffsetY - distanceY));
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Scrolling to " + mOffsetX + ", " + mOffsetY);
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            mScroller.forceFinished(true);
            mScroller.fling(
                    (int) mOffsetX,
                    (int) mOffsetY,
                    (int) velocityX,
                    (int) velocityY,
                    mWidth - mImage.getWidth(), 0, // mWidth - mImage.getWidth() is negative
                    mHeight - mImage.getHeight(), 0); // mHeight - mImage.getHeight() is negative
            postAnimateTick();
            invalidate();
            return true;
        }
    }

    private void postAnimateTick() {
        mHandler.removeCallbacks(mAnimateTickRunnable);
        mHandler.post(mAnimateTickRunnable);
    }

    private Runnable mAnimateTickRunnable = new Runnable() {
        @Override
        public void run() {
            boolean needsInvalidate = false;

            if (mScroller.computeScrollOffset()) {
                // The scroller isn't finished, meaning a fling is currently active.
                mOffsetX = mScroller.getCurrX();
                mOffsetY = mScroller.getCurrY();

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Flinging to " + mOffsetX + ", " + mOffsetY);
                }
                needsInvalidate = true;
            }

            if (needsInvalidate) {
                invalidate();
                postAnimateTick();
            }
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and classes related to view state persistence.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.offsetX = mOffsetX;
        ss.offsetY = mOffsetY;
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        mOffsetX = ss.offsetX;
        mOffsetY = ss.offsetY;
    }

    /**
     * Persistent state that is saved by PanView.
     */
    public static class SavedState extends BaseSavedState {
        private float offsetX;
        private float offsetY;

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeFloat(offsetX);
            out.writeFloat(offsetY);
        }

        @Override
        public String toString() {
            return "PanView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " offset=" + offsetX + ", " + offsetY + "}";
        }

        public static final Creator<SavedState> CREATOR
                = ParcelableCompat.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        });

        SavedState(Parcel in) {
            super(in);
            offsetX = in.readFloat();
            offsetY = in.readFloat();
        }
    }
}
