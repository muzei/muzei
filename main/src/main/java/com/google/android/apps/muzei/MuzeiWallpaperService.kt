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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.legacy.LegacySourceManager
import com.google.android.apps.muzei.notifications.NotificationUpdater
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.render.RealRenderController
import com.google.android.apps.muzei.render.RenderController
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.settings.EffectsLockScreenOpen
import com.google.android.apps.muzei.settings.Prefs
import com.google.android.apps.muzei.shortcuts.ArtworkInfoShortcutController
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.wallpaper.LockscreenObserver
import com.google.android.apps.muzei.wallpaper.WallpaperAnalytics
import com.google.android.apps.muzei.wearable.WearableController
import com.google.android.apps.muzei.widget.WidgetUpdater
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rbgrn.android.glwallpaperservice.GLWallpaperService

data class WallpaperSize(val width: Int, val height: Int)

val WallpaperSizeStateFlow = MutableStateFlow<WallpaperSize?>(null)

class MuzeiWallpaperService : GLWallpaperService(), LifecycleOwner {

    companion object {
        private const val TEMPORARY_FOCUS_DURATION_MILLIS: Long = 3000
        private const val THREE_FINGER_TAP_INTERVAL_MS = 1000L
        private const val MAX_ARTWORK_SIZE = 110 // px
    }

    private val wallpaperLifecycle = LifecycleRegistry(this)
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreateEngine(): Engine {
        return MuzeiWallpaperEngine()
    }

    @SuppressLint("InlinedApi", "WrongConstant")
    override fun onCreate() {
        super.onCreate()
        with(wallpaperLifecycle) {
            addObserver(WorkManagerInitializer.initializeObserver(this@MuzeiWallpaperService))
            addObserver(LegacySourceManager.getInstance(this@MuzeiWallpaperService))
            addObserver(NotificationUpdater(this@MuzeiWallpaperService))
            addObserver(WearableController(this@MuzeiWallpaperService))
            addObserver(WidgetUpdater(this@MuzeiWallpaperService))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                addObserver(ArtworkInfoShortcutController(this@MuzeiWallpaperService))
            }
        }
        ProviderManager.getInstance(this).observe(this) { provider ->
            if (provider == null) {
                lifecycleScope.launch(NonCancellable) {
                    ProviderManager.select(this@MuzeiWallpaperService, FEATURED_ART_AUTHORITY)
                }
            }
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
            ContextCompat.registerReceiver(
                this,
                unlockReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override val lifecycle: Lifecycle = wallpaperLifecycle

    override fun onDestroy() {
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver)
        }
        wallpaperLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    inner class MuzeiWallpaperEngine
        : GLWallpaperService.GLEngine(),
            LifecycleOwner,
            DefaultLifecycleObserver,
            RenderController.Callbacks,
            MuzeiBlurRenderer.Callbacks {

        private lateinit var renderer: MuzeiBlurRenderer
        private lateinit var renderController: RenderController
        private var currentArtworkColors: WallpaperColors? = null

        private var validDoubleTap: Boolean = false
        private var lastThreeFingerTap = 0L

        private val engineLifecycle = LifecycleRegistry(this)

        private var doubleTapTimeout: Job? = null

        private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (ArtDetailOpen.value) {
                    // The main activity is visible, so discard any double touches since focus
                    // should be forced on
                    return true
                }

                validDoubleTap = true // processed in onCommand/COMMAND_TAP

                doubleTapTimeout?.cancel()
                val timeout = ViewConfiguration.getDoubleTapTimeout().toLong()
                doubleTapTimeout = lifecycleScope.launch {
                    delay(timeout)
                    queueEvent {
                        validDoubleTap = false
                    }
                }
                return true
            }
        }
        private val gestureDetector: GestureDetector = GestureDetector(this@MuzeiWallpaperService,
                gestureListener)

        private var delayedBlur: Job? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super<GLEngine>.onCreate(surfaceHolder)

            renderer = MuzeiBlurRenderer(this@MuzeiWallpaperService, this,
                    false, isPreview)
            renderController = RealRenderController(this@MuzeiWallpaperService,
                    renderer, this)
            engineLifecycle.addObserver(renderController)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
            requestRender()

            engineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            engineLifecycle.addObserver(WallpaperAnalytics(this@MuzeiWallpaperService))
            engineLifecycle.addObserver(LockscreenObserver(this@MuzeiWallpaperService, this))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val database = MuzeiDatabase.getInstance(this@MuzeiWallpaperService)
                database.artworkDao().getCurrentArtworkFlow()
                    .filterNotNull().collectIn(this) { artwork ->
                    updateCurrentArtwork(artwork)
                }
            }

            // Use the MuzeiWallpaperService's lifecycle to wait for the user to unlock
            wallpaperLifecycle.addObserver(this)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            EffectsLockScreenOpen.collectIn(this) { isEffectsLockScreenOpen ->
                renderController.onLockScreen = isEffectsLockScreenOpen
            }
            ArtDetailOpen.collectIn(this) { isArtDetailOpened ->
                cancelDelayedBlur()
                queueEvent { renderer.setIsBlurred(!isArtDetailOpened, true) }
            }

            ArtDetailViewport.getChanges().collectIn(this) {
                requestRender()
            }
        }

        override val lifecycle: Lifecycle = engineLifecycle

        override fun onStart(owner: LifecycleOwner) {
            // The MuzeiWallpaperService only gets to ON_START when the user is unlocked
            // At that point, we can proceed with the engine's lifecycle
            // In preview mode, we only move to ON_START to avoid analytics events.
            engineLifecycle.handleLifecycleEvent(if (isPreview)
                Lifecycle.Event.ON_START else Lifecycle.Event.ON_RESUME)
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        private suspend fun updateCurrentArtwork(artwork: Artwork) {
            val image = ImageLoader.decode(
                    contentResolver, artwork.contentUri,
                    MAX_ARTWORK_SIZE / 2) ?: return
            currentArtworkColors = withContext(Dispatchers.IO) {
                WallpaperColors.fromBitmap(image)
            }
            notifyColorsChanged()
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors? =
            currentArtworkColors ?: super.onComputeColors()

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (!isPreview) {
                WallpaperSizeStateFlow.value = WallpaperSize(width, height)
            }
            renderController.reloadCurrentArtwork()
        }

        override fun onDestroy() {
            wallpaperLifecycle.removeObserver(this)
            engineLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            queueEvent {
                renderer.destroy()
            }
            super<GLEngine>.onDestroy()
        }

        fun lockScreenVisibleChanged(isLockScreenVisible: Boolean) {
            if (!EffectsLockScreenOpen.value) {
                renderController.onLockScreen = isLockScreenVisible
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderController.visible = visible
        }

        override fun onOffsetsChanged(
                xOffset: Float,
                yOffset: Float,
                xOffsetStep: Float,
                yOffsetStep: Float,
                xPixelOffset: Int,
                yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset,
                    yPixelOffset)
            renderer.setNormalOffsetX(xOffset)
        }

        override fun onZoomChanged(zoom: Float) {
            super.onZoomChanged(zoom)
            renderer.setZoom(zoom)
        }

        override fun onCommand(
                action: String?,
                x: Int,
                y: Int,
                z: Int,
                extras: Bundle?,
                resultRequested: Boolean
        ): Bundle? {
            // validDoubleTap previously set in the gesture listener
            if (WallpaperManager.COMMAND_TAP == action && validDoubleTap) {
                val prefs = Prefs.getSharedPreferences(this@MuzeiWallpaperService)
                val doubleTapValue = prefs.getString(Prefs.PREF_DOUBLE_TAP,
                        null) ?: Prefs.PREF_TAP_ACTION_TEMP
                triggerTapAction(doubleTapValue, "gesture_double_tap")
                // Reset the flag
                validDoubleTap = false
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun triggerTapAction(action: String, type: String) {
            when (action) {
                Prefs.PREF_TAP_ACTION_TEMP -> {
                    Firebase.analytics.logEvent("temp_disable_effects") {
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, type)
                    }
                    // Temporarily toggle focused/blurred
                    queueEvent {
                        renderer.setIsBlurred(!renderer.isBlurred, false)
                        // Schedule a re-blur
                        delayedBlur()
                    }
                }
                Prefs.PREF_TAP_ACTION_NEXT -> {
                    lifecycleScope.launch(NonCancellable) {
                        Firebase.analytics.logEvent("next_artwork") {
                            param(FirebaseAnalytics.Param.CONTENT_TYPE, type)
                        }
                        LegacySourceManager.getInstance(this@MuzeiWallpaperService).nextArtwork()
                    }
                }
                Prefs.PREF_TAP_ACTION_VIEW_DETAILS -> {
                    lifecycleScope.launch(NonCancellable) {
                        val artwork = MuzeiDatabase
                                .getInstance(this@MuzeiWallpaperService)
                                .artworkDao()
                                .getCurrentArtwork()
                        artwork?.run {
                            Firebase.analytics.logEvent("artwork_info_open") {
                                param(FirebaseAnalytics.Param.CONTENT_TYPE, type)
                            }
                            openArtworkInfo(this@MuzeiWallpaperService)
                        }
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // Delay blur from temporary refocus while touching the screen
            delayedBlur()
            // See if there was a valid three finger tap
            val now = SystemClock.elapsedRealtime()
            val timeSinceLastThreeFingerTap = now - lastThreeFingerTap
            if (event.pointerCount == 3
                && timeSinceLastThreeFingerTap > THREE_FINGER_TAP_INTERVAL_MS) {
                lastThreeFingerTap = now
                val prefs = Prefs.getSharedPreferences(this@MuzeiWallpaperService)
                val threeFingerTapValue = prefs.getString(Prefs.PREF_THREE_FINGER_TAP,
                        null) ?: Prefs.PREF_TAP_ACTION_NONE

                triggerTapAction(threeFingerTapValue, "gesture_three_finger")
            }
        }

        private fun cancelDelayedBlur() {
            delayedBlur?.cancel()
        }

        private fun delayedBlur() {
            if (ArtDetailOpen.value || renderer.isBlurred) {
                return
            }

            cancelDelayedBlur()
            delayedBlur = lifecycleScope.launch {
                delay(TEMPORARY_FOCUS_DURATION_MILLIS)
                queueEvent {
                    renderer.setIsBlurred(isBlurred = true, artDetailMode = false)
                }
            }
        }

        override fun requestRender() {
            if (renderController.visible) {
                super.requestRender()
            }
        }

        override fun queueEventOnGlThread(event: () -> Unit) {
            queueEvent {
                event()
            }
        }
    }
}