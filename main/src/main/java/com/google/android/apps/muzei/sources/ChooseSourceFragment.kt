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

package com.google.android.apps.muzei.sources

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.arch.lifecycle.LiveData
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.support.v4.app.Fragment
import android.support.v4.content.res.ResourcesCompat
import android.support.v7.app.AlertDialog
import android.support.v7.widget.TooltipCompat
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import android.arch.lifecycle.Observer
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Source
import com.google.android.apps.muzei.util.ObservableHorizontalScrollView
import com.google.android.apps.muzei.util.Scrollbar
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R
import java.util.*

/**
 * Activity for allowing the user to choose the active source.
 */
class ChooseSourceFragment : Fragment() {

    companion object {
        private const val TAG = "SettingsChooseSourceFrg"

        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"

        private const val SCROLLBAR_HIDE_DELAY_MILLIS = 1000L

        private const val ALPHA_DISABLED = 0.2f
        private const val ALPHA_UNSELECTED = 0.4f

        private const val REQUEST_EXTENSION_SETUP = 1
        private const val CURRENT_INITIAL_SETUP_SOURCE = "currentInitialSetupSource"
    }

    private var selectedSource: ComponentName? = null
    private val currentSourceLiveData: LiveData<Source?> by lazy {
        MuzeiDatabase.getInstance(context).sourceDao().currentSource
    }
    private val sourceViews = ArrayList<SourceView>()
    private val sourcesLiveData : LiveData<List<Source>> by lazy {
        MuzeiDatabase.getInstance(context).sourceDao().sources
    }

    private val handler = Handler()

    private lateinit var sourceContainerView: ViewGroup
    private lateinit var sourceScrollerView: ObservableHorizontalScrollView
    private lateinit var scrollbar: Scrollbar
    private var animateScroll : Boolean = true
    private var currentScroller: ObjectAnimator? = null

    private val animationDuration: Long by lazy {
        resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
    }
    private val itemWidth: Int by lazy {
        resources.getDimensionPixelSize(R.dimen.settings_choose_source_item_width)
    }
    private val itemImageSize: Int by lazy {
        resources.getDimensionPixelSize(R.dimen.settings_choose_source_item_image_size)
    }
    private val itemEstimatedHeight: Int by lazy {
        resources.getDimensionPixelSize(R.dimen.settings_choose_source_item_estimated_height)
    }

    private val tempRectF = RectF()
    private val imageFillPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private val alphaPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val selectedSourceImage: Drawable by lazy {
        BitmapDrawable(resources,
                generateSourceImage(ResourcesCompat.getDrawable(resources,
                        R.drawable.ic_source_selected, null)))
    }
    private var selectedSourceIndex: Int = -1

    private var currentInitialSetupSource: ComponentName? = null

    private val hideScrollbarRunnable = Runnable { scrollbar.hide() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        if (savedInstanceState != null) {
            currentInitialSetupSource = savedInstanceState.getParcelable(CURRENT_INITIAL_SETUP_SOURCE)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        val bundle = Bundle()
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources")
        FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.VIEW_ITEM_LIST, bundle)

        sourcesLiveData.observe(this, Observer { sources ->
            run {
                if (sources != null) {
                    updateSources(sources)
                    updatePadding()
                }
            }
        })

        currentSourceLiveData.observe(this, Observer { source ->
            updateSelectedItem(source, true)
        })

        if (activity?.intent?.categories?.contains(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES) == true) {
            FirebaseAnalytics.getInstance(context).logEvent("notification_preferences_open", null)
            NotificationSettingsDialogFragment.showSettings(this)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_choose_source, menu)
    }

    @SuppressLint("InlinedApi")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_notification_settings -> {
                NotificationSettingsDialogFragment.showSettings(this)
                return true
            }
            R.id.action_get_more_sources -> {
                FirebaseAnalytics.getInstance(requireContext()).logEvent("more_sources_open", null)
                try {
                    val playStoreIntent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    preferPackageForIntent(playStoreIntent, PLAY_STORE_PACKAGE_NAME)
                    startActivity(playStoreIntent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context,
                            R.string.play_store_not_found, Toast.LENGTH_LONG).show()
                } catch (e: SecurityException) {
                    Toast.makeText(context, R.string.play_store_not_found, Toast.LENGTH_LONG).show()
                }

                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun preferPackageForIntent(intent: Intent, packageName: String) {
        val pm = requireContext().packageManager
        for (resolveInfo in pm.queryIntentActivities(intent, 0)) {
            if (resolveInfo.activityInfo.packageName == packageName) {
                intent.`package` = packageName
                break
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(
                R.layout.settings_choose_source_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        @Suppress("DEPRECATION")
        view.requestFitSystemWindows()

        scrollbar = view.findViewById(R.id.source_scrollbar)
        sourceScrollerView = view.findViewById(R.id.source_scroller)
        sourceScrollerView.callbacks = object : ObservableHorizontalScrollView.Callbacks {
            override fun onScrollChanged(scrollX: Int) {
                showScrollbar()
            }

            override fun onDownMotionEvent() {
                if (currentScroller != null) {
                    currentScroller?.cancel()
                    currentScroller = null
                }
            }
        }
        sourceContainerView = view.findViewById(R.id.source_container)

        val sources = sourcesLiveData.value
        if (sources != null) {
            updateSources(sources)
        }

        view.visibility = View.INVISIBLE
        view.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    internal var mPass = 0

                    override fun onGlobalLayout() {
                        when {
                            mPass == 0 -> {
                                // First pass
                                updatePadding()
                                ++mPass
                            }
                            (mPass == 1) and (selectedSourceIndex >= 0) -> {
                                // Second pass
                                sourceScrollerView.scrollX = itemWidth * selectedSourceIndex
                                showScrollbar()
                                view.visibility = View.VISIBLE
                                ++mPass
                            }
                            else -> // Last pass, remove the listener
                                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                })

        view.alpha = 0f
        view.animate().alpha(1f).duration = 500
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(CURRENT_INITIAL_SETUP_SOURCE, currentInitialSetupSource)
    }

    private fun updatePadding() {
        val rootViewWidth = view?.width ?: 0
        val rootViewHeight = view?.height ?: 0
        if (rootViewWidth == 0) {
            return
        }
        val topPadding = Math.max(0, (rootViewHeight - itemEstimatedHeight) / 2)
        val numItems = sourceViews.size
        val sidePadding: Int
        val minSidePadding = resources.getDimensionPixelSize(
                R.dimen.settings_choose_source_min_side_padding)
        sidePadding = if (minSidePadding * 2 + itemWidth * numItems < rootViewWidth) {
            // Don't allow scrolling since all items can be visible. Center the entire
            // list by using just the right amount of padding to center it.
            (rootViewWidth - itemWidth * numItems) / 2 - 1
        } else {
            // Allow scrolling
            Math.max(0, (rootViewWidth - itemWidth) / 2)
        }
        sourceContainerView.setPadding(sidePadding, topPadding, sidePadding, 0)
    }

    private fun updateSelectedItem(newSelectedSource: Source?, allowAnimate: Boolean) {
        val previousSelectedSource = selectedSource
        selectedSource = newSelectedSource?.componentName
        if (previousSelectedSource != null && previousSelectedSource == selectedSource) {
            // Only update status
            sourceViews
                    .filter { it.source.componentName == selectedSource }
                    .forEach { updateSourceStatusUi(it) }
            return
        }

        // This is a newly selected source.
        var selected: Boolean
        var index = -1
        for (sourceView in sourceViews) {
            ++index
            if (sourceView.source.componentName == previousSelectedSource) {
                selected = false
            } else if (sourceView.source.componentName == selectedSource) {
                selectedSourceIndex = index
                selected = true
            } else {
                continue
            }

            val sourceImageButton = sourceView.rootView.findViewById<View>(R.id.source_image)
            val drawable = if (selected) selectedSourceImage else sourceView.icon
            drawable.setColorFilter(sourceView.source.color, PorterDuff.Mode.SRC_ATOP)
            sourceImageButton.background = drawable

            val alpha = if (selected)
                1f
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sourceView.source.targetSdkVersion >= Build.VERSION_CODES.O)
                ALPHA_DISABLED
            else
                ALPHA_UNSELECTED
            sourceView.rootView.animate()
                    .alpha(alpha).duration = animationDuration

            if (selected) {
                updateSourceStatusUi(sourceView)
            }

            animateSettingsButton(sourceView.settingsButton,
                    selected && sourceView.source.settingsActivity != null, allowAnimate)
        }

        if (selectedSourceIndex >= 0 && animateScroll) {
            currentScroller?.cancel()

            // For some reason smoothScrollTo isn't very smooth..
            currentScroller = ObjectAnimator.ofInt(sourceScrollerView, "scrollX",
                    itemWidth * selectedSourceIndex).apply {
                duration = animationDuration
                start()
            }
            animateScroll = false
        }
    }

    private fun animateSettingsButton(settingsButton: View, show: Boolean,
                                      allowAnimate: Boolean) {
        if (show && settingsButton.visibility == View.VISIBLE || !show && settingsButton.visibility == View.INVISIBLE) {
            return
        }
        settingsButton.visibility = View.VISIBLE
        settingsButton.animate()
                .translationY((if (show)
                    0
                else
                    -resources.getDimensionPixelSize(
                            R.dimen.settings_choose_source_settings_button_animate_distance)).toFloat())
                .alpha(if (show) 1f else 0f)
                .rotation((if (show) 0f else -90f))
                .setDuration((if (allowAnimate) 300L else 0L))
                .setStartDelay((if (show && allowAnimate) 200L else 0L))
                .withLayer()
                .withEndAction {
                    if (!show) {
                        settingsButton.visibility = View.INVISIBLE
                    }
                }
    }

    override fun onDestroy() {
        super.onDestroy()
        // remove all scheduled runnables
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateSources(sources: List<Source>) {
        selectedSource = null
        val pm = requireContext().packageManager
        sourceViews.clear()

        for (source in sources) {
            // Skip Sources without a label
            if (TextUtils.isEmpty(source.label)) {
                continue
            }
            val sourceView = SourceView(source)
            val info: ServiceInfo
            try {
                info = pm.getServiceInfo(source.componentName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }

            sourceView.icon = BitmapDrawable(resources, generateSourceImage(info.loadIcon(pm)))
            sourceViews.add(sourceView)
        }

        val appPackage = requireContext().packageName
        sourceViews.sortWith(Comparator { sourceView1, sourceView2 ->
            val s1 = sourceView1.source
            val s2 = sourceView2.source
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val target1IsO = s1.targetSdkVersion >= Build.VERSION_CODES.O
                val target2IsO = s2.targetSdkVersion >= Build.VERSION_CODES.O
                if (target1IsO && !target2IsO) {
                    return@Comparator 1
                } else if (!target1IsO && target2IsO) {
                    return@Comparator -1
                }
            }
            val pn1 = s1.componentName.packageName
            val pn2 = s2.componentName.packageName
            if (pn1 != pn2) {
                if (appPackage == pn1) {
                    return@Comparator -1
                } else if (appPackage == pn2) {
                    return@Comparator 1
                }
            }
            s1.label.compareTo(s2.label)
        })

        sourceContainerView.removeAllViews()
        for (sourceView in sourceViews) {
            sourceView.rootView = layoutInflater.inflate(
                    R.layout.settings_choose_source_item, sourceContainerView, false)

            sourceView.selectSourceButton = sourceView.rootView.findViewById(R.id.source_image)
            val source = sourceView.source
            sourceView.selectSourceButton.setOnClickListener {
                if (source.componentName == selectedSource) {
                    if (context is Callbacks) {
                        (context as Callbacks).onRequestCloseActivity()
                    } else if (parentFragment is Callbacks) {
                        (parentFragment as Callbacks).onRequestCloseActivity()
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.targetSdkVersion >= Build.VERSION_CODES.O) {
                    if (isStateSaved || isRemoving) {
                        return@setOnClickListener
                    }
                    val builder = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.action_source_target_too_high_title)
                            .setMessage(getString(R.string.action_source_target_too_high_message, source.label))
                            .setNegativeButton(R.string.action_source_target_too_high_learn_more
                            ) { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://medium.com/@ianhlake/the-muzei-plugin-api-and-androids-evolution-9b9979265cfb")))
                            }
                            .setPositiveButton(R.string.action_source_target_too_high_dismiss, null)
                    val sendFeedbackIntent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=${source.componentName.packageName}"))
                    if (sendFeedbackIntent.resolveActivity(requireContext().packageManager) != null) {
                        builder.setNeutralButton(
                                getString(R.string.action_source_target_too_high_send_feedback, source.label)
                        ) { _, _ -> startActivity(sendFeedbackIntent) }
                    }
                    builder.show()
                } else if (source.setupActivity != null) {
                    val bundle = Bundle()
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, source.componentName.flattenToShortString())
                    bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, source.label)
                    bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources")
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle)
                    currentInitialSetupSource = source.componentName
                    launchSourceSetup(source)
                } else {
                    val bundle = Bundle()
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, source.componentName.flattenToShortString())
                    bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources")
                    FirebaseAnalytics.getInstance(requireContext()).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
                    SourceManager.selectSource(requireContext(), source.componentName)
                }
            }

            sourceView.selectSourceButton.setOnLongClickListener {
                val pkg = source.componentName.packageName
                if (TextUtils.equals(pkg, requireContext().packageName)) {
                    // Don't open Muzei's app info
                    return@setOnLongClickListener false
                }
                // Otherwise open third party extensions
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", pkg, null)))
                } catch (e: ActivityNotFoundException) {
                    return@setOnLongClickListener false
                }

                true
            }

            val alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.targetSdkVersion >= Build.VERSION_CODES.O)
                ALPHA_DISABLED
            else
                ALPHA_UNSELECTED
            sourceView.rootView.alpha = alpha

            sourceView.icon.setColorFilter(source.color, PorterDuff.Mode.SRC_ATOP)
            sourceView.selectSourceButton.background = sourceView.icon

            val titleView = sourceView.rootView.findViewById<TextView>(R.id.source_title)
            titleView.text = source.label
            titleView.setTextColor(source.color)

            updateSourceStatusUi(sourceView)

            sourceView.settingsButton = sourceView.rootView.findViewById(R.id.source_settings_button)
            TooltipCompat.setTooltipText(sourceView.settingsButton, sourceView.settingsButton.contentDescription)
            sourceView.settingsButton.setOnClickListener { launchSourceSettings(source) }

            animateSettingsButton(sourceView.settingsButton, false, false)

            sourceContainerView.addView(sourceView.rootView)
        }

        updateSelectedItem(currentSourceLiveData.value, false)
    }

    private fun launchSourceSettings(source: Source) {
        try {
            val settingsIntent = Intent()
                    .setComponent(source.settingsActivity)
                    .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true)
            startActivity(settingsIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch source settings.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch source settings.", e)
        }

    }

    private fun launchSourceSetup(source: Source) {
        try {
            val setupIntent = Intent()
                    .setComponent(source.setupActivity)
                    .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true)
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch source setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch source setup.", e)
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_EXTENSION_SETUP) {
            val setupSource = currentInitialSetupSource
            if (resultCode == Activity.RESULT_OK && setupSource != null) {
                val bundle = Bundle()
                bundle.putString(FirebaseAnalytics.Param.ITEM_ID, setupSource.flattenToShortString())
                bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources")
                FirebaseAnalytics.getInstance(requireContext()).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
                SourceManager.selectSource(requireContext(), setupSource)
            }

            currentInitialSetupSource = null
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun updateSourceStatusUi(sourceView: SourceView) {
        (sourceView.rootView.findViewById<View>(R.id.source_status) as TextView).text =
                sourceView.source.getDescription()
    }

    private fun generateSourceImage(image: Drawable?): Bitmap {
        val bitmap = Bitmap.createBitmap(itemImageSize, itemImageSize,
                Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        tempRectF.set(0f, 0f, itemImageSize.toFloat(), itemImageSize.toFloat())
        canvas.drawOval(tempRectF, imageFillPaint)
        if (image != null) {
            @Suppress("DEPRECATION")
            canvas.saveLayer(0f, 0f, itemImageSize.toFloat(), itemImageSize.toFloat(), alphaPaint,
                    Canvas.ALL_SAVE_FLAG)
            image.setBounds(0, 0, itemImageSize, itemImageSize)
            image.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }

    private fun showScrollbar() {
        handler.removeCallbacks(hideScrollbarRunnable)
        scrollbar.setScrollRangeAndViewportWidth(
                sourceScrollerView.computeHorizontalScrollRange(),
                sourceScrollerView.width)
        scrollbar.setScrollPosition(sourceScrollerView.scrollX)
        scrollbar.show()
        handler.postDelayed(hideScrollbarRunnable, SCROLLBAR_HIDE_DELAY_MILLIS)
    }

    internal inner class SourceView(var source: Source) {
        lateinit var rootView: View
        lateinit var icon: Drawable
        lateinit var selectSourceButton: View
        lateinit var settingsButton: View
    }

    interface Callbacks {
        fun onRequestCloseActivity()
    }
}
