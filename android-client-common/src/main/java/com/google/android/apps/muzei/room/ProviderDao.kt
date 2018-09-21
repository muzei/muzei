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

package com.google.android.apps.muzei.room

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Delete
import android.arch.persistence.room.Insert
import android.arch.persistence.room.Query
import android.arch.persistence.room.Update
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.withContext

/**
 * Dao for Providers
 */
@Dao
abstract class ProviderDao {
    @get:Query("SELECT * FROM provider")
    abstract val currentProvider: LiveData<Provider?>

    @get:Query("SELECT * FROM provider")
    internal abstract val currentProviderBlocking: Provider?

    suspend fun getCurrentProvider() = withContext(Dispatchers.Default) {
        currentProviderBlocking
    }

    @Insert
    abstract fun insert(provider: Provider)

    @Update
    abstract fun update(provider: Provider)

    @Delete
    abstract fun delete(provider: Provider)

    @Query("DELETE FROM provider")
    abstract fun deleteAll()
}
