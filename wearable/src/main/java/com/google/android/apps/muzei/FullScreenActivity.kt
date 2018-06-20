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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.PanView
import com.google.android.apps.muzei.util.observeNonNull
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

class FullScreenActivity : FragmentActivity() {

    private lateinit var panView: PanView
    private lateinit var loadingIndicatorView: View
    private lateinit var scrimView: View
    private lateinit var metadataContainerView: View
    private lateinit var titleView: TextView
    private lateinit var bylineView: TextView
    @Suppress("DEPRECATION")
    private lateinit var dismissOverlay: android.support.wearable.view.DismissOverlayView
    private lateinit var detector: GestureDetector
    private var blurAnimator: Animator? = null

    private var metadataVisible = false

    private var showLoadingIndicator: Job? = null

    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        setContentView(R.layout.full_screen_activity)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        panView = findViewById(R.id.pan_view)

        scrimView = findViewById(R.id.scrim)
        loadingIndicatorView = findViewById(R.id.loading_indicator)
        showLoadingIndicator = launch(UI) {
            delay(500)
            loadingIndicatorView.isVisible = true
        }

        metadataContainerView = findViewById(R.id.metadata_container)
        titleView = findViewById(R.id.title)
        bylineView = findViewById(R.id.byline)

        dismissOverlay = findViewById(R.id.dismiss_overlay)
        // Only show the dismiss overlay on Wear 1.0 devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            dismissOverlay.setIntroText(R.string.dismiss_overlay_intro)
            dismissOverlay.showIntroIfNecessary()
        }
        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (dismissOverlay.isVisible) {
                    return false
                }

                if (metadataVisible) {
                    setMetadataVisible(false)
                } else {
                    setMetadataVisible(true)
                }
                return true
            }

            override fun onLongPress(ev: MotionEvent) {
                if (dismissOverlay.isVisible) {
                    return
                }
                // Only show the dismiss overlay on Wear 1.0 devices
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    dismissOverlay.show()
                }
            }
        })
        MuzeiDatabase.getInstance(this).artworkDao()
                .currentArtwork.observeNonNull(this) { artwork ->
            launch(UI) {
                val image = ImageLoader.decode(
                        contentResolver, artwork.contentUri)
                showLoadingIndicator?.cancel()
                loadingIndicatorView.isVisible = false
                panView.isVisible = true
                panView.setImage(image)
                titleView.text = artwork.title
                bylineView.text = artwork.byline
            }
        }
    }

    private fun setMetadataVisible(metadataVisible: Boolean) {
        this.metadataVisible = metadataVisible
        blurAnimator?.cancel()

        val set = AnimatorSet().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        }
        set
                .play(ObjectAnimator.ofFloat(panView, "blurAmount", if (metadataVisible) 1f else 0f))
                .with(ObjectAnimator.ofFloat(scrimView, View.ALPHA, if (metadataVisible) 1f else 0f))
                .with(ObjectAnimator.ofFloat(metadataContainerView, View.ALPHA,
                        if (metadataVisible) 1f else 0f))

        blurAnimator = set.also {
            it.start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return detector.onTouchEvent(ev) || super.dispatchTouchEvent(ev)
    }
}
