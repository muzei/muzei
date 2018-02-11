package com.google.android.apps.muzei.gallery

import android.app.Activity
import android.arch.lifecycle.Observer
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.ShareCompat
import android.util.Log
import android.widget.Toast

/**
 * Activity which responds to [android.content.Intent.ACTION_SEND] and
 * [android.content.Intent.ACTION_SEND_MULTIPLE] to add one or more
 * images to the Gallery
 */
class GalleryAddPhotosActivity : Activity() {

    companion object {
        private const val TAG = "GalleryAddPhotos"
    }

    private var mStreamCount: Int = 0
    private var mSuccessCount: Int = 0
    private var mFailureCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentReader = ShareCompat.IntentReader.from(this)
        if (!intentReader.isShareIntent) {
            finish()
            return
        }
        val callingApplication = getCallingApplication(intentReader)
        mStreamCount = intentReader.streamCount
        for (index in 0 until mStreamCount) {
            val photoUri = intentReader.getStream(index)
            val chosenPhoto = ChosenPhoto(photoUri)

            val insertLiveData = GalleryDatabase.getInstance(this).chosenPhotoDao()
                    .insert(this, chosenPhoto, callingApplication)
            insertLiveData.observeForever(object : Observer<Long> {
                override fun onChanged(id: Long?) {
                    insertLiveData.removeObserver(this)
                    if (id == 0L) {
                        Log.e(TAG, "Unable to insert chosen artwork for $photoUri")
                        mFailureCount++
                    } else {
                        mSuccessCount++
                    }
                    updateCount()
                }
            })
        }
    }

    private fun getCallingApplication(intentReader: ShareCompat.IntentReader): String? {
        val callingPackage = intentReader.callingPackage ?: ActivityCompat.getReferrer(this)?.host
        return callingPackage?.run {
            return try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(callingPackage, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not retrieve label for package $this", e)
                null
            }
        }
    }

    private fun updateCount() {
        if (mSuccessCount + mFailureCount == mStreamCount) {
            val message = if (mFailureCount == 0) {
                resources.getQuantityString(R.plurals.gallery_add_photos_success,
                        mSuccessCount, mSuccessCount)
            } else {
                resources.getQuantityString(R.plurals.gallery_add_photos_failure,
                        mFailureCount, mFailureCount, mStreamCount)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
