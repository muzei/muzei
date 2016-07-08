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
import android.content.Intent;
import android.os.Bundle;

import com.google.android.apps.muzei.api.internal.SourceState;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

public class SourceSubscriberService extends IntentService {
    public SourceSubscriberService() {
        super("SourceSubscriberService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (ACTION_PUBLISH_STATE.equals(action)) {
            // Handle API call from source
            String token = intent.getStringExtra(EXTRA_TOKEN);

            SourceState state = null;
            if (intent.hasExtra(EXTRA_STATE)) {
                Bundle bundle = intent.getBundleExtra(EXTRA_STATE);
                if (bundle != null) {
                    state = SourceState.fromBundle(bundle);
                }
            }

            SourceManager.getInstance(this).handlePublishState(token, state);
        }
    }
}
