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

package com.google.android.apps.muzei.util

import android.os.Bundle
import java.util.*

/**
 * Utilities for storing multiple selection information in collection views.
 */
class MultiSelectionController(private val mStateKey: String) {

    private val mSelection = HashSet<Long>()
    private var mCallbacks: Callbacks? = null

    val selection: Set<Long>
        get() = HashSet(mSelection)

    val selectedCount: Int
        get() = mSelection.size

    fun restoreInstanceState(savedInstanceState: Bundle?) {
        savedInstanceState?.run {
            mSelection.clear()
            val selection = getLongArray(mStateKey)
            if (selection?.isNotEmpty() == true) {
                for (item in selection) {
                    mSelection.add(item)
                }
            }
        }

        mCallbacks?.onSelectionChanged(true, false)
    }

    fun saveInstanceState(outBundle: Bundle?) {
        val selection = LongArray(mSelection.size)
        var i = 0
        for (item in mSelection) {
            selection[i] = item
            ++i
        }

        outBundle?.putLongArray(mStateKey, selection)
    }

    fun setCallbacks(callbacks: Callbacks) {
        mCallbacks = callbacks
    }

    fun toggle(item: Long, fromUser: Boolean) {
        if (mSelection.contains(item)) {
            mSelection.remove(item)
        } else {
            mSelection.add(item)
        }

        mCallbacks?.onSelectionChanged(false, fromUser)
    }

    fun reset(fromUser: Boolean) {
        mSelection.clear()
        mCallbacks?.onSelectionChanged(false, fromUser)
    }

    fun isSelected(item: Long): Boolean {
        return mSelection.contains(item)
    }

    interface Callbacks {
        fun onSelectionChanged(restored: Boolean, fromUser: Boolean)
    }
}
