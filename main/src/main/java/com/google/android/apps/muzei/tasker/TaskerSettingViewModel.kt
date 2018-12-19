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

package com.google.android.apps.muzei.tasker

import android.app.Application
import android.content.pm.ProviderInfo
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.google.android.apps.muzei.room.InstalledProvidersLiveData
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

internal data class Action(
        val icon: Drawable,
        val text: String,
        val action: TaskerAction,
        val packageName: String? = null)

internal class TaskerSettingViewModel(
        application: Application
) : AndroidViewModel(application) {

    private val imageSize = application.resources.getDimensionPixelSize(
            R.dimen.tasker_action_icon_size)

    private val comparator = Comparator<Action> { a1, a2 ->
        // The Next Artwork action should always be first
        if (a1.action is NextArtworkAction) {
            return@Comparator -1
        } else if (a2.action is NextArtworkAction) {
            return@Comparator 1
        }
        // The SourceArtProvider should always the last provider listed
        if (a1.action is SelectProviderAction &&
                a1.action.authority == BuildConfig.SOURCES_AUTHORITY) {
            return@Comparator 1
        } else if (a2.action is SelectProviderAction &&
                a2.action.authority == BuildConfig.SOURCES_AUTHORITY) {
            return@Comparator -1
        }
        // Then put providers from Muzei on top
        val pn1 = a1.packageName
        val pn2 = a2.packageName
        if (pn1 != pn2) {
            if (application.packageName == pn1) {
                return@Comparator -1
            } else if (application.packageName == pn2) {
                return@Comparator 1
            }
        }
        // Finally, sort actions by their text
        a1.text.compareTo(a2.text)
    }

    val actions : LiveData<List<Action>> = object : MutableLiveData<List<Action>>() {
        val nextArtworkAction = Action(
                ContextCompat.getDrawable(application, R.drawable.ic_next_artwork)!!.apply {
                    setBounds(0, 0, imageSize, imageSize)
                },
                application.getString(R.string.action_next_artwork),
                NextArtworkAction)

        val installedProvidersLiveData = InstalledProvidersLiveData(application,
                viewModelScope)
        val installedProvidersObserver = Observer<List<ProviderInfo>> { providers ->
            val pm = application.packageManager
            val actionsList = mutableListOf(nextArtworkAction)
            providers?.forEach { providerInfo ->
                actionsList.add(Action(
                        providerInfo.loadIcon(pm).apply {
                            setBounds(0, 0, imageSize, imageSize)
                        },
                        application.getString(R.string.tasker_action_select_provider,
                                providerInfo.loadLabel(pm)),
                        SelectProviderAction(providerInfo.authority)))
            }
            value = actionsList.sortedWith(comparator)
        }

        override fun onActive() {
            installedProvidersLiveData.observeForever(installedProvidersObserver)
        }

        override fun onInactive() {
            installedProvidersLiveData.removeObserver(installedProvidersObserver)
        }
    }
}
