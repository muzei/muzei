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

import android.app.Activity;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.Loader;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.wearable.view.CardScrollView;
import android.support.wearable.view.DismissOverlayView;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.util.PanView;

import net.nurik.roman.muzei.R;

import java.io.FileNotFoundException;

public class FullScreenActivity extends Activity implements LoaderManager.LoaderCallbacks<Bitmap> {
    private static final String TAG = FullScreenActivity.class.getSimpleName();

    private PanView mPanView;
    private CardScrollView mCardLayout;
    private TextView mTitle;
    private TextView mByline;
    private DismissOverlayView mDismissOverlay;
    private GestureDetector mDetector;

    private Artwork mArtwork;

    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.full_screen_activity);
        mPanView = (PanView) findViewById(R.id.pan_view);
        getLoaderManager().initLoader(0, null, this);

        mCardLayout = (CardScrollView) findViewById(R.id.card_layout);
        mCardLayout.setCardGravity(Gravity.BOTTOM);
        mTitle = (TextView) findViewById(R.id.title);
        mByline = (TextView) findViewById(R.id.byline);

        // Configure the DismissOverlayView element
        mDismissOverlay = (DismissOverlayView) findViewById(R.id.dismiss_overlay);
        mDismissOverlay.setIntroText(R.string.dismiss_overlay_intro);
        mDismissOverlay.showIntroIfNecessary();
        mDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mDismissOverlay.getVisibility() == View.VISIBLE) {
                    return false;
                }
                if (mCardLayout.getVisibility() == View.VISIBLE) {
                    mCardLayout.setVisibility(View.GONE);
                } else {
                    mCardLayout.setVisibility(View.VISIBLE);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent ev) {
                mDismissOverlay.show();
            }
        });
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
                    mArtwork = MuzeiContract.Artwork.getCurrentArtwork(FullScreenActivity.this);
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
        mPanView.setImage(image);
        mTitle.setText(mArtwork.getTitle());
        mByline.setText(mArtwork.getByline());
    }

    @Override
    public void onLoaderReset(Loader<Bitmap> loader) {
        mPanView.setImage(null);
    }
}
