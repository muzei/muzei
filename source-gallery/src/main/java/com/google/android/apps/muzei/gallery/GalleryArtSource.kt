/*
 * Copyright 2014 Google Inc.
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

import android.Manifest
import android.annotation.SuppressLint
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LifecycleRegistry
import android.arch.lifecycle.Observer
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.support.annotation.RequiresApi
import android.support.media.ExifInterface
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import androidx.database.getString
import androidx.net.toUri
import com.google.android.apps.muzei.api.Artwork
import com.google.android.apps.muzei.api.MuzeiArtSource
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class GalleryArtSource : MuzeiArtSource(SOURCE_NAME), LifecycleOwner {

    companion object {
        private const val TAG = "GalleryArtSource"
        private const val SOURCE_NAME = "GalleryArtSource"

        internal const val PREF_ROTATE_INTERVAL_MIN = "rotate_interval_min"

        internal const val DEFAULT_ROTATE_INTERVAL_MIN = 60 * 6

        internal const val ACTION_BIND_GALLERY = "com.google.android.apps.muzei.gallery.BIND_GALLERY"
        internal const val ACTION_PUBLISH_NEXT_GALLERY_ITEM =
                "com.google.android.apps.muzei.gallery.action.PUBLISH_NEXT_GALLERY_ITEM"
        internal const val EXTRA_FORCE_URI = "com.google.android.apps.muzei.gallery.extra.FORCE_URI"

        private val sRandom = Random()

        @SuppressLint("SimpleDateFormat")
        private val sExifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss")

        private val sOmitCountryCodes = hashSetOf("US")

        internal fun getSharedPreferences(context: Context): SharedPreferences {
            return MuzeiArtSource.getSharedPreferences(context, SOURCE_NAME)
        }
    }

    private val mLifecycle = LifecycleRegistry(this)
    private val mGeocoder by lazy {
        Geocoder(this)
    }

    override fun getLifecycle(): Lifecycle {
        return mLifecycle
    }

    override fun onCreate() {
        super.onCreate()
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)

        GalleryDatabase.getInstance(this).chosenPhotoDao().chosenPhotos.observe(this,
                object : Observer<List<ChosenPhoto>> {
                    private var numImages = -1

                    override fun onChanged(chosenPhotos: List<ChosenPhoto>?) {
                        val oldCount = numImages
                        // Update the metadata
                        numImages = if(chosenPhotos != null) updateMeta(chosenPhotos) else 0

                        val currentArtworkToken = currentArtwork?.token?.toUri()
                        val foundCurrentArtwork = chosenPhotos?.find {
                            chosenPhoto -> chosenPhoto.contentUri == currentArtworkToken
                        } != null
                        if (!foundCurrentArtwork) {
                            // We're showing a removed URI
                            startService(Intent(this@GalleryArtSource, GalleryArtSource::class.java)
                                    .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM))
                        } else if (oldCount == 0 && numImages > 0) {
                            // If we've transitioned from a count of zero to a count greater than zero
                            startService(Intent(this@GalleryArtSource, GalleryArtSource::class.java)
                                    .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                                    .putExtra(EXTRA_FORCE_URI, chosenPhotos?.get(0)?.contentUri))
                        }
                    }
                })
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (ACTION_BIND_GALLERY == intent?.action) {
            Binder()
        } else super.onBind(intent)
    }

    override fun onDestroy() {
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onHandleIntent(intent: Intent?) {
        super.onHandleIntent(intent)
        if (ACTION_PUBLISH_NEXT_GALLERY_ITEM == intent?.action) {
            publishNextArtwork(intent.getParcelableExtra(EXTRA_FORCE_URI))
        }
    }

    override fun onUpdate(@UpdateReason reason: Int) {
        if (reason == UPDATE_REASON_INITIAL) {
            updateMeta(GalleryDatabase.getInstance(this).chosenPhotoDao().chosenPhotosBlocking)
        }
        publishNextArtwork(null)
    }

    private fun publishNextArtwork(forceUri: Uri?) {
        // schedule next
        scheduleNext()

        val chosenPhotos = GalleryDatabase.getInstance(this)
                .chosenPhotoDao().chosenPhotosBlocking
        val numChosenUris = chosenPhotos.size

        val lastImageUri = currentArtwork?.imageUri

        val (imageUri, token) = when {
            forceUri != null -> {
                val chosenPhoto = GalleryDatabase.getInstance(this)
                        .chosenPhotoDao().chosenPhotoBlocking(ContentUris.parseId(forceUri))
                if (chosenPhoto?.isTreeUri == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val treeUri = chosenPhoto.uri
                    val photoUris = ArrayList<Uri>()
                    addAllImagesFromTree(photoUris, treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    Pair(photoUris[sRandom.nextInt(photoUris.size)], forceUri)
                } else {
                    Pair(forceUri, forceUri)
                }
            }
            numChosenUris > 0 -> {
                // First build a list of all image URIs, recursively exploring any tree URIs that were added
                val allImages = ArrayList<Uri>(numChosenUris)
                val tokens = ArrayList<Uri>(numChosenUris)
                for (chosenPhoto in chosenPhotos) {
                    val chosenUri = chosenPhoto.contentUri
                    if (chosenPhoto.isTreeUri) {
                        val treeUri = chosenPhoto.uri
                        val numAdded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            addAllImagesFromTree(allImages, treeUri,
                                    DocumentsContract.getTreeDocumentId(treeUri))
                        } else {
                            0
                        }
                        for (h in 0 until numAdded) {
                            tokens.add(chosenUri)
                        }
                    } else {
                        allImages.add(chosenUri)
                        tokens.add(chosenUri)
                    }
                }
                val numImages = allImages.size
                if (numImages == 0) {
                    Log.e(TAG, "No photos in the selected directories.")
                    return
                }
                var index : Int
                while (true) {
                    index = sRandom.nextInt(numImages)
                    if (numImages <= 1 || allImages[index] != lastImageUri) {
                        break
                    }
                }
                Pair(allImages[index], tokens[index])
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this,
                                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Missing read external storage permission.")
                    return
                }
                contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " NOT LIKE '%Screenshots%'",
                        null, null)?.use {
                    val count = it.count
                    if (count == 0) {
                        Log.e(TAG, "No photos in the gallery.")
                        return
                    }

                    var newImageUri : Uri
                    while (true) {
                        it.moveToPosition(sRandom.nextInt(count))
                        newImageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                it.getLong(0))
                        if (newImageUri != lastImageUri) {
                            break
                        }
                    }
                    Pair(newImageUri, newImageUri)
                } ?: run {
                    Log.w(TAG, "Empty cursor.")
                    return
                }
            }
        }

        // Retrieve metadata for item
        val metadata = ensureMetadataExists(imageUri)

        // Publish the actual artwork
        val title = metadata.date?.let {
            DateUtils.formatDateTime(this, it.time,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR
                            or DateUtils.FORMAT_SHOW_WEEKDAY)
        } ?: getString(R.string.gallery_from_gallery)

        val byline = if (!metadata.location.isNullOrEmpty()) {
            metadata.location
        } else {
            getString(R.string.gallery_touch_to_view)
        }

        publishArtwork(Artwork.Builder()
                .imageUri(imageUri)
                .title(title)
                .byline(byline)
                .token(token.toString())
                .viewIntent(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(imageUri, "image/jpeg"))
                .build())
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun addAllImagesFromTree(allImages: MutableList<Uri>?, treeUri: Uri, parentDocumentId: String): Int {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                parentDocumentId)
        var numImagesAdded = 0
        try {
            contentResolver.query(childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null)?.use {
                while (it.moveToNext()) {
                    val documentId = it.getString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeType = it.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    when {
                        DocumentsContract.Document.MIME_TYPE_DIR == mimeType -> // Recursively explore all directories
                            numImagesAdded += addAllImagesFromTree(allImages, treeUri, documentId)
                        mimeType.startsWith("image/") -> {
                            // Add images to the list
                            allImages?.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
                            numImagesAdded++
                        }
                    }
                }
            }
        } catch (e: NullPointerException) {
            Log.e(TAG, "Error reading $childrenUri", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error reading $childrenUri", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Error reading $childrenUri", e)
        }
        return numImagesAdded
    }

    private fun updateMeta(chosenPhotos: List<ChosenPhoto>): Int {
        var numImages = 0
        val idsToDelete = ArrayList<Long>()
        for (chosenPhoto in chosenPhotos) {
            if (chosenPhoto.isTreeUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val treeUri = chosenPhoto.uri
                try {
                    numImages += addAllImagesFromTree(null, treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unable to load images from $treeUri, deleting row", e)
                    idsToDelete.add(chosenPhoto.id)
                }

            } else {
                numImages++
            }
        }
        if (!idsToDelete.isEmpty()) {
            val applicationContext = applicationContext
            object : Thread() {
                override fun run() {
                    GalleryDatabase.getInstance(applicationContext).chosenPhotoDao()
                            .delete(applicationContext, idsToDelete)
                }
            }.start()
        }
        setDescription(if (numImages > 0)
            resources.getQuantityString(
                    R.plurals.gallery_description_choice_template,
                    numImages, numImages)
        else
            getString(R.string.gallery_description))
        if (numImages != 1) {
            setUserCommands(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK)
        } else {
            removeAllUserCommands()
        }
        return numImages
    }

    private fun scheduleNext() {
        val rotateIntervalMinutes = sharedPreferences.getInt(PREF_ROTATE_INTERVAL_MIN,
                DEFAULT_ROTATE_INTERVAL_MIN)
        if (rotateIntervalMinutes > 0) {
            scheduleUpdate(System.currentTimeMillis() + rotateIntervalMinutes * 60 * 1000)
        }
    }

    private fun ensureMetadataExists(imageUri: Uri): Metadata {
        val metadataDao = GalleryDatabase.getInstance(this).metadataDao()
        val existingMetadata = metadataDao.metadataForUri(imageUri)
        if (existingMetadata != null) {
            return existingMetadata
        }
        // No cached metadata or it's stale, need to pull it separately using Exif
        val metadata = Metadata(imageUri)

        try {
            contentResolver.openInputStream(imageUri)?.use { input ->
                val exifInterface = ExifInterface(input)
                val dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
                if (!TextUtils.isEmpty(dateString)) {
                    metadata.date = sExifDateFormat.parse(dateString)
                }

                exifInterface.latLong?.apply {
                    // Reverse geocode
                    val addresses = try {
                        mGeocoder.getFromLocation(this[0], this[1], 1)
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
                        if (!countryCode.isNullOrEmpty() && !sOmitCountryCodes.contains(countryCode)) {
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
        } catch (e: NullPointerException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't read image metadata", e)
        }

        return metadata
    }
}
