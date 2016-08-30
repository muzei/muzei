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
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.featuredart.FeaturedArtSource;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.android.apps.muzei.wearable.WearableController;
import com.google.android.apps.muzei.wearable.WearableSourceUpdateService;

import net.nurik.roman.muzei.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

/**
 * Thread-safe.
 */
public class SourceManager {
    private static final String TAG = "SourceManager";
    private static final String PREF_SELECTED_SOURCE = "selected_source";
    private static final String PREF_SELECTED_SOURCE_TOKEN = "selected_source_token";
    private static final String PREF_SOURCE_STATES = "source_states";

    private Context mApplicationContext;
    private ComponentName mSubscriberComponentName;
    private SharedPreferences mSharedPrefs;
    private ContentResolver mContentResolver;

    private ComponentName mSelectedSource;
    private String mSelectedSourceToken;

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
        mContentResolver = context.getContentResolver();
        loadStoredData();
    }

    private void loadStoredData() {
        // Migrate data to the ContentProvider
        migrateDataToContentProvider();

        // Load selected source info
        Cursor selectedSource = mContentResolver.query(MuzeiContract.Sources.CONTENT_URI,
                new String[] {MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME},
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + "=1", null, null);
        if (selectedSource != null && selectedSource.moveToFirst()) {
            mSelectedSource = ComponentName.unflattenFromString(selectedSource.getString(0));
        } else {
            selectDefaultSource();
        }
        if (selectedSource != null) {
            selectedSource.close();
        }

        mSelectedSourceToken = mSharedPrefs.getString(PREF_SELECTED_SOURCE_TOKEN, null);
    }

    private void migrateDataToContentProvider() {
        String selectedSourceString = mSharedPrefs.getString(PREF_SELECTED_SOURCE, null);
        Set<String> sourceStates = mSharedPrefs.getStringSet(PREF_SOURCE_STATES, null);
        if (selectedSourceString == null || sourceStates == null) {
            return;
        }
        ComponentName selectedSource = ComponentName.unflattenFromString(selectedSourceString);

        final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (String sourceStatesPair : sourceStates) {
            String[] pair = sourceStatesPair.split("\\|", 2);
            try {
                ContentValues values = new ContentValues();
                ComponentName source = ComponentName.unflattenFromString(pair[0]);
                values.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, source.flattenToShortString());
                values.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, source.equals(selectedSource));
                JSONObject jsonObject = (JSONObject) new JSONTokener(pair[1]).nextValue();
                values.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION, jsonObject.optString("description"));
                values.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                        jsonObject.optBoolean("wantsNetworkAvailable"));
                // Parse out the UserCommands. This ensures it is properly formatted and extracts the
                // Next Artwork built in command from the list
                List<UserCommand> commands = MuzeiContract.Sources.parseCommands(
                        jsonObject.optJSONArray("userCommands").toString());
                JSONArray commandsSerialized = new JSONArray();
                boolean supportsNextArtwork = false;
                for (UserCommand command : commands) {
                    if (command.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                        supportsNextArtwork = true;
                    } else {
                        commandsSerialized.put(command.serialize());
                    }
                }
                values.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND, supportsNextArtwork);
                values.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS, commandsSerialized.toString());
                operations.add(ContentProviderOperation.newInsert(MuzeiContract.Sources.CONTENT_URI)
                    .withValues(values).build());
            } catch (JSONException e) {
                Log.e(TAG, "Error loading source state.", e);
            }
        }
        try {
            mContentResolver.applyBatch(MuzeiContract.AUTHORITY, operations);
            mSharedPrefs.edit().remove(PREF_SELECTED_SOURCE).remove(PREF_SOURCE_STATES).apply();
            mApplicationContext.startService(
                    new Intent(mApplicationContext, WearableSourceUpdateService.class));
        } catch (RemoteException | OperationApplicationException e) {
            Log.e(TAG, "Error writing sources to ContentProvider", e);
        }
    }

    public void selectDefaultSource() {
        selectSource(new ComponentName(mApplicationContext, FeaturedArtSource.class));
    }

    public void selectSource(ComponentName source) {
        if (source == null) {
            Log.e(TAG, "selectSource: Empty source");
            return;
        }

        synchronized (this) {
            if (source.equals(mSelectedSource)) {
                return;
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Source " + source + " selected.");
            }

            final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
            if (mSelectedSource != null) {
                unsubscribeToSelectedSource();

                // Unselect the old source
                operations.add(ContentProviderOperation.newUpdate(MuzeiContract.Sources.CONTENT_URI)
                        .withValue(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                                mSelectedSource.flattenToShortString())
                        .withValue(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, false)
                        .withSelection(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                                new String[] {mSelectedSource.flattenToShortString()})
                        .build());
            }

            // Select the new source
            operations.add(ContentProviderOperation.newUpdate(MuzeiContract.Sources.CONTENT_URI)
                    .withValue(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                            source.flattenToShortString())
                    .withValue(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, false)
                    .withSelection(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                            new String[] {source.flattenToShortString()})
                    .build());

            try {
                mContentResolver.applyBatch(MuzeiContract.AUTHORITY, operations);
                // generate a new token and subscribe to new source
                mSelectedSource = source;
                mSelectedSourceToken = UUID.randomUUID().toString();
                mSharedPrefs.edit()
                        .putString(PREF_SELECTED_SOURCE, source.flattenToShortString())
                        .putString(PREF_SELECTED_SOURCE_TOKEN, mSelectedSourceToken)
                        .apply();
                mApplicationContext.startService(
                        new Intent(mApplicationContext, WearableSourceUpdateService.class));
            } catch (RemoteException | OperationApplicationException e) {
                Log.e(TAG, "Error writing sources to ContentProvider", e);
            }

            subscribeToSelectedSource();
        }

        // Ensure the artwork from the newly selected source is downloaded
        mApplicationContext.startService(TaskQueueService.getDownloadCurrentArtworkIntent(mApplicationContext));
    }

    public void handlePublishState(String token, SourceState state) {
        synchronized (this) {
            if (!TextUtils.equals(token, mSelectedSourceToken)) {
                Log.w(TAG, "Dropping update from non-selected source (token mismatch).");
                return;
            }

            ContentValues values = new ContentValues();
            values.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, mSelectedSource.flattenToShortString());
            values.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, true);
            values.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION, state.getDescription());
            values.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE, state.getWantsNetworkAvailable());
            JSONArray commandsSerialized = new JSONArray();
            int numSourceActions = state.getNumUserCommands();
            boolean supportsNextArtwork = false;
            for (int i = 0; i < numSourceActions; i++) {
                UserCommand command = state.getUserCommandAt(i);
                if (command.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                    supportsNextArtwork = true;
                } else {
                    commandsSerialized.put(command.serialize());
                }
            }
            values.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND, supportsNextArtwork);
            values.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS, commandsSerialized.toString());
            Cursor existingSource = mContentResolver.query(MuzeiContract.Sources.CONTENT_URI,
                    new String[]{BaseColumns._ID},
                    MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                    new String[] {mSelectedSource.flattenToShortString()}, null, null);
            if (existingSource != null && existingSource.moveToFirst()) {
                Uri sourceUri = ContentUris.withAppendedId(MuzeiContract.Sources.CONTENT_URI,
                        existingSource.getLong(0));
                mContentResolver.update(sourceUri, values, null, null);
            } else {
                mContentResolver.insert(MuzeiContract.Sources.CONTENT_URI, values);
            }
            if (existingSource != null) {
                existingSource.close();
            }
        }

        // We're already on a background thread, so it safe to call this directly
        WearableController.updateSource(mApplicationContext);

        Artwork artwork = state.getCurrentArtwork();
        artwork.setComponentName(mSelectedSource);
        mContentResolver.insert(MuzeiContract.Artwork.CONTENT_URI, artwork.toContentValues());

        // Download the artwork contained from the newly published SourceState
        mApplicationContext.startService(TaskQueueService.getDownloadCurrentArtworkIntent(mApplicationContext));
    }

    public synchronized ComponentName getSelectedSource() {
        return mSelectedSource;
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

    public synchronized void unsubscribeToSelectedSource() {
        if (mSelectedSource != null) {
            mApplicationContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(mSelectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT, mSubscriberComponentName)
                    .putExtra(EXTRA_TOKEN, (String) null));
        }
    }
}
