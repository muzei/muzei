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

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.ArtworkLoadingStateChangedEvent;
import com.google.android.apps.muzei.event.ArtworkSizeChangedEvent;
import com.google.android.apps.muzei.event.SelectedSourceStateChangedEvent;
import com.google.android.apps.muzei.event.SwitchingPhotosStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.render.MuzeiRendererFragment;
import com.google.android.apps.muzei.settings.SettingsActivity;
import com.google.android.apps.muzei.util.AnimatedMuzeiLoadingSpinnerView;
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment;
import com.google.android.apps.muzei.util.CheatSheet;
import com.google.android.apps.muzei.util.DrawInsetsFrameLayout;
import com.google.android.apps.muzei.util.LogUtil;
import com.google.android.apps.muzei.util.PanScaleProxyView;
import com.google.android.apps.muzei.util.ScrimUtil;
import com.google.android.apps.muzei.util.TypefaceUtil;

import net.nurik.roman.muzei.R;

import de.greenrobot.event.EventBus;

import static com.google.android.apps.muzei.util.LogUtil.LOGE;

public class MuzeiActivity extends ActionBarActivity {
    private static final String TAG = LogUtil.makeLogTag(MuzeiActivity.class);

    private static final String PREF_SEEN_TUTORIAL = "seen_tutorial";

    // Controller/logic fields
    private SourceManager mSourceManager;
    private Artwork mCurrentArtwork;
    private SourceState mSelectedSourceState;
    private int mCurrentViewportId = 0;
    private float mWallpaperAspectRatio;
    private float mArtworkAspectRatio;

    // UI flags
    private int mUiMode = UI_MODE_ART_DETAIL;
    private static final int UI_MODE_ART_DETAIL = 0;
    private static final int UI_MODE_INTRO = 1; // not active wallpaper
    private static final int UI_MODE_TUTORIAL = 2; // active wallpaper, but first time

    private static final int LOAD_ERROR_COUNT_EASTER_EGG = 4;

    private boolean mPaused;
    private boolean mWindowHasFocus;
    private boolean mOverflowMenuVisible = false;
    private boolean mGestureFlagSystemUiBecameVisible;
    private boolean mWallpaperActive;
    private boolean mSeenTutorial;
    private boolean mGuardViewportChangeListener;
    private boolean mDeferResetViewport;

    // UI
    private Handler mHandler = new Handler();
    private DrawInsetsFrameLayout mContainerView;
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

    // Normal mode UI
    private View mChromeContainerView;
    private View mStatusBarScrimView;
    private View mMetadataView;
    private View mLoadingContainerView;
    private View mLoadErrorContainerView;
    private View mLoadErrorEasterEggView;
    private AnimatedMuzeiLoadingSpinnerView mLoadingIndicatorView;
    private View mNextButton;
    private TextView mTitleView;
    private TextView mBylineView;
    private PanScaleProxyView mPanScaleProxyView;
    private boolean mArtworkLoading = false;
    private boolean mArtworkLoadingError = false;
    private boolean mLoadingSpinnerShown = false;
    private boolean mLoadErrorShown = false;
    private boolean mNextFakeLoading = false;
    private int mConsecutiveLoadErrorCount = 0;

    // Tutorial mode UI
    private View mTutorialContainerView;

    // Intro mode UI
    private ViewGroup mIntroContainerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.muzei_activity);

        mContainerView = (DrawInsetsFrameLayout) findViewById(R.id.container);

        setupArtDetailModeUi();
        setupIntroModeUi();
        setupTutorialModeUi();

        mContainerView.setOnInsetsCallback(new DrawInsetsFrameLayout.OnInsetsCallback() {
            @Override
            public void onInsetsChanged(Rect insets) {
                mChromeContainerView.setPadding(
                        insets.left, insets.top, insets.right, insets.bottom);
                if (mTutorialContainerView != null) {
                    mTutorialContainerView.setPadding(
                            insets.left, insets.top, insets.right, insets.bottom);
                }
            }
        });

        showHideChrome(true);

        mSourceManager = SourceManager.getInstance(this);

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

        mSelectedSourceState = mSourceManager.getSelectedSourceState();
        mCurrentArtwork = mSelectedSourceState != null
                ? mSelectedSourceState.getCurrentArtwork() : null;

        updateArtDetailUi();
    }

    private void setupIntroModeUi() {
        mIntroContainerView = (ViewGroup) findViewById(R.id.intro_container);

        findViewById(R.id.activate_muzei_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    new ComponentName(MuzeiActivity.this,
                                            MuzeiWallpaperService.class))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    try {
                        startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException e2) {
                        Toast.makeText(MuzeiActivity.this, R.string.error_wallpaper_chooser,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
        findViewById(R.id.setup_muzei_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(MuzeiActivity.this, SettingsActivity.class));
                } catch (ActivityNotFoundException e) {
                }
            }
        });
    }

    private void setupTutorialModeUi() {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        mSeenTutorial = sp.getBoolean(PREF_SEEN_TUTORIAL, false);
        if (mSeenTutorial) {
            return;
        }

        mTutorialContainerView = LayoutInflater.from(this)
                .inflate(R.layout.muzei_include_tutorial, mContainerView, false);

        mTutorialContainerView.findViewById(R.id.tutorial_icon_affordance).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        mSeenTutorial = true;
                        updateUiMode();
                        sp.edit().putBoolean(PREF_SEEN_TUTORIAL, mSeenTutorial).apply();
                    }
                });

        mContainerView.addView(mTutorialContainerView);
    }

    private View getMainContainerForUiMode(int mode) {
        switch (mode) {
            case UI_MODE_ART_DETAIL: return mChromeContainerView;
            case UI_MODE_INTRO: return mIntroContainerView;
            case UI_MODE_TUTORIAL: return mTutorialContainerView;
        }

        return null;
    }

    private void updateUiMode() {
        // TODO: this should really just use fragment transactions and transitions

        int newUiMode = UI_MODE_INTRO;
        if (mWallpaperActive) {
            newUiMode = UI_MODE_TUTORIAL;
            if (mSeenTutorial) {
                newUiMode = UI_MODE_ART_DETAIL;
            }
        }

        if (mUiMode == newUiMode) {
            return;
        }

        // Crossfade between main containers
        final View oldContainerView = getMainContainerForUiMode(mUiMode);
        final View newContainerView = getMainContainerForUiMode(newUiMode);

        if (oldContainerView != null) {
            oldContainerView.animate()
                    .alpha(0)
                    .setDuration(1000)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            oldContainerView.setVisibility(View.GONE);
                        }
                    });
        }

        if (newContainerView != null) {
            if (newContainerView.getAlpha() == 1) {
                newContainerView.setAlpha(0);
            }
            newContainerView.setVisibility(View.VISIBLE);
            newContainerView.animate()
                    .alpha(1)
                    .setDuration(1000)
                    .withEndAction(null);
        }

        // Special work
        if (newUiMode == UI_MODE_INTRO) {
            final View activateButton = findViewById(R.id.activate_muzei_button);
            final View setupButton = findViewById(R.id.setup_muzei_button);
            activateButton.setAlpha(0);
            setupButton.setAlpha(0);
            final AnimatedMuzeiLogoFragment logoFragment = (AnimatedMuzeiLogoFragment)
                    getFragmentManager().findFragmentById(R.id.animated_logo_fragment);
            logoFragment.reset();
            logoFragment.setOnFillStartedCallback(new Runnable() {
                @Override
                public void run() {
                    activateButton.animate().alpha(1f).setDuration(500);
                    setupButton.animate().alpha(1f).setDuration(500);
                }
            });
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    logoFragment.start();
                }
            }, 1000);
        }

        if (mUiMode == UI_MODE_INTRO || newUiMode == UI_MODE_INTRO) {
            FragmentManager fm = getFragmentManager();
            Fragment demoFragment = fm.findFragmentById(R.id.demo_view_container);
            if (newUiMode == UI_MODE_INTRO && demoFragment == null) {
                fm.beginTransaction()
                        .add(R.id.demo_view_container,
                                MuzeiRendererFragment.createInstance(true, true))
                        .commit();
            } else if (newUiMode != UI_MODE_INTRO && demoFragment != null) {
                fm.beginTransaction()
                        .remove(demoFragment)
                        .commit();
            }
        }

        if (newUiMode == UI_MODE_TUTORIAL) {
            float animateDistance = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100,
                    getResources().getDisplayMetrics());
            View mainTextView = findViewById(R.id.tutorial_main_text);
            mainTextView.setAlpha(0);
            mainTextView.setTranslationY(-animateDistance / 5);

            View subTextView = findViewById(R.id.tutorial_sub_text);
            subTextView.setAlpha(0);
            subTextView.setTranslationY(-animateDistance / 5);

            View affordanceView = findViewById(R.id.tutorial_icon_affordance);
            affordanceView.setAlpha(0);
            affordanceView.setTranslationY(animateDistance);

            AnimatorSet set = new AnimatorSet();
            set.setStartDelay(500);
            set.setDuration(250);
            set.playTogether(
                    ObjectAnimator.ofFloat(mainTextView, View.ALPHA, 1f),
                    ObjectAnimator.ofFloat(subTextView, View.ALPHA, 1f));
            set.start();

            set = new AnimatorSet();
            set.setStartDelay(2000);

            // Bug in older versions where set.setInterpolator didn't work
            Interpolator interpolator = new OvershootInterpolator();
            ObjectAnimator a1 = ObjectAnimator.ofFloat(affordanceView, View.TRANSLATION_Y, 0);
            ObjectAnimator a2 = ObjectAnimator.ofFloat(mainTextView, View.TRANSLATION_Y, 0);
            ObjectAnimator a3 = ObjectAnimator.ofFloat(subTextView, View.TRANSLATION_Y, 0);
            a1.setInterpolator(interpolator);
            a2.setInterpolator(interpolator);
            a3.setInterpolator(interpolator);
            set.setDuration(500).playTogether(
                    ObjectAnimator.ofFloat(affordanceView, View.ALPHA, 1f), a1, a2, a3);
            set.start();
        }

        mPanScaleProxyView.setVisibility(newUiMode == UI_MODE_ART_DETAIL
                ? View.VISIBLE : View.GONE);

        mUiMode = newUiMode;

        maybeUpdateArtDetailOpenedClosed();
    }

    private void maybeUpdateArtDetailOpenedClosed() {
        boolean currentlyOpened = false;
        ArtDetailOpenedClosedEvent adoce = EventBus.getDefault()
                .getStickyEvent(ArtDetailOpenedClosedEvent.class);
        if (adoce != null) {
            currentlyOpened = adoce.isArtDetailOpened();
        }

        boolean shouldBeOpened = false;
        if (mUiMode == UI_MODE_ART_DETAIL
//                && !mArtworkLoading // uncomment when this wouldn't cause
//                                    // a zoom out / in visual glitch
                && (mWindowHasFocus || mOverflowMenuVisible)
                && !mPaused) {
            shouldBeOpened = true;
        }

        if (currentlyOpened != shouldBeOpened) {
            EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(shouldBeOpened));
        }
    }

    private void setupArtDetailModeUi() {
        mChromeContainerView = findViewById(R.id.chrome_container);
        mStatusBarScrimView = findViewById(R.id.statusbar_scrim);

        mChromeContainerView.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                0xaa000000, 8, Gravity.BOTTOM));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mStatusBarScrimView.setVisibility(View.GONE);
            mStatusBarScrimView = null;
        } else {
            mStatusBarScrimView.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                    0x44000000, 8, Gravity.TOP));
        }

        mMetadataView = findViewById(R.id.metadata);

        final float metadataSlideDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        mContainerView.setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int vis) {
                        final boolean visible = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0;
                        if (visible) {
                            mGestureFlagSystemUiBecameVisible = true;
                        }

                        boolean showArtDetailChrome = (mUiMode == UI_MODE_ART_DETAIL);
                        mChromeContainerView.setVisibility(
                                showArtDetailChrome ? View.VISIBLE : View.GONE);
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

                        if (mStatusBarScrimView != null) {
                            mStatusBarScrimView.setVisibility(
                                    showArtDetailChrome ? View.VISIBLE : View.GONE);
                            mStatusBarScrimView.animate()
                                    .alpha(visible ? 1f : 0f)
                                    .setDuration(200)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!visible) {
                                                mStatusBarScrimView.setVisibility(View.GONE);
                                            }
                                        }
                                    });
                        }
                    }
                });

        Typeface tf = TypefaceUtil.getAndCache(this, "Alegreya-BlackItalic.ttf");
        mTitleView = (TextView) findViewById(R.id.title);
        mTitleView.setTypeface(tf);

        tf = TypefaceUtil.getAndCache(this, "Alegreya-Italic.ttf");
        mBylineView = (TextView) findViewById(R.id.byline);
        mBylineView.setTypeface(tf);

        setupOverflowButton();

        mNextButton = findViewById(R.id.next_button);
        mNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mSourceManager.sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
                mNextFakeLoading = true;
                showNextFakeLoading();
            }
        });
        CheatSheet.setup(mNextButton);

        mPanScaleProxyView = (PanScaleProxyView) findViewById(R.id.pan_scale_proxy);
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
                    public void onDown() {
                        mGestureFlagSystemUiBecameVisible = false;
                    }

                    @Override
                    public void onSingleTapUp() {
                        if (mUiMode == UI_MODE_ART_DETAIL) {
                            showHideChrome((mContainerView.getSystemUiVisibility()
                                    & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0);
                        }
                    }

                    @Override
                    public void onUpNonSingleTap(boolean zoomedOut) {
                        if (mGestureFlagSystemUiBecameVisible) {
                            // System UI became visible during this touch sequence. Don't
                            // change system visibility.
                            return;
                        }

                        //showHideChrome(zoomedOut);
                    }
                });

        mLoadingContainerView = findViewById(R.id.image_loading_container);
        mLoadingIndicatorView = (AnimatedMuzeiLoadingSpinnerView)
                findViewById(R.id.image_loading_indicator);
        mLoadErrorContainerView = findViewById(R.id.image_error_container);
        mLoadErrorEasterEggView = findViewById(R.id.error_easter_egg);

        findViewById(R.id.image_error_retry_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showNextFakeLoading();
                startService(TaskQueueService.getDownloadCurrentArtworkIntent(MuzeiActivity.this));
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void setupOverflowButton() {
        final View overflowButton = findViewById(R.id.overflow_button);
        mOverflowMenu = new PopupMenu(this, overflowButton);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            overflowButton.setOnTouchListener(mOverflowMenu.getDragToOpenListener());
        }
        overflowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mOverflowMenuVisible = true;
                mOverflowMenu.show();
            }
        });
        mOverflowMenu.setOnDismissListener(new PopupMenu.OnDismissListener() {
            @Override
            public void onDismiss(PopupMenu popupMenu) {
                mOverflowMenuVisible = false;
            }
        });
        mOverflowMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                int id = mOverflowSourceActionMap.get(menuItem.getItemId());
                if (id > 0) {
                    mSourceManager.sendAction(id);
                    return true;
                }

                switch (menuItem.getItemId()) {
                    case R.id.action_settings:
                        startActivity(new Intent(MuzeiActivity.this, SettingsActivity.class));
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                if (mUiMode == UI_MODE_ART_DETAIL) {
                    mOverflowMenuVisible = true;
                    mOverflowMenu.show();
                }
                return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    private void updateArtDetailUi() {
        if (mCurrentArtwork != null) {
            mTitleView.setText(mCurrentArtwork.getTitle());
            mBylineView.setText(mCurrentArtwork.getByline());

            final Intent viewIntent = mCurrentArtwork.getViewIntent();
            mMetadataView.setEnabled(viewIntent != null);
            if (viewIntent != null) {
                mMetadataView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(viewIntent);
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Toast.makeText(MuzeiActivity.this, R.string.error_view_details,
                                    Toast.LENGTH_SHORT).show();
                            LOGE(TAG, "Error viewing artwork details.", e);
                        }
                    }
                });
            } else {
                mMetadataView.setOnClickListener(null);
            }
        }

        // Update overflow and next button
        boolean nextButtonVisible = false;
        mOverflowSourceActionMap.clear();
        mOverflowMenu.getMenu().clear();
        mOverflowMenu.inflate(R.menu.muzei_overflow);
        if (mSelectedSourceState != null) {
            int numSourceActions = Math.min(SOURCE_ACTION_IDS.length,
                    mSelectedSourceState.getNumUserCommands());
            for (int i = 0; i < numSourceActions; i++) {
                UserCommand action = mSelectedSourceState.getUserCommandAt(i);
                if (action.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                    nextButtonVisible = !mArtworkLoading;
                } else {
                    mOverflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action.getId());
                    mOverflowMenu.getMenu().add(0, SOURCE_ACTION_IDS[i], 0, action.getTitle());
                }
            }
        }

        mNextButton.setVisibility(nextButtonVisible ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        EventBus.getDefault().unregister(this);
    }

    private void showHideChrome(boolean show) {
        int flags = show ? 0 : View.SYSTEM_UI_FLAG_LOW_PROFILE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (!show) {
                flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE;
            }
        }
        mContainerView.setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        mWindowHasFocus = hasFocus;
        maybeUpdateArtDetailOpenedClosed();
    }

    public void onEventMainThread(SelectedSourceStateChangedEvent e) {
        mSelectedSourceState = mSourceManager.getSelectedSourceState();
        mCurrentArtwork = mSelectedSourceState != null
                ? mSelectedSourceState.getCurrentArtwork() : null;
        updateArtDetailUi();
    }

    public void onEventMainThread(WallpaperSizeChangedEvent wsce) {
        if (wsce.getHeight() > 0) {
            mWallpaperAspectRatio = wsce.getWidth() * 1f / wsce.getHeight();
        } else {
            mWallpaperAspectRatio = mPanScaleProxyView.getWidth()
                    * 1f / mPanScaleProxyView.getHeight();
        }
        resetProxyViewport();
    }

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

    public void onEventMainThread(ArtDetailViewport e) {
        if (!e.isFromUser() && mPanScaleProxyView != null) {
            mGuardViewportChangeListener = true;
            mPanScaleProxyView.setViewport(e.getViewport(mCurrentViewportId));
            mGuardViewportChangeListener = false;
        }
    }

    public void onEventMainThread(SwitchingPhotosStateChangedEvent spe) {
        mCurrentViewportId = spe.getCurrentId();
        mPanScaleProxyView.enablePanScale(!spe.isSwitchingPhotos());
        // Process deferred artwork size change when done switching
        if (!spe.isSwitchingPhotos() && mDeferResetViewport) {
            resetProxyViewport();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPaused = true;
        maybeUpdateArtDetailOpenedClosed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mOverflowMenu.dismiss();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        mPaused = false;
        mConsecutiveLoadErrorCount = 0;

        // update intro mode UI to latest wallpaper active state
        WallpaperActiveStateChangedEvent e = EventBus.getDefault()
                .getStickyEvent(WallpaperActiveStateChangedEvent.class);
        if (e != null) {
            onEventMainThread(e);
        } else {
            onEventMainThread(new WallpaperActiveStateChangedEvent(false));
        }

        updateUiMode();
        mChromeContainerView.setVisibility((mUiMode == UI_MODE_ART_DETAIL)
                ? View.VISIBLE : View.GONE);
        if (mStatusBarScrimView != null) {
            mStatusBarScrimView.setVisibility((mUiMode == UI_MODE_ART_DETAIL)
                    ? View.VISIBLE : View.GONE);
        }

        // Note: normally should use window animations for this, but there's a bug
        // on Samsung devices where the wallpaper is animated along with the window for
        // windows showing the wallpaper (the wallpaper _should_ be static, not part of
        // the animation).
        View decorView = getWindow().getDecorView();
        decorView.setAlpha(0f);
        decorView.animate().cancel();
        decorView.animate()
                .setStartDelay(500)
                .alpha(1f)
                .setDuration(300);

        maybeUpdateArtDetailOpenedClosed();

        NewWallpaperNotificationReceiver.markNotificationRead(this);
    }

    public void onEventMainThread(final WallpaperActiveStateChangedEvent e) {
        if (mPaused) {
            return;
        }

        mWallpaperActive = e.isActive();
        updateUiMode();
    }

    public void onEventMainThread(ArtworkLoadingStateChangedEvent e) {
        mArtworkLoading = e.isLoading();
        mArtworkLoadingError = e.hadError();
        if (!mArtworkLoading) {
            mNextFakeLoading = false;
            if (!mArtworkLoadingError) {
                mConsecutiveLoadErrorCount = 0;
            }

            // Artwork no longer loading, update normal mode UI
            updateArtDetailUi();
        }

        if (mUiMode == UI_MODE_ART_DETAIL) {
            maybeUpdateArtDetailOpenedClosed();
        }

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
        boolean showLoadingSpinner = (mUiMode == UI_MODE_ART_DETAIL
                && (mArtworkLoading || mNextFakeLoading));
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
