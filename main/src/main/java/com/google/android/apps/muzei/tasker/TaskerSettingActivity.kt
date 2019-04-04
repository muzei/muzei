/*
 * Copyright 2014 Google Inc.
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

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.observe
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.twofortyfouram.locale.api.Intent.EXTRA_BUNDLE
import com.twofortyfouram.locale.api.Intent.EXTRA_STRING_BLURB
import net.nurik.roman.muzei.R

/**
 * The EDIT_SETTINGS activity for a Tasker Plugin allowing users to select whether they
 * want the Tasker action to move to the next artwork or select a particular provider
 */
class TaskerSettingActivity : AppCompatActivity() {

    private val viewModel: TaskerSettingViewModel by viewModels()

    private val adapter by lazy {
        ActionAdapter(this)
    }

    private val dialog: AlertDialog by lazy {
        MaterialAlertDialogBuilder(this, R.style.Theme_Muzei_Dialog)
                .setTitle(R.string.tasker_setting_dialog_title)
                .setSingleChoiceItems(adapter, -1) { _: DialogInterface, which: Int ->
                    val action = adapter.getItem(which) ?: return@setSingleChoiceItems
                    val intent = Intent().apply {
                        putExtra(EXTRA_STRING_BLURB, action.text)
                        putExtra(EXTRA_BUNDLE, action.action.toBundle())
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                }
                .setOnCancelListener {
                    setResult(RESULT_CANCELED, null)
                    finish()
                }
                .create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.actions.observe(this) { actions ->
            adapter.clear()
            adapter.addAll(actions)
            if (!dialog.isShowing) {
                dialog.show()
            }
        }
    }

    override fun onDestroy() {
        if (dialog.isShowing) {
            dialog.dismiss()
        }
        super.onDestroy()
    }

    private class ActionAdapter(
            context: Context
    ) : ArrayAdapter<Action>(context, R.layout.tasker_action_item, 0) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            getItem(position)?.let { action ->
                view.setCompoundDrawablesRelative(
                        action.icon, null, null, null)
                view.text = action.text
            }
            return view
        }
    }
}
