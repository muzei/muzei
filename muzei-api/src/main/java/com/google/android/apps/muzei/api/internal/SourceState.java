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

package com.google.android.apps.muzei.api.internal;

import android.os.Bundle;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.UserCommand;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the published state of an art source.
 * @deprecated This is only used by the deprecated
 * {@link com.google.android.apps.muzei.api.MuzeiArtSource}.
 */
@SuppressWarnings({"deprecation", "DeprecatedIsStillUsed"})
@Deprecated
public class SourceState {
    private Artwork mCurrentArtwork;
    private String mDescription;
    private boolean mWantsNetworkAvailable;
    private final ArrayList<UserCommand> mUserCommands = new ArrayList<>();

    @Deprecated
    public Artwork getCurrentArtwork() {
        return mCurrentArtwork;
    }

    @Deprecated
    public String getDescription() {
        return mDescription;
    }

    @Deprecated
    public boolean getWantsNetworkAvailable() {
        return mWantsNetworkAvailable;
    }

    @Deprecated
    public int getNumUserCommands() {
        return mUserCommands.size();
    }

    @Deprecated
    public UserCommand getUserCommandAt(int index) {
        return mUserCommands.get(index);
    }

    @Deprecated
    public void setCurrentArtwork(Artwork artwork) {
        mCurrentArtwork = artwork;
    }

    @Deprecated
    public void setDescription(String description) {
        mDescription = description;
    }

    @Deprecated
    public void setWantsNetworkAvailable(boolean wantsNetworkAvailable) {
        mWantsNetworkAvailable = wantsNetworkAvailable;
    }

    @Deprecated
    public synchronized void setUserCommands(int... userCommands) {
        mUserCommands.clear();
        if (userCommands != null) {
            mUserCommands.ensureCapacity(userCommands.length);
            for (int command : userCommands) {
                mUserCommands.add(new UserCommand(command));
            }
        }
    }

    @Deprecated
    public synchronized void setUserCommands(List<UserCommand> userCommands) {
        mUserCommands.clear();
        if (userCommands != null) {
            mUserCommands.addAll(userCommands);
        }
    }

    @Deprecated
    public synchronized Bundle toBundle() {
        Bundle bundle = new Bundle();
        if (mCurrentArtwork != null) {
            bundle.putBundle("currentArtwork", mCurrentArtwork.toBundle());
        }
        bundle.putString("description", mDescription);
        bundle.putBoolean("wantsNetworkAvailable", mWantsNetworkAvailable);
        String[] commandsSerialized = new String[mUserCommands.size()];
        for (int i = 0; i < commandsSerialized.length; i++) {
            commandsSerialized[i] = mUserCommands.get(i).serialize();
        }
        bundle.putStringArray("userCommands", commandsSerialized);
        return bundle;
    }

    @Deprecated
    public static SourceState fromBundle(Bundle bundle) {
        SourceState state = new SourceState();
        Bundle artworkBundle = bundle.getBundle("currentArtwork");
        if (artworkBundle != null) {
            state.mCurrentArtwork = Artwork.fromBundle(artworkBundle);
        }
        state.mDescription = bundle.getString("description");
        state.mWantsNetworkAvailable = bundle.getBoolean("wantsNetworkAvailable");
        String[] commandsSerialized = bundle.getStringArray("userCommands");
        if (commandsSerialized != null && commandsSerialized.length > 0) {
            state.mUserCommands.ensureCapacity(commandsSerialized.length);
            for (String s : commandsSerialized) {
                state.mUserCommands.add(UserCommand.deserialize(s));
            }
        }
        return state;
    }

    @Deprecated
    public synchronized JSONObject toJson() throws JSONException{
        JSONObject jsonObject = new JSONObject();
        if (mCurrentArtwork != null) {
            jsonObject.put("currentArtwork", mCurrentArtwork.toJson());
        }
        jsonObject.put("description", mDescription);
        jsonObject.put("wantsNetworkAvailable", mWantsNetworkAvailable);
        JSONArray commandsSerialized = new JSONArray();
        for (UserCommand command : mUserCommands) {
            commandsSerialized.put(command.serialize());
        }
        jsonObject.put("userCommands", commandsSerialized);
        return jsonObject;
    }

    private void readJson(JSONObject jsonObject) {
        JSONObject artworkJsonObject = jsonObject.optJSONObject("currentArtwork");
        if (artworkJsonObject != null) {
            mCurrentArtwork = Artwork.fromJson(artworkJsonObject);
        }
        mDescription = jsonObject.optString("description");
        mWantsNetworkAvailable = jsonObject.optBoolean("wantsNetworkAvailable");
        JSONArray commandsSerialized = jsonObject.optJSONArray("userCommands");
        mUserCommands.clear();
        if (commandsSerialized != null && commandsSerialized.length() > 0) {
            int length = commandsSerialized.length();
            mUserCommands.ensureCapacity(length);
            for (int i = 0; i < length; i++) {
                mUserCommands.add(UserCommand.deserialize(commandsSerialized.optString(i)));
            }
        }
    }

    @Deprecated
    public static SourceState fromJson(JSONObject jsonObject) throws JSONException{
        SourceState state = new SourceState();
        state.readJson(jsonObject);
        return state;
    }

}
