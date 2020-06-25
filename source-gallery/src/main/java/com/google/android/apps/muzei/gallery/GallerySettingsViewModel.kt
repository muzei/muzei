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

package com.google.android.apps.muzei.gallery

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ViewModel responsible for handling the list of ACTION_GET_CONTENT activities across configuration
 * changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GallerySettingsViewModel(application: Application) : AndroidViewModel(application) {
    internal val chosenPhotos = Pager(PagingConfig(48)) {
        GalleryDatabase.getInstance(application).chosenPhotoDao().chosenPhotosPaged
    }.flow.cachedIn(viewModelScope)
    internal val getContentActivityInfoList = getContentActivityInfos()
            .asLiveData(viewModelScope.coroutineContext + EmptyCoroutineContext)

    private fun getContentActivityInfos(): Flow<List<ActivityInfo>> = callbackFlow {
        val refreshList = {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val packageManager = getApplication<Application>().packageManager
            val packageName = getApplication<Application>().packageName
            sendBlocking(packageManager.queryIntentActivities(intent, 0).asSequence().map {
                it.activityInfo
            }.filter {
                // Filter out the default system UI
                it.packageName != "com.android.documentsui"
                        && it.packageName != "com.google.android.documentsui"
            }.filter {
                // Only show exported activities
                it.exported
            }.filter {
                // Only show activities that have no permissions or permissions we hold
                it.permission?.isEmpty() != false ||
                        packageManager.checkPermission(it.permission, packageName) ==
                        PackageManager.PERMISSION_GRANTED
            }.toList())
        }
        val packagesChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshList()
            }
        }
        val packageChangeIntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        getApplication<Application>().registerReceiver(packagesChangedReceiver,
                packageChangeIntentFilter)
        // Refresh the list to get any changes since we were last active
        refreshList()

        awaitClose {
            getApplication<Application>().unregisterReceiver(packagesChangedReceiver)
        }
    }
}
