package com.google.android.apps.muzei.gallery

import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.annotation.LayoutRes
import androidx.core.content.withStyledAttributes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.observe
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GalleryImportPhotosDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "GalleryImportPhotosDialogFragment"

        fun show(fragmentManager: FragmentManager) {
            GalleryImportPhotosDialogFragment().show(fragmentManager, TAG)
        }
    }

    private val viewModel: GallerySettingsViewModel by viewModels()
    private val getContentActivitiesLiveData: LiveData<List<ActivityInfo>> by lazy {
        viewModel.getContentActivityInfoList
    }
    private var listener: OnRequestContentListener? = null
    private lateinit var adapter: ArrayAdapter<CharSequence>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext().withStyledAttributes(attrs = R.styleable.AlertDialog, defStyleAttr = R.attr.alertDialogStyle) {
            @LayoutRes val listItemLayout = getResourceId(R.styleable.AlertDialog_listItemLayout, 0)
            adapter = ArrayAdapter(requireContext(), listItemLayout)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext(),
                R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
                .setTitle(R.string.gallery_import_dialog_title)
                .setAdapter(adapter) { _, which ->
                    getContentActivitiesLiveData.value?.run {
                        listener?.requestGetContent(get(which))
                    }
                }.create()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? OnRequestContentListener ?: throw IllegalArgumentException(
                "${context.javaClass.simpleName} must implement OnRequestContentListener")
        getContentActivitiesLiveData.observe(this) { getContentActivities ->
            if (getContentActivities.isEmpty()) {
                dismiss()
            } else {
                updateAdapter(getContentActivities)
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun updateAdapter(getContentActivites: List<ActivityInfo>) {
        val packageManager = requireContext().packageManager
        adapter.apply {
            clear()
            addAll(getContentActivites.map { it.loadLabel(packageManager) })
            notifyDataSetChanged()
        }
    }

    interface OnRequestContentListener {
        fun requestGetContent(info: ActivityInfo)
    }
}
