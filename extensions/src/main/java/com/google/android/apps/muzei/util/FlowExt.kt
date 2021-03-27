package com.google.android.apps.muzei.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A convenience wrapper around [androidx.lifecycle.LifecycleCoroutineScope.launch]
 * that calls [collect] with [action], all wrapped in a lifecycle-aware
 * [repeatOnLifecycle]. Think of it as [kotlinx.coroutines.flow.launchIn], but for
 * collecting.
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
    crossinline action: suspend (T) -> Unit
) = owner.lifecycleScope.launch(coroutineContext) {
    owner.lifecycle.repeatOnLifecycle(minActiveState) {
        collect { action(it) }
    }
}

/**
 * A convenience wrapper around [androidx.lifecycle.LifecycleCoroutineScope.launch]
 * that calls [collect], all wrapped in a lifecycle-aware
 * [repeatOnLifecycle]. Think of it as [kotlinx.coroutines.flow.launchIn], but for
 * collecting.
 *
 * ```
 * uiStateFlow.collectIn(owner)
 * ```
 */
fun <T> Flow<T>.collectIn(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    coroutineContext: CoroutineContext = EmptyCoroutineContext
) = owner.lifecycleScope.launch(coroutineContext) {
    owner.lifecycle.repeatOnLifecycle(minActiveState) {
        collect()
    }
}
