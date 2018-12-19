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

package com.google.android.apps.muzei.gallery

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderClient
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.gallery.BuildConfig.GALLERY_ART_AUTHORITY
import com.google.android.apps.muzei.util.getString
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Random

class GalleryScanWorker(
        context: Context,
        workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val TAG = "GalleryScanWorker"
        private const val INITIAL_SCAN_TAG = "initialScan"
        private const val INITIAL_SCAN_ID = "id"

        @SuppressLint("SimpleDateFormat")
        private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss")
        private val OMIT_COUNTRY_CODES = hashSetOf("US")

        fun enqueueInitialScan(ids: List<Long>) {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(ids.map { id ->
                OneTimeWorkRequestBuilder<GalleryScanWorker>()
                        .addTag(INITIAL_SCAN_TAG)
                        .setInputData(workDataOf(INITIAL_SCAN_ID to id))
                        .build()
            })
        }

        fun enqueueRescan() {
            val workManager = WorkManager.getInstance()
            workManager.enqueueUniqueWork("rescan",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<GalleryScanWorker>()
                            .build())
        }
    }

    private val geocoder by lazy {
        Geocoder(applicationContext)
    }

    override suspend fun doWork(): Result {
        val providerClient = ProviderContract.getProviderClient(
                applicationContext, GALLERY_ART_AUTHORITY)
        val id = inputData.getLong(INITIAL_SCAN_ID, -1L)
        if (id != -1L) {
            val chosenPhoto = GalleryDatabase.getInstance(applicationContext)
                    .chosenPhotoDao()
                    .getChosenPhoto(id)
            return if (chosenPhoto != null) {
                scanChosenPhoto(providerClient, chosenPhoto)
                deleteMediaUris(providerClient)
                Result.success()
            } else {
                Result.failure()
            }
        }
        val chosenPhotos = GalleryDatabase.getInstance(applicationContext)
                .chosenPhotoDao()
                .chosenPhotosBlocking
        val numChosenUris = chosenPhotos.size
        if (numChosenUris > 0) {
            for (chosenPhoto in chosenPhotos) {
                scanChosenPhoto(providerClient, chosenPhoto)
            }
            deleteMediaUris(providerClient)
            return Result.success()
        }
        return addMediaUri(providerClient)
    }

    private fun deleteMediaUris(providerClient: ProviderClient) {
        applicationContext.contentResolver.delete(providerClient.contentUri,
                "${ProviderContract.Artwork.METADATA}=?",
                arrayOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()))
    }

    private suspend fun scanChosenPhoto(providerClient: ProviderClient, chosenPhoto: ChosenPhoto) {
        if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            addTreeUri(providerClient, chosenPhoto)
        } else {
            val cachedFile = GalleryProvider.getCacheFileForUri(
                    applicationContext, chosenPhoto.uri)
            if (cachedFile != null && cachedFile.exists()) {
                providerClient.addArtwork(createArtwork(
                        chosenPhoto.uri,
                        Uri.fromFile(cachedFile),
                        chosenPhoto.contentUri))
            } else {
                addUri(providerClient, chosenPhoto)
            }
        }
    }

    private suspend fun addUri(providerClient: ProviderClient, chosenPhoto: ChosenPhoto) {
        val imageUri = chosenPhoto.uri
        try {
            applicationContext.contentResolver.query(imageUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null)?.use { data ->
                if (data.moveToFirst()) {
                    providerClient.addArtwork(createArtwork(chosenPhoto.uri))
                } else {
                    Log.w(TAG, "Unable to load image from $imageUri, deleting row")
                    deleteChosenPhoto(chosenPhoto)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to access image from $imageUri, deleting row", e)
            deleteChosenPhoto(chosenPhoto)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private suspend fun addTreeUri(providerClient: ProviderClient, chosenPhoto: ChosenPhoto) {
        val treeUri = chosenPhoto.uri
        val allImages = mutableListOf<Uri>()
        try {
            addAllImagesFromTree(allImages, treeUri)
            // Shuffle all the images to give a random initial load order
            allImages.shuffle()
            val currentTime = System.currentTimeMillis()
            val addedArtwork = providerClient.addArtwork(allImages.map { uri ->
                createArtwork(treeUri, uri)
            })
            val deleteOperations = ArrayList<ContentProviderOperation>()
            applicationContext.contentResolver.query(
                    providerClient.contentUri,
                    arrayOf(BaseColumns._ID),
                    "${ProviderContract.Artwork.METADATA}=? AND " +
                            "${ProviderContract.Artwork.DATE_MODIFIED}<?",
                    arrayOf(treeUri.toString(), currentTime.toString()),
                    null)?.use { data ->
                while (data.moveToNext()) {
                    val artworkUri = ContentUris.withAppendedId(providerClient.contentUri,
                            data.getLong(0))
                    if (!addedArtwork.contains(artworkUri)) {
                        deleteOperations += ContentProviderOperation
                                .newDelete(artworkUri)
                                .build()
                    }
                }
            }
            if (deleteOperations.isNotEmpty()) {
                try {
                    applicationContext.contentResolver.applyBatch(GALLERY_ART_AUTHORITY,
                            deleteOperations)
                } catch(e: Exception) {
                    Log.i(TAG, "Error removing deleted artwork", e)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to load images from $treeUri, deleting row", e)
            deleteChosenPhoto(chosenPhoto)
        }
    }

    private fun deleteChosenPhoto(chosenPhoto: ChosenPhoto) {
        GlobalScope.launch {
            GalleryDatabase.getInstance(applicationContext)
                    .chosenPhotoDao()
                    .delete(applicationContext, listOf(chosenPhoto.id))
        }
    }

    @SuppressLint("Recycle")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun addAllImagesFromTree(
            images: MutableList<Uri>,
            treeUri: Uri
    ) {
        val directories = LinkedList<String>()
        directories.add(DocumentsContract.getTreeDocumentId(treeUri))
        while (!directories.isEmpty()) {
            val parentDocumentId = directories.poll()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, parentDocumentId)
            try {
                applicationContext.contentResolver.query(childrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                        null, null, null)?.use { children ->
                    while (children.moveToNext()) {
                        val documentId = children.getString(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeType = children.getString(
                                DocumentsContract.Document.COLUMN_MIME_TYPE)
                        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                            directories.add(documentId)
                        } else if (mimeType.startsWith("image/")) {
                            // Add images to the list
                            images.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
                        }
                    }
                }
            } catch (e: SecurityException) {
                // No longer can read this URI, which means no children from this URI
            } catch (e: NullPointerException) {
            }
        }
    }

    @SuppressLint("Recycle")
    private suspend fun addMediaUri(providerClient: ProviderClient): Result {
        if (ContextCompat.checkSelfPermission(applicationContext,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing read external storage permission.")
            return Result.failure()
        }
        applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} NOT LIKE '%Screenshots%'",
                null, null)?.use { data ->
            val count = data.count
            if (count == 0) {
                Log.d(TAG, "No photos in the gallery.")
                return Result.failure()
            }
            val lastToken = providerClient.lastAddedArtwork?.token

            val random = Random()
            val randomSequence = generateSequence {
                random.nextInt(data.count)
            }.distinct().take(data.count)
            val iterator = randomSequence.iterator()
            while (iterator.hasNext()) {
                val position = iterator.next()
                if (data.moveToPosition(position)) {
                    val imageUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            data.getLong(0))
                    if (imageUri.toString() != lastToken) {
                        providerClient.addArtwork(createArtwork(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                imageUri))
                        return Result.success()
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Unable to find any other valid photos in the gallery")
            }
            return Result.failure()
        } ?: run {
            Log.w(TAG, "Empty cursor.")
            return Result.failure()
        }
    }

    private suspend fun createArtwork(
            baseUri: Uri,
            imageUri: Uri = baseUri,
            publicWebUri: Uri = imageUri
    ): Artwork {
        val imageMetadata = ensureMetadataExists(imageUri)

        return Artwork().apply {
            token = imageUri.toString()
            persistentUri = imageUri
            webUri = publicWebUri
            metadata = baseUri.toString()
            val date = imageMetadata.date
            title = if (date != null) {
                DateUtils.formatDateTime(applicationContext, date.time,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR
                                or DateUtils.FORMAT_SHOW_WEEKDAY)
            } else {
                applicationContext.getString(R.string.gallery_from_gallery)
            }
            byline = if (imageMetadata.location.isNullOrBlank()) {
                applicationContext.getString(R.string.gallery_touch_to_view)
            } else {
                imageMetadata.location
            }
        }
    }

    private suspend fun ensureMetadataExists(imageUri: Uri): Metadata {
        val metadataDao = GalleryDatabase.getInstance(applicationContext)
                .metadataDao()
        val existingMetadata = metadataDao.metadataForUri(imageUri)
        if (existingMetadata != null) {
            return existingMetadata
        }
        // No cached metadata or it's stale, need to pull it separately using Exif
        val metadata = Metadata(imageUri)

        try {
            applicationContext.contentResolver.openInputStream(imageUri)?.use { input ->
                val exifInterface = ExifInterface(input)
                val dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
                if (!TextUtils.isEmpty(dateString)) {
                    metadata.date = EXIF_DATE_FORMAT.parse(dateString)
                }

                exifInterface.latLong?.apply {
                    // Reverse geocode
                    val addresses = try {
                        geocoder.getFromLocation(this[0], this[1], 1)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Invalid latitude/longitude, skipping location metadata", e)
                        null
                    }

                    addresses?.firstOrNull()?.run {
                        val locality = locality
                        val adminArea = adminArea
                        val countryCode = countryCode
                        val sb = StringBuilder()
                        if (!locality.isNullOrEmpty()) {
                            sb.append(locality)
                        }
                        if (!adminArea.isNullOrEmpty()) {
                            if (sb.isNotEmpty()) {
                                sb.append(", ")
                            }
                            sb.append(adminArea)
                        }
                        if (!countryCode.isNullOrEmpty() && !OMIT_COUNTRY_CODES.contains(countryCode)) {
                            if (sb.isNotEmpty()) {
                                sb.append(", ")
                            }
                            sb.append(countryCode)
                        }
                        metadata.location = sb.toString()
                    }
                }

                metadataDao.insert(metadata)
                return metadata
            }
        } catch (e: ParseException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: StackOverflowError) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: ArrayIndexOutOfBoundsException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: NullPointerException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        }

        return metadata
    }
}