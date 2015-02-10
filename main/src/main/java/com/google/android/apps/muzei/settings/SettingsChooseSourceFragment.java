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

package com.google.android.apps.muzei.settings;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.SelectedSourceChangedEvent;
import com.google.android.apps.muzei.event.SelectedSourceStateChangedEvent;
import com.google.android.apps.muzei.util.CheatSheet;
import com.google.android.apps.muzei.util.LogUtil;
import com.google.android.apps.muzei.util.ObservableHorizontalScrollView;
import com.google.android.apps.muzei.util.Scrollbar;

import net.nurik.roman.muzei.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.greenrobot.event.EventBus;

import static com.google.android.apps.muzei.api.MuzeiArtSource.ACTION_MUZEI_ART_SOURCE;
import static com.google.android.apps.muzei.util.LogUtil.LOGE;
import static com.google.android.apps.muzei.util.LogUtil.LOGW;

/**
 * Fragment for allowing the user to choose the active source.
 */
public class SettingsChooseSourceFragment extends Fragment {
    private static final String TAG = LogUtil.makeLogTag(SettingsChooseSourceFragment.class);

    private static final int SCROLLBAR_HIDE_DELAY_MILLIS = 1000;

    private static final float ALPHA_UNSELECTED = 0.4f;

    private SourceManager mSourceManager;
    private ComponentName mSelectedSource;
    private List<Source> mSources = new ArrayList<>();

    private Handler mHandler = new Handler();

    private ViewGroup mRootView;
    private ViewGroup mSourceContainerView;
    private ObservableHorizontalScrollView mSourceScrollerView;
    private Scrollbar mScrollbar;
    private ObjectAnimator mCurrentScroller;

    private int mAnimationDuration;
    private int mItemWidth;
    private int mItemImageSize;
    private int mItemEstimatedHeight;

    private RectF mTempRectF = new RectF();
    private Paint mImageFillPaint = new Paint();
    private Paint mAlphaPaint = new Paint();
    private Drawable mSelectedSourceImage;
    private int mSelectedSourceIndex;

    public SettingsChooseSourceFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAnimationDuration = getResources().getInteger(android.R.integer.config_shortAnimTime);
        mItemWidth = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_item_width);
        mItemEstimatedHeight = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_item_estimated_height);
        mItemImageSize = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_item_image_size);

        mSourceManager = SourceManager.getInstance(getActivity());
        EventBus.getDefault().register(this);

        prepareGenerateSourceImages();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!(activity instanceof Callbacks)) {
            throw new ClassCastException("Activity must implement fragment callbacks.");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mRootView = (ViewGroup) inflater.inflate(
                R.layout.settings_choose_source_fragment, container, false);
        mScrollbar = (Scrollbar) mRootView.findViewById(R.id.source_scrollbar);
        mSourceScrollerView = (ObservableHorizontalScrollView)
                mRootView.findViewById(R.id.source_scroller);
        mSourceScrollerView.setCallbacks(new ObservableHorizontalScrollView.Callbacks() {
            @Override
            public void onScrollChanged(int scrollX) {
                showScrollbar();
            }

            @Override
            public void onDownMotionEvent() {
                if (mCurrentScroller != null) {
                    mCurrentScroller.cancel();
                    mCurrentScroller = null;
                }
            }

            @Override
            public void onUpOrCancelMotionEvent() {
            }
        });
        mSourceContainerView = (ViewGroup) mRootView.findViewById(R.id.source_container);

        redrawSources();

        mRootView.setVisibility(View.INVISIBLE);
        mRootView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            int mPass = 0;

            @Override
            public void onGlobalLayout() {
                if (mPass == 0) {
                    // First pass
                    updatePadding();
                    ++mPass;
                } else if (mPass == 1 & mSelectedSourceIndex >= 0) {
                    // Second pass
                    mSourceScrollerView.setScrollX(mItemWidth * mSelectedSourceIndex);
                    showScrollbar();
                    mRootView.setVisibility(View.VISIBLE);
                    ++mPass;
                } else {
                    // Last pass, remove the listener
                    mRootView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });

        mRootView.setAlpha(0);
        mRootView.animate().alpha(1f).setDuration(500);
        return mRootView;
    }

    private void updatePadding() {
        int rootViewWidth = mRootView.getWidth();
        if (rootViewWidth == 0) {
            return;
        }
        int topPadding = Math.max(0, (mRootView.getHeight() - mItemEstimatedHeight) / 2);
        int numItems = mSources.size();
        int sidePadding;
        int minSidePadding = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_min_side_padding);
        if (minSidePadding * 2 + mItemWidth * numItems < rootViewWidth) {
            // Don't allow scrolling since all items can be visible. Center the entire
            // list by using just the right amount of padding to center it.
            sidePadding = (rootViewWidth - mItemWidth * numItems) / 2 - 1;
        } else {
            // Allow scrolling
            sidePadding = Math.max(0, (rootViewWidth - mItemWidth) / 2);
        }
        mSourceContainerView.setPadding(sidePadding, topPadding, sidePadding, 0);
    }

    private BroadcastReceiver mPackagesChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateSources();
            updatePadding();
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        updateSources();

        IntentFilter packageChangeIntentFilter = new IntentFilter();
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageChangeIntentFilter.addDataScheme("package");
        getActivity().registerReceiver(mPackagesChangedReceiver, packageChangeIntentFilter);
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mPackagesChangedReceiver);
    }

    public void onEventMainThread(SelectedSourceChangedEvent e) {
        updateSelectedItem(true);
    }

    public void onEventMainThread(SelectedSourceStateChangedEvent e) {
        updateSelectedItem(true);
    }

    private void updateSelectedItem(boolean allowAnimate) {
        ComponentName previousSelectedSource = mSelectedSource;
        mSelectedSource = mSourceManager.getSelectedSource();
        if (previousSelectedSource != null && previousSelectedSource.equals(mSelectedSource)) {
            // Only update status
            for (final Source source : mSources) {
                if (!source.componentName.equals(mSelectedSource) || source.rootView == null) {
                    continue;
                }
                SourceState state = mSourceManager.getSourceState(source.componentName);
                updateSourceStatusUi(source, state);
            }
            return;
        }

        boolean selected;
        int index = -1;
        for (final Source source : mSources) {
            ++index;
            if (source.componentName.equals(previousSelectedSource)) {
                selected = false;
            } else if (source.componentName.equals(mSelectedSource)) {
                mSelectedSourceIndex = index;
                selected = true;
            } else {
                continue;
            }

            if (source.rootView == null) {
                continue;
            }

            View sourceImageButton = source.rootView.findViewById(R.id.source_image);
            Drawable drawable = selected ? mSelectedSourceImage : source.icon;
            drawable.setColorFilter(source.color, PorterDuff.Mode.SRC_ATOP);
            sourceImageButton.setBackground(drawable);

            float alpha = selected ? 1f : ALPHA_UNSELECTED;
            source.rootView.animate()
                    .alpha(alpha)
                    .setDuration(mAnimationDuration);

            if (selected) {
                SourceState state = mSourceManager.getSourceState(source.componentName);
                updateSourceStatusUi(source, state);
            }

            animateSettingsButton(source.settingsButton,
                    selected && source.settingsActivity != null, allowAnimate);
        }

        if (mSelectedSourceIndex >= 0 && allowAnimate) {
            if (mCurrentScroller != null) {
                mCurrentScroller.cancel();
            }

            // For some reason smoothScrollTo isn't very smooth..
            mCurrentScroller = ObjectAnimator.ofInt(mSourceScrollerView, "scrollX",
                    mItemWidth * mSelectedSourceIndex);
            mCurrentScroller.setDuration(mAnimationDuration);
            mCurrentScroller.start();
        }
    }

    private void animateSettingsButton(final View settingsButton, final boolean show,
                                       boolean allowAnimate) {
        settingsButton.setVisibility(View.VISIBLE);
        settingsButton.animate()
                .translationY(show ? 0 : (-getResources().getDimensionPixelSize(
                        R.dimen.settings_choose_source_settings_button_animate_distance)))
                .alpha(show ? 1f : 0f)
                .rotation(show ? 0 : -90)
                .setDuration(allowAnimate ? 300 : 0)
                .setStartDelay((show && allowAnimate) ? 200 : 0)
                .withLayer()
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!show) {
                            settingsButton.setVisibility(View.INVISIBLE);
                        }
                    }
                });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        // remove all scheduled runnables
        mHandler.removeCallbacksAndMessages(null);
    }

    public void updateSources() {
        mSelectedSource = null;
        Intent queryIntent = new Intent(ACTION_MUZEI_ART_SOURCE);
        PackageManager pm = getActivity().getPackageManager();
        mSources.clear();
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(queryIntent,
                PackageManager.GET_META_DATA);

        for (ResolveInfo ri : resolveInfos) {
            Source source = new Source();
            source.label = ri.loadLabel(pm).toString();
            source.icon = new BitmapDrawable(getResources(), generateSourceImage(ri.loadIcon(pm)));
            source.componentName = new ComponentName(ri.serviceInfo.packageName,
                    ri.serviceInfo.name);
            Context packageContext;
            try {
                packageContext = getActivity().createPackageContext(
                        source.componentName.getPackageName(), 0);
                Resources packageRes = packageContext.getResources();
                source.description = packageRes.getString(ri.serviceInfo.descriptionRes);
            } catch (PackageManager.NameNotFoundException e) {
                LOGW(TAG, "Can't read package resources for source " + source.componentName);
            }
            Bundle metaData = ri.serviceInfo.metaData;
            source.color = Color.WHITE;
            if (metaData != null) {
                String settingsActivity = metaData.getString("settingsActivity");
                if (!TextUtils.isEmpty(settingsActivity)) {
                    source.settingsActivity = ComponentName.unflattenFromString(
                            ri.serviceInfo.packageName + "/" + settingsActivity);
                }

                source.color = metaData.getInt("color", source.color);

                try {
                    float[] hsv = new float[3];
                    Color.colorToHSV(source.color, hsv);
                    boolean adjust = false;
                    if (hsv[2] < 0.8f) {
                        hsv[2] = 0.8f;
                        adjust = true;
                    }
                    if (hsv[1] > 0.4f) {
                        hsv[1] = 0.4f;
                        adjust = true;
                    }
                    if (adjust) {
                        source.color = Color.HSVToColor(hsv);
                    }
                    if (Color.alpha(source.color) != 255) {
                        source.color = Color.argb(255,
                                Color.red(source.color),
                                Color.green(source.color),
                                Color.blue(source.color));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }

            mSources.add(source);
        }

        final String appPackage = getActivity().getPackageName();
        Collections.sort(mSources, new Comparator<Source>() {
            @Override
            public int compare(Source s1, Source s2) {
                String pn1 = s1.componentName.getPackageName();
                String pn2 = s2.componentName.getPackageName();
                if (!pn1.equals(pn2)) {
                    if (appPackage.equals(pn1)) {
                        return -1;
                    } else if (appPackage.equals(pn2)) {
                        return 1;
                    }
                }
                return s1.label.compareTo(s2.label);
            }
        });

        redrawSources();
    }

    private void redrawSources() {
        if (mSourceContainerView == null || !isAdded()) {
            return;
        }

        mSourceContainerView.removeAllViews();
        for (final Source source : mSources) {
            source.rootView = LayoutInflater.from(getActivity()).inflate(
                    R.layout.settings_choose_source_item, mSourceContainerView, false);

            source.selectSourceButton = source.rootView.findViewById(R.id.source_image);
            source.selectSourceButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (source.componentName.equals(mSelectedSource)) {
                        ((Callbacks) getActivity()).onRequestCloseActivity();
                    } else {
                        mSourceManager.selectSource(source.componentName);
                    }
                }
            });

            source.selectSourceButton.setOnLongClickListener(new View.OnLongClickListener() {

                @Override
                public boolean onLongClick(View v) {
                    final String pkg = source.componentName.getPackageName();
                    if (TextUtils.equals(pkg, getActivity().getPackageName())) {
                        // Don't open Muzei's app info
                        return false;
                    }
                    // Otherwise open third party extensions
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", pkg, null)));
                    } catch (final ActivityNotFoundException e) {
                        return false;
                    }
                    return true;
                }
            });

            source.rootView.setAlpha(ALPHA_UNSELECTED);

            source.icon.setColorFilter(source.color, PorterDuff.Mode.SRC_ATOP);
            source.selectSourceButton.setBackground(source.icon);

            TextView titleView = (TextView) source.rootView.findViewById(R.id.source_title);
            titleView.setText(source.label);
            titleView.setTextColor(source.color);

            SourceState state = mSourceManager.getSourceState(source.componentName);
            updateSourceStatusUi(source, state);

            source.settingsButton = source.rootView.findViewById(R.id.source_settings_button);
            CheatSheet.setup(source.settingsButton);
            source.settingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    try {
                        startActivity(new Intent()
                                .setComponent(source.settingsActivity)
                                .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        LOGE(TAG, "Can't launch source settings.", e);
                    }
                }
            });

            animateSettingsButton(source.settingsButton, false, false);

            mSourceContainerView.addView(source.rootView);
        }

        updateSelectedItem(false);
    }

    private void updateSourceStatusUi(Source source, SourceState state) {
        if (source.rootView == null) {
            return;
        }

        ((TextView) source.rootView.findViewById(R.id.source_status)).setText(
                (state != null &&
                        !TextUtils.isEmpty(state.getDescription()))
                        ? state.getDescription()
                        : source.description);
    }

    private void prepareGenerateSourceImages() {
        mImageFillPaint.setColor(Color.WHITE);
        mImageFillPaint.setAntiAlias(true);
        mAlphaPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        mSelectedSourceImage = new BitmapDrawable(getResources(),
                generateSourceImage(getResources().getDrawable(R.drawable.ic_source_selected)));
    }

    private Bitmap generateSourceImage(Drawable image) {
        Bitmap bitmap = Bitmap.createBitmap(mItemImageSize, mItemImageSize,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        mTempRectF.set(0, 0, mItemImageSize, mItemImageSize);
        canvas.drawOval(mTempRectF, mImageFillPaint);
        if (image != null) {
            canvas.saveLayer(0, 0, mItemImageSize, mItemImageSize, mAlphaPaint,
                    Canvas.ALL_SAVE_FLAG);
            image.setBounds(0, 0, mItemImageSize, mItemImageSize);
            image.draw(canvas);
            canvas.restore();
        }
        return bitmap;
    }

    private void showScrollbar() {
        mHandler.removeCallbacks(mHideScrollbarRunnable);
        mScrollbar.setScrollRangeAndViewportWidth(
                mSourceScrollerView.computeHorizontalScrollRange(),
                mSourceScrollerView.getWidth());
        mScrollbar.setScrollPosition(mSourceScrollerView.getScrollX());
        mScrollbar.show();
        mHandler.postDelayed(mHideScrollbarRunnable, SCROLLBAR_HIDE_DELAY_MILLIS);
    }

    private Runnable mHideScrollbarRunnable = new Runnable() {
        @Override
        public void run() {
            mScrollbar.hide();
        }
    };

    public class Source {
        public View rootView;
        public Drawable icon;
        public int color;
        public String label;
        public ComponentName componentName;
        public String description;
        public ComponentName settingsActivity;
        public View selectSourceButton;
        public View settingsButton;
    }

    public interface Callbacks {
        void onRequestCloseActivity();
    }
}
