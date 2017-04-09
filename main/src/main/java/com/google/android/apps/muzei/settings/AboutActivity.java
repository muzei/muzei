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

import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.support.customtabs.CustomTabsIntent;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.TextView;

import com.google.android.apps.muzei.render.MuzeiRendererFragment;
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

public class AboutActivity extends AppCompatActivity {

    private ViewPropertyAnimator mAnimator = null;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.about_activity);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        ((Toolbar) findViewById(R.id.app_bar)).setNavigationOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        onNavigateUp();
                    }
                });

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.demo_view_container,
                            MuzeiRendererFragment.createInstance(true, false))
                    .commit();
        }

        // Build the about body view and append the link to see OSS licenses
        TextView versionView = (TextView) findViewById(R.id.app_version);
        versionView.setText(Html.fromHtml(
                getString(R.string.about_version_template, BuildConfig.VERSION_NAME)));

        TextView aboutBodyView = (TextView) findViewById(R.id.about_body);
        aboutBodyView.setText(Html.fromHtml(getString(R.string.about_body)));
        aboutBodyView.setMovementMethod(new LinkMovementMethod());

        findViewById(R.id.android_experiment_link).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CustomTabsIntent cti = new CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .setToolbarColor(ContextCompat.getColor(AboutActivity.this, R.color.theme_primary))
                        .build();
                try {
                    cti.launchUrl(AboutActivity.this, Uri.parse("https://www.androidexperiments.com/experiment/muzei"));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        View demoContainerView = findViewById(R.id.demo_view_container);
        demoContainerView.setAlpha(0);
        mAnimator = demoContainerView.animate()
                .alpha(1)
                .setStartDelay(250)
                .setDuration(1000)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        AnimatedMuzeiLogoFragment logoFragment = (AnimatedMuzeiLogoFragment)
                                getFragmentManager().findFragmentById(R.id.animated_logo_fragment);
                        if (logoFragment != null) {
                            logoFragment.start();
                        }
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }
}
