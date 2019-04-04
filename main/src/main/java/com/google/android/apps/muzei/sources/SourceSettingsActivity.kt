/*
 * Copyright 2018 Google Inc.
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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.observe
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Source
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

/**
 * Activity for allowing the user to choose the active source.
 */
class SourceSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SourceSettings"

        private const val ALPHA_DISABLED = 0.2f
        private const val ALPHA_DEFAULT = 1.0f

        private const val REQUEST_EXTENSION_SETUP = 1
        private const val CURRENT_INITIAL_SETUP_SOURCE = "currentInitialSetupSource"
    }

    private val sourcesLiveData: LiveData<List<Source>> by lazy {
        MuzeiDatabase.getInstance(this).sourceDao().sources
    }

    private val adapter by lazy {
        SourceListAdapter(this)
    }

    private val itemImageSize: Int by lazy {
        resources.getDimensionPixelSize(R.dimen.choose_source_item_image_size)
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

    private var currentInitialSetupSource: ComponentName? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            currentInitialSetupSource = savedInstanceState.getParcelable(CURRENT_INITIAL_SETUP_SOURCE)
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_Muzei_Dialog)
                .setTitle(R.string.source_provider_name)
                .setSingleChoiceItems(adapter, -1) { dialog: DialogInterface, which: Int ->
                    adapter.getItem(which)?.source?.let { source ->
                        onSourceSelected(dialog, source)
                    }
                }
                .setPositiveButton(R.string.action_souce_done, null)
                .setOnDismissListener {
                    finish()
                }
                .create()
        sourcesLiveData.observe(this) { sources ->
            if (sources.any { it.selected }) {
                setResult(RESULT_OK)
            }
            val pm = packageManager
            val sourcesViews = sources.asSequence().filterNot { source ->
                source.label.isNullOrEmpty()
            }.mapNotNull { source ->
                try {
                    source to pm.getServiceInfo(source.componentName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }.map { (source, info) ->
                SourceView(source).apply {
                    icon = BitmapDrawable(resources, generateSourceImage(
                            info.loadIcon(pm))).apply {
                        setColorFilter(source.color, PorterDuff.Mode.SRC_ATOP)
                    }
                }
            }.sortedWith(Comparator { sourceView1, sourceView2 ->
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
                    if (packageName == pn1) {
                        return@Comparator -1
                    } else if (packageName == pn2) {
                        return@Comparator 1
                    }
                }
                // These labels should be non-null with the isNullOrEmpty() check above
                val label1 = s1.label
                        ?: throw IllegalStateException("Found null label for ${s1.componentName}")
                val label2 = s2.label
                        ?: throw IllegalStateException("Found null label for ${s2.componentName}")
                label1.compareTo(label2)
            }).toList()
            adapter.clear()
            adapter.addAll(sourcesViews)
            if (!dialog.isShowing) {
                FirebaseAnalytics.getInstance(this).logEvent(FirebaseAnalytics.Event.VIEW_ITEM_LIST,
                        bundleOf(FirebaseAnalytics.Param.ITEM_CATEGORY to "sources"))
                dialog.show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(CURRENT_INITIAL_SETUP_SOURCE, currentInitialSetupSource)
    }

    private fun onSourceSelected(dialog: DialogInterface, source: Source) {
        if (source.selected) {
            dialog.dismiss()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.targetSdkVersion >= Build.VERSION_CODES.O) {
            val builder = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.action_source_target_too_high_title)
                    .setMessage(getString(R.string.action_source_target_too_high_message, source.label))
                    .setNegativeButton(R.string.action_source_target_too_high_learn_more) { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://medium.com/@ianhlake/the-muzei-plugin-api-and-androids-evolution-9b9979265cfb"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .setPositiveButton(R.string.action_source_target_too_high_dismiss, null)
            val sendFeedbackIntent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${source.componentName.packageName}"))
            if (sendFeedbackIntent.resolveActivity(packageManager) != null) {
                builder.setNeutralButton(
                        getString(R.string.action_source_target_too_high_send_feedback, source.label)
                ) { _, _ -> startActivity(sendFeedbackIntent) }
            }
            builder.show()
        } else if (source.setupActivity != null) {
            FirebaseAnalytics.getInstance(this).logEvent(FirebaseAnalytics.Event.VIEW_ITEM,
                    bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to source.componentName.flattenToShortString(),
                            FirebaseAnalytics.Param.ITEM_NAME to source.label,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "sources"))
            currentInitialSetupSource = source.componentName
            launchSourceSetup(source)
        } else {
            FirebaseAnalytics.getInstance(this).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                    FirebaseAnalytics.Param.ITEM_ID to source.componentName.flattenToShortString(),
                    FirebaseAnalytics.Param.ITEM_NAME to source.label,
                    FirebaseAnalytics.Param.ITEM_CATEGORY to "sources",
                    FirebaseAnalytics.Param.CONTENT_TYPE to "choose"))
            GlobalScope.launch {
                SourceManager.selectSource(this@SourceSettingsActivity, source.componentName)
            }
        }
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
                FirebaseAnalytics.getInstance(this).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                        FirebaseAnalytics.Param.ITEM_ID to setupSource.flattenToShortString(),
                        FirebaseAnalytics.Param.CONTENT_TYPE to "sources",
                        FirebaseAnalytics.Param.CONTENT_TYPE to "after_setup"))
                GlobalScope.launch {
                    SourceManager.selectSource(this@SourceSettingsActivity, setupSource)
                }
            }

            currentInitialSetupSource = null
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
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

    internal inner class SourceListAdapter(
            context: Context
    ) : ArrayAdapter<SourceView>(context, R.layout.choose_source_item, R.id.choose_source_title) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val sourceView = getItem(position) ?: return view
            return view.apply {
                val textView: TextView = findViewById(R.id.choose_source_title)
                textView.text = sourceView.toCharSequence()
                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        sourceView.source.targetSdkVersion >= Build.VERSION_CODES.O) {
                    ALPHA_DISABLED
                } else {
                    ALPHA_DEFAULT
                }
                if (sourceView.source.selected) {
                    selectedSourceImage.setColorFilter(
                            sourceView.source.color, PorterDuff.Mode.SRC_ATOP)
                    textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            selectedSourceImage,null, null, null)

                } else {
                    textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            sourceView.icon,null, null, null)
                }
                findViewById<View>(R.id.choose_source_settings).apply {
                    val show = sourceView.source.selected &&
                            sourceView.source.settingsActivity != null
                    val wasVisible = isVisible
                    isVisible = show
                    if (!wasVisible && show) {
                        if (rotation == 0f) {
                            rotation = -90f
                        }
                        animate()
                                .rotation(0f)
                                .setDuration(300L)
                                .withLayer()
                    }
                    setOnClickListener {
                        launchSourceSettings(sourceView.source)
                    }
                }
            }
        }
    }

    internal inner class SourceView(var source: Source) {
        lateinit var icon: Drawable
        var animation: ViewPropertyAnimator? = null

        fun toCharSequence() = buildSpannedString {
            bold { append(source.label) }
            if (source.displayDescription != null) {
                append("\n")
                append(source.displayDescription)
            }
        }
    }
}
