/*
 * Copyright 2017 Google Inc.
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
import androidx.room.Insert
import androidx.room.Query

/**
 * Dao for Artwork
 */
@Dao
abstract class ArtworkDao {

    @Query("SELECT * FROM artwork ORDER BY date_added DESC LIMIT 100")
    abstract suspend fun getArtwork(): List<Artwork>

    @get:Query("SELECT artwork.* FROM artwork " +
            "inner join provider on providerAuthority = authority " +
            "ORDER BY date_added DESC")
    abstract val currentArtwork: LiveData<Artwork?>

    @get:Query("SELECT artwork.* FROM artwork " +
            "inner join provider on providerAuthority = authority " +
            "ORDER BY date_added DESC")
    internal abstract val currentArtworkBlocking: Artwork?

    @Query("SELECT artwork.* FROM artwork " +
            "inner join provider on providerAuthority = authority " +
            "ORDER BY date_added DESC")
    abstract suspend fun getCurrentArtwork(): Artwork?

    @Insert
    abstract suspend fun insert(artwork: Artwork): Long

    @Query("SELECT * FROM artwork WHERE providerAuthority = :providerAuthority ORDER BY date_added DESC")
    abstract suspend fun getCurrentArtworkForProvider(providerAuthority: String): Artwork?

    @get:Query("SELECT * FROM artwork art1 WHERE _id IN (" +
            "SELECT _id FROM artwork art2 WHERE art1._id=art2._id " +
            "ORDER BY date_added DESC limit 1)")
    abstract val currentArtworkByProvider : LiveData<List<Artwork>>

    @Query("SELECT * FROM artwork WHERE _id=:id")
    internal abstract fun getArtworkByIdBlocking(id: Long): Artwork?

    @Query("SELECT * FROM artwork WHERE _id=:id")
    abstract suspend fun getArtworkById(id: Long): Artwork?

    @Query("SELECT * FROM artwork WHERE title LIKE :query OR byline LIKE :query OR attribution LIKE :query")
    abstract suspend fun searchArtwork(query: String): List<Artwork>

    @Query("DELETE FROM artwork WHERE _id=:id")
    abstract fun deleteById(id: Long)
}
