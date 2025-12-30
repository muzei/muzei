/*
 * Copyright 2025 Google Inc.
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

package com.google.android.apps.muzei.settings

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class PreferenceSourcedValue<T : Any>(
    private val coroutineScope: CoroutineScope,
    private val userControlledValue: MutableState<T>,
    private val preferenceControlledValue: State<T>,
    private val updateValue: (newValue: T) -> Unit,
) {
    var userControlled by mutableStateOf(false)
    private var updateJob by mutableStateOf<Job?>(null)

    var value: T
        get() = if (userControlled || updateJob != null) userControlledValue.value else preferenceControlledValue.value
        set(value) {
            userControlledValue.value = value
            userControlled = true
            updateJob?.cancel()
            updateJob = coroutineScope.launch {
                delay(750)
                updateValue(value)
                updateJob = null
            }
        }
}

@Composable
fun rememberPreferenceSourcedValue(
    prefs: SharedPreferences,
    prefName: String,
    defaultValue: Int,
): PreferenceSourcedValue<Int> {
    return rememberPreferenceSourcedValue(
        prefs = prefs,
        prefName = prefName,
        defaultValue = defaultValue,
        getValue = { prefs, prefName, defaultValue ->
            prefs.getInt(prefName, defaultValue)
        },
        updateValue = { prefName, newValue ->
            prefs.edit {
                putInt(prefName, newValue)
            }
        }
    )
}

@Composable
fun rememberPreferenceSourcedValue(
    prefs: SharedPreferences,
    prefName: String,
    defaultValue: Boolean,
): PreferenceSourcedValue<Boolean> {
    return rememberPreferenceSourcedValue(
        prefs = prefs,
        prefName = prefName,
        defaultValue = defaultValue,
        getValue = { prefs, prefName, defaultValue ->
            prefs.getBoolean(prefName, defaultValue)
        },
        updateValue = { prefName, newValue ->
            prefs.edit {
                putBoolean(prefName, newValue)
            }
        }
    )
}

@Composable
private fun <T : Any> rememberPreferenceSourcedValue(
    prefs: SharedPreferences,
    prefName: String,
    defaultValue: T,
    getValue: (prefs: SharedPreferences, prefName: String, defaultValue: T) -> T,
    updateValue: (prefName: String, newValue: T) -> Unit,
): PreferenceSourcedValue<T> {
    val defaultValue = remember {
        getValue(prefs, prefName, defaultValue)
    }
    val userControlledValue = remember { mutableStateOf(defaultValue) }
    val preferenceControlledValue = produceState(defaultValue) {
        prefs.callbackFlow(prefName, defaultValue, getValue).collect {
            value = it
        }
    }
    val coroutineScope = rememberCoroutineScope()
    return remember(
        prefs, prefName, coroutineScope, userControlledValue, preferenceControlledValue
    ) {
        PreferenceSourcedValue(
            coroutineScope, userControlledValue, preferenceControlledValue
        ) {
            updateValue(prefName, it)
        }
    }
}

fun <T : Any> SharedPreferences.callbackFlow(
    prefName: String,
    defaultValue: T,
    getValue: (prefs: SharedPreferences, prefName: String, defaultValue: T) -> T,
): Flow<T> {
    val prefs = this
    return callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == prefName) {
                trySend(getValue(prefs, prefName, defaultValue))
            }

        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val preferences = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun contains(key: String): Boolean = preferences.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private var calledClear = false
        private val removedKeys = mutableSetOf<String>()
        private val modifiedPreferences = mutableMapOf<String, Any?>()

        override fun apply() {
            commit()
        }

        override fun clear(): SharedPreferences.Editor {
            calledClear = true
            return this
        }

        override fun commit(): Boolean {
            if (calledClear) {
                preferences.clear()
            }
            removedKeys.forEach {
                val oldValue = preferences.remove(it)
                if (oldValue != null) {
                    listeners.forEach { listener ->
                        listener.onSharedPreferenceChanged(this@FakeSharedPreferences, it)
                    }
                }
            }
            modifiedPreferences.forEach { (key, value) ->
                val oldValue = preferences.put(key, value)
                if (oldValue != value) {
                    listeners.forEach { listener ->
                        listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key)
                    }
                }
            }
            return true
        }

        override fun putBoolean(
            key: String,
            value: Boolean
        ): SharedPreferences.Editor {
            modifiedPreferences[key] = value
            return this
        }

        override fun putFloat(
            key: String,
            value: Float
        ): SharedPreferences.Editor {
            modifiedPreferences[key] = value
            return this
        }

        override fun putInt(
            key: String,
            value: Int
        ): SharedPreferences.Editor {
            modifiedPreferences[key] = value
            return this
        }

        override fun putLong(
            key: String,
            value: Long
        ): SharedPreferences.Editor {
            modifiedPreferences[key] = value
            return this
        }

        override fun putString(
            key: String,
            value: String?
        ): SharedPreferences.Editor {

            modifiedPreferences[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: Set<String?>?
        ): SharedPreferences.Editor {
            modifiedPreferences[key] = values
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removedKeys.add(key)
            return this
        }
    }

    override fun getAll(): Map<String, *> = preferences

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return preferences[key] as? Boolean ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return preferences[key] as? Float ?: defValue
    }

    override fun getInt(key: String, defValue: Int): Int {
        return preferences[key] as? Int ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        return preferences[key] as? Long ?: defValue
    }

    override fun getString(key: String, defValue: String?): String? {
        return preferences[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: Set<String?>?
    ): Set<String?>? {
        return preferences[key] as? Set<String?> ?: defValues
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }
}