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
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.sync.TaskQueueService;

import java.util.ArrayList;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

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
        Source source = MuzeiDatabase.getInstance(this).sourceDao().getCurrentSourceBlocking();
        if (source == null ||
                !TextUtils.equals(token, source.componentName.flattenToShortString())) {
            Log.w(TAG, "Dropping update from non-selected source, token=" + token
                    + " does not match token for " + source);
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

        source.description = state.getDescription();
        source.wantsNetworkAvailable = state.getWantsNetworkAvailable();
        source.supportsNextArtwork = false;
        source.commands = new ArrayList<>();
        int numSourceActions = state.getNumUserCommands();
        for (int i = 0; i < numSourceActions; i++) {
            UserCommand command = state.getUserCommandAt(i);
            if (command.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                source.supportsNextArtwork = true;
            } else {
                source.commands.add(command);
            }
        }

        com.google.android.apps.muzei.api.Artwork currentArtwork = state.getCurrentArtwork();
        if (currentArtwork != null) {
            MuzeiDatabase database = MuzeiDatabase.getInstance(this);
            database.beginTransaction();
            database.sourceDao().update(source);
            Artwork artwork = new Artwork();
            artwork.sourceComponentName = source.componentName;
            artwork.imageUri = currentArtwork.getImageUri();
            artwork.title = currentArtwork.getTitle();
            artwork.byline = currentArtwork.getByline();
            artwork.attribution = currentArtwork.getAttribution();
            artwork.token = currentArtwork.getToken();
            if (currentArtwork.getMetaFont() != null) {
                artwork.metaFont = currentArtwork.getMetaFont();
            }
            artwork.viewIntent = currentArtwork.getViewIntent();

            try {
                // Make sure we can construct a PendingIntent for the Intent
                PendingIntent.getActivity(this, 0, artwork.viewIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            } catch (RuntimeException e) {
                // This is actually meant to catch a FileUriExposedException, but you can't
                // have catch statements for exceptions that don't exist at your minSdkVersion
                Log.w(TAG, "Removing invalid View Intent that contains a file:// URI: " +
                        artwork.viewIntent, e);
                artwork.viewIntent = null;
            }

            database.artworkDao().insert(this, artwork);

            // Download the artwork contained from the newly published SourceState
            startService(TaskQueueService.getDownloadCurrentArtworkIntent(this));

            database.setTransactionSuccessful();
            database.endTransaction();
        }
    }
}
