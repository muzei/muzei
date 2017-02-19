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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.featuredart.FeaturedArtSource;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

public class SourceManager {
    private static final String TAG = "SourceManager";
    private static final String PREF_SELECTED_SOURCE = "selected_source";
    private static final String PREF_SOURCE_STATES = "source_states";
    private static final String USER_PROPERTY_SELECTED_SOURCE = "selected_source";
    private static final String USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package";

    private SourceManager() {
    }

    /**
     * One time migration of source data from SharedPreferences to the ContentProvider
     */
    private static void migrateDataToContentProvider(Context context) {
        SharedPreferences sharedPrefs = context.getSharedPreferences("muzei_art_sources", 0);
        String selectedSourceString = sharedPrefs.getString(PREF_SELECTED_SOURCE, null);
        Set<String> sourceStates = sharedPrefs.getStringSet(PREF_SOURCE_STATES, null);
        if (selectedSourceString == null || sourceStates == null) {
            return;
        }
        ComponentName selectedSource = ComponentName.unflattenFromString(selectedSourceString);

        final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (String sourceStatesPair : sourceStates) {
            String[] pair = sourceStatesPair.split("\\|", 2);
            ComponentName source = ComponentName.unflattenFromString(pair[0]);
            try {
                ContentValues values = new ContentValues();
                try {
                    // Ensure the source is a valid Service
                    context.getPackageManager().getServiceInfo(source, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    // No need to keep no longer valid sources
                    continue;
                }
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
                Log.e(TAG, "Error loading source state for " + source, e);
            }
        }
        try {
            context.getContentResolver().applyBatch(MuzeiContract.AUTHORITY, operations);
            sharedPrefs.edit().remove(PREF_SELECTED_SOURCE).remove(PREF_SOURCE_STATES).apply();
            sendSelectedSourceAnalytics(context, selectedSource);
        } catch (RemoteException | OperationApplicationException e) {
            Log.e(TAG, "Error writing sources to ContentProvider", e);
        }
    }

    public static void selectSource(Context context, ComponentName source) {
        if (source == null) {
            Log.e(TAG, "selectSource: Empty source");
            return;
        }

        ComponentName selectedSource = getSelectedSource(context);
        if (source.equals(selectedSource)) {
            return;
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Source " + source + " selected.");
        }

        final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        if (selectedSource != null) {
            unsubscribeToSelectedSource(context);

            // Unselect the old source
            operations.add(ContentProviderOperation.newUpdate(MuzeiContract.Sources.CONTENT_URI)
                    .withValue(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                            selectedSource.flattenToShortString())
                    .withValue(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, false)
                    .withSelection(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                            new String[] {selectedSource.flattenToShortString()})
                    .build());
        }

        // Select the new source
        operations.add(ContentProviderOperation.newUpdate(MuzeiContract.Sources.CONTENT_URI)
                .withValue(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                        source.flattenToShortString())
                .withValue(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED, true)
                .withSelection(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                        new String[] {source.flattenToShortString()})
                .build());

        try {
            context.getContentResolver().applyBatch(MuzeiContract.AUTHORITY, operations);
            sendSelectedSourceAnalytics(context, source);
        } catch (RemoteException | OperationApplicationException e) {
            Log.e(TAG, "Error writing sources to ContentProvider", e);
        }

        subscribeToSelectedSource(context);

        // Ensure the artwork from the newly selected source is downloaded
        context.startService(TaskQueueService.getDownloadCurrentArtworkIntent(context));
    }

    private static void sendSelectedSourceAnalytics(Context context, ComponentName selectedSource) {
        // The current limit for user property values
        final int MAX_VALUE_LENGTH = 36;
        String packageName = selectedSource.getPackageName();
        if (packageName.length() > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName);
        String className = selectedSource.flattenToShortString();
        className = className.substring(className.indexOf('/')+1);
        if (className.length() > MAX_VALUE_LENGTH) {
            className = className.substring(className.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
                className);
    }

    public static ComponentName getSelectedSource(Context context) {
        try (Cursor data = context.getContentResolver().query(MuzeiContract.Sources.CONTENT_URI,
                new String[] {MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME},
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + "=1", null, null)) {
            return data != null && data.moveToFirst()
                    ? ComponentName.unflattenFromString(data.getString(0))
                    : null;
        }
    }

    public static void sendAction(Context context, int id) {
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource != null) {
            context.startService(new Intent(ACTION_HANDLE_COMMAND)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_COMMAND_ID, id));
        }
    }

    public static void subscribeToSelectedSource(Context context) {
        // Migrate any legacy data to the ContentProvider
        migrateDataToContentProvider(context);
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource != null) {
            context.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(context, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()));
        } else {
            // Select the default source
            selectSource(context, new ComponentName(context, FeaturedArtSource.class));
        }
    }

    public static void unsubscribeToSelectedSource(Context context) {
        ComponentName selectedSource = getSelectedSource(context);
        if (selectedSource != null) {
            context.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(context, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, (String) null));
        }
    }
}
