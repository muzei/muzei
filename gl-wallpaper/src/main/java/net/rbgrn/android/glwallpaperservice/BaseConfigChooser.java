/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.rbgrn.android.glwallpaperservice;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Created by romannurik on 11/6/13.
 */
abstract class BaseConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private int eglContextClientVersion;

        public BaseConfigChooser(int[] configSpec, int eglContextClientVersion) {
                this.eglContextClientVersion = eglContextClientVersion;
        mConfigSpec = filterConfigSpec(configSpec);
    }

    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        int[] num_config = new int[1];
        if (!egl.eglChooseConfig(display, mConfigSpec, null, 0,
                num_config)) {
            throw new IllegalArgumentException("eglChooseConfig failed");
        }

        int numConfigs = num_config[0];

        if (numConfigs <= 0) {
            throw new IllegalArgumentException(
                    "No configs match configSpec");
        }

        EGLConfig[] configs = new EGLConfig[numConfigs];
        if (!egl.eglChooseConfig(display, mConfigSpec, configs, numConfigs,
                num_config)) {
            throw new IllegalArgumentException("eglChooseConfig#2 failed");
        }
        EGLConfig config = chooseConfig(egl, display, configs);
        if (config == null) {
            throw new IllegalArgumentException("No config chosen");
        }
        return config;
    }

    abstract EGLConfig chooseConfig(EGL10 egl, EGLDisplay display,
            EGLConfig[] configs);

    protected int[] mConfigSpec;

    private int[] filterConfigSpec(int[] configSpec) {
        if (eglContextClientVersion != 2) {
            return configSpec;
        }
        /* We know none of the subclasses define EGL_RENDERABLE_TYPE.
         * And we know the configSpec is well formed.
         */
        int len = configSpec.length;
        int[] newConfigSpec = new int[len + 2];
        System.arraycopy(configSpec, 0, newConfigSpec, 0, len-1);
        newConfigSpec[len-1] = EGL10.EGL_RENDERABLE_TYPE;
        newConfigSpec[len] = 4; /* EGL_OPENGL_ES2_BIT */
        newConfigSpec[len+1] = EGL10.EGL_NONE;
        return newConfigSpec;
    }

        public static class ComponentSizeChooser extends BaseConfigChooser {
                public ComponentSizeChooser(int redSize, int greenSize, int blueSize, int alphaSize, int depthSize,
                                int stencilSize, int eglContextClientVersion) {
                        super(new int[] { EGL10.EGL_RED_SIZE, redSize, EGL10.EGL_GREEN_SIZE, greenSize, EGL10.EGL_BLUE_SIZE,
                                        blueSize, EGL10.EGL_ALPHA_SIZE, alphaSize, EGL10.EGL_DEPTH_SIZE, depthSize, EGL10.EGL_STENCIL_SIZE,
                                        stencilSize, EGL10.EGL_NONE }, eglContextClientVersion);
                        mValue = new int[1];
                        mRedSize = redSize;
                        mGreenSize = greenSize;
                        mBlueSize = blueSize;
                        mAlphaSize = alphaSize;
                        mDepthSize = depthSize;
                        mStencilSize = stencilSize;
                }

                @Override
                public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs) {
                        EGLConfig closestConfig = null;
                        int closestDistance = 1000;
                        for (EGLConfig config : configs) {
                                int d = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE);
                                int s = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE);
                                if (d >= mDepthSize && s >= mStencilSize) {
                                        int r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE);
                                        int g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE);
                                        int b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE);
                                        int a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE);
                                        int distance = Math.abs(r - mRedSize) + Math.abs(g - mGreenSize) + Math.abs(b - mBlueSize)
                                        + Math.abs(a - mAlphaSize);
                                        if (distance < closestDistance) {
                                                closestDistance = distance;
                                                closestConfig = config;
                                        }
                                }
                        }
                        return closestConfig;
                }

                private int findConfigAttrib(EGL10 egl, EGLDisplay display, EGLConfig config, int attribute) {

                        if (egl.eglGetConfigAttrib(display, config, attribute, mValue)) {
                                return mValue[0];
                        }
                        return 0;
                }

                private int[] mValue;
                // Subclasses can adjust these values:
                protected int mRedSize;
                protected int mGreenSize;
                protected int mBlueSize;
                protected int mAlphaSize;
                protected int mDepthSize;
                protected int mStencilSize;
        }

        /**
         * This class will choose a supported surface as close to RGB565 as possible, with or without a depth buffer.
         *
         */
        public static class SimpleEGLConfigChooser extends ComponentSizeChooser {
                public SimpleEGLConfigChooser(boolean withDepthBuffer, int eglContextClientVersion) {
                        super(4, 4, 4, 0, withDepthBuffer ? 16 : 0, 0, eglContextClientVersion);
                        // Adjust target values. This way we'll accept a 4444 or
                        // 555 buffer if there's no 565 buffer available.
                        mRedSize = 5;
                        mGreenSize = 6;
                        mBlueSize = 5;
                }
        }
}
