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

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.widget.TooltipCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.ArtworkLoadingStateChangedEvent;
import com.google.android.apps.muzei.event.ArtworkSizeChangedEvent;
import com.google.android.apps.muzei.event.SwitchingPhotosStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.settings.AboutActivity;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.android.apps.muzei.util.AnimatedMuzeiLoadingSpinnerView;
import com.google.android.apps.muzei.util.PanScaleProxyView;
import com.google.android.apps.muzei.util.ScrimUtil;
import com.google.android.apps.muzei.widget.AppWidgetUpdateTask;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.List;

public class ArtDetailFragment extends Fragment {
    private static final String TAG = "ArtDetailFragment";

    private int mCurrentViewportId = 0;
    private float mWallpaperAspectRatio;
    private float mArtworkAspectRatio;

    private boolean mSupportsNextArtwork = false;
    private Observer<Source> mSourceObserver = new Observer<Source>() {
        @Override
        public void onChanged(@Nullable final Source source) {
            // Update overflow and next button
            mOverflowSourceActionMap.clear();
            mOverflowMenu.getMenu().clear();
            mOverflowMenu.inflate(R.menu.muzei_overflow);
            if (source != null) {
                mSupportsNextArtwork = source.supportsNextArtwork;
                List<UserCommand> commands = source.commands;
                int numSourceActions = Math.min(SOURCE_ACTION_IDS.length,
                        commands.size());
                for (int i = 0; i < numSourceActions; i++) {
                    UserCommand action = commands.get(i);
                    mOverflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action.getId());
                    mOverflowMenu.getMenu().add(0, SOURCE_ACTION_IDS[i], 0, action.getTitle());
                }
            }
            mNextButton.setVisibility(mSupportsNextArtwork && !mArtworkLoading ? View.VISIBLE : View.GONE);
        }
    };

    private Observer<Artwork> mArtworkObserver = new Observer<Artwork>() {
        @Override
        public void onChanged(@Nullable final Artwork currentArtwork) {
            if (currentArtwork == null) {
                return;
            }
            int titleFont = R.font.alegreya_sans_black;
            int bylineFont = R.font.alegreya_sans_medium;
            if (MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT.equals(currentArtwork.metaFont)) {
                titleFont = R.font.alegreya_black_italic;
                bylineFont = R.font.alegreya_italic;
            }

            mTitleView.setTypeface(ResourcesCompat.getFont(getContext(), titleFont));
            mTitleView.setText(currentArtwork.title);

            mBylineView.setTypeface(ResourcesCompat.getFont(getContext(), bylineFont));
            mBylineView.setText(currentArtwork.byline);

            String attribution = currentArtwork.attribution;
            if (!TextUtils.isEmpty(attribution)) {
                mAttributionView.setText(attribution);
                mAttributionView.setVisibility(View.VISIBLE);
            } else {
                mAttributionView.setVisibility(View.GONE);
            }

            final Intent viewIntent = currentArtwork.viewIntent;
            mMetadataView.setEnabled(viewIntent != null);
            if (viewIntent != null) {
                mMetadataView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        // Make sure any data URIs granted to Muzei are passed onto the
                        // started Activity
                        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(viewIntent);
                        } catch (RuntimeException e) {
                            // Catch ActivityNotFoundException, SecurityException,
                            // and FileUriExposedException
                            Toast.makeText(getContext(), R.string.error_view_details,
                                    Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Error viewing artwork details.", e);
                        }
                    }
                });
            } else {
                mMetadataView.setOnClickListener(null);
            }
        }
    };

    private static final int LOAD_ERROR_COUNT_EASTER_EGG = 4;

    private boolean mGuardViewportChangeListener;
    private boolean mDeferResetViewport;

    private Handler mHandler = new Handler();
    private View mContainerView;
    private PopupMenu mOverflowMenu;
    private SparseIntArray mOverflowSourceActionMap = new SparseIntArray();
    private static final int[] SOURCE_ACTION_IDS = {
            R.id.source_action_1,
            R.id.source_action_2,
            R.id.source_action_3,
            R.id.source_action_4,
            R.id.source_action_5,
            R.id.source_action_6,
            R.id.source_action_7,
            R.id.source_action_8,
            R.id.source_action_9,
            R.id.source_action_10,
    };
    private View mChromeContainerView;
    private View mMetadataView;
    private View mLoadingContainerView;
    private View mLoadErrorContainerView;
    private View mLoadErrorEasterEggView;
    private AnimatedMuzeiLoadingSpinnerView mLoadingIndicatorView;
    private View mNextButton;
    private TextView mTitleView;
    private TextView mBylineView;
    private TextView mAttributionView;
    private PanScaleProxyView mPanScaleProxyView;
    private boolean mArtworkLoading = false;
    private boolean mArtworkLoadingError = false;
    private boolean mLoadingSpinnerShown = false;
    private boolean mLoadErrorShown = false;
    private boolean mNextFakeLoading = false;
    private int mConsecutiveLoadErrorCount = 0;
    private LiveData<Source> mCurrentSourceLiveData;
    private LiveData<Artwork> mCurrentArtworkLiveData;

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        mContainerView = inflater.inflate(R.layout.art_detail_fragment, container, false);

        mChromeContainerView = mContainerView.findViewById(R.id.chrome_container);
        showHideChrome(true);

        return mContainerView;
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        // Ensure we have the latest insets
        view.requestFitSystemWindows();

        mChromeContainerView.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                0xaa000000, 8, Gravity.BOTTOM));

        mMetadataView = view.findViewById(R.id.metadata);

        final float metadataSlideDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        mContainerView.setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int vis) {
                        final boolean visible = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0;

                        mChromeContainerView.setVisibility(View.VISIBLE);
                        mChromeContainerView.animate()
                                .alpha(visible ? 1f : 0f)
                                .translationY(visible ? 0 : metadataSlideDistance)
                                .setDuration(200)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!visible) {
                                            mChromeContainerView.setVisibility(View.GONE);
                                        }
                                    }
                                });
                    }
                });

        mTitleView = view.findViewById(R.id.title);
        mBylineView = view.findViewById(R.id.byline);
        mAttributionView = view.findViewById(R.id.attribution);

        final View overflowButton = view.findViewById(R.id.overflow_button);
        mOverflowMenu = new PopupMenu(getContext(), overflowButton);
        overflowButton.setOnTouchListener(mOverflowMenu.getDragToOpenListener());
        overflowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mOverflowMenu.show();
            }
        });
        mOverflowMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                Context context = getContext();
                if (context == null) {
                    return false;
                }
                int id = mOverflowSourceActionMap.get(menuItem.getItemId());
                if (id > 0) {
                    SourceManager.sendAction(context, id);
                    return true;
                }

                switch (menuItem.getItemId()) {
                    case R.id.action_about:
                        FirebaseAnalytics.getInstance(context).logEvent("about_open", null);
                        startActivity(new Intent(context, AboutActivity.class));
                        return true;
                }
                return false;
            }
        });

        mNextButton = view.findViewById(R.id.next_button);
        mNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Context context = getContext();
                if (context == null) {
                    return;
                }
                SourceManager.sendAction(context,
                        MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
                mNextFakeLoading = true;
                showNextFakeLoading();
            }
        });
        TooltipCompat.setTooltipText(mNextButton, mNextButton.getContentDescription());

        mPanScaleProxyView = view.findViewById(R.id.pan_scale_proxy);
        mPanScaleProxyView.setMaxZoom(5);
        mPanScaleProxyView.setOnViewportChangedListener(
                new PanScaleProxyView.OnViewportChangedListener() {
                    @Override
                    public void onViewportChanged() {
                        if (mGuardViewportChangeListener) {
                            return;
                        }

                        ArtDetailViewport.getInstance().setViewport(
                                mCurrentViewportId, mPanScaleProxyView.getCurrentViewport(), true);
                    }
                });
        mPanScaleProxyView.setOnOtherGestureListener(
                new PanScaleProxyView.OnOtherGestureListener() {
                    @Override
                    public void onSingleTapUp() {
                        showHideChrome((getActivity().getWindow().getDecorView().getSystemUiVisibility()
                                & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0);
                    }

                    @Override
                    public void onLongPress() {
                        new AppWidgetUpdateTask(getContext(), true).execute();
                    }
                });

        mLoadingContainerView = view.findViewById(R.id.image_loading_container);
        mLoadingIndicatorView = view.findViewById(R.id.image_loading_indicator);
        mLoadErrorContainerView = view.findViewById(R.id.image_error_container);
        mLoadErrorEasterEggView = view.findViewById(R.id.error_easter_egg);

        view.findViewById(R.id.image_error_retry_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showNextFakeLoading();
                getContext().startService(TaskQueueService.getDownloadCurrentArtworkIntent(getContext()));
            }
        });

        EventBus.getDefault().register(this);

        WallpaperSizeChangedEvent wsce = EventBus.getDefault().getStickyEvent(
                WallpaperSizeChangedEvent.class);
        if (wsce != null) {
            onEventMainThread(wsce);
        }

        ArtworkSizeChangedEvent asce = EventBus.getDefault().getStickyEvent(
                ArtworkSizeChangedEvent.class);
        if (asce != null) {
            onEventMainThread(asce);
        }

        ArtworkLoadingStateChangedEvent alsce = EventBus.getDefault().getStickyEvent(
                ArtworkLoadingStateChangedEvent.class);
        if (alsce != null) {
            onEventMainThread(alsce);
        }

        ArtDetailViewport fve = EventBus.getDefault().getStickyEvent(ArtDetailViewport.class);
        if (fve != null) {
            onEventMainThread(fve);
        }

        SwitchingPhotosStateChangedEvent spsce = EventBus.getDefault().getStickyEvent(
                SwitchingPhotosStateChangedEvent.class);
        if (spsce != null) {
            onEventMainThread(spsce);
        }
        MuzeiDatabase database = MuzeiDatabase.getInstance(getContext());
        mCurrentSourceLiveData = database.sourceDao().getCurrentSource();
        mCurrentSourceLiveData.observe(this, mSourceObserver);
        mCurrentArtworkLiveData = database.artworkDao().getCurrentArtwork();
        mCurrentArtworkLiveData.observe(this, mArtworkObserver);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(true));
    }

    @Override
    public void onResume() {
        super.onResume();
        mConsecutiveLoadErrorCount = 0;
        NewWallpaperNotificationReceiver.markNotificationRead(getContext());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacksAndMessages(null);
        EventBus.getDefault().unregister(this);
        mCurrentSourceLiveData.removeObserver(mSourceObserver);
        mCurrentArtworkLiveData.removeObserver(mArtworkObserver);
    }

    private void showHideChrome(boolean show) {
        int flags = show ? 0 : View.SYSTEM_UI_FLAG_LOW_PROFILE;
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!show) {
            flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;
        }
        getActivity().getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Subscribe
    public void onEventMainThread(WallpaperSizeChangedEvent wsce) {
        if (wsce.getHeight() > 0) {
            mWallpaperAspectRatio = wsce.getWidth() * 1f / wsce.getHeight();
        } else {
            mWallpaperAspectRatio = mPanScaleProxyView.getWidth()
                    * 1f / mPanScaleProxyView.getHeight();
        }
        resetProxyViewport();
    }

    @Subscribe
    public void onEventMainThread(ArtworkSizeChangedEvent ase) {
        mArtworkAspectRatio = ase.getWidth() * 1f / ase.getHeight();
        resetProxyViewport();
    }

    private void resetProxyViewport() {
        if (mWallpaperAspectRatio == 0 || mArtworkAspectRatio == 0) {
            return;
        }

        mDeferResetViewport = false;
        SwitchingPhotosStateChangedEvent spe = EventBus.getDefault()
                .getStickyEvent(SwitchingPhotosStateChangedEvent.class);
        if (spe != null && spe.isSwitchingPhotos()) {
            mDeferResetViewport = true;
            return;
        }

        if (mPanScaleProxyView != null) {
            mPanScaleProxyView.setRelativeAspectRatio(mArtworkAspectRatio / mWallpaperAspectRatio);
        }
    }

    @Subscribe
    public void onEventMainThread(ArtDetailViewport e) {
        if (!e.isFromUser() && mPanScaleProxyView != null) {
            mGuardViewportChangeListener = true;
            mPanScaleProxyView.setViewport(e.getViewport(mCurrentViewportId));
            mGuardViewportChangeListener = false;
        }
    }

    @Subscribe
    public void onEventMainThread(SwitchingPhotosStateChangedEvent spe) {
        mCurrentViewportId = spe.getCurrentId();
        mPanScaleProxyView.enablePanScale(!spe.isSwitchingPhotos());
        // Process deferred artwork size change when done switching
        if (!spe.isSwitchingPhotos() && mDeferResetViewport) {
            resetProxyViewport();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mOverflowMenu.dismiss();
        EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(false));
    }

    @Subscribe
    public void onEventMainThread(ArtworkLoadingStateChangedEvent e) {
        mArtworkLoading = e.isLoading();
        mArtworkLoadingError = e.hadError();
        if (!mArtworkLoading) {
            mNextFakeLoading = false;
            if (!mArtworkLoadingError) {
                mConsecutiveLoadErrorCount = 0;
            }
        }

        // Artwork no longer loading, update the visibility of the next button
        mNextButton.setVisibility(mSupportsNextArtwork && !mArtworkLoading ? View.VISIBLE : View.GONE);

        updateLoadingSpinnerAndErrorVisibility();
    }

    private void showNextFakeLoading() {
        mNextFakeLoading = true;
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away. See onEventMainThread(ArtworkLoadingStateChangedEvent)
        mHandler.removeCallbacks(mUnsetNextFakeLoadingRunnable);
        mHandler.postDelayed(mUnsetNextFakeLoadingRunnable, 10000);
        updateLoadingSpinnerAndErrorVisibility();
    }

    private Runnable mUnsetNextFakeLoadingRunnable = new Runnable() {
        @Override
        public void run() {
            mNextFakeLoading = false;
            updateLoadingSpinnerAndErrorVisibility();
        }
    };

    private void updateLoadingSpinnerAndErrorVisibility() {
        boolean showLoadingSpinner = mArtworkLoading || mNextFakeLoading;
        boolean showError = !showLoadingSpinner && mArtworkLoadingError;

        if (showLoadingSpinner != mLoadingSpinnerShown) {
            mLoadingSpinnerShown = showLoadingSpinner;
            mHandler.removeCallbacks(mShowLoadingSpinnerRunnable);
            if (showLoadingSpinner) {
                mHandler.postDelayed(mShowLoadingSpinnerRunnable, 700);
            } else {
                mLoadingContainerView.animate()
                        .alpha(0)
                        .setDuration(1000)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                mLoadingContainerView.setVisibility(View.GONE);
                                mLoadingIndicatorView.stop();
                            }
                        });
            }
        }

        if (showError != mLoadErrorShown) {
            mLoadErrorShown = showError;
            mHandler.removeCallbacks(mShowLoadErrorRunnable);
            if (showError) {
                mHandler.postDelayed(mShowLoadErrorRunnable, 700);
            } else {
                mLoadErrorContainerView.animate()
                        .alpha(0)
                        .setDuration(1000)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                mLoadErrorContainerView.setVisibility(View.GONE);
                            }
                        });
            }
        }
    }

    private Runnable mShowLoadingSpinnerRunnable = new Runnable() {
        @Override
        public void run() {
            mLoadingIndicatorView.start();
            mLoadingContainerView.setVisibility(View.VISIBLE);
            mLoadingContainerView.animate()
                    .alpha(1)
                    .setDuration(300)
                    .withEndAction(null);
        }
    };

    private Runnable mShowLoadErrorRunnable = new Runnable() {
        @Override
        public void run() {
            ++mConsecutiveLoadErrorCount;
            mLoadErrorEasterEggView.setVisibility(
                    (mConsecutiveLoadErrorCount >= LOAD_ERROR_COUNT_EASTER_EGG)
                    ? View.VISIBLE : View.GONE);
            mLoadErrorContainerView.setVisibility(View.VISIBLE);
            mLoadErrorContainerView.animate()
                    .alpha(1)
                    .setDuration(300)
                    .withEndAction(null);
        }
    };
}
