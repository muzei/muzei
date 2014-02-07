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

package com.google.android.apps.muzei.api;

import android.text.TextUtils;

/**
 * Data class representing a user-visible command.
 *
 * @see MuzeiArtSource#setUserCommands(int...)
 */
public class UserCommand {
    private int mId;
    private String mTitle;

    /**
     * Instantiates a user command with the given ID.
     */
    public UserCommand(int id) {
        mId = id;
    }

    /**
     * Instantiates a user command with the given ID and user-visible title text.
     */
    public UserCommand(int id, String title) {
        mId = id;
        mTitle = title;
    }

    /**
     * Returns the ID for this user command.
     */
    public int getId() {
        return mId;
    }

    /**
     * Sets the ID for this user command.
     */
    public void setId(int id) {
        mId = id;
    }


    /**
     * Returns the user-visible title text, or null if none is provided. When none is provided,
     * a default will be used if available.
     */
    public String getTitle() {
        return mTitle;
    }

    /**
     * Sets the user-visible title text, or null if none is provided. When none is provided,
     * a default will be used if available.
     */
    public void setTitle(String title) {
        mTitle = title;
    }

    /**
     * Returns a serialized version of this user command.
     */
    public String serialize() {
        return Integer.toString(mId) + (TextUtils.isEmpty(mTitle) ? "" : (":" + mTitle));
    }

    /**
     * Deserializes a user command from the given string.
     */
    public static UserCommand deserialize(String s) {
        int id = -1;
        if (TextUtils.isEmpty(s)) {
            return new UserCommand(id, null);
        }

        String[] arr = s.split(":", 2);
        try {
            id = Integer.parseInt(arr[0]);
        } catch (NumberFormatException ignored) {
        }

        String title = null;
        if (arr.length > 1) {
            title = arr[1];
        }

        return new UserCommand(id, title);
    }
}
