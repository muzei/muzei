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

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.Fragment
import android.support.v4.content.res.ResourcesCompat
import android.support.v7.widget.TooltipCompat
import android.util.Log
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.view.isGone
import androidx.view.isVisible
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.event.ArtworkLoadingStateChangedEvent
import com.google.android.apps.muzei.event.ArtworkSizeChangedEvent
import com.google.android.apps.muzei.event.SwitchingPhotosStateChangedEvent
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Source
import com.google.android.apps.muzei.settings.AboutActivity
import com.google.android.apps.muzei.sources.SourceManager
import com.google.android.apps.muzei.sync.TaskQueueService
import com.google.android.apps.muzei.util.AnimatedMuzeiLoadingSpinnerView
import com.google.android.apps.muzei.util.PanScaleProxyView
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.android.apps.muzei.widget.AppWidgetUpdateTask
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

object ArtDetailOpenLiveData : MutableLiveData<Boolean>()

class ArtDetailFragment : Fragment() {

    companion object {
        private const val TAG = "ArtDetailFragment"

        private const val LOAD_ERROR_COUNT_EASTER_EGG = 4
        private val SOURCE_ACTION_IDS = intArrayOf(
                R.id.source_action_1,
                R.id.source_action_2,
                R.id.source_action_3,
                R.id.source_action_4,
                R.id.source_action_5,
                R.id.source_action_6,
                R.id.source_action_7,
                R.id.source_action_8,
                R.id.source_action_9,
                R.id.source_action_10)
    }

    private var currentViewportId = 0
    private var wallpaperAspectRatio: Float = 0f
    private var artworkAspectRatio: Float = 0f

    private var supportsNextArtwork = false
    private val sourceObserver = Observer<Source?> { source ->
        // Update overflow and next button
        overflowSourceActionMap.clear()
        overflowMenu.menu.clear()
        overflowMenu.inflate(R.menu.muzei_overflow)
        if (source != null) {
            supportsNextArtwork = source.supportsNextArtwork
            val commands = source.commands
            val numSourceActions = Math.min(SOURCE_ACTION_IDS.size,
                    commands.size)
            for (i in 0 until numSourceActions) {
                val action = commands[i]
                overflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action.id)
                overflowMenu.menu.add(0, SOURCE_ACTION_IDS[i], 0, action.title)
            }
        }
        nextButton.isVisible = supportsNextArtwork && !artworkLoading
    }

    private val artworkObserver = Observer<Artwork?> { currentArtwork ->
        if (currentArtwork == null) {
            return@Observer
        }
        var titleFont = R.font.alegreya_sans_black
        var bylineFont = R.font.alegreya_sans_medium
        if (MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT == currentArtwork.metaFont) {
            titleFont = R.font.alegreya_black_italic
            bylineFont = R.font.alegreya_italic
        }

        titleView.typeface = ResourcesCompat.getFont(requireContext(), titleFont)
        titleView.text = currentArtwork.title

        bylineView.typeface = ResourcesCompat.getFont(requireContext(), bylineFont)
        bylineView.text = currentArtwork.byline

        val attribution = currentArtwork.attribution
        if (attribution?.isNotEmpty() == true) {
            attributionView.text = attribution
            attributionView.isVisible = true
        } else {
            attributionView.isGone = true
        }

        val viewIntent = currentArtwork.viewIntent
        metadataView.isEnabled = viewIntent != null
        if (viewIntent != null) {
            metadataView.setOnClickListener {
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Make sure any data URIs granted to Muzei are passed onto the
                // started Activity
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(viewIntent)
                } catch (e: RuntimeException) {
                    // Catch ActivityNotFoundException, SecurityException,
                    // and FileUriExposedException
                    Toast.makeText(context, R.string.error_view_details,
                            Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Error viewing artwork details.", e)
                }
            }
        } else {
            metadataView.setOnClickListener(null)
        }
    }

    private var guardViewportChangeListener: Boolean = false
    private var deferResetViewport: Boolean = false

    private val handler = Handler()
    private lateinit var containerView: View
    private lateinit var overflowMenu: PopupMenu
    private val overflowSourceActionMap = SparseIntArray()
    private lateinit var chromeContainerView: View
    private lateinit var metadataView: View
    private lateinit var loadingContainerView: View
    private lateinit var loadErrorContainerView: View
    private lateinit var loadErrorEasterEggView: View
    private lateinit var loadingIndicatorView: AnimatedMuzeiLoadingSpinnerView
    private lateinit var nextButton: View
    private lateinit var titleView: TextView
    private lateinit var bylineView: TextView
    private lateinit var attributionView: TextView
    private lateinit var panScaleProxyView: PanScaleProxyView
    private var artworkLoading = false
    private var artworkLoadingError = false
    private var loadingSpinnerShown = false
    private var loadErrorShown = false
    private var nextFakeLoading = false
    private var consecutiveLoadErrorCount = 0
    private val currentSourceLiveData: LiveData<Source?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).sourceDao().currentSource
    }
    private val currentArtworkLiveData: LiveData<Artwork?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).artworkDao().currentArtwork
    }

    private val unsetNextFakeLoading = {
        nextFakeLoading = false
        updateLoadingSpinnerAndErrorVisibility()
    }

    private val showLoadingSpinner = Runnable {
        loadingIndicatorView.start()
        loadingContainerView.isVisible = true
        loadingContainerView.animate()
                .alpha(1f)
                .setDuration(300)
                .withEndAction(null)
    }

    private val showLoadError = Runnable {
        ++consecutiveLoadErrorCount
        loadErrorEasterEggView.isVisible = consecutiveLoadErrorCount >= LOAD_ERROR_COUNT_EASTER_EGG
        loadErrorContainerView.isVisible = true
        loadErrorContainerView.animate()
                .alpha(1f)
                .setDuration(300)
                .withEndAction(null)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        containerView = inflater.inflate(R.layout.art_detail_fragment, container, false)

        chromeContainerView = containerView.findViewById(R.id.chrome_container)
        showHideChrome(true)

        return containerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        @Suppress("DEPRECATION")
        view.requestFitSystemWindows()

        chromeContainerView.background = makeCubicGradientScrimDrawable(Gravity.BOTTOM, 0xAA)

        metadataView = view.findViewById(R.id.metadata)

        val metadataSlideDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
        containerView.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0

            chromeContainerView.isVisible = true
            chromeContainerView.animate()
                    .alpha(if (visible) 1f else 0f)
                    .translationY(if (visible) 0f else metadataSlideDistance)
                    .setDuration(200)
                    .withEndAction {
                        if (!visible) {
                            chromeContainerView.isGone = true
                        }
                    }
        }

        titleView = view.findViewById(R.id.title)
        bylineView = view.findViewById(R.id.byline)
        attributionView = view.findViewById(R.id.attribution)

        val overflowButton = view.findViewById<View>(R.id.overflow_button)
        overflowMenu = PopupMenu(context, overflowButton)
        overflowButton.setOnTouchListener(overflowMenu.dragToOpenListener)
        overflowButton.setOnClickListener { overflowMenu.show() }
        overflowMenu.setOnMenuItemClickListener { menuItem ->
            val context = context ?: return@setOnMenuItemClickListener false
            val id = overflowSourceActionMap.get(menuItem.itemId)
            if (id > 0) {
                SourceManager.sendAction(context, id)
                return@setOnMenuItemClickListener true
            }

            return@setOnMenuItemClickListener when (menuItem.itemId) {
                R.id.action_about -> {
                    FirebaseAnalytics.getInstance(context).logEvent("about_open", null)
                    startActivity(Intent(context, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        nextButton = view.findViewById(R.id.next_button)
        nextButton.setOnClickListener {
            val context = context ?: return@setOnClickListener
            SourceManager.sendAction(context,
                    MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK)
            nextFakeLoading = true
            showNextFakeLoading()
        }
        TooltipCompat.setTooltipText(nextButton, nextButton.contentDescription)

        panScaleProxyView = view.findViewById(R.id.pan_scale_proxy)
        panScaleProxyView.apply {
            setMaxZoom(5)
            onViewportChanged = {
                if (!guardViewportChangeListener) {
                    ArtDetailViewport.setViewport(
                            currentViewportId, panScaleProxyView.currentViewport, true)
                }
            }
            onSingleTapUp = {
                val window = activity?.window
                if (window != null) {
                    showHideChrome(window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
                }
            }
            onLongPress = {
                AppWidgetUpdateTask(requireContext(), true).execute()
            }
        }

        loadingContainerView = view.findViewById(R.id.image_loading_container)
        loadingIndicatorView = view.findViewById(R.id.image_loading_indicator)
        loadErrorContainerView = view.findViewById(R.id.image_error_container)
        loadErrorEasterEggView = view.findViewById(R.id.error_easter_egg)

        view.findViewById<View>(R.id.image_error_retry_button).setOnClickListener {
            showNextFakeLoading()
            requireContext().startService(TaskQueueService.getDownloadCurrentArtworkIntent(context))
        }

        EventBus.getDefault().register(this)

        val wsce = EventBus.getDefault().getStickyEvent(
                WallpaperSizeChangedEvent::class.java)
        if (wsce != null) {
            onEventMainThread(wsce)
        }

        val asce = EventBus.getDefault().getStickyEvent(
                ArtworkSizeChangedEvent::class.java)
        if (asce != null) {
            onEventMainThread(asce)
        }

        val alsce = EventBus.getDefault().getStickyEvent(
                ArtworkLoadingStateChangedEvent::class.java)
        if (alsce != null) {
            onEventMainThread(alsce)
        }

        val fve = EventBus.getDefault().getStickyEvent(ArtDetailViewport::class.java)
        if (fve != null) {
            onEventMainThread(fve)
        }

        val spsce = EventBus.getDefault().getStickyEvent(
                SwitchingPhotosStateChangedEvent::class.java)
        if (spsce != null) {
            onEventMainThread(spsce)
        }
        currentSourceLiveData.observe(this, sourceObserver)
        currentArtworkLiveData.observe(this, artworkObserver)
    }

    override fun onStart() {
        super.onStart()
        ArtDetailOpenLiveData.value = true
    }

    override fun onResume() {
        super.onResume()
        consecutiveLoadErrorCount = 0
        NewWallpaperNotificationReceiver.markNotificationRead(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        EventBus.getDefault().unregister(this)
        currentSourceLiveData.removeObserver(sourceObserver)
        currentArtworkLiveData.removeObserver(artworkObserver)
    }

    private fun showHideChrome(show: Boolean) {
        var flags = if (show) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
        flags = flags or (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        if (!show) {
            flags = flags or (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
        requireActivity().window.decorView.systemUiVisibility = flags
    }

    @Subscribe
    fun onEventMainThread(wsce: WallpaperSizeChangedEvent) {
        wallpaperAspectRatio = if (wsce.height > 0) {
            wsce.width * 1f / wsce.height
        } else {
            panScaleProxyView.width * 1f / panScaleProxyView.height
        }
        resetProxyViewport()
    }

    @Subscribe
    fun onEventMainThread(ase: ArtworkSizeChangedEvent) {
        artworkAspectRatio = ase.width * 1f / ase.height
        resetProxyViewport()
    }

    private fun resetProxyViewport() {
        if (wallpaperAspectRatio == 0f || artworkAspectRatio == 0f) {
            return
        }

        deferResetViewport = false
        val spe = EventBus.getDefault()
                .getStickyEvent(SwitchingPhotosStateChangedEvent::class.java)
        if (spe != null && spe.isSwitchingPhotos) {
            deferResetViewport = true
            return
        }

        panScaleProxyView.relativeAspectRatio = artworkAspectRatio / wallpaperAspectRatio
    }

    @Subscribe
    fun onEventMainThread(e: ArtDetailViewport) {
        if (!e.isFromUser) {
            guardViewportChangeListener = true
            panScaleProxyView.setViewport(e.getViewport(currentViewportId))
            guardViewportChangeListener = false
        }
    }

    @Subscribe
    fun onEventMainThread(spe: SwitchingPhotosStateChangedEvent) {
        currentViewportId = spe.currentId
        panScaleProxyView.panScaleEnabled = !spe.isSwitchingPhotos
        // Process deferred artwork size change when done switching
        if (!spe.isSwitchingPhotos && deferResetViewport) {
            resetProxyViewport()
        }
    }

    override fun onStop() {
        super.onStop()
        overflowMenu.dismiss()
        ArtDetailOpenLiveData.value = false
    }

    @Subscribe
    fun onEventMainThread(e: ArtworkLoadingStateChangedEvent) {
        artworkLoading = e.isLoading
        artworkLoadingError = e.hadError()
        if (!artworkLoading) {
            nextFakeLoading = false
            if (!artworkLoadingError) {
                consecutiveLoadErrorCount = 0
            }
        }

        // Artwork no longer loading, update the visibility of the next button
        nextButton.isVisible = supportsNextArtwork && !artworkLoading

        updateLoadingSpinnerAndErrorVisibility()
    }

    private fun showNextFakeLoading() {
        nextFakeLoading = true
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away. See onEventMainThread(ArtworkLoadingStateChangedEvent)
        handler.removeCallbacks(unsetNextFakeLoading)
        handler.postDelayed(unsetNextFakeLoading, 10000)
        updateLoadingSpinnerAndErrorVisibility()
    }

    private fun updateLoadingSpinnerAndErrorVisibility() {
        val showLoadingSpinner = artworkLoading || nextFakeLoading
        val showError = !showLoadingSpinner && artworkLoadingError

        if (showLoadingSpinner != loadingSpinnerShown) {
            loadingSpinnerShown = showLoadingSpinner
            handler.removeCallbacks(this.showLoadingSpinner)
            if (showLoadingSpinner) {
                handler.postDelayed(this.showLoadingSpinner, 700)
            } else {
                loadingContainerView.animate()
                        .alpha(0f)
                        .setDuration(1000)
                        .withEndAction {
                            loadingContainerView.isGone = true
                            loadingIndicatorView.stop()
                        }
            }
        }

        if (showError != loadErrorShown) {
            loadErrorShown = showError
            handler.removeCallbacks(showLoadError)
            if (showError) {
                handler.postDelayed(showLoadError, 700)
            } else {
                loadErrorContainerView.animate()
                        .alpha(0f)
                        .setDuration(1000)
                        .withEndAction { loadErrorContainerView.isGone = true }
            }
        }
    }
}
