package com.google.android.apps.muzei.provider

import android.os.Looper
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.withContext

/**
 * Ensures that [block] is not run on the main thread.
 *
 * This results in one of two outcomes:
 * - You were already on a background thread and [block]
 * is run directly.
 * - The main thread is blocked until [block] completes on
 * the [CommonPool] coroutine context.
 */
internal fun <T> ensureBackground(
        block: () -> T
) : T = if (Looper.getMainLooper() == Looper.myLooper()) {
        runBlocking {
            withContext(CommonPool) {
                block()
            }
        }
    } else {
        block()
    }
