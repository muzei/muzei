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

import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer

fun <T> LiveData<T>.observe(owner: LifecycleOwner, callback: (T?) -> Unit) {
    observe(owner, Observer<T?> { value ->
        callback(value)
    })
}

fun <T> LiveData<T>.observeNonNull(owner: LifecycleOwner, callback: (T) -> Unit) {
    observe(owner, Observer<T?> { value ->
        if (value != null) {
            callback(value)
        }
    })
}

fun <T> LiveData<T>.observeOnce(callback: (T?) -> Unit) {
    val observer: Observer<T> = object : Observer<T> {
        override fun onChanged(value: T?) {
            removeObserver(this)
            callback(value)
        }
    }
    observeForever(observer)
}
