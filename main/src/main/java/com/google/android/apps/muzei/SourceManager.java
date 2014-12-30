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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.SelectedSourceChangedEvent;
import com.google.android.apps.muzei.event.SelectedSourceStateChangedEvent;
import com.google.android.apps.muzei.featuredart.FeaturedArtSource;
import com.google.android.apps.muzei.util.LogUtil;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.greenrobot.event.EventBus;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;
import static com.google.android.apps.muzei.util.LogUtil.LOGD;
import static com.google.android.apps.muzei.util.LogUtil.LOGE;
import static com.google.android.apps.muzei.util.LogUtil.LOGW;

/**
 * Thread-safe.
 */
public class SourceManager {
    private static final String TAG = LogUtil.makeLogTag(SourceManager.class);
    private static final String PREF_SELECTED_SOURCE = "selected_source";
    private static final String PREF_SELECTED_SOURCE_TOKEN = "selected_source_token";
    private static final String PREF_SOURCE_STATES = "source_states";

    private Context mApplicationContext;
    private ComponentName mSubscriberComponentName;
    private SharedPreferences mSharedPrefs;

    private ComponentName mSelectedSource;
    private String mSelectedSourceToken;
    private Map<ComponentName, SourceState> mSourceStates = new HashMap<>();

    private static SourceManager sInstance;

    public static SourceManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SourceManager(context);
        }

        return sInstance;
    }

    private SourceManager(Context context) {
        mApplicationContext = context.getApplicationContext();
        mSubscriberComponentName = new ComponentName(context, SourceSubscriberService.class);
        mSharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
        loadStoredData();
    }

    private void loadStoredData() {
        // Load selected source info
        String selectedSource = mSharedPrefs.getString(PREF_SELECTED_SOURCE, null);
        if (selectedSource != null) {
            mSelectedSource = ComponentName.unflattenFromString(selectedSource);
        } else {
            selectDefaultSource();
            return;
        }

        mSelectedSourceToken = mSharedPrefs.getString(PREF_SELECTED_SOURCE_TOKEN, null);

        // Load current source states
        Set<String> sourceStates = mSharedPrefs.getStringSet(PREF_SOURCE_STATES, null);
        mSourceStates.clear();
        if (sourceStates != null) {
            for (String sourceStatesPair : sourceStates) {
                String[] pair = sourceStatesPair.split("\\|", 2);
                try {
                    mSourceStates.put(
                            ComponentName.unflattenFromString(pair[0]),
                            SourceState.fromJson(
                                    (JSONObject) new JSONTokener(pair[1]).nextValue()));
                } catch (JSONException e) {
                    LOGE(TAG, "Error loading source state.", e);
                }
            }
        }
    }

    public void selectDefaultSource() {
        selectSource(new ComponentName(mApplicationContext, FeaturedArtSource.class));
    }

    public void selectSource(ComponentName source) {
        if (source == null) {
            LOGE(TAG, "selectSource: Empty source");
            return;
        }

        synchronized (this) {
            if (source.equals(mSelectedSource)) {
                return;
            }

            LOGD(TAG, "Source " + source + " selected.");

            if (mSelectedSource != null) {
                // unsubscribe from existing source
                mApplicationContext.startService(new Intent(ACTION_SUBSCRIBE)
                        .setComponent(mSelectedSource)
                        .putExtra(EXTRA_SUBSCRIBER_COMPONENT, mSubscriberComponentName)
                        .putExtra(EXTRA_TOKEN, (String) null));
            }

            // generate a new token and subscribe to new source
            mSelectedSource = source;
            mSelectedSourceToken = UUID.randomUUID().toString();
            mSharedPrefs.edit()
                    .putString(PREF_SELECTED_SOURCE, source.flattenToShortString())
                    .putString(PREF_SELECTED_SOURCE_TOKEN, mSelectedSourceToken)
                    .apply();

            subscribeToSelectedSource();
        }

        EventBus.getDefault().post(new SelectedSourceChangedEvent());
        EventBus.getDefault().post(new SelectedSourceStateChangedEvent());
    }

    public void handlePublishState(String token, SourceState state) {
        synchronized (this) {
            if (!TextUtils.equals(token, mSelectedSourceToken)) {
                LOGW(TAG, "Dropping update from non-selected source (token mismatch).");
                return;
            }

            if (state == null) {
                mSourceStates.remove(mSelectedSource);
            } else {
                mSourceStates.put(mSelectedSource, state);
            }

            try {
                StringBuilder sb = new StringBuilder();
                Set<String> sourceStates = new HashSet<>();
                for (ComponentName source : mSourceStates.keySet()) {
                    SourceState sourceState = mSourceStates.get(source);
                    if (sourceState == null) {
                        continue;
                    }

                    sb.setLength(0);
                    sb.append(source.flattenToShortString())
                            .append("|")
                            .append(sourceState.toJson().toString());
                    sourceStates.add(sb.toString());
                }

                mSharedPrefs.edit().putStringSet(PREF_SOURCE_STATES, sourceStates).apply();
            } catch (JSONException e) {
                LOGE(TAG, "Error storing source status data.", e);
            }
        }

        EventBus.getDefault().post(new SelectedSourceStateChangedEvent());
    }

    public synchronized ComponentName getSelectedSource() {
        return mSelectedSource;
    }

    public synchronized SourceState getSourceState(ComponentName source) {
        return mSourceStates.get(source);
    }

    public synchronized SourceState getSelectedSourceState() {
        return mSourceStates.get(mSelectedSource);
    }

    public synchronized void sendAction(int id) {
        if (mSelectedSource != null) {
            mApplicationContext.startService(new Intent(ACTION_HANDLE_COMMAND)
                    .setComponent(mSelectedSource)
                    .putExtra(EXTRA_COMMAND_ID, id));
        }
    }

    public synchronized void subscribeToSelectedSource() {
        if (mSelectedSource != null) {
            mApplicationContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(mSelectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT, mSubscriberComponentName)
                    .putExtra(EXTRA_TOKEN, mSelectedSourceToken));
        }
    }

    public synchronized void maybeDispatchNetworkAvailable() {
        SourceState state = getSelectedSourceState();
        if (state != null && state.getWantsNetworkAvailable()) {
            mApplicationContext.startService(new Intent(ACTION_NETWORK_AVAILABLE)
                    .setComponent(mSelectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT, mSubscriberComponentName)
                    .putExtra(EXTRA_TOKEN, mSelectedSourceToken));
        }
    }
}
