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

package com.google.android.apps.muzei.complications;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationManager;
import android.support.wearable.complications.ComplicationProviderService;
import android.support.wearable.complications.ComplicationText;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.FullScreenActivity;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;

import java.util.Set;
import java.util.TreeSet;

/**
 * Provide Muzei backgrounds to other watch faces
 */
@TargetApi(Build.VERSION_CODES.N)
public class ArtworkComplicationProviderService extends ComplicationProviderService {
    private static final String TAG = "ArtworkComplProvider";

    static final String KEY_COMPLICATION_IDS = "complication_ids";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE);
    }

    @Override
    public void onComplicationActivated(int complicationId, int type, ComplicationManager manager) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Activated " + complicationId);
        }
        addComplication(complicationId);
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, Integer.toString(type));
        FirebaseAnalytics.getInstance(this).logEvent("complication_artwork_activated", bundle);
    }

    private void addComplication(int complicationId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complications = preferences.getStringSet(KEY_COMPLICATION_IDS, new TreeSet<String>());
        complications.add(Integer.toString(complicationId));
        preferences.edit().putStringSet(KEY_COMPLICATION_IDS, complications).apply();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "addComplication: " + complications);
        }
        ArtworkComplicationJobService.scheduleComplicationUpdateJob(this);
    }

    @Override
    public void onComplicationDeactivated(int complicationId) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Deactivated " + complicationId);
        }
        FirebaseAnalytics.getInstance(this).logEvent("complication_artwork_deactivated", null);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complications = preferences.getStringSet(KEY_COMPLICATION_IDS, new TreeSet<String>());
        complications.remove(Integer.toString(complicationId));
        preferences.edit().putStringSet(KEY_COMPLICATION_IDS, complications).apply();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Current complications: " + complications);
        }
        if (complications.isEmpty()) {
            ArtworkComplicationJobService.cancelComplicationUpdateJob(this);
        }
    }

    @Override
    public void onComplicationUpdate(final int complicationId, final int type, final ComplicationManager complicationManager) {
        // Make sure that the complicationId is really in our set of added complications
        // This fixes corner cases like Muzei being uninstalled and reinstalled
        // (which wipes out our SharedPreferences but keeps any complications activated)
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complications = preferences.getStringSet(KEY_COMPLICATION_IDS, new TreeSet<String>());
        if (!complications.contains(Integer.toString(complicationId))) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Update missing id " + complicationId);
            }
            addComplication(complicationId);
        }
        final Context applicationContext = getApplicationContext();
        final LiveData<Artwork> artworkLiveData = MuzeiDatabase.getInstance(this).artworkDao().getCurrentArtwork();
        artworkLiveData.observeForever(new Observer<Artwork>() {
            @Override
            public void onChanged(@Nullable final Artwork artwork) {
                artworkLiveData.removeObserver(this);
                if (artwork == null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Update no artwork for " + complicationId);
                    }
                    complicationManager.updateComplicationData(complicationId,
                            new ComplicationData.Builder(ComplicationData.TYPE_NO_DATA).build());
                    return;
                }
                ComplicationData.Builder builder = new ComplicationData.Builder(type);
                Intent intent = new Intent(applicationContext, FullScreenActivity.class);
                PendingIntent tapAction = PendingIntent.getActivity(applicationContext, 0, intent, 0);
                switch (type) {
                    case ComplicationData.TYPE_LONG_TEXT:
                        String title = artwork.title;
                        String byline = artwork.byline;
                        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(byline)) {
                            // Both are empty so we don't have any data to show
                            complicationManager.updateComplicationData(complicationId,
                                    new ComplicationData.Builder(ComplicationData.TYPE_NO_DATA).build());
                            return;
                        } else if (TextUtils.isEmpty(title)) {
                            // We only have the byline, so use that as the long text
                            builder.setLongText(ComplicationText.plainText(byline));
                        } else {
                            if (!TextUtils.isEmpty(byline)) {
                                builder.setLongTitle(ComplicationText.plainText(byline));
                            }
                            builder.setLongText(ComplicationText.plainText(title));
                        }
                        builder.setTapAction(tapAction);
                        break;
                    case ComplicationData.TYPE_SMALL_IMAGE:
                        builder.setImageStyle(ComplicationData.IMAGE_STYLE_PHOTO)
                                .setSmallImage(Icon.createWithContentUri(MuzeiContract.Artwork.CONTENT_URI));
                        builder.setTapAction(tapAction);
                        break;
                    case ComplicationData.TYPE_LARGE_IMAGE:
                        builder.setLargeImage(Icon.createWithContentUri(MuzeiContract.Artwork.CONTENT_URI));
                        break;
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Updated " + complicationId);
                }
                complicationManager.updateComplicationData(complicationId, builder.build());
            }
        });
    }
}
