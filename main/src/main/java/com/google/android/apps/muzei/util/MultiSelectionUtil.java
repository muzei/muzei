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

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Pair;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Adapter;
import android.widget.AdapterView;

import java.util.HashSet;

import static android.widget.AbsListView.MultiChoiceModeListener;

/**
 * Utilities for handling multiple selection in list views. Contains functionality similar to
 * {@link AbsListView#CHOICE_MODE_MULTIPLE_MODAL}.
 */
public class MultiSelectionUtil {
    public static Controller attachMultiSelectionController(final AbsListView listView,
            final Activity activity, final MultiChoiceModeListener listener) {
        return Controller.attach(listView, activity, listener);
    }

    public static class Controller implements
            ActionMode.Callback,
            AdapterView.OnItemClickListener,
            AdapterView.OnItemLongClickListener {
        private Handler mHandler = new Handler();
        private ActionMode mActionMode;
        private AbsListView mAbsListView = null;
        private Activity mActivity = null;
        private MultiChoiceModeListener mListener = null;
        private HashSet<Long> mTempIdsToCheckOnRestore;
        private HashSet<Pair<Integer, Long>> mItemsToCheck;
        private AdapterView.OnItemClickListener mOldItemClickListener;

        private Controller() {
        }

        public static Controller attach(AbsListView listView, Activity activity,
                MultiChoiceModeListener listener) {
            Controller controller = new Controller();
            controller.mAbsListView = listView;
            controller.mActivity = activity;
            controller.mListener = listener;
            listView.setOnItemLongClickListener(controller);
            return controller;
        }

        private void readInstanceState(Bundle savedInstanceState) {
            mTempIdsToCheckOnRestore = null;
            if (savedInstanceState != null) {
                long[] checkedIds = savedInstanceState.getLongArray(getStateKey());
                if (checkedIds != null && checkedIds.length > 0) {
                    mTempIdsToCheckOnRestore = new HashSet<Long>();
                    for (long id : checkedIds) {
                        mTempIdsToCheckOnRestore.add(id);
                    }
                }
            }
        }

        public void tryRestoreInstanceState(Bundle savedInstanceState) {
            readInstanceState(savedInstanceState);
            tryRestoreInstanceState();
        }

        public void finish() {
            if (mActionMode != null) {
                mActionMode.finish();
            }
        }

        public void tryRestoreInstanceState() {
            if (mTempIdsToCheckOnRestore == null || mAbsListView.getAdapter() == null) {
                return;
            }

            boolean idsFound = false;
            Adapter adapter = mAbsListView.getAdapter();
            for (int pos = adapter.getCount() - 1; pos >= 0; pos--) {
                if (mTempIdsToCheckOnRestore.contains(adapter.getItemId(pos))) {
                    idsFound = true;
                    if (mItemsToCheck == null) {
                        mItemsToCheck = new HashSet<Pair<Integer, Long>>();
                    }
                    mItemsToCheck.add(
                            new Pair<Integer, Long>(pos, adapter.getItemId(pos)));
                }
            }

            if (idsFound) {
                // We found some IDs that were checked. Let's now restore the multi-selection
                // state.
                mTempIdsToCheckOnRestore = null; // clear out this temp field
                mActionMode = mActivity.startActionMode(Controller.this);
            }
        }

        public boolean saveInstanceState(Bundle outBundle) {
            // TODO: support non-stable IDs by persisting positions instead of IDs
            if (mActionMode != null && mAbsListView.getAdapter().hasStableIds()) {
                long[] checkedIds = mAbsListView.getCheckedItemIds();
                outBundle.putLongArray(getStateKey(), checkedIds);
                return true;
            }

            return false;
        }

        private String getStateKey() {
            return MultiSelectionUtil.class.getSimpleName() + "_" + mAbsListView.getId();
        }

        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            if (mListener.onCreateActionMode(actionMode, menu)) {
                mActionMode = actionMode;
                mOldItemClickListener = mAbsListView.getOnItemClickListener();
                mAbsListView.setOnItemClickListener(Controller.this);
                mAbsListView.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE);
                mHandler.removeCallbacks(mSetChoiceModeNoneRunnable);

                if (mItemsToCheck != null) {
                    for (Pair<Integer, Long> posAndId : mItemsToCheck) {
                        mAbsListView.setItemChecked(posAndId.first, true);
                        mListener.onItemCheckedStateChanged(mActionMode, posAndId.first,
                                posAndId.second, true);
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            if (mListener.onPrepareActionMode(actionMode, menu)) {
                mActionMode = actionMode;
                return true;
            }
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
            return mListener.onActionItemClicked(actionMode, menuItem);
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {
            mListener.onDestroyActionMode(actionMode);
            SparseBooleanArray checkedPositions = mAbsListView.getCheckedItemPositions();
            int numItems = (mAbsListView.getAdapter() == null)
                    ? Integer.MAX_VALUE : mAbsListView.getAdapter().getCount();
            if (checkedPositions != null) {
                for (int i = 0; i < checkedPositions.size(); i++) {
                    int position = checkedPositions.keyAt(i);
                    // Workaround for AbsListView bug.
                    if (position >= numItems) {
                        continue;
                    }
                    mAbsListView.setItemChecked(position, false);
                }
            }
            mAbsListView.setOnItemClickListener(mOldItemClickListener);
            mActionMode = null;
            mHandler.post(mSetChoiceModeNoneRunnable);
        }

        private Runnable mSetChoiceModeNoneRunnable = new Runnable() {
            @Override
            public void run() {
                mAbsListView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
            }
        };

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
            boolean checked = mAbsListView.isItemChecked(position);
            mListener.onItemCheckedStateChanged(mActionMode, position, id, checked);

            int numChecked = 0;
            SparseBooleanArray checkedItemPositions = mAbsListView.getCheckedItemPositions();
            if (checkedItemPositions != null) {
                for (int i = 0; i < checkedItemPositions.size(); i++) {
                    numChecked += checkedItemPositions.valueAt(i) ? 1 : 0;
                }
            }

            if (numChecked <= 0) {
                mActionMode.finish();
            }
        }

        @Override
        public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position,
        long id) {
            if (mActionMode != null) {
                return false;
            }

            mItemsToCheck = new HashSet<Pair<Integer, Long>>();
            mItemsToCheck.add(new Pair<Integer, Long>(position, id));
            mActionMode = mActivity.startActionMode(Controller.this);
            return true;
        }
    }
}
