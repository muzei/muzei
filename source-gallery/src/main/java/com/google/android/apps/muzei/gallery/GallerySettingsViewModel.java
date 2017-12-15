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

package com.google.android.apps.muzei.gallery;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.arch.paging.LivePagedListBuilder;
import android.arch.paging.PagedList;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewModel responsible for handling the list of ACTION_GET_CONTENT activities across configuration
 * changes.
 */
public class GallerySettingsViewModel extends AndroidViewModel {
    private final LiveData<PagedList<ChosenPhoto>> mChosenPhotos;
    private final LiveData<List<ActivityInfo>> mGetContentActivityInfoListLiveData;

    public GallerySettingsViewModel(Application application) {
        super(application);
        mChosenPhotos = new LivePagedListBuilder<>(
                GalleryDatabase.getInstance(application).chosenPhotoDao().getChosenPhotosPaged(),
                24).build();
        mGetContentActivityInfoListLiveData = new MutableLiveData<List<ActivityInfo>>() {
            private BroadcastReceiver mPackagesChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refreshList();
                }
            };

            @Override
            protected void onActive() {
                IntentFilter packageChangeIntentFilter = new IntentFilter();
                packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
                packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                packageChangeIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                packageChangeIntentFilter.addDataScheme("package");
                getApplication().registerReceiver(mPackagesChangedReceiver, packageChangeIntentFilter);
                // Refresh the list to get any changes since we were last active
                refreshList();
            }

            @Override
            protected void onInactive() {
                getApplication().unregisterReceiver(mPackagesChangedReceiver);
            }

            private void refreshList() {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                PackageManager packageManager = getApplication().getPackageManager();
                List<ResolveInfo> getContentActivities = packageManager
                        .queryIntentActivities(intent, 0);
                List<ActivityInfo> contentActivities = new ArrayList<>(getContentActivities.size());
                for (ResolveInfo info : getContentActivities) {
                    // Filter out the default system UI
                    if (TextUtils.equals(info.activityInfo.packageName, "com.android.documentsui")) {
                        continue;
                    }
                    // Filter out non-exported activities
                    if (!info.activityInfo.exported) {
                        continue;
                    }
                    // Filter out activities we don't have permission to start
                    if (!TextUtils.isEmpty(info.activityInfo.permission)
                            && packageManager.checkPermission(info.activityInfo.permission,
                            getApplication().getPackageName()) != PackageManager.PERMISSION_GRANTED) {
                        continue;
                    }
                    contentActivities.add(info.activityInfo);
                }
                setValue(contentActivities);
            }
        };
    }

    LiveData<PagedList<ChosenPhoto>> getChosenPhotos() {
        return mChosenPhotos;
    }

    LiveData<List<ActivityInfo>> getGetContentActivityInfoList() {
        return mGetContentActivityInfoListLiveData;
    }
}
