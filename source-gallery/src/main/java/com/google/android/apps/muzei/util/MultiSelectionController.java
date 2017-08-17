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

package com.google.android.apps.muzei.util;

import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;

/**
 * Utilities for storing multiple selection information in collection views.
 */
public class MultiSelectionController {
    private String mStateKey;
    private Set<Long> mSelection = new HashSet<>();
    private Callbacks mCallbacks = DUMMY_CALLBACKS;

    public MultiSelectionController(String stateKey) {
        mStateKey = stateKey;
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mSelection.clear();
            long[] selection = savedInstanceState.getLongArray(mStateKey);
            if (selection != null && selection.length > 0) {
                for (long item : selection) {
                    mSelection.add(item);
                }
            }
        }

        mCallbacks.onSelectionChanged(true, false);
    }

    public void saveInstanceState(Bundle outBundle) {
        long[] selection = new long[mSelection.size()];
        int i = 0;
        for (Long item : mSelection) {
            selection[i] = item;
            ++i;
        }

        outBundle.putLongArray(mStateKey, selection);
    }

    public void setCallbacks(Callbacks callbacks) {
        mCallbacks = callbacks;
        if (mCallbacks == null) {
            mCallbacks = DUMMY_CALLBACKS;
        }
    }

    public Set<Long> getSelection() {
        return new HashSet<>(mSelection);
    }

    public int getSelectedCount() {
        return mSelection.size();
    }

    public boolean isSelecting() {
        return mSelection.size() > 0;
    }

    public void toggle(long item, boolean fromUser) {
        if (mSelection.contains(item)) {
            mSelection.remove(item);
        } else {
            mSelection.add(item);
        }

        mCallbacks.onSelectionChanged(false, fromUser);
    }

    public void reset(boolean fromUser) {
        mSelection.clear();
        mCallbacks.onSelectionChanged(false, fromUser);
    }

    public boolean isSelected(long item) {
        return mSelection.contains(item);
    }

    public interface Callbacks {
        void onSelectionChanged(boolean restored, boolean fromUser);
    }

    private static final Callbacks DUMMY_CALLBACKS = new Callbacks() {
        @Override
        public void onSelectionChanged(boolean restored, boolean fromUser) {
        }
    };
}
