package com.google.android.apps.muzei.gallery

import android.app.Dialog
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.support.annotation.LayoutRes
import android.support.v4.app.DialogFragment
import android.support.v4.app.FragmentManager
import android.support.v7.app.AlertDialog
import android.widget.ArrayAdapter
import androidx.content.withStyledAttributes

class GalleryImportPhotosDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "GalleryImportPhotosDialogFragment"

        fun show(fragmentManager : FragmentManager) {
            GalleryImportPhotosDialogFragment().show(fragmentManager, TAG)
        }
    }

    private val getContentActivitiesLiveData: LiveData<List<ActivityInfo>> by lazy {
        ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application))
                .get(GallerySettingsViewModel::class.java)
                .getContentActivityInfoList
    }
    private var listener: OnRequestContentListener? = null
    private lateinit var adapter: ArrayAdapter<CharSequence>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context?.withStyledAttributes(attrs = R.styleable.AlertDialog, defStyleAttr = R.attr.alertDialogStyle) {
            @LayoutRes val listItemLayout = getResourceId(R.styleable.AlertDialog_listItemLayout, 0)
            adapter = ArrayAdapter(context, listItemLayout)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
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
                "$context.javaClass.simpleName must implement OnRequestContentListener")
        getContentActivitiesLiveData.observe(this, Observer { getContentActivities ->
            run {
                if (getContentActivities?.isEmpty() != false) {
                    dismiss()
                } else {
                    updateAdapter(getContentActivities)
                }
            }
        })
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun updateAdapter(getContentActivites: List<ActivityInfo>) {
        val packageManager = context?.packageManager
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
