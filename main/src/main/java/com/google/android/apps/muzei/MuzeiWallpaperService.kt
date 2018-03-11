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

package com.google.android.apps.muzei

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.arch.lifecycle.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.annotation.RequiresApi
import android.support.v4.os.UserManagerCompat
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.notifications.NotificationUpdater
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.render.RealRenderController
import com.google.android.apps.muzei.render.RenderController
import com.google.android.apps.muzei.render.sampleSize
import com.google.android.apps.muzei.shortcuts.ArtworkInfoShortcutController
import com.google.android.apps.muzei.sources.SourceManager
import com.google.android.apps.muzei.util.observeNonNull
import com.google.android.apps.muzei.wallpaper.LockscreenObserver
import com.google.android.apps.muzei.wallpaper.WallpaperAnalytics
import com.google.android.apps.muzei.wearable.WearableController
import com.google.android.apps.muzei.widget.WidgetUpdater
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.IOException

data class WallpaperSize(val width: Int, val height: Int)

object WallpaperSizeLiveData : MutableLiveData<WallpaperSize>()

class MuzeiWallpaperService : GLWallpaperService(), LifecycleOwner {

    companion object {
        private const val TAG = "MuzeiWallpaperService"
        private const val TEMPORARY_FOCUS_DURATION_MILLIS: Long = 3000
        private const val MAX_ARTWORK_SIZE = 110 // px
    }

    private val wallpaperLifecycle = LifecycleRegistry(this)
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreateEngine(): Engine {
        return MuzeiWallpaperEngine()
    }

    @SuppressLint("InlinedApi")
    override fun onCreate() {
        super.onCreate()
        wallpaperLifecycle.addObserver(SourceManager(this))
        wallpaperLifecycle.addObserver(NotificationUpdater(this))
        wallpaperLifecycle.addObserver(WearableController(this))
        wallpaperLifecycle.addObserver(WidgetUpdater(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            wallpaperLifecycle.addObserver(ArtworkInfoShortcutController(this, this))
        }
        if (UserManagerCompat.isUserUnlocked(this)) {
            wallpaperLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    wallpaperLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    unregisterReceiver(this)
                    unlockReceiver = null
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            registerReceiver(unlockReceiver, filter)
        }
    }

    override fun getLifecycle(): Lifecycle {
        return wallpaperLifecycle
    }

    override fun onDestroy() {
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver)
        }
        wallpaperLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    inner class MuzeiWallpaperEngine : GLWallpaperService.GLEngine(), LifecycleOwner, DefaultLifecycleObserver, RenderController.Callbacks, MuzeiBlurRenderer.Callbacks {

        private val mainThreadHandler = Handler()

        private lateinit var renderer: MuzeiBlurRenderer
        private lateinit var renderController: RenderController
        private var currentArtwork: Bitmap? = null

        private var validDoubleTap: Boolean = false

        private val engineLifecycle = LifecycleRegistry(this)
        private val eventBusSubscriber = EventBusSubscriber()

        private val doubleTapTimeout = Runnable { queueEvent { validDoubleTap = false } }

        private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (ArtDetailOpenLiveData.value == true) {
                    // The main activity is visible, so discard any double touches since focus
                    // should be forced on
                    return true
                }

                validDoubleTap = true // processed in onCommand/COMMAND_TAP

                mainThreadHandler.removeCallbacks(doubleTapTimeout)
                val timeout = ViewConfiguration.getDoubleTapTimeout().toLong()
                mainThreadHandler.postDelayed(doubleTapTimeout, timeout)
                return true
            }
        }
        private val gestureDetector: GestureDetector = GestureDetector(this@MuzeiWallpaperService,
                gestureListener)

        private val blurRunnable = Runnable { queueEvent { renderer.setIsBlurred(true, false) } }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super<GLEngine>.onCreate(surfaceHolder)

            renderer = MuzeiBlurRenderer(this@MuzeiWallpaperService, this,
                    false, isPreview)
            renderController = RealRenderController(this@MuzeiWallpaperService,
                    renderer, this)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
            requestRender()

            engineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            engineLifecycle.addObserver(WallpaperAnalytics(this@MuzeiWallpaperService))
            engineLifecycle.addObserver(LockscreenObserver(this@MuzeiWallpaperService, this))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                engineLifecycle.addObserver(object : DefaultLifecycleObserver {
                    private lateinit var contentObserver: ContentObserver

                    override fun onCreate(owner: LifecycleOwner) {
                        contentObserver = object : ContentObserver(Handler()) {
                            override fun onChange(selfChange: Boolean, uri: Uri) {
                                updateCurrentArtwork()
                            }
                        }
                        contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                                true, contentObserver)
                        contentObserver.onChange(true, MuzeiContract.Artwork.CONTENT_URI)
                    }

                    override fun onDestroy(owner: LifecycleOwner) {
                        contentResolver.unregisterContentObserver(contentObserver)
                    }
                })
            }

            if (!isPreview) {
                // Use the MuzeiWallpaperService's lifecycle to wait for the user to unlock
                wallpaperLifecycle.addObserver(this)
            }
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            ArtDetailOpenLiveData.observeNonNull(this) { isArtDetailOpened ->
                cancelDelayedBlur()
                queueEvent { renderer.setIsBlurred(!isArtDetailOpened, true) }
            }
            EventBus.getDefault().register(eventBusSubscriber)
        }

        override fun getLifecycle(): Lifecycle {
            return engineLifecycle
        }

        override fun onStart(owner: LifecycleOwner) {
            // The MuzeiWallpaperService only gets to ON_START when the user is unlocked
            // At that point, we can proceed with the engine's lifecycle
            engineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        private fun updateCurrentArtwork() {
            try {
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
                options.inSampleSize = options.sampleSize(MAX_ARTWORK_SIZE / 2)
                options.inJustDecodeBounds = false
                contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI)?.use { input ->
                    currentArtwork = BitmapFactory.decodeStream(input, null, options)
                }
                notifyColorsChanged()
            } catch (e: IOException) {
                Log.w(TAG, "Error reading current artwork", e)
            }

        }

        @RequiresApi(api = 27)
        override fun onComputeColors(): WallpaperColors? =
                currentArtwork?.run {
                    WallpaperColors.fromBitmap(this)
                } ?: super.onComputeColors()

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (!isPreview) {
                WallpaperSizeLiveData.value = WallpaperSize(width, height)
            }
            renderController.reloadCurrentArtwork(true)
        }

        override fun onDestroy() {
            EventBus.getDefault().unregister(eventBusSubscriber)
            if (!isPreview) {
                lifecycle.removeObserver(this)
            }
            engineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            queueEvent {
                renderer.destroy()
            }
            renderController.destroy()
            super<GLEngine>.onDestroy()
        }

        internal inner class EventBusSubscriber {
            @Subscribe
            fun onEventMainThread(e: ArtDetailViewport) {
                requestRender()
            }
        }

        fun lockScreenVisibleChanged(isLockScreenVisible: Boolean) {
            cancelDelayedBlur()
            queueEvent { renderer.setIsBlurred(!isLockScreenVisible, false) }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderController.visible = visible
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float,
                                      yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset,
                    yPixelOffset)
            renderer.setNormalOffsetX(xOffset)
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?,
                               resultRequested: Boolean): Bundle? {
            // validDoubleTap previously set in the gesture listener
            if (WallpaperManager.COMMAND_TAP == action && validDoubleTap) {
                // Temporarily toggle focused/blurred
                queueEvent {
                    renderer.setIsBlurred(!renderer.isBlurred, false)
                    // Schedule a re-blur
                    delayedBlur()
                }
                // Reset the flag
                validDoubleTap = false
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // Delay blur from temporary refocus while touching the screen
            delayedBlur()
        }

        private fun cancelDelayedBlur() {
            mainThreadHandler.removeCallbacks(blurRunnable)
        }

        private fun delayedBlur() {
            if (ArtDetailOpenLiveData.value == true || renderer.isBlurred) {
                return
            }

            cancelDelayedBlur()
            mainThreadHandler.postDelayed(blurRunnable, TEMPORARY_FOCUS_DURATION_MILLIS)
        }

        override fun requestRender() {
            if (renderController.visible) {
                super.requestRender()
            }
        }

        override fun queueEventOnGlThread(event : () -> Unit) {
            queueEvent({ event() })
        }
    }
}
