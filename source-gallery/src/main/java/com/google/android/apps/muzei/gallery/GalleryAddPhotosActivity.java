package com.google.android.apps.muzei.gallery;

import android.app.Activity;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ShareCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.util.Date;

/**
 * Activity which responds to {@link android.content.Intent#ACTION_SEND} and
 * {@link android.content.Intent#ACTION_SEND_MULTIPLE} to add one or more
 * images to the Gallery
 */
public class GalleryAddPhotosActivity extends Activity {
    private static final String TAG = "GalleryAddPhotos";

    private int mStreamCount;
    private int mSuccessCount;
    private int mFailureCount;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ShareCompat.IntentReader intentReader =
                ShareCompat.IntentReader.from(this);
        if (!intentReader.isShareIntent()) {
            finish();
            return;
        }
        final String callingApplication = getCallingApplication(intentReader);
        mStreamCount = intentReader.getStreamCount();
        for (int index = 0; index < mStreamCount; index++) {
            final Uri photoUri = intentReader.getStream(index);
            ChosenPhoto chosenPhoto = new ChosenPhoto(photoUri);
            Metadata metadata = new Metadata(photoUri);
            metadata.date = new Date();
            if (!TextUtils.isEmpty(callingApplication)) {
                metadata.location = getString(R.string.gallery_shared_from, callingApplication);
            }

            final LiveData<Long> insertLiveData = GalleryDatabase.getInstance(this).chosenPhotoDao()
                    .insert(this, chosenPhoto, metadata);
            insertLiveData.observeForever(new Observer<Long>() {
                @Override
                public void onChanged(@Nullable final Long id) {
                    insertLiveData.removeObserver(this);
                    if (id == null || id == 0L) {
                        Log.e(TAG, "Unable to insert chosen artwork for " + photoUri);
                        mFailureCount++;
                    } else {
                        mSuccessCount++;
                    }
                    updateCount();
                }
            });
        }
    }

    @Nullable
    private String getCallingApplication(ShareCompat.IntentReader intentReader) {
        String callingPackage = intentReader.getCallingPackage();
        if (TextUtils.isEmpty(callingPackage)) {
            Uri referrer = ActivityCompat.getReferrer(this);
            if (referrer != null) {
                callingPackage = referrer.getHost();
            }
        }
        CharSequence label = null;
        if (!TextUtils.isEmpty(callingPackage)) {
            PackageManager pm = getPackageManager();
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(callingPackage, 0));
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Could not retrieve label for package " + callingPackage, e);
            }
        }
        return label != null ? label.toString() : null;
    }

    private void updateCount() {
        if (mSuccessCount + mFailureCount == mStreamCount) {
            String message;
            if (mFailureCount == 0) {
                message = getResources().getQuantityString(R.plurals.gallery_add_photos_success,
                        mSuccessCount, mSuccessCount);
            } else {
                message = getResources().getQuantityString(R.plurals.gallery_add_photos_failure,
                        mFailureCount, mFailureCount, mStreamCount);
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
