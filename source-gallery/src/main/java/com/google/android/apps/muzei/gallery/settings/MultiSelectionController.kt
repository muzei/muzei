package com.google.android.apps.muzei.gallery.settings

import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.core.os.bundleOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Utilities for storing multiple selection information in collection views.
 */
class MultiSelectionController(
        savedStateRegistryOwner: SavedStateRegistryOwner
) : DefaultLifecycleObserver, SavedStateRegistry.SavedStateProvider {

    companion object {
        private const val STATE_SELECTION = "selection"
    }

    private val lifecycle = savedStateRegistryOwner.lifecycle
    private val savedStateRegistry = savedStateRegistryOwner.savedStateRegistry

    val selection = SnapshotStateSet<Long>()

    init {
        lifecycle.addObserver(this)
        savedStateRegistry.registerSavedStateProvider(STATE_SELECTION, this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        savedStateRegistry.consumeRestoredStateForKey(STATE_SELECTION)?.run {
            selection.clear()
            val savedSelection = getLongArray(STATE_SELECTION)
            if (savedSelection?.isNotEmpty() == true) {
                for (item in savedSelection) {
                    selection.add(item)
                }
            }
        }
    }

    override fun saveState() = bundleOf(STATE_SELECTION to selection.toLongArray())

    fun toggle(item: Long) {
        if (selection.contains(item)) {
            selection.remove(item)
        } else {
            selection.add(item)
        }
    }

    fun reset() {
        selection.clear()
    }
}