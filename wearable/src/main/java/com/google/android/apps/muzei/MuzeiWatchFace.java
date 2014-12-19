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

package com.google.android.apps.muzei;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.R;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Default watch face for Muzei, showing the current time atop the current artwork. In ambient
 * mode, the artwork is invisible. On devices with low-bit ambient mode, the text is drawn without
 * anti-aliasing in ambient mode. On devices which require burn-in protection, the hours are drawn
 * with a thinner font.
 */
public class MuzeiWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "MuzeiWatchFace";

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode.
     */
    private static final long NORMAL_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    private static final String FORMAT_12_HR = "%l:%M";
    private static final String FORMAT_24_HR = "%H:%M";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private class LoadImageContentObserver extends ContentObserver {
            LoadImageContentObserver(Handler handler) {
                super(handler);
            }

            @Override
            public void onChange(boolean selfChange) {
                Bitmap bitmap;
                try {
                    bitmap = MuzeiContract.Artwork.getCurrentArtworkBitmap(MuzeiWatchFace.this);
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Could not find current artwork image", e);
                    bitmap = null;
                }
                if (bitmap == null) {
                    try {
                        bitmap = BitmapFactory.decodeStream(getAssets().open("starrynight.jpg"));
                        // Try to download the artwork from the DataLayer, showing a notification
                        // to activate Muzei if it isn't found
                        Intent intent = new Intent(MuzeiWatchFace.this, ArtworkCacheIntentService.class);
                        intent.putExtra(ArtworkCacheIntentService.SHOW_ACTIVATE_NOTIFICATION_EXTRA, true);
                        startService(intent);
                    } catch (IOException e) {
                        Log.e(TAG, "Error opening starry night asset", e);
                    }
                }
                if (bitmap != null && !bitmap.sameAs(mBackgroundBitmap)) {
                    mBackgroundBitmap = bitmap;
                    createScaledBitmap();
                    postInvalidate();
                }
            }
        }

        static final int MSG_UPDATE_TIME = 0;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        HandlerThread mLoadImageHandlerThread;
        Handler mLoadImageHandler;
        ContentObserver mLoadImageContentObserver;
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        /**
         * How often {@link #mUpdateTimeHandler} ticks in milliseconds.
         */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;
        Typeface mHeavyTypeface;
        Typeface mLightTypeface;
        Paint mClockAmbientShadowPaint;
        Paint mClockPaint;
        float mClockTextHeight;
        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (isVisible()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };
        Bitmap mBackgroundScaledBitmap;
        Bitmap mBackgroundBitmap;
        float mClockMargin;
        boolean mAmbient;
        boolean mMute;
        Time mTime;
        Rect mCardBounds = new Rect();
        int mWidth = 0;
        int mHeight = 0;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mIsRound;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            mMute = getInterruptionFilter() == WatchFaceService.INTERRUPTION_FILTER_NONE;
            updateWatchFaceStyle();

            mTime = new Time();
            mClockMargin = getResources().getDimension(R.dimen.clock_margin);
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.BLACK);
            mHeavyTypeface = Typeface.createFromAsset(getAssets(), "NunitoClock-Bold.ttf");
            mLightTypeface = Typeface.createFromAsset(getAssets(), "NunitoClock-Regular.ttf");

            float densityMultiplier = getResources().getDisplayMetrics().density;

            mClockPaint = new Paint();
            mClockPaint.setColor(Color.WHITE);
            mClockPaint.setShadowLayer(
                    1f * densityMultiplier, 0, 0.5f * densityMultiplier, 0xcc000000);
            mClockPaint.setAntiAlias(true);
            mClockPaint.setTypeface(mHeavyTypeface);
            // Square watch face as defaults, will be changed in onApplyWindowInsets() if needed
            mClockPaint.setTextAlign(Paint.Align.RIGHT);
            mClockPaint.setTextSize(getResources().getDimension(R.dimen.clock_text_size));
            recomputeClockTextHeight();

            mClockAmbientShadowPaint = new Paint(mClockPaint);
            mClockAmbientShadowPaint.setColor(Color.TRANSPARENT);
            mClockAmbientShadowPaint.setShadowLayer(
                    8f * densityMultiplier, 0, 3f * densityMultiplier,
                    0x66000000);
        }

        private void recomputeClockTextHeight() {
            Paint.FontMetrics fm = mClockPaint.getFontMetrics();
            mClockTextHeight = -fm.top;
        }

        private void updateWatchFaceStyle() {
            setWatchFaceStyle(new WatchFaceStyle.Builder(MuzeiWatchFace.this)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT)
                    .setStatusBarGravity(Gravity.TOP | Gravity.START)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.START)
                    .setViewProtection(WatchFaceStyle.PROTECT_HOTWORD_INDICATOR |
                            WatchFaceStyle.PROTECT_STATUS_BAR)
                    .setShowUnreadCountIndicator(!mMute)
                    .build());
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mWidth = width;
            mHeight = height;
            createScaledBitmap();
        }

        private void createScaledBitmap() {
            if (mWidth == 0 || mHeight == 0) {
                // Wait for the surface to be created
                return;
            }
            if (mBackgroundBitmap == null) {
                // Wait for callback with the artwork image
                return;
            }
            if (mBackgroundBitmap.getWidth() > mBackgroundBitmap.getHeight()) {
                float scalingFactor = mHeight * 1f / mBackgroundBitmap.getHeight();
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(
                        mBackgroundBitmap,
                        (int)(scalingFactor * mBackgroundBitmap.getWidth()),
                        mHeight,
                        true /* filter */);
            } else {
                float scalingFactor = mWidth * 1f / mBackgroundBitmap.getWidth();
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(
                        mBackgroundBitmap,
                        mWidth,
                        (int) (scalingFactor * mBackgroundBitmap.getHeight()),
                        true /* filter */);
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            mIsRound = insets.isRound();
            Paint.Align textAlign = mIsRound ? Paint.Align.CENTER : Paint.Align.RIGHT;
            mClockPaint.setTextAlign(textAlign);
            mClockAmbientShadowPaint.setTextAlign(textAlign);
            float textSize = getResources().getDimension(mIsRound
                    ? R.dimen.clock_text_size_round
                    : R.dimen.clock_text_size);
            mClockPaint.setTextSize(textSize);
            mClockAmbientShadowPaint.setTextSize(textSize);
            recomputeClockTextHeight();
            updateWatchFaceStyle();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                mLoadImageHandlerThread = new HandlerThread(MuzeiWatchFace.class.getSimpleName());
                mLoadImageHandlerThread.start();
                mLoadImageHandler = new Handler(mLoadImageHandlerThread.getLooper());
                mLoadImageContentObserver = new LoadImageContentObserver(mLoadImageHandler);
                getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                        true, mLoadImageContentObserver);

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                // Load the image in case it has changed while we weren't visible
                mLoadImageHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mLoadImageContentObserver.onChange(true);
                    }
                });

                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            } else {
                mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);

                unregisterReceiver();

                getContentResolver().unregisterContentObserver(mLoadImageContentObserver);
                mLoadImageHandlerThread.quit();
            }
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MuzeiWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MuzeiWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            Typeface textTypeface = burnInProtection ? mLightTypeface : mHeavyTypeface;
            mClockPaint.setTypeface(textTypeface);
            mClockAmbientShadowPaint.setTypeface(textTypeface);
            recomputeClockTextHeight();

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onPeekCardPositionUpdate(Rect bounds) {
            super.onPeekCardPositionUpdate(bounds);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPeekCardPositionUpdate: " + bounds);
            }
            super.onPeekCardPositionUpdate(bounds);
            if (!bounds.equals(mCardBounds)) {
                mCardBounds.set(bounds);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onPeekCardPositionUpdate: " + mCardBounds);
                }
                invalidate();
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + mAmbient);
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;

                if (mLowBitAmbient) {
                    boolean antiAlias = !inAmbientMode;
                    mClockPaint.setAntiAlias(antiAlias);
                    mClockAmbientShadowPaint.setAntiAlias(antiAlias);
                }
                invalidate();
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                updateWatchFaceStyle();
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;
            if (isVisible()) {
                mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            int width = canvas.getWidth();
            int height = canvas.getHeight();

            // Draw the background
            if (mAmbient || mBackgroundBitmap == null) {
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);
            } else {
                // Draw the scaled background
                canvas.drawBitmap(mBackgroundScaledBitmap,
                        (width - mBackgroundScaledBitmap.getWidth()) / 2,
                        (height - mBackgroundScaledBitmap.getHeight()) / 2, null);
            }

            // Draw the text
            String formattedTime = mTime.format(DateFormat.is24HourFormat(MuzeiWatchFace.this)
                    ? FORMAT_24_HR
                    : FORMAT_12_HR);
            float xOffset = mIsRound
                    ? width / 2
                    : width - mClockMargin;
            float yOffset = mIsRound
                    ? Math.min((height + mClockTextHeight) / 2,
                    (mCardBounds.top == 0 ? height : mCardBounds.top) - mClockMargin)
                    : mClockTextHeight + mClockMargin;
            canvas.drawText(formattedTime,
                    xOffset,
                    yOffset,
                    mClockAmbientShadowPaint);
            canvas.drawText(formattedTime,
                    xOffset,
                    yOffset,
                    mClockPaint);
        }


    }
}
