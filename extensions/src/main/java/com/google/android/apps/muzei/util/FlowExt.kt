package com.google.android.apps.muzei.util

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Similar to [kotlinx.coroutines.flow.launchIn] but using
 * [androidx.lifecycle.LifecycleCoroutineScope.launchWhenStarted].
 */
fun <T> Flow<T>.launchWhenStartedIn(
        lifecycleOwner: LifecycleOwner
) = lifecycleOwner.lifecycleScope.launchWhenStarted {
    collect()
}
