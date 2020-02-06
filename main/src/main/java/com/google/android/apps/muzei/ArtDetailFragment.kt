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

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.legacy.LegacySourceServiceProtocol
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver
import com.google.android.apps.muzei.render.ArtworkSizeLiveData
import com.google.android.apps.muzei.render.ContentUriImageLoader
import com.google.android.apps.muzei.render.SwitchingPhotosDone
import com.google.android.apps.muzei.render.SwitchingPhotosInProgress
import com.google.android.apps.muzei.render.SwitchingPhotosLiveData
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.room.sendAction
import com.google.android.apps.muzei.settings.AboutActivity
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.android.apps.muzei.widget.showWidgetPreview
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.BuildConfig.LEGACY_AUTHORITY
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.ArtDetailFragmentBinding

object ArtDetailOpenLiveData : MutableLiveData<Boolean>()

private fun TextView.setTextOrGone(text: String?) {
    if (text?.isNotEmpty() == true) {
        this.text = text
        isVisible = true
    } else {
        isGone = true
    }
}

class ArtDetailFragment : Fragment(R.layout.art_detail_fragment), (Boolean) -> Unit {

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

    private lateinit var binding: ArtDetailFragmentBinding
    private val overflowSourceActionMap = SparseIntArray()
    private var loadingSpinnerShown = false
    private var showFakeLoading = false
    private var showChrome = true
    private var backgroundImageViewState: ImageViewState? = null
    private val currentProviderLiveData: LiveData<Provider?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).providerDao().currentProvider
    }
    private val currentArtworkLiveData: LiveData<Artwork?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).artworkDao().currentArtwork
    }

    private var unsetNextFakeLoading: Job? = null
    private var showLoadingSpinner: Job? = null

    init {
        lifecycleScope.launchWhenResumed {
            NewWallpaperNotificationReceiver.markNotificationRead(requireContext())
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding.scrim.background = makeCubicGradientScrimDrawable(Gravity.TOP, 0x44)
        }

        val scrimColor = resources.getInteger(R.integer.scrim_channel_color)
        binding.chromeContainer.background = makeCubicGradientScrimDrawable(Gravity.BOTTOM, 0xAA,
                scrimColor, scrimColor, scrimColor)

        view.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0
            animateChromeVisibility(visible)
        }

        binding.title.typeface = ResourcesCompat.getFont(requireContext(), R.font.alegreya_sans_black)
        binding.byline.typeface = ResourcesCompat.getFont(requireContext(), R.font.alegreya_sans_medium)

        binding.overflowMenu.overflowIcon = ContextCompat.getDrawable(requireContext(),
                R.drawable.ic_overflow)
        binding.overflowMenu.setOnMenuItemClickListener { menuItem ->
            val context = context ?: return@setOnMenuItemClickListener false
            val id = overflowSourceActionMap.get(menuItem.itemId)
            if (id > 0) {
                currentArtworkLiveData.value?.run {
                    GlobalScope.launch {
                        if (id == LegacySourceServiceProtocol.LEGACY_COMMAND_ID_NEXT_ARTWORK) {
                            FirebaseAnalytics.getInstance(context).logEvent("next_artwork", bundleOf(
                                    FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
                        } else {
                            FirebaseAnalytics.getInstance(context).logEvent(
                                    FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                                    FirebaseAnalytics.Param.ITEM_ID to id,
                                    FirebaseAnalytics.Param.ITEM_NAME to menuItem.title,
                                    FirebaseAnalytics.Param.ITEM_CATEGORY to "actions",
                                    FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
                        }
                        sendAction(context, id)
                    }
                }
                return@setOnMenuItemClickListener true
            }

            return@setOnMenuItemClickListener when (menuItem.itemId) {
                R.id.action_gestures -> {
                    FirebaseAnalytics.getInstance(context).logEvent("gestures_open", null)
                    findNavController().navigate(ArtDetailFragmentDirections.gestures())
                    true
                }
                R.id.action_always_dark -> {
                    val alwaysDark = !menuItem.isChecked
                    menuItem.isChecked = alwaysDark
                    FirebaseAnalytics.getInstance(context).logEvent("always_dark", bundleOf(
                            FirebaseAnalytics.Param.CONTENT_TYPE to alwaysDark.toString()))
                    MuzeiApplication.setAlwaysDark(context, alwaysDark)
                    true
                }
                R.id.action_about -> {
                    FirebaseAnalytics.getInstance(context).logEvent("about_open", null)
                    startActivity(Intent(context, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.nextArtwork.setOnClickListener {
            FirebaseAnalytics.getInstance(requireContext()).logEvent("next_artwork", bundleOf(
                    FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
            ProviderManager.getInstance(requireContext()).nextArtwork()
            showFakeLoading()
        }
        TooltipCompat.setTooltipText(binding.nextArtwork, binding.nextArtwork.contentDescription)

        backgroundImageViewState = savedInstanceState?.getSerializable(
                KEY_IMAGE_VIEW_STATE) as ImageViewState?
        binding.backgroundImageContainer.isVisible = showBackgroundImage
        binding.backgroundImageContainer.children.forEachIndexed { index, img ->
            val backgroundImage = img as SubsamplingScaleImageView
            backgroundImage.apply {
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
                setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() {
                        // Only update the displayedChild when the image has finished loading
                        binding.backgroundImageContainer.displayedChild = index
                    }
                })
                setOnClickListener {
                    showChrome = !showChrome
                    animateChromeVisibility(showChrome)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setOnLongClickListener {
                        lifecycleScope.launch {
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
                val window = activity?.window
                if (window != null) {
                    showHideChrome(window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                onLongPress = {
                    lifecycleScope.launch {
                        showWidgetPreview(requireContext().applicationContext)
                    }
                }
            }
        }

        WallpaperSizeLiveData.observe(viewLifecycleOwner) { size ->
            wallpaperAspectRatio = if (size.height > 0) {
                size.width * 1f / size.height
            } else {
                binding.panScaleProxy.width * 1f / binding.panScaleProxy.height
            }
            resetProxyViewport()
        }

        ArtworkSizeLiveData.observe(viewLifecycleOwner) { size ->
            artworkAspectRatio = size.width * 1f / size.height
            resetProxyViewport()
        }

        ArtDetailViewport.addObserver(this)

        SwitchingPhotosLiveData.observe(viewLifecycleOwner) { switchingPhotos ->
            currentViewportId = switchingPhotos.viewportId
            binding.panScaleProxy.panScaleEnabled = switchingPhotos is SwitchingPhotosDone
            // Process deferred artwork size change when done switching
            if (switchingPhotos is SwitchingPhotosDone && deferResetViewport) {
                resetProxyViewport()
            }
        }

        currentProviderLiveData.observe(viewLifecycleOwner) { provider ->
            val supportsNextArtwork = provider?.supportsNextArtwork == true
            binding.nextArtwork.isVisible = supportsNextArtwork
        }

        currentArtworkLiveData.observe(viewLifecycleOwner) { currentArtwork ->
            binding.title.setTextOrGone(currentArtwork?.title)
            binding.byline.setTextOrGone(currentArtwork?.byline)
            binding.attribution.setTextOrGone(currentArtwork?.attribution)

            binding.metadata.setOnClickListener {
                val context = requireContext()
                lifecycleScope.launch {
                    FirebaseAnalytics.getInstance(context).logEvent("artwork_info_open", bundleOf(
                            FirebaseAnalytics.Param.CONTENT_TYPE to "art_detail"))
                    currentArtworkLiveData.value?.openArtworkInfo(context)
                }
            }

            if (binding.backgroundImageContainer.isVisible) {
                lifecycleScope.launch {
                    val nextId = (binding.backgroundImageContainer.displayedChild + 1) % 2
                    val orientation = withContext(Dispatchers.IO) {
                        ContentUriImageLoader(requireContext().contentResolver,
                                MuzeiContract.Artwork.CONTENT_URI).getRotation()
                    }
                    val backgroundImage = binding.backgroundImageContainer[nextId]
                            as SubsamplingScaleImageView
                    backgroundImage.orientation = orientation
                    backgroundImage.setImage(ImageSource.uri(MuzeiContract.Artwork.CONTENT_URI),
                            backgroundImageViewState)
                    backgroundImageViewState = null
                    // Set the image to visible since SubsamplingScaleImageView does some of
                    // its processing in onDraw()
                    backgroundImage.isVisible = true
                }
            }

            lifecycleScope.launch(Dispatchers.Main) {
                val commands = context?.run {
                    currentArtwork?.getCommands(this) ?: run {
                        if (currentProviderLiveData.value?.authority == LEGACY_AUTHORITY) {
                            listOf(UserCommand(
                                    LegacySourceServiceProtocol.LEGACY_COMMAND_ID_NEXT_ARTWORK,
                                    getString(R.string.action_next_artwork)))
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
                commands.take(SOURCE_ACTION_IDS.size).forEachIndexed { i, action ->
                    overflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action.id)
                    val menuItem = binding.overflowMenu.menu.add(0, SOURCE_ACTION_IDS[i],
                            0, action.title)
                    if (action.id == LegacySourceServiceProtocol.LEGACY_COMMAND_ID_NEXT_ARTWORK &&
                            currentProviderLiveData.value?.authority == LEGACY_AUTHORITY) {
                        menuItem.setIcon(R.drawable.ic_skip)
                        menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                }
            }
            showFakeLoading = false
            updateLoadingSpinnerVisibility()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null) {
            val backgroundImage =
                    binding.backgroundImageContainer[binding.backgroundImageContainer.displayedChild]
                    as SubsamplingScaleImageView
            outState.putSerializable(KEY_IMAGE_VIEW_STATE, backgroundImage.state)
        }
    }

    override fun onStart() {
        super.onStart()
        ArtDetailOpenLiveData.value = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ArtDetailViewport.removeObserver(this)
    }

    private fun showHideChrome(show: Boolean) {
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
        binding.scrim.visibility = View.VISIBLE
        binding.scrim.animate()
                .alpha(if (visible) 1f else 0f)
                .setDuration(200)
                .withEndAction {
                    if (!visible) {
                        binding.scrim.visibility = View.GONE
                    }
                }

        binding.chromeContainer.isVisible = true
        binding.chromeContainer.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else metadataSlideDistance)
                .setDuration(200)
                .withEndAction {
                    if (!visible) {
                        binding.chromeContainer.isGone = true
                    }
                }
    }

    private fun resetProxyViewport() {
        if (wallpaperAspectRatio == 0f || artworkAspectRatio == 0f) {
            return
        }

        deferResetViewport = false
        if (SwitchingPhotosLiveData.value is SwitchingPhotosInProgress) {
            deferResetViewport = true
            return
        }

        binding.panScaleProxy.relativeAspectRatio = artworkAspectRatio / wallpaperAspectRatio
    }

    override fun invoke(isFromUser: Boolean) {
        if (!isFromUser) {
            guardViewportChangeListener = true
            binding.panScaleProxy.setViewport(ArtDetailViewport.getViewport(currentViewportId))
            guardViewportChangeListener = false
        }
    }

    override fun onStop() {
        super.onStop()
        binding.overflowMenu.hideOverflowMenu()
        ArtDetailOpenLiveData.value = false
    }

    private fun showFakeLoading() {
        showFakeLoading = true
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away.
        updateLoadingSpinnerVisibility()
        unsetNextFakeLoading?.cancel()
        unsetNextFakeLoading = lifecycleScope.launch(Dispatchers.Main) {
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
                this.showLoadingSpinner = lifecycleScope.launch(Dispatchers.Main) {
                    delay(700)
                    binding.imageLoadingIndicator.start()
                    binding.imageLoadingContainer.isVisible = true
                    binding.imageLoadingContainer.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .withEndAction(null)
                }
            } else {
                binding.imageLoadingContainer.animate()
                        .alpha(0f)
                        .setDuration(1000)
                        .withEndAction {
                            binding.imageLoadingContainer.isGone = true
                            binding.imageLoadingIndicator.stop()
                        }
            }
        }
    }
}
