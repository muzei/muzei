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

import android.content.Context
import android.support.annotation.StringRes
import android.widget.Toast
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.android.Main
import kotlinx.coroutines.experimental.launch

fun Context.toastFromBackground(
        text: CharSequence,
        duration: Int = Toast.LENGTH_SHORT
) {
    GlobalScope.launch(Dispatchers.Main) {
        Toast.makeText(this@toastFromBackground, text, duration).apply { show() }
    }
}

fun Context.toastFromBackground(
        @StringRes resId: Int,
        duration: Int = Toast.LENGTH_SHORT
) {
    GlobalScope.launch(Dispatchers.Main) {
        Toast.makeText(this@toastFromBackground, resId, duration).apply { show() }
    }
}
