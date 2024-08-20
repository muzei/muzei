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

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.app.RemoteActionCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.api.internal.RemoteActionBroadcastReceiver
import com.google.android.apps.muzei.legacy.BuildConfig.LEGACY_AUTHORITY
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver
import com.google.android.apps.muzei.render.ArtworkSizeStateFlow
import com.google.android.apps.muzei.render.ContentUriImageLoader
import com.google.android.apps.muzei.render.SwitchingPhotosDone
import com.google.android.apps.muzei.render.SwitchingPhotosInProgress
import com.google.android.apps.muzei.render.SwitchingPhotosStateFlow
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.settings.AboutActivity
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.autoCleared
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.android.apps.muzei.util.sendFromBackground
import com.google.android.apps.muzei.widget.showWidgetPreview
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.ArtDetailFragmentBinding
import net.nurik.roman.muzei.androidclientcommon.R as CommonR

val ArtDetailOpen = MutableStateFlow(false)

private fun TextView.setTextOrGone(text: String?) {
    if (text?.isNotEmpty() == true) {
        this.text = text
        isVisible = true
    } else {
        isGone = true
    }
}

class ArtDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MuzeiDatabase.getInstance(application)

    val currentProvider = database.providerDao().getCurrentProviderFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val currentArtwork = database.artworkDao().getCurrentArtworkFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)
}

class ArtDetailFragment : Fragment(R.layout.art_detail_fragment) {

    companion object {
        private const val KEY_IMAGE_VIEW_STATE = "IMAGE_VIEW_STATE"
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
    private var guardViewportChangeListener: Boolean = false
    private var deferResetViewport: Boolean = false

    private val showBackgroundImage by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && requireActivity().isInMultiWindowMode
    }
    private val metadataSlideDistance by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
    }

    private var binding: ArtDetailFragmentBinding by autoCleared()
    private val overflowSourceActionMap = SparseArray<RemoteActionCompat>()
    private var loadingSpinnerShown = false
    private var showFakeLoading = false
    private var showChrome = true

    private val viewModel: ArtDetailViewModel by viewModels()

    private var unsetNextFakeLoading: Job? = null
    private var showLoadingSpinner: Job? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                NewWallpaperNotificationReceiver.markNotificationRead(requireContext())
            }
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        showHideChrome(true)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = ArtDetailFragmentBinding.bind(view)
        binding.scrim.background = makeCubicGradientScrimDrawable(Gravity.TOP, 0x44)

        val scrimColor = resources.getInteger(R.integer.scrim_channel_color)
        binding.chromeContainer.background = makeCubicGradientScrimDrawable(Gravity.BOTTOM, 0xAA,
                scrimColor, scrimColor, scrimColor)

        @Suppress("DEPRECATION")
        view.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0
            animateChromeVisibility(visible)
        }

        binding.title.typeface = ResourcesCompat.getFont(requireContext(), R.font.alegreya_sans_black)
        binding.byline.typeface = ResourcesCompat.getFont(requireContext(), R.font.alegreya_sans_medium)

        binding.overflowMenu.setOnMenuItemClickListener { menuItem ->
            val context = context ?: return@setOnMenuItemClickListener false
            val action = overflowSourceActionMap.get(menuItem.itemId)
            if (action != null) {
                viewModel.currentArtwork.value?.run {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerAuthority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, menuItem.title.toString())
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "art_detail")
                    }
                    try {
                        action.actionIntent.sendFromBackground()
                    } catch (e: PendingIntent.CanceledException) {
                        // Why do you give us a cancelled PendingIntent.
                        // We can't do anything with that.
                    }
                }
                return@setOnMenuItemClickListener true
            }

            return@setOnMenuItemClickListener when (menuItem.itemId) {
                R.id.action_gestures -> {
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.main_art_details) {
                        Firebase.analytics.logEvent("gestures_open", null)
                        navController.navigate(ArtDetailFragmentDirections.gestures())
                    }
                    true
                }
                R.id.action_always_dark -> {
                    val alwaysDark = !menuItem.isChecked
                    menuItem.isChecked = alwaysDark
                    Firebase.analytics.logEvent("always_dark") {
                        param(FirebaseAnalytics.Param.VALUE, alwaysDark.toString())
                    }
                    MuzeiApplication.setAlwaysDark(context, alwaysDark)
                    true
                }
                R.id.action_about -> {
                    Firebase.analytics.logEvent("about_open", null)
                    startActivity(Intent(context, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.nextArtwork.setOnClickListener {
            Firebase.analytics.logEvent("next_artwork") {
                param(FirebaseAnalytics.Param.CONTENT_TYPE, "art_detail")
            }
            ProviderManager.getInstance(requireContext()).nextArtwork()
            showFakeLoading()
        }
        TooltipCompat.setTooltipText(binding.nextArtwork, binding.nextArtwork.contentDescription)

        // Ensure that when the view state is saved, the SubsamplingScaleImageView also
        // has its state saved
        view.findViewTreeSavedStateRegistryOwner()?.savedStateRegistry
                ?.registerSavedStateProvider(KEY_IMAGE_VIEW_STATE) {
                    val backgroundImage =
                            binding.backgroundImageContainer[binding.backgroundImageContainer.displayedChild]
                                    as SubsamplingScaleImageView
                    Bundle().apply {
                        putSerializable(KEY_IMAGE_VIEW_STATE, backgroundImage.state)
                    }
                }
        binding.backgroundImageContainer.isVisible = showBackgroundImage
        val viewLifecycle = viewLifecycleOwner.lifecycle
        binding.backgroundImageContainer.children.forEachIndexed { index, img ->
            val backgroundImage = img as SubsamplingScaleImageView
            backgroundImage.apply {
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
                setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() {
                        if (viewLifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                            // Only update the displayedChild when the image has finished loading
                            binding.backgroundImageContainer.displayedChild = index
                        }
                    }
                })
                setOnClickListener {
                    showChrome = !showChrome
                    animateChromeVisibility(showChrome)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val scope = viewLifecycleOwner.lifecycleScope
                    setOnLongClickListener {
                        scope.launch {
                            showWidgetPreview(requireContext().applicationContext)
                        }
                        true
                    }
                }
            }
        }

        binding.panScaleProxy.apply {
            // Don't show the PanScaleProxyView when the background image is visible
            isVisible = !showBackgroundImage
            setMaxZoom(5)
            onViewportChanged = {
                if (!guardViewportChangeListener) {
                    ArtDetailViewport.setViewport(
                            currentViewportId, binding.panScaleProxy.currentViewport, true)
                }
            }
            onSingleTapUp = {
                activity?.window?.let {
                    @Suppress("DEPRECATION")
                    showHideChrome(it.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val scope = viewLifecycleOwner.lifecycleScope
                onLongPress = {
                    scope.launch {
                        showWidgetPreview(requireContext().applicationContext)
                    }
                }
            }
        }

        WallpaperSizeStateFlow.filterNotNull().collectIn(viewLifecycleOwner) { size ->
            wallpaperAspectRatio = if (size.height > 0) {
                size.width * 1f / size.height
            } else {
                binding.panScaleProxy.width * 1f / binding.panScaleProxy.height
            }
            resetProxyViewport()
        }

        ArtworkSizeStateFlow.filterNotNull().collectIn(viewLifecycleOwner) { size ->
            artworkAspectRatio = size.width * 1f / size.height
            resetProxyViewport()
        }

        ArtDetailViewport.getChanges().collectIn(viewLifecycleOwner) { isFromUser ->
            if (!isFromUser) {
                guardViewportChangeListener = true
                binding.panScaleProxy.setViewport(ArtDetailViewport.getViewport(currentViewportId))
                guardViewportChangeListener = false
            }
        }

        SwitchingPhotosStateFlow.filterNotNull().collectIn(viewLifecycleOwner) { switchingPhotos ->
            currentViewportId = switchingPhotos.viewportId
            binding.panScaleProxy.panScaleEnabled = switchingPhotos is SwitchingPhotosDone
            // Process deferred artwork size change when done switching
            if (switchingPhotos is SwitchingPhotosDone && deferResetViewport) {
                resetProxyViewport()
            }
        }

        viewModel.currentProvider.collectIn(viewLifecycleOwner) { provider ->
            val supportsNextArtwork = provider?.supportsNextArtwork == true
            binding.nextArtwork.isVisible = supportsNextArtwork
        }

        viewModel.currentArtwork.collectIn(viewLifecycleOwner) { currentArtwork ->
            binding.title.setTextOrGone(currentArtwork?.title)
            binding.byline.setTextOrGone(currentArtwork?.byline)
            binding.attribution.setTextOrGone(currentArtwork?.attribution)

            binding.metadata.setOnClickListener {
                val context = requireContext()
                lifecycleScope.launch {
                    Firebase.analytics.logEvent("artwork_info_open") {
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "art_detail")
                    }
                    viewModel.currentArtwork.value?.openArtworkInfo(context)
                }
            }

            if (binding.backgroundImageContainer.isVisible) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val nextId = (binding.backgroundImageContainer.displayedChild + 1) % 2
                    val orientation = withContext(Dispatchers.IO) {
                        ContentUriImageLoader(requireContext().contentResolver,
                                MuzeiContract.Artwork.CONTENT_URI).getRotation()
                    }
                    val backgroundImage = binding.backgroundImageContainer[nextId]
                            as SubsamplingScaleImageView
                    backgroundImage.orientation = orientation
                    // Try to restore any saved state of the SubsamplingScaleImageView
                    // This would normally only be available after onViewStateRestored(), but
                    // this is within coroutine that is only launched when STARTED
                    @Suppress("DEPRECATION")
                    val backgroundImageViewState = view.findViewTreeSavedStateRegistryOwner()
                            ?.savedStateRegistry
                            ?.consumeRestoredStateForKey(KEY_IMAGE_VIEW_STATE)
                            ?.getSerializable(KEY_IMAGE_VIEW_STATE) as ImageViewState?
                    backgroundImage.setImage(ImageSource.uri(MuzeiContract.Artwork.CONTENT_URI),
                            backgroundImageViewState)
                    // Set the image to visible since SubsamplingScaleImageView does some of
                    // its processing in onDraw()
                    backgroundImage.isVisible = true
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val commands = context?.run {
                    currentArtwork?.getCommands(this) ?: run {
                        if (viewModel.currentProvider.value?.authority == LEGACY_AUTHORITY) {
                            listOf(RemoteActionCompat(
                                    IconCompat.createWithResource(this, CommonR.drawable.ic_next_artwork),
                                    getString(R.string.action_next_artwork),
                                    getString(R.string.action_next_artwork),
                                    RemoteActionBroadcastReceiver.createPendingIntent(
                                            this, LEGACY_AUTHORITY, id.toLong(), id)))
                        } else {
                            listOf()
                        }
                    }
                } ?: return@launch
                val activity = activity ?: return@launch
                overflowSourceActionMap.clear()
                binding.overflowMenu.menu.clear()
                activity.menuInflater.inflate(R.menu.muzei_overflow,
                        binding.overflowMenu.menu)
                binding.overflowMenu.menu.findItem(R.id.action_always_dark)?.isChecked =
                        MuzeiApplication.getAlwaysDark(activity)
                commands.filterNot { action ->
                    action.title.isBlank()
                }.take(SOURCE_ACTION_IDS.size).forEachIndexed { i, action ->
                    overflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action)
                    binding.overflowMenu.menu.add(0, SOURCE_ACTION_IDS[i],
                            0, action.title).apply {
                        isEnabled = action.isEnabled
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            contentDescription = action.contentDescription
                        }
                        if (action.shouldShowIcon()) {
                            icon = action.icon.loadDrawable(activity)
                            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                    }
                }
            }
            showFakeLoading = false
            updateLoadingSpinnerVisibility()
        }
    }

    override fun onStart() {
        super.onStart()
        ArtDetailOpen.value = true
    }

    private fun showHideChrome(show: Boolean) {
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.apply {
            var flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            flags = flags or if (show) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
            flags = flags or (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            if (!show) {
                flags = flags or (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE)
            }
            systemUiVisibility = flags
        }
    }

    private fun animateChromeVisibility(visible: Boolean) {
        with(binding.scrim) {
            visibility = View.VISIBLE
            animate()
                .alpha(if (visible) 1f else 0f)
                .setDuration(200)
                .withEndAction {
                    if (!visible) {
                        visibility = View.GONE
                    }
                }
        }

        with(binding.chromeContainer) {
            isVisible = true
            animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else metadataSlideDistance)
                .setDuration(200)
                .withEndAction {
                    if (!visible) {
                        isGone = true
                    }
                }
        }
    }

    private fun resetProxyViewport() {
        if (wallpaperAspectRatio == 0f || artworkAspectRatio == 0f) {
            return
        }

        deferResetViewport = false
        if (SwitchingPhotosStateFlow.value is SwitchingPhotosInProgress) {
            deferResetViewport = true
            return
        }

        binding.panScaleProxy.relativeAspectRatio = artworkAspectRatio / wallpaperAspectRatio
    }

    override fun onStop() {
        super.onStop()
        binding.overflowMenu.hideOverflowMenu()
        ArtDetailOpen.value = false
    }

    private fun showFakeLoading() {
        showFakeLoading = true
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away.
        updateLoadingSpinnerVisibility()
        unsetNextFakeLoading?.cancel()
        unsetNextFakeLoading = viewLifecycleOwner.lifecycleScope.launch {
            delay(10000)
            showFakeLoading = false
            updateLoadingSpinnerVisibility()
        }
        updateLoadingSpinnerVisibility()
    }

    private fun updateLoadingSpinnerVisibility() {
        if (showFakeLoading != loadingSpinnerShown) {
            loadingSpinnerShown = showFakeLoading
            showLoadingSpinner?.cancel()?.also {
                showLoadingSpinner = null
            }
            if (showFakeLoading) {
                showLoadingSpinner = viewLifecycleOwner.lifecycleScope.launch {
                    delay(700)
                    binding.imageLoadingIndicator.start()
                    binding.imageLoadingContainer.isVisible = true
                    var animator: ViewPropertyAnimator? = null
                    try {
                        animator = binding.imageLoadingContainer.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .withEndAction {
                                showLoadingSpinner = null
                                animator = null
                                cancel()
                            }
                        awaitCancellation()
                    } finally {
                        animator?.cancel()
                    }
                }
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    var animator: ViewPropertyAnimator? = null
                    try {
                        animator = binding.imageLoadingContainer.animate()
                            .alpha(0f)
                            .setDuration(10000)
                            .withEndAction {
                                binding.imageLoadingContainer.isGone = true
                                binding.imageLoadingIndicator.stop()
                                animator = null
                                cancel()
                            }
                        awaitCancellation()
                    } finally {
                        animator?.cancel()
                    }
                }
            }
        }
    }
}