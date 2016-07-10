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

package com.google.android.apps.muzei.render;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.google.android.apps.muzei.util.ImageBlurrer;
import com.google.android.apps.muzei.util.MathUtil;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

public class MuzeiRendererFragment extends Fragment implements
        RenderController.Callbacks,
        MuzeiBlurRenderer.Callbacks {

    private static final String ARG_DEMO_MODE = "demo_mode";
    private static final String ARG_DEMO_FOCUS = "demo_focus";

    private MuzeiView mView;
    private ImageView mSimpleDemoModeImageView;
    private boolean mDemoMode;
    private boolean mDemoFocus;

    public static MuzeiRendererFragment createInstance(boolean demoMode, boolean demoFocus) {
        MuzeiRendererFragment fragment = new MuzeiRendererFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_DEMO_MODE, demoMode);
        args.putBoolean(ARG_DEMO_FOCUS, demoFocus);
        fragment.setArguments(args);
        return fragment;
    }

    public MuzeiRendererFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mDemoMode = args.getBoolean(ARG_DEMO_MODE, false);
        mDemoFocus = args.getBoolean(ARG_DEMO_FOCUS, false);
    }

    @Override
    @TargetApi(Build.VERSION_CODES.KITKAT)
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        boolean simpleDemoMode = false;
        if (mDemoMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ActivityManager activityManager = (ActivityManager)
                    getActivity().getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager.isLowRamDevice()) {
                simpleDemoMode = true;
            }
        }

        if (simpleDemoMode) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int targetWidth = dm.widthPixels;
            int targetHeight = dm.heightPixels;
            if (!mDemoFocus) {
                targetHeight = MathUtil.roundMult4(ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS
                        * 10000 / MuzeiBlurRenderer.DEFAULT_BLUR);
                targetWidth = MathUtil.roundMult4(
                        (int) (dm.widthPixels * 1f / dm.heightPixels * targetHeight));
            }

            mSimpleDemoModeImageView = new ImageView(container.getContext());
            mSimpleDemoModeImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Picasso.with(getActivity())
                    .load("file:///android_asset/starrynight.jpg")
                    .resize(targetWidth, targetHeight)
                    .centerCrop()
                    .into(mSimpleDemoModeLoadedTarget);
            return mSimpleDemoModeImageView;
        } else {
            mView = new MuzeiView(getActivity());
            mView.setPreserveEGLContextOnPause(true);
            return mView;
        }
    }

    private Target mSimpleDemoModeLoadedTarget = new Target() {
        @Override
        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom loadedFrom) {
            if (!mDemoFocus) {
                // Blur
                ImageBlurrer blurrer = new ImageBlurrer(getActivity());
                Bitmap blurred = blurrer.blurBitmap(bitmap,
                        ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS, 0);

                // Dim
                Canvas c = new Canvas(blurred);
                c.drawColor(Color.argb(255 - MuzeiBlurRenderer.DEFAULT_MAX_DIM,
                        0, 0, 0));

                bitmap = blurred;
            }

            mSimpleDemoModeImageView.setImageBitmap(bitmap);
        }

        @Override
        public void onBitmapFailed(Drawable drawable) {
        }

        @Override
        public void onPrepareLoad(Drawable drawable) {
        }
    };

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (mView == null) {
            return;
        }
        mView.mRenderController.setVisible(!hidden);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mView = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mView == null) {
            return;
        }

        mView.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mView == null) {
            return;
        }

        mView.onResume();
    }

    @Override
    public void queueEventOnGlThread(Runnable runnable) {
        if (mView == null) {
            return;
        }

        mView.queueEvent(runnable);
    }

    @Override
    public void requestRender() {
        if (mView == null) {
            return;
        }

        mView.requestRender();
    }

    private class MuzeiView extends GLTextureView {
        private MuzeiBlurRenderer mRenderer;
        private RenderController mRenderController;

        public MuzeiView(Context context) {
            super(context);
            mRenderer = new MuzeiBlurRenderer(getContext(), MuzeiRendererFragment.this);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 8, 0, 0);
            setRenderer(mRenderer);
            setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            if (mDemoMode) {
                mRenderController = new DemoRenderController(getContext(), mRenderer,
                        MuzeiRendererFragment.this, mDemoFocus);
            } else {
                mRenderController = new RealRenderController(getContext(), mRenderer,
                        MuzeiRendererFragment.this);
            }
            mRenderer.setDemoMode(mDemoMode);
            mRenderController.setVisible(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mRenderer.hintViewportSize(w, h);
            mRenderController.reloadCurrentArtwork(true);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mRenderController.destroy();
            queueEventOnGlThread(new Runnable() {
                @Override
                public void run() {
                    mRenderer.destroy();
                }
            });
        }
    }
}
