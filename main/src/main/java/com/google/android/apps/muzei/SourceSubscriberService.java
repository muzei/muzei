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

import android.app.IntentService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.sync.TaskQueueService;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

import org.json.JSONArray;

public class SourceSubscriberService extends IntentService {
    private static final String TAG = "SourceSubscriberService";

    public SourceSubscriberService() {
        super("SourceSubscriberService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!ACTION_PUBLISH_STATE.equals(action)) {
            return;
        }
        // Handle API call from source
        String token = intent.getStringExtra(EXTRA_TOKEN);
        ComponentName selectedSource = SourceManager.getSelectedSource(this);
        if (selectedSource == null ||
                !TextUtils.equals(token, selectedSource.flattenToShortString())) {
            Log.w(TAG, "Dropping update from non-selected source, token=" + token
                    + " does not match token for " + selectedSource);
            return;
        }

        SourceState state = null;
        if (intent.hasExtra(EXTRA_STATE)) {
            Bundle bundle = intent.getBundleExtra(EXTRA_STATE);
            if (bundle != null) {
                state = SourceState.fromBundle(bundle);
            }
        }

        if (state == null) {
            // If there is no state, there is nothing to change
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, selectedSource.flattenToShortString());
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
        ContentResolver contentResolver = getContentResolver();
        Cursor existingSource = contentResolver.query(MuzeiContract.Sources.CONTENT_URI,
                new String[]{BaseColumns._ID},
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                new String[] {selectedSource.flattenToShortString()}, null, null);
        if (existingSource != null && existingSource.moveToFirst()) {
            Uri sourceUri = ContentUris.withAppendedId(MuzeiContract.Sources.CONTENT_URI,
                    existingSource.getLong(0));
            contentResolver.update(sourceUri, values, null, null);
        } else {
            contentResolver.insert(MuzeiContract.Sources.CONTENT_URI, values);
        }
        if (existingSource != null) {
            existingSource.close();
        }

        Artwork artwork = state.getCurrentArtwork();
        if (artwork != null) {
            artwork.setComponentName(selectedSource);
            contentResolver.insert(MuzeiContract.Artwork.CONTENT_URI, artwork.toContentValues());

            // Download the artwork contained from the newly published SourceState
            startService(TaskQueueService.getDownloadCurrentArtworkIntent(this));
        }
    }
}
