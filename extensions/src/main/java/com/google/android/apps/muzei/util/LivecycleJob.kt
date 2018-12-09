/*
 * Copyright 2018 Google Inc.
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

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

private fun Lifecycle.createJob(): Job = Job().also { job ->
    addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            removeObserver(this)
            job.cancel()
        }
    })
}

private val lifecycleCoroutineScopes = mutableMapOf<Lifecycle, CoroutineScope>()

val Lifecycle.coroutineScope: CoroutineScope
    get() = lifecycleCoroutineScopes[this] ?: createJob().let { job ->
        val newScope = CoroutineScope(job + Dispatchers.Main)
        lifecycleCoroutineScopes[this] = newScope
        job.invokeOnCompletion { lifecycleCoroutineScopes -= this }
        newScope
    }

val LifecycleOwner.coroutineScope get() = lifecycle.coroutineScope
