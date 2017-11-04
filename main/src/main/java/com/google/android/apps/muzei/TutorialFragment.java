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
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

public class TutorialFragment extends Fragment {
    public static final String PREF_SEEN_TUTORIAL = "seen_tutorial";

    private AnimatorSet mAnimator = null;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tutorial_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        // Ensure we have the latest insets
        view.requestFitSystemWindows();
        view.findViewById(R.id.tutorial_icon_affordance).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        FirebaseAnalytics.getInstance(getContext())
                                .logEvent(FirebaseAnalytics.Event.TUTORIAL_COMPLETE, null);
                        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
                        sp.edit().putBoolean(PREF_SEEN_TUTORIAL, true).apply();
                    }
                });

        if (savedInstanceState == null) {
            float animateDistance = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100,
                    getResources().getDisplayMetrics());
            View mainTextView = view.findViewById(R.id.tutorial_main_text);
            mainTextView.setAlpha(0);
            mainTextView.setTranslationY(-animateDistance / 5);
            View subTextView = view.findViewById(R.id.tutorial_sub_text);
            subTextView.setAlpha(0);
            subTextView.setTranslationY(-animateDistance / 5);
            final View affordanceView = view.findViewById(R.id.tutorial_icon_affordance);
            affordanceView.setAlpha(0);
            affordanceView.setTranslationY(animateDistance);
            View iconTextView = view.findViewById(R.id.tutorial_icon_text);
            iconTextView.setAlpha(0);
            iconTextView.setTranslationY(animateDistance);
            mAnimator = new AnimatorSet();
            mAnimator.setStartDelay(500);
            mAnimator.setDuration(250);
            mAnimator.playTogether(
                    ObjectAnimator.ofFloat(mainTextView, View.ALPHA, 1f),
                    ObjectAnimator.ofFloat(subTextView, View.ALPHA, 1f));
            mAnimator.start();
            mAnimator = new AnimatorSet();
            mAnimator.setStartDelay(2000);
            // Bug in older versions where set.setInterpolator didn't work
            Interpolator interpolator = new OvershootInterpolator();
            ObjectAnimator a1 = ObjectAnimator.ofFloat(affordanceView, View.TRANSLATION_Y, 0);
            ObjectAnimator a2 = ObjectAnimator.ofFloat(iconTextView, View.TRANSLATION_Y, 0);
            ObjectAnimator a3 = ObjectAnimator.ofFloat(mainTextView, View.TRANSLATION_Y, 0);
            ObjectAnimator a4 = ObjectAnimator.ofFloat(subTextView, View.TRANSLATION_Y, 0);
            a1.setInterpolator(interpolator);
            a2.setInterpolator(interpolator);
            a3.setInterpolator(interpolator);
            a4.setInterpolator(interpolator);
            mAnimator.setDuration(500).playTogether(
                    ObjectAnimator.ofFloat(affordanceView, View.ALPHA, 1f),
                    ObjectAnimator.ofFloat(iconTextView, View.ALPHA, 1f),
                    a1, a2, a3, a4);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (isAdded() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ImageView emanateView = view.findViewById(R.id.tutorial_icon_emanate);
                            AnimatedVectorDrawable avd = (AnimatedVectorDrawable)
                                    getResources().getDrawable(
                                            R.drawable.avd_tutorial_icon_emanate,
                                            getContext().getTheme());
                            emanateView.setImageDrawable(avd);
                            avd.start();
                        }
                    }
                });
            }
            mAnimator.start();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ImageView emanateView = view.findViewById(R.id.tutorial_icon_emanate);
            AnimatedVectorDrawable avd = (AnimatedVectorDrawable)
                    getResources().getDrawable(
                            R.drawable.avd_tutorial_icon_emanate,
                            getContext().getTheme());
            emanateView.setImageDrawable(avd);
            avd.start();
        }
    }

    @Override
    public void onDestroyView() {
        if (mAnimator != null) {
            mAnimator.end();
        }
        super.onDestroyView();
    }
}
