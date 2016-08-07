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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import java.text.ParseException;

public class AnimatedMuzeiLoadingSpinnerView extends View {
    private static final String TAG = "AnimatedLoadingSpinner";

    private static final int TRACE_TIME = 1000;
    private static final int MARKER_LENGTH_DIP = 16;
    private static final int TRACE_RESIDUE_COLOR = Color.argb(50, 255, 255, 255);
    private static final int TRACE_COLOR = Color.WHITE;
    private static final RectF VIEWPORT = new RectF(0, 88, 318, 300);

    private static final Interpolator INTERPOLATOR = new LinearInterpolator();

    private GlyphData mGlyphData;
    private float mMarkerLength;
    private int mWidth;
    private int mHeight;
    private long mStartTime = -1;

    public AnimatedMuzeiLoadingSpinnerView(Context context) {
        super(context);
        init();
    }

    public AnimatedMuzeiLoadingSpinnerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedMuzeiLoadingSpinnerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mMarkerLength = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                MARKER_LENGTH_DIP, getResources().getDisplayMetrics());

        // See https://github.com/romainguy/road-trip/blob/master/application/src/main/java/org/curiouscreature/android/roadtrip/IntroView.java
        // Note: using a software layer here is an optimization. This view works with
        // hardware accelerated rendering but every time a path is modified (when the
        // dash path effect is modified), the graphics pipeline will rasterize the path
        // again in a new texture. Since we are dealing with dozens of paths, it is much
        // more efficient to rasterize the entire view into a single re-usable texture
        // instead. Ideally this should be toggled using a heuristic based on the number
        // and or dimensions of paths to render.
        // Note that PathDashPathEffects can lead to clipping issues with hardware rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void start() {
        mStartTime = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    public void stop() {
        mStartTime = -1;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
        rebuildGlyphData();
    }

    private void rebuildGlyphData() {
        SvgPathParser parser = new SvgPathParser() {
            @Override
            protected float transformX(float x) {
                return x * mWidth / VIEWPORT.width();
            }

            @Override
            protected float transformY(float y) {
                return y * mHeight / VIEWPORT.height();
            }
        };

        mGlyphData = new GlyphData();
        try {
            mGlyphData.path = parser.parsePath(LogoPaths.GLYPHS[0]);
        } catch (ParseException e) {
            mGlyphData.path = new Path();
            Log.e(TAG, "Couldn't parse path", e);
        }
        PathMeasure pm = new PathMeasure(mGlyphData.path, true);
        while (true) {
            mGlyphData.length = Math.max(mGlyphData.length, pm.getLength());
            if (!pm.nextContour()) {
                break;
            }
        }
        mGlyphData.paint = new Paint();
        mGlyphData.paint.setStyle(Paint.Style.STROKE);
        mGlyphData.paint.setAntiAlias(true);
        mGlyphData.paint.setColor(Color.WHITE);
        mGlyphData.paint.setStrokeWidth(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                        getResources().getDisplayMetrics()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mStartTime < 0 || mGlyphData == null) {
            return;
        }

        long t = (System.currentTimeMillis() - mStartTime) % (TRACE_TIME * 2);

        int sc = canvas.save();
        canvas.translate(
                -VIEWPORT.left * mWidth / VIEWPORT.width(),
                -VIEWPORT.top * mHeight / VIEWPORT.height());

        // Draw outlines (starts as traced)
        float phase = MathUtil.constrain(0, 1, (t % TRACE_TIME) * 1f / TRACE_TIME);
        float distance = INTERPOLATOR.getInterpolation(phase) * mGlyphData.length;

        mGlyphData.paint.setColor(TRACE_RESIDUE_COLOR);
        if (t < TRACE_TIME) {
            mGlyphData.paint.setPathEffect(new DashPathEffect(
                    new float[]{distance, mGlyphData.length}, 0));
        } else {
            mGlyphData.paint.setPathEffect(new DashPathEffect(
                    new float[]{0, distance, mGlyphData.length, 0}, 0));
        }
        canvas.drawPath(mGlyphData.path, mGlyphData.paint);

        mGlyphData.paint.setColor(TRACE_COLOR);
        mGlyphData.paint.setPathEffect(new DashPathEffect(
                new float[]{0, distance, phase > 0 ? mMarkerLength : 0,
                        mGlyphData.length}, 0));
        canvas.drawPath(mGlyphData.path, mGlyphData.paint);
        canvas.restoreToCount(sc);

        postInvalidateOnAnimation();
    }

    private static class GlyphData {
        Path path;
        Paint paint;
        float length;
    }
}
