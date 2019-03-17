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
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.preference.PreferenceManager
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.SystemProviders
import android.support.wearable.complications.rendering.ComplicationDrawable
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.lifecycle.observe
import com.google.android.apps.muzei.complications.ArtworkComplicationProviderService
import com.google.android.apps.muzei.datalayer.ActivateMuzeiIntentService
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.ImageBlurrer
import com.google.android.apps.muzei.util.blur
import com.google.android.apps.muzei.util.coroutineScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Default watch face for Muzei, showing the current time atop the current artwork. In ambient
 * mode, the artwork is invisible. On devices with low-bit ambient mode, the text is drawn without
 * anti-aliasing in ambient mode. On devices which require burn-in protection, the hours are drawn
 * with a thinner font.
 */
class MuzeiWatchFace : CanvasWatchFaceService(), LifecycleOwner {

    companion object {
        private const val TAG = "MuzeiWatchFace"
        internal const val TOP_COMPLICATION_ID = 0
        internal const val BOTTOM_COMPLICATION_ID = 1

        /**
         * Preference key for saving whether the watch face is blurred
         */
        private const val BLURRED_PREF_KEY = "BLURRED"

        /**
         * Update rate in milliseconds.
         */
        private val UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1)
        internal const val MSG_UPDATE_TIME = 0
    }

    private val lifecycleRegistry = LifecycleRegistry(this)

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        ProviderManager.getInstance(this).observe(this) { provider ->
            if (provider == null) {
                val context = this@MuzeiWatchFace
                GlobalScope.launch {
                    ProviderManager.select(context, FEATURED_ART_AUTHORITY)
                    ActivateMuzeiIntentService.checkForPhoneApp(context)
                }
            }
        }
        ProviderChangedReceiver.observeForVisibility(this, this)
    }

    override fun onCreateEngine(): CanvasWatchFaceService.Engine {
        return Engine()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class Engine : CanvasWatchFaceService.Engine() {
        internal val timeZoneReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                calendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }
        internal val localeChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                recomputeDateFormat()
                invalidate()
            }
        }

        internal var registeredTimeZoneReceiver = false
        internal var registeredLocaleChangedReceiver = false
        internal val backgroundPaint: Paint = Paint().apply {
            color = Color.BLACK
        }
        internal val heavyTypeface: Typeface? by lazy {
            ResourcesCompat.getFont(this@MuzeiWatchFace, R.font.nunito_clock_bold)
        }
        internal val lightTypeface: Typeface? by lazy {
            ResourcesCompat.getFont(this@MuzeiWatchFace, R.font.nunito_clock_regular)
        }
        val densityMultiplier = resources.displayMetrics.density
        internal lateinit var clockPaint: Paint
        internal lateinit var clockAmbientShadowPaint: Paint
        internal var clockTextHeight: Float = 0f
        internal lateinit var datePaint: Paint
        internal lateinit var dateAmbientShadowPaint: Paint
        internal var dateTextHeight: Float = 0f
        internal lateinit var timeFormat12h: SimpleDateFormat
        internal lateinit var timeFormat24h: SimpleDateFormat
        internal lateinit var dateFormat: SimpleDateFormat
        internal var topComplication: ComplicationDrawable? = null
        internal var bottomComplication: ComplicationDrawable? = null
        private val drawableCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable?) {
                invalidate()
            }

            override fun scheduleDrawable(who: Drawable?, what: Runnable?, `when`: Long) {
            }

            override fun unscheduleDrawable(who: Drawable?, what: Runnable?) {
            }
        }

        /**
         * Handler to update the time periodically in interactive mode.
         */
        internal val updateTimeHandler: Handler = @SuppressLint("HandlerLeak")
        object : Handler() {
            override fun handleMessage(message: Message) {
                when (message.what) {
                    MSG_UPDATE_TIME -> {
                        invalidate()
                        if (isVisible) {
                            val timeMs = System.currentTimeMillis()
                            val delayMs = UPDATE_RATE_MS - timeMs % UPDATE_RATE_MS
                            this.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
                        }
                    }
                }
            }
        }
        internal var backgroundScaledBlurredBitmap: Bitmap? = null
        internal var backgroundScaledBitmap: Bitmap? = null
        internal var backgroundBitmap: Bitmap? = null
        internal val clockMargin: Float by lazy {
            resources.getDimension(R.dimen.clock_margin)
        }
        internal val dateMinAvailableMargin: Float by lazy {
            resources.getDimension(R.dimen.date_min_available_margin)
        }
        internal val complicationMaxHeight: Float by lazy {
            resources.getDimension(R.dimen.complication_max_height)
        }
        internal var ambient: Boolean = false
        internal val calendar: Calendar = Calendar.getInstance()
        internal var cardBounds = Rect()
        internal var currentWidth = 0
        internal var currentHeight = 0
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        internal var lowBitAmbient: Boolean = false
        internal lateinit var tapAction: String
        internal var blurred: Boolean = false

        private inner class LoadImageObserver : Observer<Artwork?> {
            override fun onChanged(artwork: Artwork?) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Artwork = ${artwork?.contentUri}")
                }
                coroutineScope.launch {
                    val bitmap: Bitmap? = try {
                        artwork?.run {
                            ImageLoader.decode(contentResolver, contentUri)
                        }
                    } catch (e: FileNotFoundException) {
                        Log.w(TAG, "Could not find current artwork image", e)
                        null
                    }

                    if (bitmap != null && !bitmap.sameAs(backgroundBitmap)) {
                        backgroundBitmap = bitmap
                        createScaledBitmap()
                        postInvalidate()
                    }
                }
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            FirebaseAnalytics.getInstance(this@MuzeiWatchFace).logEvent("watchface_created", null)
            MuzeiDatabase.getInstance(this@MuzeiWatchFace)
                    .artworkDao().currentArtwork
                    .observe(this@MuzeiWatchFace, LoadImageObserver())

            clockPaint = Paint().apply {
                color = Color.WHITE
                setShadowLayer(1f * densityMultiplier, 0f, 0.5f * densityMultiplier,
                        -0x34000000)
                isAntiAlias = true
                typeface = heavyTypeface
                textAlign = Paint.Align.CENTER
                textSize = resources.getDimension(R.dimen.clock_text_size)
            }
            recomputeClockTextHeight()

            clockAmbientShadowPaint = Paint(clockPaint).apply {
                color = Color.TRANSPARENT
                setShadowLayer(6f * densityMultiplier, 0f, 2f * densityMultiplier,
                        0x66000000)
            }

            datePaint = Paint().apply {
                color = Color.WHITE
                setShadowLayer(1f * densityMultiplier, 0f, 0.5f * densityMultiplier,
                        -0x34000000)
                isAntiAlias = true
                typeface = lightTypeface
                textAlign = Paint.Align.CENTER
                textSize = resources.getDimension(R.dimen.date_text_size)
            }
            val fm = datePaint.fontMetrics
            dateTextHeight = -fm.top

            dateAmbientShadowPaint = Paint(datePaint).apply {
                color = Color.TRANSPARENT
                setShadowLayer(4f * densityMultiplier, 0f, 2f * densityMultiplier,
                        0x66000000)
            }
            recomputeDateFormat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(
                        this@MuzeiWatchFace)
                val showDate = sharedPreferences.getBoolean(
                        ConfigActivity.SHOW_DATE_PREFERENCE_KEY, true)
                if (showDate) {
                    setDefaultSystemComplicationProvider(TOP_COMPLICATION_ID, SystemProviders.DATE,
                            ComplicationData.TYPE_SHORT_TEXT)
                }
                setDefaultComplicationProvider(BOTTOM_COMPLICATION_ID,
                        ComponentName(this@MuzeiWatchFace,
                                ArtworkComplicationProviderService::class.java),
                        ComplicationData.TYPE_LONG_TEXT)
                setActiveComplications(TOP_COMPLICATION_ID, BOTTOM_COMPLICATION_ID)
                topComplication = (getDrawable(R.drawable.complication)
                        as ComplicationDrawable).apply {
                    setContext(this@MuzeiWatchFace)
                }
                bottomComplication = (getDrawable(R.drawable.complication)
                        as ComplicationDrawable).apply {
                    setContext(this@MuzeiWatchFace)
                }

                listOfNotNull(topComplication, bottomComplication).forEach {
                    it.callback = drawableCallback
                }
            }

            updateBlurredStatus()
        }

        private fun updateBlurredStatus() {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this@MuzeiWatchFace)
            tapAction = preferences.getString(ConfigActivity.TAP_PREFERENCE_KEY,
                    null) ?: getString(R.string.config_tap_default)
            blurred = when(tapAction) {
                "always" -> true
                "never" -> false
                else -> preferences.getBoolean(BLURRED_PREF_KEY, false)
            }
            updateWatchFaceStyle()
        }

        private fun recomputeClockTextHeight() {
            val fm = clockPaint.fontMetrics
            clockTextHeight = -fm.top
        }

        private fun recomputeDateFormat() {
            timeFormat12h = SimpleDateFormat("h:mm", Locale.getDefault())
            timeFormat24h = SimpleDateFormat("H:mm", Locale.getDefault())
            val bestPattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "Md")
            dateFormat = SimpleDateFormat(bestPattern, Locale.getDefault())
        }

        @Suppress("DEPRECATION")
        private fun updateWatchFaceStyle() {
            setWatchFaceStyle(WatchFaceStyle.Builder(this@MuzeiWatchFace)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setPeekOpacityMode(if (blurred)
                        WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT
                    else
                        WatchFaceStyle.PEEK_OPACITY_MODE_OPAQUE)
                    .setStatusBarGravity(Gravity.TOP or Gravity.START)
                    .setHotwordIndicatorGravity(Gravity.TOP or Gravity.START)
                    .setViewProtectionMode(if (blurred)
                        0
                    else
                        WatchFaceStyle.PROTECT_HOTWORD_INDICATOR or WatchFaceStyle.PROTECT_STATUS_BAR)
                    .setAcceptsTapEvents(true)
                    .build())
            listOfNotNull(topComplication, bottomComplication).forEach {
                it.setBackgroundColorActive(if (blurred) Color.TRANSPARENT else 0x66000000)
            }
            invalidate()
        }

        override fun onDestroy() {
            FirebaseAnalytics.getInstance(this@MuzeiWatchFace).logEvent("watchface_destroyed", null)
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentWidth = width
            currentHeight = height
            createScaledBitmap()
        }

        private fun createScaledBitmap() {
            if (currentWidth == 0 || currentHeight == 0) {
                // Wait for the surface to be created
                return
            }
            val background = backgroundBitmap ?: return
            backgroundScaledBitmap = if (background.width > background.height) {
                val scalingFactor = currentHeight * 1f / background.height
                Bitmap.createScaledBitmap(
                        background,
                        (scalingFactor * background.width).toInt(),
                        currentHeight,
                        true /* filter */)
            } else {
                val scalingFactor = currentWidth * 1f / background.width
                Bitmap.createScaledBitmap(
                        background,
                        currentWidth,
                        (scalingFactor * background.height).toInt(),
                        true /* filter */)
            }
            backgroundScaledBlurredBitmap = backgroundScaledBitmap.blur(this@MuzeiWatchFace,
                    (ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS / 2).toFloat())
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onVisibilityChanged: visible = $visible")
            }
            if (visible) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

                registerReceiver()

                // Update time zone in case it changed while we weren't visible.
                calendar.timeZone = TimeZone.getDefault()

                // Update the blurred status in case the preference has changed
                updateBlurredStatus()

                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            } else {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                updateTimeHandler.removeMessages(MSG_UPDATE_TIME)

                unregisterReceiver()
            }
        }

        private fun registerReceiver() {
            if (registeredTimeZoneReceiver) {
                return
            }
            registeredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MuzeiWatchFace.registerReceiver(timeZoneReceiver, filter)
            registeredLocaleChangedReceiver = true
            val localeFilter = IntentFilter(Intent.ACTION_LOCALE_CHANGED)
            this@MuzeiWatchFace.registerReceiver(localeChangedReceiver, localeFilter)
        }

        private fun unregisterReceiver() {
            if (registeredTimeZoneReceiver) {
                registeredTimeZoneReceiver = false
                this@MuzeiWatchFace.unregisterReceiver(timeZoneReceiver)
            }
            if (registeredLocaleChangedReceiver) {
                registeredLocaleChangedReceiver = false
                this@MuzeiWatchFace.unregisterReceiver(localeChangedReceiver)
            }
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)

            val burnInProtection = properties.getBoolean(WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
            val textTypeface = if (burnInProtection) lightTypeface else heavyTypeface
            clockPaint.typeface = textTypeface
            clockAmbientShadowPaint.typeface = textTypeface
            recomputeClockTextHeight()

            lowBitAmbient = properties.getBoolean(WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            listOfNotNull(topComplication, bottomComplication).forEach {
                it.setBurnInProtection(burnInProtection)
                it.setLowBitAmbient(lowBitAmbient)
            }
            invalidate()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = $burnInProtection, " +
                        "low-bit ambient = $lowBitAmbient")
            }
        }

        @Suppress("OverridingDeprecatedMember", "DEPRECATION")
        override fun onPeekCardPositionUpdate(bounds: Rect) {
            super.onPeekCardPositionUpdate(bounds)
            if (bounds != cardBounds) {
                cardBounds.set(bounds)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onPeekCardPositionUpdate: $cardBounds")
                }
                invalidate()
            }
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onComplicationDataUpdate(watchFaceComplicationId: Int, data: ComplicationData?) {
            when (watchFaceComplicationId) {
                TOP_COMPLICATION_ID -> {
                    topComplication?.setComplicationData(data)
                }
                BOTTOM_COMPLICATION_ID -> {
                    bottomComplication?.setComplicationData(data)
                }
            }
            invalidate()
        }

        override fun onTapCommand(@TapType tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TAP -> {
                    when {
                        topComplication?.safeOnTap(x, y) == true -> {
                            invalidate()
                        }
                        bottomComplication?.safeOnTap(x, y) == true -> {
                            invalidate()
                        }
                        tapAction == "toggle" -> {
                            blurred = !blurred
                            val preferences = PreferenceManager.getDefaultSharedPreferences(this@MuzeiWatchFace)
                            preferences.edit { putBoolean(BLURRED_PREF_KEY, blurred) }
                            updateWatchFaceStyle()
                            invalidate()
                        }
                    }
                }
                else -> super.onTapCommand(tapType, x, y, eventTime)
            }
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onAmbientModeChanged: $inAmbientMode")
            }
            super.onAmbientModeChanged(inAmbientMode)
            if (ambient != inAmbientMode) {
                ambient = inAmbientMode

                if (lowBitAmbient) {
                    val antiAlias = !inAmbientMode
                    clockPaint.isAntiAlias = antiAlias
                    clockAmbientShadowPaint.isAntiAlias = antiAlias
                    datePaint.isAntiAlias = antiAlias
                    dateAmbientShadowPaint.isAntiAlias = antiAlias
                }
                listOfNotNull(topComplication, bottomComplication).forEach {
                    it.setInAmbientMode(ambient)
                }
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            calendar.timeInMillis = System.currentTimeMillis()

            val width = canvas.width
            val height = canvas.height

            // Draw the background
            val background = if (blurred)
                backgroundScaledBlurredBitmap
            else
                backgroundScaledBitmap
            if (ambient || background == null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
            } else {
                // Draw the scaled background
                canvas.drawBitmap(background,
                        ((width - background.width) / 2).toFloat(),
                        ((height - background.height) / 2).toFloat(), null)
                if (blurred) {
                    canvas.drawColor(Color.argb(68, 0, 0, 0))
                }
            }

            // Draw the time
            val formattedTime = if (DateFormat.is24HourFormat(this@MuzeiWatchFace))
                timeFormat24h.format(calendar.time)
            else
                timeFormat12h.format(calendar.time)
            val xOffset = width / 2f
            val yOffset = Math.min((height + clockTextHeight) / 2,
                    (if (cardBounds.top == 0) height else cardBounds.top) - clockMargin)
            if (!blurred) {
                canvas.drawText(formattedTime,
                        xOffset,
                        yOffset,
                        clockAmbientShadowPaint)
            }
            canvas.drawText(formattedTime,
                    xOffset,
                    yOffset,
                    clockPaint)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                topComplication?.run {
                    val (top, bottom) = run {
                        var top = clockMargin.toInt()
                        var bottom = ((height - clockTextHeight - clockMargin) / 2).toInt()
                        val maxHeight = bottom - top
                        if (maxHeight > complicationMaxHeight.toInt()) {
                            val difference = maxHeight - complicationMaxHeight.toInt()
                            top += difference / 2
                            bottom -= difference / 2
                        }
                        Pair(top, bottom)
                    }
                    val complicationHeight = bottom - top
                    setBounds((canvas.width - complicationHeight) / 2, top,
                            (canvas.width + complicationHeight) / 2, bottom)
                    draw(canvas, calendar.timeInMillis)
                }
            } else {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(
                        this@MuzeiWatchFace)
                val showDate = sharedPreferences.getBoolean(
                        ConfigActivity.SHOW_DATE_PREFERENCE_KEY, true)
                // If no card is visible, we have the entire screen.
                // Otherwise, only the space above the card is available
                val spaceAvailable = (if (cardBounds.top == 0) height else cardBounds.top).toFloat()
                // Compute the height of the clock and date
                val clockHeight = clockTextHeight + clockMargin
                val dateHeight = dateTextHeight + clockMargin

                // Only show the date if the height of the clock + date + margin fits in the
                // available space Otherwise it may be obstructed by an app icon (square)
                // or unread notification / charging indicator (round)
                if (showDate &&
                        clockHeight + dateHeight + dateMinAvailableMargin < spaceAvailable) {
                    // Draw the date
                    val formattedDate = dateFormat.format(calendar.time)
                    val yDateOffset = yOffset - clockTextHeight - clockMargin // date above centered time
                    if (!blurred) {
                        canvas.drawText(formattedDate,
                                xOffset,
                                yDateOffset,
                                dateAmbientShadowPaint)
                    }
                    canvas.drawText(formattedDate,
                            xOffset,
                            yDateOffset,
                            datePaint)
                }
            }

            // Draw the bottom complication
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                bottomComplication?.run {
                    val (top, bottom) = run {
                        var top = ((height + clockTextHeight + clockMargin) / 2).toInt()
                        var bottom = height - clockMargin.toInt()
                        val maxHeight = bottom - top
                        if (maxHeight > complicationMaxHeight.toInt()) {
                            val difference = maxHeight - complicationMaxHeight.toInt()
                            top += difference / 2
                            bottom -= difference / 2
                        }
                        Pair(top, bottom)
                    }
                    val complicationHeight = bottom - top
                    val complicationWidth = (2.75f * complicationHeight).toInt()
                    setBounds((canvas.width - complicationWidth) / 2, top,
                            (canvas.width + complicationWidth) / 2, bottom)
                    draw(canvas, calendar.timeInMillis)
                }
            }
        }
    }
}
