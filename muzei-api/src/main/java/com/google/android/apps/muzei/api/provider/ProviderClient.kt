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
package com.google.android.apps.muzei.api.provider

import android.content.ContentResolver
import android.net.Uri

/**
 * Interface for interacting with a [MuzeiArtProvider]. Methods of this interface can
 * be used directly within a `MuzeiArtProvider` or you can get an instance via
 * [ProviderContract.getProviderClient].
 */
interface ProviderClient {
    /**
     * Retrieve the content URI for the [MuzeiArtProvider], allowing you to build
     * custom queries, inserts, updates, and deletes using a [ContentResolver].
     *
     * @return The content URI for the [MuzeiArtProvider]
     */
    val contentUri: Uri

    /**
     * Retrieve the last added artwork from the [MuzeiArtProvider].
     *
     * @return The last added Artwork, or null if no artwork has been added
     */
    val lastAddedArtwork: Artwork?

    /**
     * Add a new piece of artwork to the [MuzeiArtProvider].
     *
     * @param artwork The artwork to add
     * @return The URI of the newly added artwork or null if the insert failed
     */
    fun addArtwork(artwork: Artwork): Uri?

    /**
     * Add multiple artwork as a batch operation to the [MuzeiArtProvider].
     *
     * @param artwork The artwork to add
     * @return The URIs of the newly added artwork or an empty List if the insert failed.
     */
    fun addArtwork(artwork: Iterable<Artwork>): List<Uri>

    /**
     * Set the [MuzeiArtProvider] to only show the given artwork, deleting any other
     * artwork previously added. Only in the cases where the artwork is successfully inserted
     * will the other artwork be removed.
     *
     * @param artwork The artwork to set
     * @return The URI of the newly set artwork or null if the insert failed
     */
    fun setArtwork(artwork: Artwork): Uri?

    /**
     * Set the [MuzeiArtProvider] to only show the given artwork, deleting any other
     * artwork previously added. Only in the cases where the artwork is successfully inserted
     * will the other artwork be removed.
     *
     * @param artwork The artwork to set
     * @return The URIs of the newly set artwork or an empty List if the inserts failed.
     */
    fun setArtwork(artwork: Iterable<Artwork>): List<Uri>
}
