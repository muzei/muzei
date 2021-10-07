/*
 * Copyright 2021 Google Inc.
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

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.annotation.MenuRes
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider

/**
 * Add a [MenuProvider] created from the [menuRes] and
 * [onSelected].
 *
 * @return The newly created [MenuProvider].
 */
fun MenuHost.addMenuProvider(
    @MenuRes menuRes: Int,
    onSelected: (menuItem: MenuItem) -> Boolean
): MenuProvider = object : MenuProvider {
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(menuRes, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return onSelected(menuItem)
    }
}.also {
    addMenuProvider(it)
}
