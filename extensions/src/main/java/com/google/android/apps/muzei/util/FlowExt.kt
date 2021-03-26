package com.google.android.apps.muzei.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.addRepeatingJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A convenience wrapper around [addRepeatingJob] that simply calls [collect]
 * with [action]. Think of it as [kotlinx.coroutines.flow.launchIn], but for collecting.
 *
 * ```
 * uiStateFlow.collectIn(owner) { uiState ->
 *   updateUi(uiState)
 * }
 * ```
 */
inline fun <T> Flow<T>.collectIn(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    crossinline action: suspend CoroutineScope.(T) -> Unit
) = owner.addRepeatingJob(minActiveState, coroutineContext) {
    collect {
        action(it)
    }
}

/**
 * A convenience wrapper around [addRepeatingJob] that simply calls [collect].
 * Think of it as [kotlinx.coroutines.flow.launchIn], but for collecting.
 *
 * ```
 * uiStateFlow.collectIn(owner)
 * ```
 */
fun <T> Flow<T>.collectIn(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    coroutineContext: CoroutineContext = EmptyCoroutineContext
) = owner.addRepeatingJob(minActiveState, coroutineContext) {
    collect()
}
