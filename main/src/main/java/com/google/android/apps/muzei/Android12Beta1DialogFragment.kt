/*
 * Copyright 2021 Google Inc.
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

package com.google.android.apps.muzei

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.apps.muzei.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.nurik.roman.muzei.R

class Android12Beta1DialogFragment : DialogFragment() {
    companion object {
        fun showDialogIfNeeded(activity: FragmentActivity) : Boolean {
            val onAndroid12Beta1 = Build.ID.contains("SPB1.210331.013")
            if (onAndroid12Beta1) {
                Android12Beta1DialogFragment().show(
                        activity.supportFragmentManager,"android12beta1")
            }
            return onAndroid12Beta1
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
                .setTitle("Android 12 Beta 1 Detected")
                .setMessage("Due to a bug in Android 12 Beta 1, live wallpapers " +
                        "such as Muzei will instantly crash after being set as your wallpaper. " +
                        "Please upgrade to Beta 2 as soon as it is available.")
                .setPositiveButton("View Issue") { _: DialogInterface, _: Int ->
                    try {
                        val issueIntent = Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://issuetracker.google.com/issues/188636390"))
                        startActivity(issueIntent)
                    } catch (e: ActivityNotFoundException) {
                        requireContext().toast("Unable to open browser", Toast.LENGTH_LONG)
                    } catch (e: SecurityException) {
                        requireContext().toast("Unable to open browser", Toast.LENGTH_LONG)
                    }
                    requireActivity().finish()
                }
                .setNegativeButton(R.string.missing_resources_quit) { _: DialogInterface, _: Int ->
                    requireActivity().finish()
                }
                .create()
    }
}