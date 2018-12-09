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
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Creates and shows a [Toast] with the given [text]
 *
 * @param duration Toast duration, defaults to [Toast.LENGTH_SHORT]
 */
fun Context.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, text, duration).apply { show() }
}

/**
 * Creates and shows a [Toast] with text from a resource
 *
 * @param resId Resource id of the string resource to use
 * @param duration Toast duration, defaults to [Toast.LENGTH_SHORT]
 */
fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).apply { show() }
}

fun Context.toastFromBackground(
        @StringRes resId: Int,
        duration: Int = Toast.LENGTH_SHORT
) {
    GlobalScope.launch(Dispatchers.Main) {
        Toast.makeText(this@toastFromBackground, resId, duration).apply { show() }
    }
}
