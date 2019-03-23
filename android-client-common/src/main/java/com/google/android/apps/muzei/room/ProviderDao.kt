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

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * Dao for Providers
 */
@Dao
abstract class ProviderDao {
    @get:Query("SELECT * FROM provider")
    abstract val currentProvider: LiveData<Provider?>

    @get:Query("SELECT * FROM provider")
    internal abstract val currentProviderBlocking: Provider?

    @Query("SELECT * FROM provider")
    abstract suspend fun getCurrentProvider(): Provider?

    @Transaction
    open suspend fun select(authority: String) {
        deleteAll()
        insert(Provider(authority))
    }

    @Insert
    internal abstract suspend fun insert(provider: Provider)

    @Update
    abstract suspend fun update(provider: Provider)

    @Delete
    abstract suspend fun delete(provider: Provider)

    @Query("DELETE FROM provider")
    internal abstract suspend fun deleteAll()
}
