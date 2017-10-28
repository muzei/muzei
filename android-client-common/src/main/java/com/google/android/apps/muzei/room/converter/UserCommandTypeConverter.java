/*
 * Copyright 2017 Google Inc.
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

package com.google.android.apps.muzei.room.converter;

import android.arch.persistence.room.TypeConverter;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.UserCommand;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a list of {@link UserCommand}s into and from a persisted value
 */
public class UserCommandTypeConverter {
    private final static String TAG = "UserCmdTypeConverter";

    @TypeConverter
    @NonNull
    public static List<UserCommand> fromString(String commandsString) {
        ArrayList<UserCommand> commands = new ArrayList<>();
        if (TextUtils.isEmpty(commandsString)) {
            return commands;
        }
        try {
            JSONArray commandArray = new JSONArray(commandsString);
            for (int h=0; h<commandArray.length(); h++) {
                commands.add(UserCommand.deserialize(commandArray.getString(h)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing commands from " + commandsString, e);
        }
        return commands;
    }

    @TypeConverter
    @NonNull
    public static String commandsListToString(List<UserCommand> commands) {
        JSONArray commandsSerialized = new JSONArray();
        if (commands != null) {
            for (UserCommand command : commands) {
                commandsSerialized.put(command.serialize());
            }
        }
        return commandsSerialized.toString();
    }
}
