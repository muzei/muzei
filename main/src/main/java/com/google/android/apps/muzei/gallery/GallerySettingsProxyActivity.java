/*
 * Copyright 2015 Google Inc.
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

package com.google.android.apps.muzei.gallery;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;

import com.google.android.apps.muzei.api.MuzeiArtSource;

import net.nurik.roman.muzei.R;

public class GallerySettingsProxyActivity extends AppCompatActivity {
    private static final int REQUEST_READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE = 1;

    private boolean mInitialSetup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() != null) {
            mInitialSetup = getIntent().getBooleanExtra(MuzeiArtSource.EXTRA_INITIAL_SETUP, false);
        }

        verifyOrRequestPermission();
    }

    private void verifyOrRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            if (mInitialSetup) {
                setResult(RESULT_OK);
                finish();
            } else {
                finish();
                startActivity(new Intent(this, GallerySettingsActivity.class));
            }

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE) {
            return;
        }

        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            verifyOrRequestPermission();
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[0])) {
            new AlertDialog.Builder(this, R.style.Theme_Muzei_Dialog)
                    .setTitle(R.string.gallery_permission_dialog_hard_title)
                    .setMessage(R.string.gallery_permission_dialog_hard_message)
                    .setPositiveButton(
                            R.string.gallery_permission_dialog_hard_positive_title,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    finish();
                                    setResult(RESULT_CANCELED);
                                    Intent intent = new Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                                    intent.setData(uri);
                                    startActivity(intent);
                                }
                            })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            finish();
                            setResult(RESULT_CANCELED);
                        }
                    })
                    .setNegativeButton(R.string.gallery_permission_dialog_hard_negative_title, null)
                    .show();
        } else {
            finish();
            setResult(RESULT_CANCELED);
        }
    }

}
