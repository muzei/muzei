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

import android.app.backup.BackupAgentHelper;
import android.app.backup.SharedPreferencesBackupHelper;

public class SettingsBackupAgent extends BackupAgentHelper {
    static final String PREFS_ADVANCED_BACKUP = "advanced_prefs";

    public void onCreate() {
        String preferencesName = getPreferencesName();

        SharedPreferencesBackupHelper helper = new SharedPreferencesBackupHelper(this, preferencesName);
        addHelper(PREFS_ADVANCED_BACKUP, helper);
    }

    private String getPreferencesName() {
        return String.format("%s_preferences", getPackageName());
    }
}
