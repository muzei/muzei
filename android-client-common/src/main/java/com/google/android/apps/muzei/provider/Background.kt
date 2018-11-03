package com.google.android.apps.muzei.provider

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Ensures that [block] is not run on the main thread.
 *
 * This results in one of two outcomes:
 * - You were already on a background thread and [block]
 * is run directly.
 * - The main thread is blocked until [block] completes on
 * the [Dispatchers.Default] coroutine context.
 */
internal fun <T> ensureBackground(
        block: () -> T
) : T = if (Looper.getMainLooper() == Looper.myLooper()) {
        runBlocking {
            withContext(Dispatchers.Default) {
                block()
            }
        }
    } else {
        block()
    }
