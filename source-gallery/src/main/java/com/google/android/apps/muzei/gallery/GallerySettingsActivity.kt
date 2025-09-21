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

package com.google.android.apps.muzei.gallery

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewAnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.android.apps.muzei.gallery.databinding.GalleryActivityBinding
import com.google.android.apps.muzei.gallery.theme.GalleryTheme
import com.google.android.apps.muzei.util.MultiSelectionController
import com.google.android.apps.muzei.util.addMenuProvider
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.util.getString
import com.google.android.apps.muzei.util.getStringOrNull
import com.google.android.apps.muzei.util.plus
import com.google.android.apps.muzei.util.toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    GalleryImportPhotosDialogFragment.OnRequestContentListener,
    MultiSelectionController.Callbacks,
    MenuProvider {

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

    private val addToolbarOnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideAddToolbar(true)
        }
    }

    private var chosenPhotosCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = GalleryActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, addToolbarOnBackPressedCallback)

        setupMultiSelect()

        binding.photoGrid.setContent {
            GalleryTheme(
                dynamicColor = false
            ) {
                val photos = viewModel.chosenPhotos.collectAsLazyPagingItems()
                val selectedItems = multiSelectionController.selection
                var firstIdle by remember { mutableStateOf(true) }
                LaunchedEffect(photos.loadState.isIdle) {
                    if (photos.loadState.isIdle) {
                        if (firstIdle) {
                            tryUpdateSelection(false)
                            firstIdle = false
                        }
                        chosenPhotosCount = photos.itemCount
                        onDataSetChanged()
                    }
                }
                GalleryChosenPhotoGrid(
                    photos = photos,
                    modifier = Modifier.padding(bottom = 90.dp),
                    contentPadding = WindowInsets.safeDrawing
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        .asPaddingValues() + PaddingValues(bottom = 90.dp),
                    checkedItemIds = selectedItems,
                    onCheckedToggled = { chosenPhoto ->
                        multiSelectionController.toggle(chosenPhoto.id, true)
                    },
                ) { chosenPhoto, maxImages ->
                    if (chosenPhoto.isTreeUri)
                        getImagesFromTreeUri(chosenPhoto.uri, maxImages)
                    else
                        listOf(chosenPhoto.contentUri)
                }
            }
        }

        binding.reselectSelectedPhotos.setOnClickListener {
            requestStoragePermission.launch()
        }
        binding.emptyStateGraphic.setContent {
            GalleryTheme(
                dynamicColor = false
            ) {
                GalleryEmptyStateGraphic()
            }
        }
        binding.enableRandom.setOnClickListener {
            requestStoragePermission.launch()
        }
        binding.editPermissionSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        binding.addFab.apply {
            setOnClickListener {
                showAddToolbar()
            }
        }
        binding.addPhotos.setOnClickListener { requestPhotos() }
        binding.addFolder.setOnClickListener {
            try {
                chooseFolder.launch()
                val preferences = getSharedPreferences(SHARED_PREF_NAME, MODE_PRIVATE)
                if (preferences.getBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, true)) {
                    toast(R.string.gallery_internal_storage_message, Toast.LENGTH_LONG)
                }
            } catch (_: ActivityNotFoundException) {
                Snackbar.make(binding.photoGrid, R.string.gallery_add_folder_error,
                        Snackbar.LENGTH_LONG).show()
                hideAddToolbar(true)
            }
        }
        addMenuProvider(this)
        viewModel.getContentActivityInfoList.map {
            it.size
        }.distinctUntilChanged().collectIn(this) {
            invalidateMenu()
        }
        GalleryScanWorker.enqueueRescan(this)
    }

    private fun requestPhotos() {
        try {
            choosePhotos.launch()
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.photoGrid, R.string.gallery_add_photos_error,
                    Snackbar.LENGTH_LONG).show()
            hideAddToolbar(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions might have changed in the background
        onDataSetChanged()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.gallery_activity, menu)
        // Make sure the 'Import photos' MenuItem is set up properly based on the number of
        // activities that handle ACTION_GET_CONTENT
        // 0 = hide the MenuItem
        // 1 = show 'Import photos from APP_NAME' to go to the one app that exists
        // 2 = show 'Import photos...' to have the user pick which app to import photos from
        val getContentActivities = viewModel.getContentActivityInfoList.value
        // Hide the 'Import photos' action if there are no activities found
        val importPhotosMenuItem = menu.findItem(R.id.action_import_photos)
        importPhotosMenuItem.isVisible = getContentActivities.isNotEmpty()
        // If there's only one app that supports ACTION_GET_CONTENT, tell the user what that app is
        if (getContentActivities.size == 1) {
            importPhotosMenuItem.title = getString(R.string.gallery_action_import_photos_from,
                    getContentActivities.first().loadLabel(packageManager))
        } else {
            importPhotosMenuItem.setTitle(R.string.gallery_action_import_photos)
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_photos -> {
                when (viewModel.getContentActivityInfoList.value.size) {
                    0 -> {
                        // Ignore
                    }
                    1 -> {
                        // Just start the one ACTION_GET_CONTENT app
                        requestGetContent(viewModel.getContentActivityInfoList.value.first())
                    }
                    else -> {
                        // Let the user pick which app they want to import photos from
                        GalleryImportPhotosDialogFragment.show(supportFragmentManager)
                    }
                }
                return true
            }
            R.id.action_clear_photos -> {
                val context = this
                lifecycleScope.launch(NonCancellable) {
                    GalleryDatabase.getInstance(context)
                            .chosenPhotoDao().deleteAll(context)
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun requestGetContent(info: ActivityInfo) {
        getContents.launch(info)
    }

    private fun setupMultiSelect() {
        // Set up toolbar
        binding.selectionToolbar.setNavigationOnClickListener {
            multiSelectionController.reset(true)
        }

        binding.selectionToolbar.addMenuProvider(R.menu.gallery_selection) { item ->
            when (item.itemId) {
                R.id.action_remove -> {
                    val removePhotos = ArrayList(multiSelectionController.selection)

                    lifecycleScope.launch(NonCancellable) {
                        // Remove chosen URIs
                        GalleryDatabase.getInstance(this@GallerySettingsActivity)
                            .chosenPhotoDao()
                            .delete(this@GallerySettingsActivity, removePhotos)
                    }

                    multiSelectionController.reset(true)
                    true
                }
                else -> false
            }
        }

        // Set up controller
        onBackPressedDispatcher.addCallback(multiSelectionController)
        multiSelectionController.callbacks = this
    }

    override fun onSelectionChanged(restored: Boolean, fromUser: Boolean) {
        tryUpdateSelection(!restored)
    }

    private fun showAddToolbar() {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime) / 2
        // Hide the add button
        binding.addFab.animate()
                .scaleX(0f)
                .scaleY(0f)
                .translationY(resources.getDimension(R.dimen.gallery_fab_margin))
                .setDuration(duration.toLong())
                .withEndAction {
                    if (isDestroyed) {
                        return@withEndAction
                    }
                    binding.addFab.visibility = View.INVISIBLE
                    // Then show the toolbar
                    binding.addToolbar.visibility = View.VISIBLE
                    addToolbarOnBackPressedCallback.isEnabled = true
                    ViewAnimationUtils.createCircularReveal(
                            binding.addToolbar,
                            binding.addToolbar.width / 2,
                            binding.addToolbar.height / 2,
                            0f,
                            (binding.addToolbar.width / 2).toFloat())
                            .setDuration(duration.toLong())
                            .start()
                }
    }

    private fun hideAddToolbar(showAddButton: Boolean) {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime) / 2
        // Hide the toolbar
        val hideAnimator = ViewAnimationUtils.createCircularReveal(
                binding.addToolbar,
                binding.addToolbar.width / 2,
                binding.addToolbar.height / 2,
                (binding.addToolbar.width / 2).toFloat(),
                0f).setDuration((if (showAddButton) duration else duration * 2).toLong())
        hideAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.addToolbar.visibility = View.INVISIBLE
                addToolbarOnBackPressedCallback.isEnabled = false
                if (showAddButton) {
                    binding.addFab.visibility = View.VISIBLE
                    binding.addFab.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f).duration = duration.toLong()
                } else {
                    // Just reset the translationY
                    binding.addFab.translationY = 0f
                }
            }
        })
        hideAnimator.start()
    }

    private fun tryUpdateSelection(allowAnimate: Boolean) {
        val selectedCount = multiSelectionController.selectedCount
        val toolbarVisible = selectedCount > 0

        val previouslyVisible: Boolean = binding.selectionToolbarContainer.getTag(
                R.id.gallery_viewtag_previously_visible) as? Boolean ?: false

        if (previouslyVisible != toolbarVisible) {
            binding.selectionToolbarContainer.setTag(R.id.gallery_viewtag_previously_visible, toolbarVisible)

            val duration = if (allowAnimate)
                resources.getInteger(android.R.integer.config_shortAnimTime)
            else
                0
            if (toolbarVisible) {
                binding.selectionToolbarContainer.visibility = View.VISIBLE
                binding.selectionToolbarContainer.translationY = (-binding.selectionToolbarContainer.height).toFloat()
                binding.selectionToolbarContainer.animate()
                        .translationY(0f)
                        .setDuration(duration.toLong())
                        .withEndAction(null)

                if (binding.addToolbar.isVisible) {
                    hideAddToolbar(false)
                } else {
                    binding.addFab.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .setDuration(duration.toLong())
                            .withEndAction { binding.addFab.visibility = View.INVISIBLE }
                }
            } else {
                binding.selectionToolbarContainer.animate()
                        .translationY((-binding.selectionToolbarContainer.height).toFloat())
                        .setDuration(duration.toLong())
                        .withEndAction { binding.selectionToolbarContainer.visibility = View.INVISIBLE }

                binding.addFab.visibility = View.VISIBLE
                binding.addFab.animate()
                        .scaleY(1f)
                        .scaleX(1f)
                        .setDuration(duration.toLong())
                        .withEndAction(null)
            }
        }

        if (toolbarVisible) {
            val title = selectedCount.toString()
            if (selectedCount == 1) {
                // If they've selected a tree URI, show the DISPLAY_NAME instead of just '1'
                val selectedId = multiSelectionController.selection.iterator().next()
                lifecycleScope.launch(Dispatchers.Main) {
                    val chosenPhoto = GalleryDatabase.getInstance(this@GallerySettingsActivity)
                            .chosenPhotoDao()
                            .getChosenPhoto(selectedId)
                    if (chosenPhoto?.isTreeUri == true && binding.selectionToolbar.isAttachedToWindow) {
                        getDisplayNameForTreeUri(chosenPhoto.uri)?.takeUnless { it.isEmpty() }?.run {
                            binding.selectionToolbar.title = this
                        }
                    }
                }
            }
            binding.selectionToolbar.title = title
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
            if (RequestStoragePermissions.isPartialGrant(this)) {
                // Only a partial grant is done, which means we can scan for the images
                // we have, but should offer the ability for users to reselect what images
                // they want us to have access to
                GalleryScanWorker.enqueueRescan(this)
                binding.emptyAnimator.displayedChild = 0
                binding.emptyDescription.setText(R.string.gallery_empty)
                setResult(RESULT_OK)
            } else if (RequestStoragePermissions.checkSelfPermission(this)) {
                // Permission is granted, we can show the random camera photos image
                GalleryScanWorker.enqueueRescan(this)
                binding.emptyAnimator.displayedChild = 1
                binding.emptyDescription.setText(R.string.gallery_empty)
                setResult(RESULT_OK)
            } else {
                // We have no images until they enable the permission
                setResult(RESULT_CANCELED)
                if (RequestStoragePermissions.shouldShowRequestPermissionRationale(this)) {
                    // We should show rationale on why they should enable the storage permission and
                    // random camera photos
                    binding.emptyAnimator.displayedChild = 2
                    binding.emptyDescription.setText(R.string.gallery_permission_rationale)
                } else {
                    // The user has permanently denied the storage permission. Give them a link to app settings
                    binding.emptyAnimator.displayedChild = 3
                    binding.emptyDescription.setText(R.string.gallery_denied_explanation)
                }
            }
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
        if (!binding.addToolbar.isAttachedToWindow) {
            // Can't animate detached Views
            binding.addToolbar.visibility = View.INVISIBLE
            addToolbarOnBackPressedCallback.isEnabled = false
            binding.addFab.visibility = View.VISIBLE
        } else {
            hideAddToolbar(true)
        }
        if (uris.isEmpty()) {
            // Nothing to do, so we can avoid posting the runnable at all
            return
        }
        // Update chosen URIs
        lifecycleScope.launch(NonCancellable) {
            GalleryDatabase.getInstance(this@GallerySettingsActivity)
                    .chosenPhotoDao()
                    .insertAll(this@GallerySettingsActivity, uris)
        }
    }
}