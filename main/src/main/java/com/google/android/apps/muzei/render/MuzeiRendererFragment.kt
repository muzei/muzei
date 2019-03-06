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
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.observe
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.google.android.apps.muzei.settings.EffectsLockScreenOpenLiveData
import com.google.android.apps.muzei.util.ImageBlurrer
import com.google.android.apps.muzei.util.blur
import com.google.android.apps.muzei.util.roundMult4
import java.security.MessageDigest

class MuzeiRendererFragment : Fragment(), RenderController.Callbacks, MuzeiBlurRenderer.Callbacks {

    companion object {

        private const val ARG_DEMO_MODE = "demo_mode"
        private const val ARG_DEMO_FOCUS = "demo_focus"

        private const val TRANSFORMATION_ID = "simpleDemoModeTransformation"
        private val TRANSFORMATION_BYTES = TRANSFORMATION_ID.toByteArray()

        fun createInstance(demoMode: Boolean, demoFocus: Boolean = false): MuzeiRendererFragment {
            return MuzeiRendererFragment().apply {
                arguments = bundleOf(
                        ARG_DEMO_MODE to demoMode,
                        ARG_DEMO_FOCUS to demoFocus)
            }
        }
    }

    private var muzeiView: MuzeiView? = null
    private lateinit var simpleDemoModeImageView: ImageView
    private var demoMode: Boolean = false
    private var demoFocus: Boolean = false

    private val simpleDemoModeTransformation = object : BitmapTransformation() {
        override fun transform(
                pool: BitmapPool,
                bitmap: Bitmap,
                outWidth: Int,
                outHeight: Int
        ) = if (!demoFocus) {
            bitmap.blur(requireContext())?.apply {
                // Dim
                val c = Canvas(this)
                c.drawColor(Color.argb(255 - MuzeiBlurRenderer.DEFAULT_MAX_DIM,
                        0, 0, 0))
            }
        } else {
            bitmap
        }

        override fun equals(other: Any?) = other === this

        override fun hashCode() = TRANSFORMATION_ID.hashCode()

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update(TRANSFORMATION_BYTES)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        demoMode = arguments?.getBoolean(ARG_DEMO_MODE, false) ?: true
        demoFocus = arguments?.getBoolean(ARG_DEMO_FOCUS, false) ?: true
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        if (demoMode && activityManager.isLowRamDevice) {
            val dm = resources.displayMetrics
            var targetWidth = dm.widthPixels
            var targetHeight = dm.heightPixels
            if (!demoFocus) {
                targetHeight = (ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS * 10000 / MuzeiBlurRenderer.DEFAULT_BLUR)
                        .roundMult4()
                targetWidth = (dm.widthPixels * 1f / dm.heightPixels * targetHeight).toInt().roundMult4()
            }

            simpleDemoModeImageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glide.with(this)
                    .load("file:///android_asset/starrynight.jpg")
                    .override(targetWidth, targetHeight)
                    .transform(CenterCrop(), simpleDemoModeTransformation)
                    .into(simpleDemoModeImageView)
            return simpleDemoModeImageView
        } else {
            muzeiView = MuzeiView(requireContext())
            return muzeiView
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        muzeiView?.renderController?.visible = !hidden
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

    override fun queueEventOnGlThread(event: () -> Unit) {
        muzeiView?.queueEvent {
            event()
        }
    }

    override fun requestRender() {
        muzeiView?.requestRender()
    }

    private inner class MuzeiView(context: Context) : GLTextureView(context) {
        private val renderer = MuzeiBlurRenderer(getContext(), this@MuzeiRendererFragment, demoMode)
        internal val renderController: RenderController

        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            preserveEGLContextOnPause = true
            renderController = if (demoMode) {
                DemoRenderController(getContext(), renderer,
                        this@MuzeiRendererFragment, demoFocus)
            } else {
                RealRenderController(getContext(), renderer,
                        this@MuzeiRendererFragment)
            }
            lifecycle.addObserver(renderController)
            if (!demoMode) {
                EffectsLockScreenOpenLiveData.observe(this@MuzeiRendererFragment) {
                    isEffectsLockScreenOpen ->
                    renderController.onLockScreen = isEffectsLockScreenOpen
                }
            }
            renderController.visible = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            renderer.hintViewportSize(w, h)
            renderController.reloadCurrentArtwork()
        }

        override fun onDetachedFromWindow() {
            queueEventOnGlThread { renderer.destroy() }
            super.onDetachedFromWindow()
        }
    }
}
