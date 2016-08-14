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
import android.graphics.Paint;
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
import android.widget.EdgeEffect;
import android.widget.OverScroller;

/**
 * View which supports panning around an image larger than the screen size. Supports both scrolling
 * and flinging
 */
public class PanView extends View {
    private static final String TAG = "PanView";

    private Bitmap mImage;
    private Bitmap mScaledImage;

    private Bitmap mBlurredImage;
    private float mBlurAmount = 0f;
    private Paint mDrawBlurredPaint;

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
    private OverScroller mScroller;
    /**
     * Handler for posting fling animation updates
     */
    private Handler mHandler = new Handler();

    // Edge effect / overscroll tracking objects.
    private EdgeEffect mEdgeEffectTop;
    private EdgeEffect mEdgeEffectBottom;
    private EdgeEffect mEdgeEffectLeft;
    private EdgeEffect mEdgeEffectRight;

    private boolean mEdgeEffectTopActive;
    private boolean mEdgeEffectBottomActive;
    private boolean mEdgeEffectLeftActive;
    private boolean mEdgeEffectRightActive;

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
        mScroller = new OverScroller(context);
        mEdgeEffectLeft = new EdgeEffect(context);
        mEdgeEffectTop = new EdgeEffect(context);
        mEdgeEffectRight = new EdgeEffect(context);
        mEdgeEffectBottom = new EdgeEffect(context);

        mDrawBlurredPaint = new Paint();
        mDrawBlurredPaint.setDither(true);
    }

    /**
     * Sets an image to be displayed. Preferably this image should be larger than this view's size
     * to allow scrolling. Note that the image will be centered on first display
     * @param image Image to display
     */
    public void setImage(Bitmap image) {
        mImage = image;
        updateScaledImage();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = Math.max(1, w);
        mHeight = Math.max(1, h);
        updateScaledImage();
    }

    private void updateScaledImage() {
        if (mImage == null) {
            return;
        }
        int width = mImage.getWidth();
        int height = mImage.getHeight();
        if (width > height) {
            float scalingFactor = mHeight * 1f / height;
            mScaledImage = Bitmap.createScaledBitmap(mImage, (int)(scalingFactor * width), mHeight, true);
        } else {
            float scalingFactor = mWidth * 1f / width;
            mScaledImage = Bitmap.createScaledBitmap(mImage, mWidth, (int)(scalingFactor * height), true);
        }
        ImageBlurrer blurrer = new ImageBlurrer(getContext());
        mBlurredImage = blurrer.blurBitmap(mScaledImage,
                ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS, 0f);
        blurrer.destroy();
        // Center the image
        mOffsetX = (mWidth - mScaledImage.getWidth()) / 2;
        mOffsetY = (mHeight - mScaledImage.getHeight()) / 2;
        invalidate();
    }

    public void setBlurAmount(float blurAmount) {
        mBlurAmount = blurAmount;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mBlurAmount < 1f) {
            if (mScaledImage != null) {
                canvas.drawBitmap(mScaledImage, mOffsetX, mOffsetY, null);
            }
        }
        if (mBlurAmount > 0f) {
            if (mBlurredImage != null) {
                mDrawBlurredPaint.setAlpha((int) (mBlurAmount * 255));
                canvas.drawBitmap(mBlurredImage, mOffsetX, mOffsetY, mDrawBlurredPaint);
            }
        }
        drawEdgeEffects(canvas);
    }

    /**
     * Draws the overscroll "glow" at the four edges, if necessary
     *
     * @see EdgeEffect
     */
    private void drawEdgeEffects(Canvas canvas) {
        // The methods below rotate and translate the canvas as needed before drawing the glow,
        // since EdgeEffect always draws a top-glow at 0,0.

        boolean needsInvalidate = false;

        if (!mEdgeEffectTop.isFinished()) {
            final int restoreCount = canvas.save();
            mEdgeEffectTop.setSize(mWidth, mHeight);
            if (mEdgeEffectTop.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!mEdgeEffectBottom.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(-mWidth, mHeight);
            canvas.rotate(180, mWidth, 0);
            mEdgeEffectBottom.setSize(mWidth, mHeight);
            if (mEdgeEffectBottom.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!mEdgeEffectLeft.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(0, mHeight);
            canvas.rotate(-90, 0, 0);
            //noinspection SuspiciousNameCombination
            mEdgeEffectLeft.setSize(mHeight, mWidth);
            if (mEdgeEffectLeft.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!mEdgeEffectRight.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(mWidth, 0);
            canvas.rotate(90, 0, 0);
            //noinspection SuspiciousNameCombination
            mEdgeEffectRight.setSize(mHeight, mWidth);
            if (mEdgeEffectRight.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (needsInvalidate) {
            invalidate();
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

    private void setOffset(float offsetX, float offsetY) {
        if (mScaledImage == null) {
            return;
        }

        // Constrain between mWidth - mScaledImage.getWidth() and 0
        // mWidth - mScaledImage.getWidth() -> right edge visible
        // 0 -> left edge visible
        mOffsetX = Math.min(0, Math.max(mWidth - mScaledImage.getWidth(), offsetX));
        // Constrain between mHeight - mScaledImage.getHeight() and 0
        // mHeight - mScaledImage.getHeight() -> bottom edge visible
        // 0 -> top edge visible
        mOffsetY = Math.min(0, Math.max(mHeight - mScaledImage.getHeight(), offsetY));
    }

    /**
     * The gesture listener, used for handling simple gestures such as scrolls and flings.
     */
    private class ScrollFlingGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            releaseEdgeEffects();
            mScroller.forceFinished(true);
            invalidate();
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (mScaledImage == null) {
                return true;
            }

            float offsetX = mOffsetX;
            float offsetY = mOffsetY;
            setOffset(mOffsetX - distanceX, mOffsetY - distanceY);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Scrolling to " + mOffsetX + ", " + mOffsetY);
            }
            if (mWidth != mScaledImage.getWidth() && mOffsetX < offsetX - distanceX) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Left edge pulled " + -distanceX);
                }
                mEdgeEffectLeft.onPull(-distanceX * 1f / mWidth);
                mEdgeEffectLeftActive = true;
            }
            if (mHeight != mScaledImage.getHeight() && mOffsetY < offsetY - distanceY) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Top edge pulled " + distanceY);
                }
                mEdgeEffectTop.onPull(-distanceY * 1f / mHeight);
                mEdgeEffectTopActive = true;
            }
            if (mHeight != mScaledImage.getHeight() && mOffsetY > offsetY - distanceY) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Bottom edge pulled " + -distanceY);
                }
                mEdgeEffectBottom.onPull(distanceY * 1f / mHeight);
                mEdgeEffectBottomActive = true;
            }
            if (mWidth != mScaledImage.getWidth() && mOffsetX > offsetX - distanceX) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Right edge pulled " + distanceX);
                }
                mEdgeEffectRight.onPull(distanceX * 1f / mWidth);
                mEdgeEffectRightActive = true;
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (mScaledImage == null) {
                return true;
            }

            releaseEdgeEffects();
            mScroller.forceFinished(true);
            mScroller.fling(
                    (int) mOffsetX,
                    (int) mOffsetY,
                    (int) velocityX,
                    (int) velocityY,
                    mWidth - mScaledImage.getWidth(), 0, // mWidth - mScaledImage.getWidth() is negative
                    mHeight - mScaledImage.getHeight(), 0, // mHeight - mScaledImage.getHeight() is negative
                    mScaledImage.getWidth() / 2,
                    mScaledImage.getHeight() / 2);
            postAnimateTick();
            invalidate();
            return true;
        }

        private void releaseEdgeEffects() {
            mEdgeEffectLeftActive
                    = mEdgeEffectTopActive
                    = mEdgeEffectRightActive
                    = mEdgeEffectBottomActive
                    = false;
            mEdgeEffectLeft.onRelease();
            mEdgeEffectTop.onRelease();
            mEdgeEffectRight.onRelease();
            mEdgeEffectBottom.onRelease();
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
                setOffset(mScroller.getCurrX(), mScroller.getCurrY());

                if (mWidth != mScaledImage.getWidth() && mOffsetX < mScroller.getCurrX()
                        && mEdgeEffectLeft.isFinished()
                        && !mEdgeEffectLeftActive) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Left edge absorbing " + mScroller.getCurrVelocity());
                    }
                    mEdgeEffectLeft.onAbsorb((int) mScroller.getCurrVelocity());
                    mEdgeEffectLeftActive = true;
                } else if (mWidth != mScaledImage.getWidth() && mOffsetX > mScroller.getCurrX()
                        && mEdgeEffectRight.isFinished()
                        && !mEdgeEffectRightActive) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Right edge absorbing " + mScroller.getCurrVelocity());
                    }
                    mEdgeEffectRight.onAbsorb((int) mScroller.getCurrVelocity());
                    mEdgeEffectRightActive = true;
                }

                if (mHeight != mScaledImage.getHeight() && mOffsetY < mScroller.getCurrY()
                        && mEdgeEffectTop.isFinished()
                        && !mEdgeEffectTopActive) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Top edge absorbing " + mScroller.getCurrVelocity());
                    }
                    mEdgeEffectTop.onAbsorb((int) mScroller.getCurrVelocity());
                    mEdgeEffectTopActive = true;
                } else if (mHeight != mScaledImage.getHeight() && mOffsetY > mScroller.getCurrY()
                        && mEdgeEffectBottom.isFinished()
                        && !mEdgeEffectBottomActive) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bottom edge absorbing " + mScroller.getCurrVelocity());
                    }
                    mEdgeEffectBottom.onAbsorb((int) mScroller.getCurrVelocity());
                    mEdgeEffectBottomActive = true;
                }

                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Flinging to " + mOffsetX + ", " + mOffsetY);
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
