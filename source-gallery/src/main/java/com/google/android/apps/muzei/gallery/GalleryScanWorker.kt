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
import android.content.ContentUris
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.support.annotation.RequiresApi
import android.support.media.ExifInterface
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import kotlinx.coroutines.experimental.launch
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Random

class GalleryScanWorker : Worker() {
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
                        .setInputData(Data.Builder()
                                .putLong(INITIAL_SCAN_ID, id)
                                .build())
                        .build()
            })
        }

        fun enqueueRescan() {
            val workManager = WorkManager.getInstance()
            workManager.beginUniqueWork("rescan",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<GalleryScanWorker>()
                            .build()).enqueue()
        }
    }

    private val geocoder by lazy {
        Geocoder(applicationContext)
    }

    override fun doWork(): Result {
        val id = inputData.getLong(INITIAL_SCAN_ID, -1L)
        if (id != -1L) {
            val chosenPhoto = GalleryDatabase.getInstance(applicationContext)
                    .chosenPhotoDao()
                    .chosenPhotoBlocking(id)
            return if (chosenPhoto != null) {
                deleteMediaUris()
                scanChosenPhoto(chosenPhoto)
                Result.SUCCESS
            } else {
                Result.FAILURE
            }
        }
        val chosenPhotos = GalleryDatabase.getInstance(applicationContext)
                .chosenPhotoDao()
                .chosenPhotosBlocking
        val numChosenUris = chosenPhotos.size
        if (numChosenUris > 0) {
            deleteMediaUris()
            // Now add all of the chosen photos
            for (chosenPhoto in chosenPhotos) {
                scanChosenPhoto(chosenPhoto)
            }
            return Result.SUCCESS
        }
        return addMediaUri()
    }

    private fun deleteMediaUris() {
        val contentUri = ProviderContract.Artwork.getContentUri(
                applicationContext,
                GalleryArtProvider::class.java)
        applicationContext.contentResolver.delete(contentUri,
                "${ProviderContract.Artwork.METADATA}=?",
                arrayOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()))
    }

    private fun scanChosenPhoto(chosenPhoto: ChosenPhoto) {
        if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            addTreeUri(chosenPhoto)
        } else {
            val cachedFile = GalleryProvider.getCacheFileForUri(
                    applicationContext, chosenPhoto.uri)
            if (cachedFile != null && cachedFile.exists()) {
                addUri(chosenPhoto.uri, Uri.fromFile(cachedFile))
            } else {
                addUri(chosenPhoto.uri, chosenPhoto.uri)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun addTreeUri(chosenPhoto: ChosenPhoto) {
        val treeUri = chosenPhoto.uri
        val allImages = mutableListOf<Uri>()
        try {
            addAllImagesFromTree(allImages, treeUri)
            // Shuffle all the images to give a random initial load order
            allImages.shuffle()
            for (uri in allImages) {
                addUri(treeUri, uri)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to load images from $treeUri, deleting row", e)
            launch {
                GalleryDatabase.getInstance(applicationContext)
                        .chosenPhotoDao()
                        .delete(applicationContext, listOf(chosenPhoto.id))
            }
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
                                children.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                        val mimeType = children.getString(
                                children.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
                        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                            directories.add(documentId)
                        } else if (mimeType != null && mimeType.startsWith("image/")) {
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
    private fun addMediaUri(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing read external storage permission.")
            return Result.FAILURE
        }
        applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} NOT LIKE '%Screenshots%'",
                null, null)?.use { data ->
            val count = data.count
            if (count == 0) {
                Log.d(TAG, "No photos in the gallery.")
                return Result.FAILURE
            }
            val lastToken = ProviderContract.Artwork.getLastAddedArtwork(
                    applicationContext, GalleryArtProvider::class.java)?.token

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
                        addUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageUri)
                        return Result.SUCCESS
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Unable to find any other valid photos in the gallery")
            }
            return Result.FAILURE
        } ?: run {
            Log.w(TAG, "Empty cursor.")
            return Result.FAILURE
        }
    }

    private fun addUri(baseUri: Uri, imageUri: Uri) {
        val imageMetadata = ensureMetadataExists(imageUri)

        val artwork = Artwork().apply {
            token = imageUri.toString()
            persistentUri = imageUri
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

        ProviderContract.Artwork.addArtwork(applicationContext,
                GalleryArtProvider::class.java, artwork)
    }

    private fun ensureMetadataExists(imageUri: Uri): Metadata {
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