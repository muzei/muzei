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

package com.google.android.apps.muzei.legacy

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.legacy.R

class SourceSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                return when (loadFragmentClass(classLoader, className)) {
                    SourceWarningDialogFragment::class.java -> createSourceWarningDialog()
                    else -> super.instantiate(classLoader, className)
                }
            }
        }
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add<SourceWarningDialogFragment>("warning")
            }
        }
    }

    private fun createSourceWarningDialog() = SourceWarningDialogFragment(
        positiveListener = {
            val database = LegacyDatabase.getInstance(this)
            lifecycleScope.launch {
                val source = database.sourceDao().getCurrentSource()
                if (source != null) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    // Push the user to the SourceSettingsActivity to select a source
                    val intent = Intent(this@SourceSetupActivity,
                            SourceSettingsActivity::class.java)

                    if (getIntent().getBooleanExtra(
                                    MuzeiArtProvider.EXTRA_FROM_MUZEI, false)) {
                        intent.putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
                    }
                    startSettings.launch(intent)
                }
            }
        },
        neutralListener = {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://medium.com/@ianhlake/muzei-3-0-and-legacy-sources-8261979e2264"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        negativeListener = {
            finish()
        })

    private val startSettings = registerForActivityResult(StartActivityForResult()) { (resultCode, _) ->
        // Pass on the resultCode from the SourceSettingsActivity onto Muzei
        setResult(resultCode)
        finish()
    }
}

class SourceWarningDialogFragment(
        val positiveListener: () -> Unit = {},
        val neutralListener: () -> Unit = {},
        val negativeListener: () -> Unit = {}
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Legacy_Dialog)
                .setTitle(R.string.legacy_source_warning_title)
                .setMessage(R.string.legacy_source_warning_message)
                .setPositiveButton(R.string.legacy_source_warning_positive) { _, _ ->
                    positiveListener()
                }
                .setNeutralButton(R.string.legacy_source_warning_learn_more) { _, _ ->
                    neutralListener()
                }
                .setNegativeButton(R.string.legacy_source_warning_negative) { _, _ ->
                    negativeListener()
                }
                .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        requireActivity().finish()
    }
}