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

package com.google.android.apps.muzei;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.Loader;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.wearable.view.DismissOverlayView;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.util.PanView;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

import java.io.FileNotFoundException;

public class FullScreenActivity extends Activity implements LoaderManager.LoaderCallbacks<Bitmap> {
    private static final String TAG = "FullScreenActivity";

    private PanView mPanView;
    private View mLoadingIndicatorView;
    private View mScrimView;
    private View mMetadataContainerView;
    private TextView mTitleView;
    private TextView mBylineView;
    private DismissOverlayView mDismissOverlay;
    private GestureDetector mDetector;
    private Animator mBlurAnimator;
    private Handler mHandler = new Handler();

    private Artwork mArtwork;
    private boolean mMetadataVisible = false;

    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.full_screen_activity);
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE);
        mPanView = findViewById(R.id.pan_view);
        getLoaderManager().initLoader(0, null, this);

        mScrimView = findViewById(R.id.scrim);
        mLoadingIndicatorView = findViewById(R.id.loading_indicator);
        mHandler.postDelayed(mShowLoadingIndicatorRunnable, 500);

        mMetadataContainerView = findViewById(R.id.metadata_container);
        mTitleView = findViewById(R.id.title);
        mBylineView = findViewById(R.id.byline);

        mDismissOverlay = findViewById(R.id.dismiss_overlay);
        // Only show the dismiss overlay on Wear 1.0 devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            mDismissOverlay.setIntroText(R.string.dismiss_overlay_intro);
            mDismissOverlay.showIntroIfNecessary();
        }
        mDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mDismissOverlay.getVisibility() == View.VISIBLE) {
                    return false;
                }

                if (mMetadataVisible) {
                    setMetadataVisible(false);
                } else {
                    setMetadataVisible(true);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent ev) {
                if (mDismissOverlay.getVisibility() == View.VISIBLE) {
                    return;
                }
                // Only show the dismiss overlay on Wear 1.0 devices
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    mDismissOverlay.show();
                }
            }
        });
    }

    private Runnable mShowLoadingIndicatorRunnable = new Runnable() {
        @Override
        public void run() {
            mLoadingIndicatorView.setVisibility(View.VISIBLE);
        }
    };

    private void setMetadataVisible(boolean metadataVisible) {
        mMetadataVisible = metadataVisible;
        if (mBlurAnimator != null) {
            mBlurAnimator.cancel();
        }

        AnimatorSet set = new AnimatorSet();
        set
                .play(ObjectAnimator.ofFloat(mPanView, "blurAmount", metadataVisible? 1f : 0f))
                .with(ObjectAnimator.ofFloat(mScrimView, View.ALPHA, metadataVisible ? 1f : 0f))
                .with(ObjectAnimator.ofFloat(mMetadataContainerView, View.ALPHA,
                        metadataVisible ? 1f : 0f));
        set.setDuration(getResources().getInteger(android.R.integer.config_shortAnimTime));

        mBlurAnimator = set;
        mBlurAnimator.start();
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
        return mDetector.onTouchEvent(ev) || super.dispatchTouchEvent(ev);
    }

    @Override
    public Loader<Bitmap> onCreateLoader(int id, Bundle args) {
        return new AsyncTaskLoader<Bitmap>(this) {
            private ContentObserver mContentObserver;
            private Bitmap mImage;

            @Override
            protected void onStartLoading() {
                if (mImage != null) {
                    deliverResult(mImage);
                }
                if (mContentObserver == null) {
                    mContentObserver = new ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onContentChanged();
                        }
                    };
                    getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                            true, mContentObserver);
                }
                forceLoad();
            }

            @Override
            public Bitmap loadInBackground() {
                try {
                    mArtwork = MuzeiDatabase.getInstance(FullScreenActivity.this)
                            .artworkDao().getCurrentArtworkBlocking();
                    mImage = MuzeiContract.Artwork.getCurrentArtworkBitmap(FullScreenActivity.this);
                    return mImage;
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Error getting artwork", e);
                    return null;
                }
            }

            @Override
            protected void onReset() {
                super.onReset();
                mImage = null;
                if (mContentObserver != null) {
                    getContentResolver().unregisterContentObserver(mContentObserver);
                }
            }
        };
    }

    @Override
    public void onLoadFinished(Loader<Bitmap> loader, final Bitmap image) {
        if (image == null) {
            return;
        }

        mHandler.removeCallbacks(mShowLoadingIndicatorRunnable);
        mLoadingIndicatorView.setVisibility(View.GONE);
        mPanView.setVisibility(View.VISIBLE);
        mPanView.setImage(image);
        mTitleView.setText(mArtwork.title);
        mBylineView.setText(mArtwork.byline);
    }

    @Override
    public void onLoaderReset(Loader<Bitmap> loader) {
        mPanView.setImage(null);
    }
}
