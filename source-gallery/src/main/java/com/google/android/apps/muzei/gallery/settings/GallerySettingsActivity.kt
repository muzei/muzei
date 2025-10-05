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

package com.google.android.apps.muzei.gallery.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import androidx.savedstate.serialization.saved
import com.google.android.apps.muzei.gallery.GalleryDatabase
import com.google.android.apps.muzei.gallery.GalleryScanWorker
import com.google.android.apps.muzei.gallery.R
import com.google.android.apps.muzei.gallery.RequestStoragePermissions
import com.google.android.apps.muzei.gallery.contentUri
import com.google.android.apps.muzei.gallery.databinding.GalleryActivityBinding
import com.google.android.apps.muzei.gallery.theme.GalleryTheme
import com.google.android.apps.muzei.util.getString
import com.google.android.apps.muzei.util.getStringOrNull
import com.google.android.apps.muzei.util.plus
import com.google.android.apps.muzei.util.toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList

private class GetContentsFromActivityInfo : ActivityResultContract<ActivityInfo, List<Uri>>() {
    private val getMultipleContents = ActivityResultContracts.GetMultipleContents()

    override fun createIntent(context: Context, input: ActivityInfo): Intent =
            getMultipleContents.createIntent(context, "image/*")
                    .setClassName(input.packageName, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
            getMultipleContents.parseResult(resultCode, intent)
}

private class ChoosePhotos : ActivityResultContract<Unit, List<Uri>>() {
    private val openMultipleDocuments = ActivityResultContracts.OpenMultipleDocuments()

    @SuppressLint("InlinedApi")
    override fun createIntent(context: Context, input: Unit) =
            openMultipleDocuments.createIntent(context, arrayOf("image/*"))
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, true)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
            openMultipleDocuments.parseResult(resultCode, intent)
}

private class ChooseFolder : ActivityResultContract<Unit, Uri?>() {
    private val openDocumentTree = ActivityResultContracts.OpenDocumentTree()

    @SuppressLint("InlinedApi")
    override fun createIntent(context: Context, input: Unit) =
            openDocumentTree.createIntent(context, null)
                    .putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, true)

    override fun parseResult(resultCode: Int, intent: Intent?) =
            openDocumentTree.parseResult(resultCode, intent)
}

class GallerySettingsActivity : AppCompatActivity(),
    GalleryImportPhotosDialogFragment.OnRequestContentListener {

    companion object {
        private const val TAG = "GallerySettingsActivity"
        private const val SHARED_PREF_NAME = "GallerySettingsActivity"
        private const val SHOW_INTERNAL_STORAGE_MESSAGE = "show_internal_storage_message"
    }

    private val viewModel: GallerySettingsViewModel by viewModels()

    private val requestStoragePermission = registerForActivityResult(RequestStoragePermissions()) {
        onDataSetChanged()
    }

    /**
     * Use ACTION_OPEN_DOCUMENT by default for adding photos.
     * This allows us to use persistent URI permissions to access the underlying photos
     * meaning we don't need to use additional storage space and will pull in edits automatically
     * in addition to syncing deletions.
     *
     * (There's a separate 'Import photos' option which uses ACTION_GET_CONTENT to support
     * legacy apps)
     */
    private val choosePhotos = registerForActivityResult(ChoosePhotos()) { photos ->
        processSelectedUris(photos)
    }
    private val chooseFolder = registerForActivityResult(ChooseFolder()) { folder ->
        if (folder != null) {
            getSharedPreferences(SHARED_PREF_NAME, MODE_PRIVATE).edit {
                putBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, false)
            }
        }
        processSelectedUris(listOfNotNull(folder))
    }
    private val getContents = registerForActivityResult(GetContentsFromActivityInfo()) { photos ->
        processSelectedUris(photos)
    }

    private lateinit var binding: GalleryActivityBinding

    private val multiSelectionController = MultiSelectionController(this)

    private var chosenPhotosCount = 0
    private val permissionStateFlow by lazy {
        MutableStateFlow(checkRequestPermissionState(this))
    }
    private val addMode: MutableState<AddMode> by saved(
        serializer = MutableStateSerializer()
    ) {
        mutableStateOf(AddFab)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = GalleryActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMultiSelect()

        binding.photoGrid.setContent {
            GalleryTheme(
                dynamicColor = false
            ) {
                val photos = viewModel.chosenPhotos.collectAsLazyPagingItems()
                val scrollBehavior =
                    TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        val getContentActivityInfoList by viewModel.getContentActivityInfoList.collectAsState(
                            emptyList()
                        )
                        val context = LocalContext.current
                        val scope = rememberCoroutineScope()
                        GalleryTopAppBar(
                            importPhotoCount = getContentActivityInfoList.size,
                            importPhotoTitle = if (getContentActivityInfoList.size == 1) {
                                // If there's only one app that supports ACTION_GET_CONTENT, tell the user what that app is
                                stringResource(
                                    R.string.gallery_action_import_photos_from,
                                    getContentActivityInfoList.first().loadLabel(packageManager)
                                )
                            } else {
                                stringResource(R.string.gallery_action_import_photos)
                            },
                            photoCount = photos.itemCount,
                            scrollBehavior = scrollBehavior,
                            onImportPhotos = {
                                when (getContentActivityInfoList.size) {
                                    0 -> {
                                        // Ignore
                                    }

                                    1 -> {
                                        // Just start the one ACTION_GET_CONTENT app
                                        requestGetContent(getContentActivityInfoList.first())
                                    }

                                    else -> {
                                        // Let the user pick which app they want to import photos from
                                        GalleryImportPhotosDialogFragment.show(
                                            supportFragmentManager
                                        )
                                    }
                                }
                            },
                            onClearPhotos = {
                                scope.launch(NonCancellable) {
                                    GalleryDatabase.Companion.getInstance(context)
                                        .chosenPhotoDao().deleteAll(context)
                                }
                            }
                        )
                    },
                    contentWindowInsets = WindowInsets(),
                ) { innerPadding ->
                    val selectedItems = multiSelectionController.selection
                    LaunchedEffect(photos.loadState.isIdle) {
                        if (photos.loadState.isIdle) {
                            chosenPhotosCount = photos.itemCount
                            onDataSetChanged()
                        }
                    }
                    GalleryChosenPhotoGrid(
                        photos = photos,
                        contentPadding = innerPadding + WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                            .asPaddingValues() + PaddingValues(bottom = 90.dp),
                        checkedItemIds = selectedItems,
                        onCheckedToggled = { chosenPhoto ->
                            multiSelectionController.toggle(chosenPhoto.id)
                        },
                    ) { chosenPhoto, maxImages ->
                        if (chosenPhoto.isTreeUri)
                            getImagesFromTreeUri(chosenPhoto.uri, maxImages)
                        else
                            listOf(chosenPhoto.contentUri)
                    }
                }
            }
        }

        binding.empty.setContent {
            GalleryTheme(
                dynamicColor = false
            ) {
                GalleryEmpty(
                    permissionStateFlow = permissionStateFlow,
                    contentPadding = PaddingValues(bottom = 90.dp),
                    onReselectSelectedPhotos = {
                        requestStoragePermission.launch()
                    },
                    onEnableRandom = {
                        requestStoragePermission.launch()
                    },
                    onEditPermissionSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    },
                )
            }
        }
        binding.add.setContent {
            GalleryTheme(
                dynamicColor = false
            ) {
                val selectedCount = multiSelectionController.selection.size
                LaunchedEffect(selectedCount > 0) {
                    addMode.value = if (selectedCount > 0) AddNone else AddFab
                }
                GalleryAdd(
                    mode = addMode.value,
                    onToggleToolbar = {
                        when (addMode.value) {
                            AddFab -> addMode.value = AddToolbar
                            AddToolbar -> addMode.value = AddFab
                            AddNone -> addMode.value = AddFab
                        }
                    },
                    onAddPhoto = {
                        requestPhotos()
                    },
                    onAddFolder = {
                        try {
                            chooseFolder.launch()
                            val preferences = getSharedPreferences(SHARED_PREF_NAME, MODE_PRIVATE)
                            if (preferences.getBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, true)) {
                                toast(R.string.gallery_internal_storage_message, Toast.LENGTH_LONG)
                            }
                        } catch (_: ActivityNotFoundException) {
                            Snackbar.make(
                                binding.photoGrid, R.string.gallery_add_folder_error,
                                Snackbar.LENGTH_LONG
                            ).show()
                            addMode.value = AddFab
                        }
                    }
                )
            }
        }
        GalleryScanWorker.Companion.enqueueRescan(this)
    }

    private fun requestPhotos() {
        try {
            choosePhotos.launch()
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.photoGrid, R.string.gallery_add_photos_error,
                    Snackbar.LENGTH_LONG).show()
            addMode.value = AddFab
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions might have changed in the background
        onDataSetChanged()
    }


    override fun requestGetContent(info: ActivityInfo) {
        getContents.launch(info)
    }

    private fun setupMultiSelect() {
        // Set up toolbar
        binding.selectionToolbar.setContent {
            GalleryTheme(
                dynamicColor = false
            ) {
                val scope = rememberCoroutineScope()
                var title by remember { mutableStateOf(multiSelectionController.selection.size.toString()) }
                val firstSelectedId = multiSelectionController.selection.firstOrNull()
                LaunchedEffect(multiSelectionController.selection.size, firstSelectedId) {
                    val selectedCount = multiSelectionController.selection.size
                    if (selectedCount == 0) {
                        // Don't update the title as we are animating out
                        return@LaunchedEffect
                    }
                    title = multiSelectionController.selection.size.toString()
                    if (selectedCount == 1 && firstSelectedId != null) {
                        // If they've selected a tree URI, show the DISPLAY_NAME instead of just '1'
                        val chosenPhoto = GalleryDatabase.Companion.getInstance(this@GallerySettingsActivity)
                            .chosenPhotoDao()
                            .getChosenPhoto(firstSelectedId)
                        if (chosenPhoto?.isTreeUri == true) {
                            val displayName = getDisplayNameForTreeUri(chosenPhoto.uri)
                            if (displayName != null && displayName.isNotEmpty()) {
                                title = displayName
                            }
                        }
                    }
                }
                GallerySelectionToolbar(
                    selectionCount = multiSelectionController.selection.size,
                    title = title,
                    onReset = {
                        multiSelectionController.reset()
                    },
                    onRemove = {
                        val removePhotos = ArrayList(multiSelectionController.selection)

                        scope.launch(NonCancellable) {
                            // Remove chosen URIs
                            GalleryDatabase.Companion.getInstance(this@GallerySettingsActivity)
                                .chosenPhotoDao()
                                .delete(this@GallerySettingsActivity, removePhotos)
                        }

                        multiSelectionController.reset()
                    }
                )
            }
        }
    }

    private fun getDisplayNameForTreeUri(treeUri: Uri): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri))
        try {
            contentResolver.query(documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null)?.use { data ->
                if (data.moveToNext()) {
                    return data.getStringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                }
            }
        } catch (_: SecurityException) {
            // No longer can read this URI, which means no display name for this URI
        }
        return null
    }

    private fun onDataSetChanged() {
        if (chosenPhotosCount != 0) {
            binding.empty.visibility = View.GONE
            // We have at least one image, so consider the Gallery source properly setup
            setResult(RESULT_OK)
        } else {
            // No chosen images, show the empty View
            binding.empty.visibility = View.VISIBLE
            val newState = checkRequestPermissionState(this)
            when (newState) {
                is PartialPermissionGranted -> {
                    // Only a partial grant is done, which means we can scan for the images
                    // we have, but should offer the ability for users to reselect what images
                    // they want us to have access to
                    GalleryScanWorker.Companion.enqueueRescan(this)
                    setResult(RESULT_OK)
                }

                PermissionGranted -> {
                    // Permission is granted, we can show the random camera photos image
                    GalleryScanWorker.Companion.enqueueRescan(this)
                    setResult(RESULT_OK)
                }

                is PermissionDenied -> {
                    // We have no images until they enable the permission
                    setResult(RESULT_CANCELED)
                }
            }
            permissionStateFlow.value = newState
        }
    }

    private fun getImagesFromTreeUri(treeUri: Uri, maxImages: Int): List<Uri> {
        val images = ArrayList<Uri>()
        val directories = LinkedList<String>()
        directories.add(DocumentsContract.getTreeDocumentId(treeUri))
        while (images.size < maxImages && !directories.isEmpty()) {
            val parentDocumentId = directories.poll()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    parentDocumentId)
            try {
                contentResolver.query(childrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                        null, null, null)?.use { children ->
                    while (children.moveToNext()) {
                        val documentId = children.getString(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeType = children.getString(
                                DocumentsContract.Document.COLUMN_MIME_TYPE)
                        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                            directories.add(documentId)
                        } else if (mimeType.startsWith("image/")) {
                            // Add images to the list
                            images.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
                        }
                        if (images.size == maxImages) {
                            break
                        }
                    }
                }
            } catch (_: SecurityException) {
                // No longer can read this URI, which means no children from this URI
            } catch (e: Exception) {
                // Could be anything: NullPointerException, IllegalArgumentException, etc.
                Log.i(TAG, "Unable to load images from $treeUri", e)
            }
        }
        return images
    }

    private fun processSelectedUris(uris: List<Uri>) {
        addMode.value = AddFab
        if (uris.isEmpty()) {
            // Nothing to do, so we can avoid posting the runnable at all
            return
        }
        // Update chosen URIs
        lifecycleScope.launch(NonCancellable) {
            GalleryDatabase.Companion.getInstance(this@GallerySettingsActivity)
                    .chosenPhotoDao()
                    .insertAll(this@GallerySettingsActivity, uris)
        }
    }
}