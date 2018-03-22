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
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.FragmentActivity
import android.support.v4.app.LoaderManager
import android.support.v4.content.AsyncTaskLoader
import android.support.v4.content.Loader
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.PanView
import com.google.firebase.analytics.FirebaseAnalytics

import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

import java.io.FileNotFoundException

class FullScreenActivity : FragmentActivity(), LoaderManager.LoaderCallbacks<Pair<Artwork?, Bitmap?>> {

    companion object {
        private const val TAG = "FullScreenActivity"
    }

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
    private val handler = Handler()

    private var metadataVisible = false

    private val showLoadingIndicatorRunnable = Runnable { loadingIndicatorView.visibility = View.VISIBLE }

    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        setContentView(R.layout.full_screen_activity)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        panView = findViewById(R.id.pan_view)
        supportLoaderManager.initLoader(0, null, this)

        scrimView = findViewById(R.id.scrim)
        loadingIndicatorView = findViewById(R.id.loading_indicator)
        handler.postDelayed(showLoadingIndicatorRunnable, 500)

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
                if (dismissOverlay.visibility == View.VISIBLE) {
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
                if (dismissOverlay.visibility == View.VISIBLE) {
                    return
                }
                // Only show the dismiss overlay on Wear 1.0 devices
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    dismissOverlay.show()
                }
            }
        })
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

    private class ArtworkLoader internal constructor(context: Context)
        : AsyncTaskLoader<Pair<Artwork?, Bitmap?>>(context) {
        private var contentObserver: ContentObserver? = null
        private var artwork: Artwork? = null
        private var image: Bitmap? = null

        override fun onStartLoading() {
            if (artwork != null && image != null) {
                deliverResult(Pair(artwork, image))
            }
            if (contentObserver == null) {
                contentObserver = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        onContentChanged()
                    }
                }.also { contentObserver ->
                    context.contentResolver.registerContentObserver(
                            MuzeiContract.Artwork.CONTENT_URI, true, contentObserver)
                }
            }
            forceLoad()
        }

        override fun loadInBackground(): Pair<Artwork?, Bitmap?> {
            try {
                artwork = MuzeiDatabase.getInstance(context)
                        .artworkDao().currentArtworkBlocking
                image = MuzeiContract.Artwork.getCurrentArtworkBitmap(context)
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "Error getting artwork", e)
            }
            return Pair(artwork, image)
        }

        override fun onReset() {
            super.onReset()
            image = null
            contentObserver?.let { contentObserver ->
                context.contentResolver.unregisterContentObserver(contentObserver)
                this.contentObserver = null
            }
        }
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Pair<Artwork?, Bitmap?>> {
        return ArtworkLoader(this)
    }

    override fun onLoadFinished(loader: Loader<Pair<Artwork?, Bitmap?>>, pair: Pair<Artwork?, Bitmap?>) {
        val (artwork, image) = pair
        if (artwork == null || image == null) {
            return
        }

        handler.removeCallbacks(showLoadingIndicatorRunnable)
        loadingIndicatorView.visibility = View.GONE
        panView.visibility = View.VISIBLE
        panView.setImage(image)
        titleView.text = artwork.title
        bylineView.text = artwork.byline
    }

    override fun onLoaderReset(loader: Loader<Pair<Artwork?, Bitmap?>>) {
        panView.setImage(null)
    }
}
