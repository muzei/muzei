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

import android.app.Application;

import com.google.android.apps.muzei.event.SelectedSourceStateChangedEvent;

import de.greenrobot.event.EventBus;

/**
 * Note the goal of this application class is to do application-wide event wiring, NOT to
 * store any state or perform global logic. That's reserved for singletons such as
 * {@link SourceManager}.
 */
public class MuzeiApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SourceManager.getInstance(this).subscribeToSelectedSource();
        EventBus.getDefault().register(this);
    }

    public void onEvent(SelectedSourceStateChangedEvent e) {
        // When the current artwork changes, kick off the download task service.
        startService(TaskQueueService.getDownloadCurrentArtworkIntent(this));
    }
}
