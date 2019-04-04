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

package com.google.android.apps.muzei.sources

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.observe
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.nurik.roman.muzei.R

class SourceSetupActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CHOOSE_SOURCE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            SourceWarningDialogFragment().show(supportFragmentManager, "warning")
        }
    }

    override fun onAttachFragment(fragment: Fragment) {
        if (fragment is SourceWarningDialogFragment) {
            fragment.positiveListener = {
                MuzeiDatabase.getInstance(this).sourceDao().currentSource.observe(this) { source ->
                    if (source != null) {
                        setResult(Activity.RESULT_OK)
                        finish()
                    } else {
                        // Push the user to the SourceSettingsActivity to select a source
                        val intent = Intent(this@SourceSetupActivity,
                                SourceSettingsActivity::class.java)

                        if (getIntent().getBooleanExtra(
                                        MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, false)) {
                            intent.putExtra(MuzeiArtSource.EXTRA_FROM_MUZEI_SETTINGS, true)
                        }
                        startActivityForResult(intent, REQUEST_CHOOSE_SOURCE)
                    }
                }
            }
            fragment.neutralListener = {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://medium.com/@ianhlake/muzei-3-0-and-legacy-sources-8261979e2264"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            fragment.negativeListener = {
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CHOOSE_SOURCE) {
            return
        }
        // Pass on the resultCode from the SourceSettingsActivity onto Muzei
        setResult(resultCode)
        finish()
    }
}

class SourceWarningDialogFragment : DialogFragment() {
    var positiveListener : () -> Unit = {}
    var neutralListener : () -> Unit = {}
    var negativeListener : () -> Unit = {}

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Muzei_Dialog)
                .setTitle(R.string.source_warning_title)
                .setMessage(R.string.source_warning_message)
                .setPositiveButton(R.string.source_warning_positive) { _, _ ->
                    positiveListener()
                }
                .setNeutralButton(R.string.source_warning_learn_more) { _, _ ->
                    neutralListener()
                }
                .setNegativeButton(R.string.source_warning_negative) { _, _ ->
                    negativeListener()
                }
                .create()
    }

    override fun onStop() {
        if (!requireDialog().isShowing) {
            requireActivity().finish()
        }
        super.onStop()
    }
}
