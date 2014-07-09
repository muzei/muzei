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
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.render.MuzeiRendererFragment;
import com.google.android.apps.muzei.util.LogUtil;

import net.nurik.roman.muzei.R;

import de.greenrobot.event.EventBus;

/**
 * The primary widget configuration activity. Serves as an interstitial when adding the widget, and
 * shows when pressing the settings button in the widget.
 */
public class SettingsActivity extends Activity implements SettingsChooseSourceFragment.Callbacks {
    private static final String TAG = LogUtil.makeLogTag(SettingsActivity.class);

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

    private int mStartSection = START_SECTION_SOURCE;

    private ObjectAnimator mBackgroundAnimator;
    private View mContainerView;
    private boolean mPaused;
    private boolean mRenderLocally;

    public void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_ACTION_BAR);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.settings_activity);
        mContainerView = findViewById(R.id.content_container);

        // Set up UI widgets
        setupActionBar();

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

    private void setupActionBar() {
        final LayoutInflater inflater = getLayoutInflater();
        View navContainerView = inflater.inflate(R.layout.settings_include_actionbar_nav, null);
        navContainerView.findViewById(R.id.actionbar_done).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });

        Spinner sectionSpinner = (Spinner) navContainerView.findViewById(R.id.section_spinner);
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
                Fragment currentFragment = getFragmentManager().findFragmentById(
                        R.id.content_container);
                if (currentFragment != null && fragmentClass.equals(currentFragment.getClass())) {
                    return;
                }

                try {
                    Fragment newFragment = fragmentClass.newInstance();
                    getFragmentManager().beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .setTransitionStyle(R.style.Muzei_SimpleFadeFragmentAnimation)
                            .replace(R.id.content_container, newFragment)
                            .commitAllowingStateLoss();
                } catch (InstantiationException e) {
                    throw new RuntimeException(e);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> spinner) {
            }
        });

        sectionSpinner.setSelection(mStartSection);

        getActionBar().setCustomView(navContainerView);
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

    public void onEventMainThread(final WallpaperActiveStateChangedEvent e) {
        if (mPaused) {
            return;
        }

        updateRenderLocally(!e.isActive());
    }

    private void updateRenderLocallyToLatestActiveState() {
        WallpaperActiveStateChangedEvent e = (WallpaperActiveStateChangedEvent)
                EventBus.getDefault().getStickyEvent(WallpaperActiveStateChangedEvent.class);
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

        final ViewGroup localRenderContainer = (ViewGroup)
                findViewById(R.id.local_render_container);

        FragmentManager fm = getFragmentManager();
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
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_get_more_sources:
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://play.google.com/store/search?q=Muzei+Extension"
                                    + "&c=apps"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException activityNotFoundException1) {
                    Toast.makeText(this, this.getString(R.string.play_store_not_found), Toast.LENGTH_LONG).show();
                }
                return true;

            case R.id.action_about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestCloseActivity() {
        finish();
    }
}
