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

@file:Suppress("DEPRECATION")

package com.google.android.apps.muzei.legacy

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
import android.graphics.PorterDuffColorFilter
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
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.util.getParcelableCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.legacy.R
import net.nurik.roman.muzei.legacy.databinding.LegacyChooseSourceItemBinding

private class SourceSetupFromSettings : ActivityResultContract<Source, Boolean>() {
    override fun createIntent(context: Context, input: Source): Intent =
            Intent().setComponent(input.setupActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == Activity.RESULT_OK
}

/**
 * Activity for allowing the user to choose the active source.
 */
class SourceSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SourceSettings"

        private const val ALPHA_DISABLED = 0.2f
        private const val ALPHA_DEFAULT = 1.0f

        private const val CURRENT_INITIAL_SETUP_SOURCE = "currentInitialSetupSource"
    }

    private val adapter by lazy {
        SourceListAdapter(this)
    }

    private val itemImageSize: Int by lazy {
        resources.getDimensionPixelSize(R.dimen.legacy_choose_source_item_image_size)
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
                        R.drawable.legacy_ic_source_selected, null)))
    }

    private var currentInitialSetupSource: ComponentName? = null
    private val sourceSetup = registerForActivityResult(SourceSetupFromSettings()) { success ->
        val setupSource = currentInitialSetupSource
        if (success && setupSource != null) {
            Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                param(FirebaseAnalytics.Param.ITEM_LIST_ID, setupSource.flattenToShortString())
                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "sources")
                param(FirebaseAnalytics.Param.CONTENT_TYPE, "after_setup")
            }
            lifecycleScope.launch(NonCancellable) {
                LegacySourceService.selectSource(this@SourceSettingsActivity, setupSource)
            }
        }

        currentInitialSetupSource = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            currentInitialSetupSource = savedInstanceState.getParcelableCompat(
                CURRENT_INITIAL_SETUP_SOURCE
            )
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.Theme_Legacy_Dialog)
                .setTitle(R.string.legacy_source_provider_name)
                .setSingleChoiceItems(adapter, -1) { dialog: DialogInterface, which: Int ->
                    adapter.getItem(which)?.source?.let { source ->
                        onSourceSelected(dialog, source)
                    }
                }
                .setPositiveButton(R.string.legacy_source_selection_done, null)
                .setOnDismissListener {
                    finish()
                }
                .create()
        val database = LegacyDatabase.getInstance(this)
        database.sourceDao().sources.collectIn(this) { sources ->
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
                        colorFilter = PorterDuffColorFilter(source.color, PorterDuff.Mode.SRC_ATOP)
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
                Firebase.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM_LIST) {
                    param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "sources")
                }
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
                    .setTitle(R.string.legacy_source_target_too_high_title)
                    .setMessage(getString(R.string.legacy_source_target_too_high_message, source.label))
                    .setNegativeButton(R.string.legacy_source_target_too_high_learn_more) { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://medium.com/@ianhlake/the-muzei-plugin-api-and-androids-evolution-9b9979265cfb"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .setPositiveButton(R.string.legacy_source_target_too_high_dismiss, null)
            val sendFeedbackIntent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${source.componentName.packageName}"))
            if (sendFeedbackIntent.resolveActivity(packageManager) != null) {
                builder.setNeutralButton(
                        getString(R.string.legacy_source_target_too_high_send_feedback, source.label)
                ) { _, _ -> startActivity(sendFeedbackIntent) }
            }
            builder.show()
        } else if (source.setupActivity != null) {
            Firebase.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM) {
                param(FirebaseAnalytics.Param.ITEM_LIST_ID, source.componentName.flattenToShortString())
                param(FirebaseAnalytics.Param.ITEM_NAME, source.label ?: "")
                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "sources")
                param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
            }
            currentInitialSetupSource = source.componentName
            launchSourceSetup(source)
        } else {
            Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                param(FirebaseAnalytics.Param.ITEM_LIST_ID, source.componentName.flattenToShortString())
                param(FirebaseAnalytics.Param.ITEM_NAME, source.label ?: "")
                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "sources")
                param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
            }
            lifecycleScope.launch(NonCancellable) {
                LegacySourceService.selectSource(this@SourceSettingsActivity, source.componentName)
            }
        }
    }

    private fun launchSourceSettings(source: Source) {
        try {
            val settingsIntent = Intent()
                    .setComponent(source.settingsActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
            startActivity(settingsIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch source settings.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch source settings.", e)
        }
    }

    private fun launchSourceSetup(source: Source) {
        try {
            sourceSetup.launch(source)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch source setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch source setup.", e)
        }
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
    ) : ArrayAdapter<SourceView>(context, R.layout.legacy_choose_source_item, R.id.title) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val sourceView = getItem(position) ?: return view
            return LegacyChooseSourceItemBinding.bind(view).run {
                title.text = sourceView.toCharSequence()
                root.alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        sourceView.source.targetSdkVersion >= Build.VERSION_CODES.O) {
                    ALPHA_DISABLED
                } else {
                    ALPHA_DEFAULT
                }
                if (sourceView.source.selected) {
                    selectedSourceImage.colorFilter = PorterDuffColorFilter(sourceView.source.color,
                            PorterDuff.Mode.SRC_ATOP)
                    title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            selectedSourceImage,null, null, null)

                } else {
                    title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            sourceView.icon,null, null, null)
                }
                settings.apply {
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
                root
            }
        }
    }

    internal class SourceView(var source: Source) {
        lateinit var icon: Drawable

        fun toCharSequence() = buildSpannedString {
            bold { append(source.label) }
            if (source.displayDescription != null) {
                append("\n")
                append(source.displayDescription)
            }
        }
    }
}