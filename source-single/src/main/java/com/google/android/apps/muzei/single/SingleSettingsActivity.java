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

package com.google.android.apps.muzei.single;

import android.app.Activity;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;

/**
 * Settings Activity which allows users to select a new photo
 */
public class SingleSettingsActivity extends Activity {
    private static final int REQUEST_PHOTO = 1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PHOTO) {
            return;
        }
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            LiveData<Boolean> insertArtworkLiveData =
                    SingleArtSource.setArtwork(this, data.getData());
            insertArtworkLiveData.observeForever(new Observer<Boolean>() {
                @Override
                public void onChanged(@Nullable Boolean success) {
                    insertArtworkLiveData.removeObserver(this);
                    setResult(success != null && success ? RESULT_OK : RESULT_CANCELED);
                    finish();
                }
            });
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}
