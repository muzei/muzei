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
import android.app.Notification;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.TooltipCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.util.ObservableHorizontalScrollView;
import com.google.android.apps.muzei.util.Scrollbar;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.google.android.apps.muzei.api.MuzeiArtSource.ACTION_MUZEI_ART_SOURCE;

/**
 * Fragment for allowing the user to choose the active source.
 */
public class ChooseSourceFragment extends Fragment {
    private static final String TAG = "SettingsChooseSourceFrg";

    private static final String PLAY_STORE_PACKAGE_NAME = "com.android.vending";

    private static final int SCROLLBAR_HIDE_DELAY_MILLIS = 1000;

    private static final float ALPHA_DISABLED = 0.2f;
    private static final float ALPHA_UNSELECTED = 0.4f;

    private static final int REQUEST_EXTENSION_SETUP = 1;

    private ComponentName mSelectedSource;
    private LiveData<com.google.android.apps.muzei.room.Source> mCurrentSourceLiveData;
    private List<Source> mSources = new ArrayList<>();

    private Handler mHandler = new Handler();

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

    private ComponentName mCurrentInitialSetupSource;

    public ChooseSourceFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mAnimationDuration = getResources().getInteger(android.R.integer.config_shortAnimTime);
        mItemWidth = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_item_width);
        mItemEstimatedHeight = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_item_estimated_height);
        mItemImageSize = getResources().getDimensionPixelSize(
                R.dimen.settings_choose_source_item_image_size);

        prepareGenerateSourceImages();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources");
        FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.VIEW_ITEM_LIST, bundle);

        mCurrentSourceLiveData = MuzeiDatabase.getInstance(context).sourceDao().getCurrentSource();
        mCurrentSourceLiveData.observe(this,
                new Observer<com.google.android.apps.muzei.room.Source>() {
                    @Override
                    public void onChanged(@Nullable final com.google.android.apps.muzei.room.Source source) {
                        updateSelectedItem(source, true);
                    }
                });

        Intent intent = ((Activity) context).getIntent();
        if (intent != null && intent.getCategories() != null &&
                intent.getCategories().contains(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)) {
            FirebaseAnalytics.getInstance(context).logEvent("notification_preferences_open", null);
            NotificationSettingsDialogFragment.showSettings(this);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.settings_choose_source, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_notification_settings:
                NotificationSettingsDialogFragment.showSettings(this);
                return true;
            case R.id.action_get_more_sources:
                FirebaseAnalytics.getInstance(getContext()).logEvent("more_sources_open", null);
                try {
                    Intent playStoreIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                    preferPackageForIntent(playStoreIntent, PLAY_STORE_PACKAGE_NAME);
                    startActivity(playStoreIntent);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(getContext(),
                            R.string.play_store_not_found, Toast.LENGTH_LONG).show();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void preferPackageForIntent(Intent intent, String packageName) {
        PackageManager pm = getContext().getPackageManager();
        for (ResolveInfo resolveInfo : pm.queryIntentActivities(intent, 0)) {
            if (resolveInfo.activityInfo.packageName.equals(packageName)) {
                intent.setPackage(packageName);
                break;
            }
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.settings_choose_source_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable Bundle savedInstanceState) {
        // Ensure we have the latest insets
        view.requestFitSystemWindows();

        mScrollbar = view.findViewById(R.id.source_scrollbar);
        mSourceScrollerView = view.findViewById(R.id.source_scroller);
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
        });
        mSourceContainerView = view.findViewById(R.id.source_container);

        redrawSources();

        view.setVisibility(View.INVISIBLE);
        view.getViewTreeObserver().addOnGlobalLayoutListener(
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
                            view.setVisibility(View.VISIBLE);
                            ++mPass;
                        } else {
                            // Last pass, remove the listener
                            view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });

        view.setAlpha(0);
        view.animate().alpha(1f).setDuration(500);
    }

    private void updatePadding() {
        int rootViewWidth = getView() != null ? getView().getWidth() : 0;
        if (rootViewWidth == 0) {
            return;
        }
        int topPadding = Math.max(0, (getView().getHeight() - mItemEstimatedHeight) / 2);
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
        getContext().registerReceiver(mPackagesChangedReceiver, packageChangeIntentFilter);
    }

    @Override
    public void onPause() {
        super.onPause();
        getContext().unregisterReceiver(mPackagesChangedReceiver);
    }

    private void updateSelectedItem(com.google.android.apps.muzei.room.Source selectedSource, boolean allowAnimate) {
        ComponentName previousSelectedSource = mSelectedSource;
        if (selectedSource != null) {
            mSelectedSource = selectedSource.componentName;
        }
        if (previousSelectedSource != null && previousSelectedSource.equals(mSelectedSource)) {
            // Only update status
            for (final Source source : mSources) {
                if (!source.componentName.equals(mSelectedSource) || source.rootView == null) {
                    continue;
                }
                updateSourceStatusUi(source);
            }
            return;
        }

        // This is a newly selected source.
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

            float alpha = selected ? 1f : Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && source.targetSdkVersion >= Build.VERSION_CODES.O ? ALPHA_DISABLED : ALPHA_UNSELECTED;
            source.rootView.animate()
                    .alpha(alpha)
                    .setDuration(mAnimationDuration);

            if (selected) {
                updateSourceStatusUi(source);
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
        if ((show && settingsButton.getVisibility() == View.VISIBLE) ||
                (!show && settingsButton.getVisibility() == View.INVISIBLE)) {
            return;
        }
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
        // remove all scheduled runnables
        mHandler.removeCallbacksAndMessages(null);
    }

    public void updateSources() {
        mSelectedSource = null;
        Intent queryIntent = new Intent(ACTION_MUZEI_ART_SOURCE);
        PackageManager pm = getContext().getPackageManager();
        mSources.clear();
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(queryIntent,
                PackageManager.GET_META_DATA);

        for (ResolveInfo ri : resolveInfos) {
            Source source = new Source();
            source.label = ri.loadLabel(pm).toString();
            source.icon = new BitmapDrawable(getResources(), generateSourceImage(ri.loadIcon(pm)));
            source.targetSdkVersion = ri.serviceInfo.applicationInfo.targetSdkVersion;
            source.componentName = new ComponentName(ri.serviceInfo.packageName,
                    ri.serviceInfo.name);
            if (ri.serviceInfo.descriptionRes != 0) {
                try {
                    Context packageContext = getContext().createPackageContext(
                            source.componentName.getPackageName(), 0);
                    Resources packageRes = packageContext.getResources();
                    source.description = packageRes.getString(ri.serviceInfo.descriptionRes);
                } catch (PackageManager.NameNotFoundException|Resources.NotFoundException e) {
                    Log.e(TAG, "Can't read package resources for source " + source.componentName);
                }
            }
            Bundle metaData = ri.serviceInfo.metaData;
            source.color = Color.WHITE;
            if (metaData != null) {
                String settingsActivity = metaData.getString("settingsActivity");
                if (!TextUtils.isEmpty(settingsActivity)) {
                    source.settingsActivity = ComponentName.unflattenFromString(
                            ri.serviceInfo.packageName + "/" + settingsActivity);
                }

                String setupActivity = metaData.getString("setupActivity");
                if (!TextUtils.isEmpty(setupActivity)) {
                    source.setupActivity = ComponentName.unflattenFromString(
                            ri.serviceInfo.packageName + "/" + setupActivity);
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

        final String appPackage = getContext().getPackageName();
        Collections.sort(mSources, new Comparator<Source>() {
            @Override
            public int compare(Source s1, Source s2) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    boolean target1IsO = s1.targetSdkVersion >= Build.VERSION_CODES.O;
                    boolean target2IsO = s2.targetSdkVersion >= Build.VERSION_CODES.O;
                    if (target1IsO && !target2IsO) {
                        return 1;
                    } else if (!target1IsO && target2IsO) {
                        return -1;
                    }
                }
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
            source.rootView = LayoutInflater.from(getContext()).inflate(
                    R.layout.settings_choose_source_item, mSourceContainerView, false);

            source.selectSourceButton = source.rootView.findViewById(R.id.source_image);
            source.selectSourceButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (source.componentName.equals(mSelectedSource)) {
                        if (getContext() instanceof Callbacks) {
                            ((Callbacks) getContext()).onRequestCloseActivity();
                        } else if (getParentFragment() instanceof Callbacks) {
                            ((Callbacks) getParentFragment()).onRequestCloseActivity();
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && source.targetSdkVersion >= Build.VERSION_CODES.O) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                                .setTitle(R.string.action_source_target_too_high_title)
                                .setMessage(R.string.action_source_target_too_high_message)
                                .setNegativeButton(R.string.action_source_target_too_high_learn_more,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                startActivity(new Intent(Intent.ACTION_VIEW,
                                                        Uri.parse("https://medium.com/@ianhlake/the-muzei-plugin-api-and-androids-evolution-9b9979265cfb")));
                                            }
                                        })
                                .setPositiveButton(R.string.action_source_target_too_high_dismiss, null);
                        final Intent sendFeedbackIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id="
                                        + source.componentName.getPackageName()));
                        if (sendFeedbackIntent.resolveActivity(getContext().getPackageManager()) != null) {
                            builder.setNeutralButton(
                                    getString(R.string.action_source_target_too_high_send_feedback, source.label),
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            startActivity(sendFeedbackIntent);
                                        }
                                    });
                        }
                        builder.show();
                    } else if (source.setupActivity != null) {
                        Bundle bundle = new Bundle();
                        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, source.componentName.flattenToShortString());
                        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, source.label);
                        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "sources");
                        FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.VIEW_ITEM, bundle);
                        mCurrentInitialSetupSource = source.componentName;
                        launchSourceSetup(source);
                    } else {
                        Bundle bundle = new Bundle();
                        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, source.componentName.flattenToShortString());
                        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                        FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                        SourceManager.selectSource(getContext(), source.componentName);
                    }
                }
            });

            source.selectSourceButton.setOnLongClickListener(new View.OnLongClickListener() {

                @Override
                public boolean onLongClick(View v) {
                    final String pkg = source.componentName.getPackageName();
                    if (TextUtils.equals(pkg, getContext().getPackageName())) {
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

            float alpha = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && source.targetSdkVersion >= Build.VERSION_CODES.O
                    ? ALPHA_DISABLED
                    : ALPHA_UNSELECTED;
            source.rootView.setAlpha(alpha);

            source.icon.setColorFilter(source.color, PorterDuff.Mode.SRC_ATOP);
            source.selectSourceButton.setBackground(source.icon);

            TextView titleView = source.rootView.findViewById(R.id.source_title);
            titleView.setText(source.label);
            titleView.setTextColor(source.color);

            updateSourceStatusUi(source);

            source.settingsButton = source.rootView.findViewById(R.id.source_settings_button);
            TooltipCompat.setTooltipText(source.settingsButton, source.settingsButton.getContentDescription());
            source.settingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    launchSourceSettings(source);
                }
            });

            animateSettingsButton(source.settingsButton, false, false);

            mSourceContainerView.addView(source.rootView);
        }

        updateSelectedItem(mCurrentSourceLiveData.getValue(), false);
    }

    private void launchSourceSettings(Source source) {
        try {
            Intent settingsIntent = new Intent()
                    .setComponent(source.settingsActivity)
                    .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true);
            startActivity(settingsIntent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Can't launch source settings.", e);
        }
    }

    private void launchSourceSetup(Source source) {
        try {
            Intent setupIntent = new Intent()
                    .setComponent(source.setupActivity)
                    .putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true);
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Can't launch source setup.", e);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_EXTENSION_SETUP) {
            if (resultCode == Activity.RESULT_OK && mCurrentInitialSetupSource != null) {
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.ITEM_ID, mCurrentInitialSetupSource.flattenToShortString());
                bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "sources");
                FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
                SourceManager.selectSource(getContext(), mCurrentInitialSetupSource);
            }

            mCurrentInitialSetupSource = null;
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void updateSourceStatusUi(final Source source) {
        if (source.rootView == null) {
            return;
        }
        final LiveData<com.google.android.apps.muzei.room.Source> sourceLiveData = MuzeiDatabase
                .getInstance(getContext())
                .sourceDao()
                .getSourceByComponentName(source.componentName);
        sourceLiveData.observeForever(new Observer<com.google.android.apps.muzei.room.Source>() {
            @Override
            public void onChanged(@Nullable final com.google.android.apps.muzei.room.Source storedSource) {
                sourceLiveData.removeObserver(this);
                String description = storedSource != null ? storedSource.description : null;
                ((TextView) source.rootView.findViewById(R.id.source_status)).setText(
                        !TextUtils.isEmpty(description) ? description : source.description);
            }
        });
    }

    private void prepareGenerateSourceImages() {
        mImageFillPaint.setColor(Color.WHITE);
        mImageFillPaint.setAntiAlias(true);
        mAlphaPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        mSelectedSourceImage = new BitmapDrawable(getResources(),
                generateSourceImage(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_source_selected, null)));
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

    class Source {
        View rootView;
        Drawable icon;
        int color;
        String label;
        int targetSdkVersion;
        ComponentName componentName;
        String description;
        ComponentName settingsActivity;
        View selectSourceButton;
        View settingsButton;
        ComponentName setupActivity;
    }

    public interface Callbacks {
        void onRequestCloseActivity();
    }
}
