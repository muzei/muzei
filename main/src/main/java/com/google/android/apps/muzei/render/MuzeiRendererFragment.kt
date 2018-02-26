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

package com.google.android.apps.muzei.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

import com.google.android.apps.muzei.util.ImageBlurrer
import com.google.android.apps.muzei.util.MathUtil
import com.google.android.apps.muzei.util.blur
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target

class MuzeiRendererFragment : Fragment(), RenderController.Callbacks, MuzeiBlurRenderer.Callbacks {

    companion object {

        private const val ARG_DEMO_MODE = "demo_mode"
        private const val ARG_DEMO_FOCUS = "demo_focus"

        fun createInstance(demoMode: Boolean, demoFocus: Boolean = false): MuzeiRendererFragment {
            return MuzeiRendererFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_DEMO_MODE, demoMode)
                    putBoolean(ARG_DEMO_FOCUS, demoFocus)
                }
            }
        }
    }

    private var muzeiView: MuzeiView? = null
    private lateinit var simpleDemoModeImageView: ImageView
    private var demoMode: Boolean = false
    private var demoFocus: Boolean = false

    private val mSimpleDemoModeLoadedTarget = object : Target {
        override fun onBitmapLoaded(bitmap: Bitmap?, loadedFrom: Picasso.LoadedFrom) {
            simpleDemoModeImageView.setImageBitmap(if (!demoFocus) {
                // Blur
                val blurrer = ImageBlurrer(context!!, bitmap)
                val blurred = blurrer.blurBitmap()
                blurrer.destroy()

                // Dim
                val c = Canvas(blurred!!)
                c.drawColor(Color.argb(255 - MuzeiBlurRenderer.DEFAULT_MAX_DIM,
                        0, 0, 0))
                blurred
            } else {
                bitmap
            })
        }

        override fun onBitmapFailed(drawable: Drawable) {}

        override fun onPrepareLoad(drawable: Drawable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        demoMode = arguments?.getBoolean(ARG_DEMO_MODE, false) ?: true
        demoFocus = arguments?.getBoolean(ARG_DEMO_FOCUS, false) ?: true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val activityManager = context!!.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        if (demoMode && activityManager.isLowRamDevice) {
            val dm = resources.displayMetrics
            var targetWidth = dm.widthPixels
            var targetHeight = dm.heightPixels
            if (!demoFocus) {
                targetHeight = MathUtil.roundMult4(ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS * 10000 / MuzeiBlurRenderer.DEFAULT_BLUR)
                targetWidth = MathUtil.roundMult4(
                        (dm.widthPixels * 1f / dm.heightPixels * targetHeight).toInt())
            }

            simpleDemoModeImageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Picasso.with(context)
                    .load("file:///android_asset/starrynight.jpg")
                    .resize(targetWidth, targetHeight)
                    .centerCrop()
                    .into(mSimpleDemoModeLoadedTarget)
            return simpleDemoModeImageView
        } else {
            muzeiView = MuzeiView(context!!)
            return muzeiView
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        muzeiView?.mRenderController?.visible = !hidden
    }

    override fun onDestroyView() {
        super.onDestroyView()
        muzeiView = null
    }

    override fun onPause() {
        super.onPause()
        muzeiView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        muzeiView?.onResume()
    }

    override fun queueEventOnGlThread(runnable: Runnable) {
        muzeiView?.queueEvent(runnable)
    }

    override fun requestRender() {
        muzeiView?.requestRender()
    }

    private inner class MuzeiView(context: Context) : GLTextureView(context) {
        private val mRenderer = MuzeiBlurRenderer(getContext(), this@MuzeiRendererFragment)
        internal val mRenderController: RenderController

        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(mRenderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            preserveEGLContextOnPause = true
            mRenderController = if (demoMode) {
                DemoRenderController(getContext(), mRenderer,
                        this@MuzeiRendererFragment, demoFocus)
            } else {
                RealRenderController(getContext(), mRenderer,
                        this@MuzeiRendererFragment)
            }
            mRenderer.setDemoMode(demoMode)
            mRenderController.visible = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            mRenderer.hintViewportSize(w, h)
            mRenderController.reloadCurrentArtwork(true)
        }

        override fun onDetachedFromWindow() {
            mRenderController.destroy()
            queueEventOnGlThread(Runnable { mRenderer.destroy() })
            super.onDetachedFromWindow()
        }
    }
}
