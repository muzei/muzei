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
import android.app.Notification;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.render.MuzeiRendererFragment;
import com.google.android.apps.muzei.util.DrawInsetsFrameLayout;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

/**
 * The primary widget configuration activity. Serves as an interstitial when adding the widget, and
 * shows when pressing the settings button in the widget.
 */
public class SettingsActivity extends AppCompatActivity
        implements SettingsChooseSourceFragment.Callbacks {
    public static final String EXTRA_START_SECTION =
            "com.google.android.apps.muzei.settings.extra.START_SECTION";

    public static final int START_SECTION_SOURCE = 0;
    public static final int START_SECTION_ADVANCED = 1;

    private static final int[] SECTION_LABELS = new int[]{
            R.string.section_choose_source,
            R.string.section_advanced,
    };

    @SuppressWarnings("unchecked")
    private static final Class<? extends Fragment>[] SECTION_FRAGMENTS = new Class[]{
            SettingsChooseSourceFragment.class,
            SettingsAdvancedFragment.class,
    };

    private static final String PLAY_STORE_PACKAGE_NAME = "com.android.vending";

    private int mStartSection = START_SECTION_SOURCE;

    private Toolbar mAppBar;

    private ObjectAnimator mBackgroundAnimator;
    private boolean mPaused;
    private boolean mRenderLocally;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(R.layout.settings_activity);

        if (getIntent() != null && getIntent().getCategories() != null &&
                getIntent().getCategories().contains(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)) {
            mStartSection = START_SECTION_ADVANCED;
        }

        // Set up UI widgets
        setupAppBar();

        ((DrawInsetsFrameLayout) findViewById(R.id.draw_insets_frame_layout)).setOnInsetsCallback(
                new DrawInsetsFrameLayout.OnInsetsCallback() {
                    @Override
                    public void onInsetsChanged(Rect insets) {
                        View container = findViewById(R.id.container);
                        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)
                                container.getLayoutParams();
                        lp.leftMargin = insets.left;
                        lp.topMargin = insets.top;
                        lp.rightMargin = insets.right;
                        lp.bottomMargin = insets.bottom;
                        container.setLayoutParams(lp);
                    }
                });

        if (mBackgroundAnimator != null) {
            mBackgroundAnimator.cancel();
        }

        mBackgroundAnimator = ObjectAnimator.ofFloat(this, "backgroundOpacity", 0, 1);
        mBackgroundAnimator.setDuration(1000);
        mBackgroundAnimator.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    private void setupAppBar() {
        mAppBar = (Toolbar) findViewById(R.id.app_bar);
        mAppBar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onNavigateUp();
            }
        });

        final LayoutInflater inflater = LayoutInflater.from(this);
        Spinner sectionSpinner = (Spinner) findViewById(R.id.section_spinner);
        sectionSpinner.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return SECTION_LABELS.length;
            }

            @Override
            public Object getItem(int position) {
                return SECTION_LABELS[position];
            }

            @Override
            public long getItemId(int position) {
                return position + 1;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.settings_ab_spinner_list_item,
                            parent, false);
                }
                ((TextView) convertView.findViewById(android.R.id.text1)).setText(
                        getString(SECTION_LABELS[position]));
                return convertView;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.settings_ab_spinner_list_item_dropdown,
                            parent, false);
                }
                ((TextView) convertView.findViewById(android.R.id.text1)).setText(
                        getString(SECTION_LABELS[position]));
                return convertView;
            }
        });

        sectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
                Class<? extends Fragment> fragmentClass = SECTION_FRAGMENTS[position];
                Fragment currentFragment = getSupportFragmentManager().findFragmentById(
                        R.id.content_container);
                if (currentFragment != null && fragmentClass.equals(currentFragment.getClass())) {
                    return;
                }

                inflateMenuFromFragment(0);

                try {
                    Fragment newFragment = fragmentClass.newInstance();
                    getSupportFragmentManager().beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .setTransitionStyle(R.style.Muzei_SimpleFadeFragmentAnimation)
                            .replace(R.id.content_container, newFragment)
                            .commitAllowingStateLoss();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> spinner) {
            }
        });

        sectionSpinner.setSelection(mStartSection);

        inflateMenuFromFragment(0);
        mAppBar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.action_get_more_sources:
                        try {
                            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                            preferPackageForIntent(SettingsActivity.this,
                                    playStoreIntent, PLAY_STORE_PACKAGE_NAME);
                            startActivity(playStoreIntent);
                        } catch (ActivityNotFoundException activityNotFoundException1) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.play_store_not_found, Toast.LENGTH_LONG).show();
                        }
                        return true;

                    case R.id.action_about:
                        startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
                        return true;
                }

                Fragment currentFragment = getSupportFragmentManager().findFragmentById(
                        R.id.content_container);
                if (currentFragment != null
                        && currentFragment instanceof SettingsActivityMenuListener) {
                    ((SettingsActivityMenuListener) currentFragment)
                            .onSettingsActivityMenuItemClick(item);
                }

                return false;
            }
        });
    }

    public static void preferPackageForIntent(Context context, Intent intent, String packageName) {
        PackageManager pm = context.getPackageManager();
        for (ResolveInfo resolveInfo : pm.queryIntentActivities(intent, 0)) {
            if (resolveInfo.activityInfo.packageName.equals(packageName)) {
                intent.setPackage(packageName);
                break;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPaused = true;
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        mPaused = false;
        updateRenderLocallyToLatestActiveState();
    }

    @Subscribe
    public void onEventMainThread(final WallpaperActiveStateChangedEvent e) {
        if (mPaused) {
            return;
        }

        updateRenderLocally(!e.isActive());
    }

    private void updateRenderLocallyToLatestActiveState() {
        WallpaperActiveStateChangedEvent e = EventBus.getDefault().getStickyEvent(
                WallpaperActiveStateChangedEvent.class);
        if (e != null) {
            onEventMainThread(e);
        } else {
            onEventMainThread(new WallpaperActiveStateChangedEvent(false));
        }
    }

    private void updateRenderLocally(boolean renderLocally) {
        if (mRenderLocally == renderLocally) {
            return;
        }

        mRenderLocally = renderLocally;

        final View uiContainer = findViewById(R.id.container);
        final ViewGroup localRenderContainer = (ViewGroup)
                findViewById(R.id.local_render_container);

        FragmentManager fm = getSupportFragmentManager();
        Fragment localRenderFragment = fm.findFragmentById(R.id.local_render_container);
        if (mRenderLocally) {
            if (localRenderFragment == null) {
                fm.beginTransaction()
                        .add(R.id.local_render_container,
                                MuzeiRendererFragment.createInstance(false, false))
                        .commit();
            }
            if (localRenderContainer.getAlpha() == 1) {
                localRenderContainer.setAlpha(0);
            }
            localRenderContainer.setVisibility(View.VISIBLE);
            localRenderContainer.animate()
                    .alpha(1)
                    .setDuration(2000)
                    .withEndAction(null);
            uiContainer.setBackgroundColor(0x00000000); // for ripple touch feedback
        } else {
            if (localRenderFragment != null) {
                fm.beginTransaction()
                        .remove(localRenderFragment)
                        .commit();
            }
            localRenderContainer.animate()
                    .alpha(0)
                    .setDuration(1000)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            localRenderContainer.setVisibility(View.GONE);
                        }
                    });
            uiContainer.setBackground(null);
        }
    }

    @Override
    public void onRequestCloseActivity() {
        finish();
    }

    void inflateMenuFromFragment(int menuResId) {
        if (mAppBar == null) {
            return;
        }

        mAppBar.getMenu().clear();
        if (menuResId != 0) {
            mAppBar.inflateMenu(menuResId);
        }
        mAppBar.inflateMenu(R.menu.settings);
    }

    public interface SettingsActivityMenuListener {
        void onSettingsActivityMenuItemClick(MenuItem item);
    }
}
