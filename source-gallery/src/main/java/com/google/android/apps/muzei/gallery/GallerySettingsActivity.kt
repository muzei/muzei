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

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewAnimator
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.observe
import androidx.paging.PagedList
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.apps.muzei.util.MultiSelectionController
import com.google.android.apps.muzei.util.coroutineScope
import com.google.android.apps.muzei.util.getString
import com.google.android.apps.muzei.util.getStringOrNull
import com.google.android.apps.muzei.util.toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedList

class GallerySettingsActivity : AppCompatActivity(), Observer<PagedList<ChosenPhoto>>,
        GalleryImportPhotosDialogFragment.OnRequestContentListener, MultiSelectionController.Callbacks {

    companion object {
        private const val SHARED_PREF_NAME = "GallerySettingsActivity"
        private const val SHOW_INTERNAL_STORAGE_MESSAGE = "show_internal_storage_message"
        private const val REQUEST_CHOOSE_PHOTOS = 1
        private const val REQUEST_CHOOSE_FOLDER = 2
        private const val REQUEST_STORAGE_PERMISSION = 3

        internal val CHOSEN_PHOTO_DIFF_CALLBACK: DiffUtil.ItemCallback<ChosenPhoto> = object : DiffUtil.ItemCallback<ChosenPhoto>() {
            override fun areItemsTheSame(oldItem: ChosenPhoto, newItem: ChosenPhoto): Boolean {
                return oldItem.uri == newItem.uri
            }

            override fun areContentsTheSame(oldItem: ChosenPhoto, newItem: ChosenPhoto): Boolean {
                // If the items are the same (same image URI), then they are equivalent and
                // no change animation is needed
                return true
            }
        }
    }

    private val viewModel: GallerySettingsViewModel by viewModels()

    private val chosenPhotosLiveData: LiveData<PagedList<ChosenPhoto>> by lazy {
        viewModel.chosenPhotos
    }

    private val selectionToolbar: Toolbar by lazy {
        findViewById<Toolbar>(R.id.selection_toolbar)
    }

    private val photoGridView: RecyclerView by lazy {
        findViewById<RecyclerView>(R.id.photo_grid).apply {
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
            }
        }
    }
    private var itemSize = 10

    private val multiSelectionController = MultiSelectionController(this)

    private val placeholderDrawable: ColorDrawable by lazy {
        ColorDrawable(ContextCompat.getColor(this,
                R.color.gallery_chosen_photo_placeholder))
    }

    private val getContentActivitiesLiveData: LiveData<List<ActivityInfo>> by lazy {
        viewModel.getContentActivityInfoList
    }

    private var updatePosition = -1
    private lateinit var addButton: View
    private val addToolbar: LinearLayout by lazy {
        findViewById<LinearLayout>(R.id.add_toolbar)
    }

    private var lastTouchPosition: Int = 0
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0

    private val chosenPhotosAdapter = GalleryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gallery_activity)
        setSupportActionBar(findViewById(R.id.app_bar))

        setupMultiSelect()

        val gridLayoutManager = GridLayoutManager(this, 1)
        photoGridView.layoutManager = gridLayoutManager

        val vto = photoGridView.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val width = (photoGridView.width
                        - photoGridView.paddingStart - photoGridView.paddingEnd)
                if (width <= 0) {
                    return
                }

                // Compute number of columns
                val maxItemWidth = resources.getDimensionPixelSize(
                        R.dimen.gallery_chosen_photo_grid_max_item_size)
                var numColumns = 1
                while (true) {
                    if (width / numColumns > maxItemWidth) {
                        ++numColumns
                    } else {
                        break
                    }
                }

                val spacing = resources.getDimensionPixelSize(
                        R.dimen.gallery_chosen_photo_grid_spacing)
                itemSize = (width - spacing * (numColumns - 1)) / numColumns

                // Complete setup
                gridLayoutManager.spanCount = numColumns
                chosenPhotosAdapter.setHasStableIds(true)
                photoGridView.adapter = chosenPhotosAdapter

                photoGridView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                tryUpdateSelection(false)
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(photoGridView) { v, insets ->
            val gridSpacing = resources
                    .getDimensionPixelSize(R.dimen.gallery_chosen_photo_grid_spacing)
            ViewCompat.onApplyWindowInsets(v, insets.replaceSystemWindowInsets(
                    insets.systemWindowInsetLeft + gridSpacing,
                    gridSpacing,
                    insets.systemWindowInsetRight + gridSpacing,
                    insets.systemWindowInsetBottom + insets.systemWindowInsetTop + gridSpacing +
                            resources.getDimensionPixelSize(R.dimen.gallery_fab_space)))
            insets
        }

        val enableRandomImages = findViewById<Button>(R.id.gallery_enable_random)
        enableRandomImages.setOnClickListener { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION) }
        val permissionSettings = findViewById<Button>(R.id.gallery_edit_permission_settings)
        permissionSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        addButton = findViewById<View>(R.id.add_fab).apply {
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // On Lollipop and higher, we show the add toolbar to allow users to add either
                    // individual photos or a whole directory
                    showAddToolbar()
                } else {
                    requestPhotos()
                }
            }
        }
        findViewById<View>(R.id.add_photos).setOnClickListener { requestPhotos() }
        findViewById<View>(R.id.add_folder).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, true)
            }
            try {
                startActivityForResult(intent, REQUEST_CHOOSE_FOLDER)
                val preferences = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
                if (preferences.getBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, true)) {
                    toast(R.string.gallery_internal_storage_message, Toast.LENGTH_LONG)
                }
            } catch (e: ActivityNotFoundException) {
                Snackbar.make(photoGridView, R.string.gallery_add_folder_error,
                        Snackbar.LENGTH_LONG).show()
                hideAddToolbar(true)
            }
        }
        chosenPhotosLiveData.observe(this, this)
        getContentActivitiesLiveData.observe(this) { invalidateOptionsMenu() }
    }

    private fun requestPhotos() {
        // Use ACTION_OPEN_DOCUMENT by default for adding photos.
        // This allows us to use persistent URI permissions to access the underlying photos
        // meaning we don't need to use additional storage space and will pull in edits automatically
        // in addition to syncing deletions.
        // (There's a separate 'Import photos' option which uses ACTION_GET_CONTENT to support legacy apps)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, true)
        }
        try {
            startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(photoGridView, R.string.gallery_add_photos_error,
                    Snackbar.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                hideAddToolbar(true)
            }
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE_PERMISSION) {
            return
        }
        onDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        // Permissions might have changed in the background
        onDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.gallery_activity, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        // Make sure the 'Import photos' MenuItem is set up properly based on the number of
        // activities that handle ACTION_GET_CONTENT
        // 0 = hide the MenuItem
        // 1 = show 'Import photos from APP_NAME' to go to the one app that exists
        // 2 = show 'Import photos...' to have the user pick which app to import photos from
        val getContentActivites = getContentActivitiesLiveData.value
                ?: // We'll get another chance when the list is populated
                return false
        // Hide the 'Import photos' action if there are no activities found
        val importPhotosMenuItem = menu.findItem(R.id.action_import_photos)
        importPhotosMenuItem.isVisible = !getContentActivites.isEmpty()
        // If there's only one app that supports ACTION_GET_CONTENT, tell the user what that app is
        if (getContentActivites.size == 1) {
            importPhotosMenuItem.title = getString(R.string.gallery_action_import_photos_from,
                    getContentActivites[0].loadLabel(packageManager))
        } else {
            importPhotosMenuItem.setTitle(R.string.gallery_action_import_photos)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_photos -> {
                getContentActivitiesLiveData.value?.run {
                    when (size) {
                        0 -> {
                            // Ignore
                        }
                        1 -> {
                            // Just start the one ACTION_GET_CONTENT app
                            requestGetContent(this[0])
                        }
                        else -> {
                            // Let the user pick which app they want to import photos from
                            GalleryImportPhotosDialogFragment.show(supportFragmentManager)
                        }
                    }
                }
                return true
            }
            R.id.action_clear_photos -> {
                val context = this
                GlobalScope.launch {
                    GalleryDatabase.getInstance(context)
                            .chosenPhotoDao().deleteAll(context)
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun requestGetContent(info: ActivityInfo) {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            setClassName(info.packageName, info.name)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQUEST_CHOOSE_PHOTOS)
    }

    private fun setupMultiSelect() {
        // Set up toolbar
        selectionToolbar.setNavigationOnClickListener { multiSelectionController.reset(true) }

        selectionToolbar.inflateMenu(R.menu.gallery_selection)
        selectionToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_remove -> {
                    val removePhotos = ArrayList(
                            multiSelectionController.selection)

                    GlobalScope.launch {
                        // Remove chosen URIs
                        GalleryDatabase.getInstance(this@GallerySettingsActivity)
                                .chosenPhotoDao()
                                .delete(this@GallerySettingsActivity, removePhotos)
                    }

                    multiSelectionController.reset(true)
                    return@setOnMenuItemClickListener true
                }
                else -> return@setOnMenuItemClickListener false
            }
        }

        // Set up controller
        multiSelectionController.callbacks = this
    }

    override fun onSelectionChanged(restored: Boolean, fromUser: Boolean) {
        tryUpdateSelection(!restored)
    }

    override fun onBackPressed() {
        when {
            multiSelectionController.selectedCount > 0 -> multiSelectionController.reset(true)
            addToolbar.visibility == View.VISIBLE -> hideAddToolbar(true)
            else -> super.onBackPressed()
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun showAddToolbar() {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime) / 2
        // Hide the add button
        addButton.animate()
                .scaleX(0f)
                .scaleY(0f)
                .translationY(resources.getDimension(R.dimen.gallery_fab_margin))
                .setDuration(duration.toLong())
                .withEndAction {
                    if (isDestroyed) {
                        return@withEndAction
                    }
                    addButton.visibility = View.INVISIBLE
                    // Then show the toolbar
                    addToolbar.visibility = View.VISIBLE
                    ViewAnimationUtils.createCircularReveal(
                            addToolbar,
                            addToolbar.width / 2,
                            addToolbar.height / 2,
                            0f,
                            (addToolbar.width / 2).toFloat())
                            .setDuration(duration.toLong())
                            .start()
                }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun hideAddToolbar(showAddButton: Boolean) {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime) / 2
        // Hide the toolbar
        val hideAnimator = ViewAnimationUtils.createCircularReveal(
                addToolbar,
                addToolbar.width / 2,
                addToolbar.height / 2,
                (addToolbar.width / 2).toFloat(),
                0f).setDuration((if (showAddButton) duration else duration * 2).toLong())
        hideAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                addToolbar.visibility = View.INVISIBLE
                if (showAddButton) {
                    addButton.visibility = View.VISIBLE
                    addButton.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f).duration = duration.toLong()
                } else {
                    // Just reset the translationY
                    addButton.translationY = 0f
                }
            }
        })
        hideAnimator.start()
    }

    private fun tryUpdateSelection(allowAnimate: Boolean) {
        val selectionToolbarContainer = findViewById<View>(R.id.selection_toolbar_container)

        if (updatePosition >= 0) {
            chosenPhotosAdapter.notifyItemChanged(updatePosition)
            updatePosition = -1
        } else {
            chosenPhotosAdapter.notifyDataSetChanged()
        }

        val selectedCount = multiSelectionController.selectedCount
        val toolbarVisible = selectedCount > 0

        val previouslyVisible: Boolean = selectionToolbarContainer.getTag(
                R.id.gallery_viewtag_previously_visible) as? Boolean ?: false

        if (previouslyVisible != toolbarVisible) {
            selectionToolbarContainer.setTag(R.id.gallery_viewtag_previously_visible, toolbarVisible)

            val duration = if (allowAnimate)
                resources.getInteger(android.R.integer.config_shortAnimTime)
            else
                0
            if (toolbarVisible) {
                selectionToolbarContainer.visibility = View.VISIBLE
                selectionToolbarContainer.translationY = (-selectionToolbarContainer.height).toFloat()
                selectionToolbarContainer.animate()
                        .translationY(0f)
                        .setDuration(duration.toLong())
                        .withEndAction(null)

                if (addToolbar.visibility == View.VISIBLE) {
                    hideAddToolbar(false)
                } else {
                    addButton.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .setDuration(duration.toLong())
                            .withEndAction { addButton.visibility = View.INVISIBLE }
                }
            } else {
                selectionToolbarContainer.animate()
                        .translationY((-selectionToolbarContainer.height).toFloat())
                        .setDuration(duration.toLong())
                        .withEndAction { selectionToolbarContainer.visibility = View.INVISIBLE }

                addButton.visibility = View.VISIBLE
                addButton.animate()
                        .scaleY(1f)
                        .scaleX(1f)
                        .setDuration(duration.toLong())
                        .withEndAction(null)
            }
        }

        if (toolbarVisible) {
            val title = Integer.toString(selectedCount)
            if (selectedCount == 1) {
                // If they've selected a tree URI, show the DISPLAY_NAME instead of just '1'
                val selectedId = multiSelectionController.selection.iterator().next()
                coroutineScope.launch(Dispatchers.Main) {
                    val chosenPhoto = GalleryDatabase.getInstance(this@GallerySettingsActivity)
                            .chosenPhotoDao()
                            .getChosenPhoto(selectedId)
                    if (chosenPhoto?.isTreeUri == true && selectionToolbar.isAttachedToWindow) {
                        getDisplayNameForTreeUri(chosenPhoto.uri)?.takeUnless { it.isEmpty() }?.run {
                            selectionToolbar.title = this
                        }
                    }
                }
            }
            selectionToolbar.title = title
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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
        } catch (e: SecurityException) {
            // No longer can read this URI, which means no display name for this URI
        }
        return null
    }

    private fun onDataSetChanged() {
        val emptyView = findViewById<View>(android.R.id.empty)
        val emptyDescription = findViewById<TextView>(R.id.empty_description)
        val chosenPhotos = chosenPhotosLiveData.value
        if (chosenPhotos != null && !chosenPhotos.isEmpty()) {
            emptyView.visibility = View.GONE
            // We have at least one image, so consider the Gallery source properly setup
            setResult(RESULT_OK)
        } else {
            // No chosen images, show the empty View
            emptyView.visibility = View.VISIBLE
            val animator = findViewById<ViewAnimator>(R.id.empty_animator)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted, we can show the random camera photos image
                GalleryScanWorker.enqueueRescan()
                animator.displayedChild = 0
                emptyDescription.setText(R.string.gallery_empty)
                setResult(RESULT_OK)
            } else {
                // We have no images until they enable the permission
                setResult(RESULT_CANCELED)
                if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                                Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    // We should show rationale on why they should enable the storage permission and
                    // random camera photos
                    animator.displayedChild = 1
                    emptyDescription.setText(R.string.gallery_permission_rationale)
                } else {
                    // The user has permanently denied the storage permission. Give them a link to app settings
                    animator.displayedChild = 2
                    emptyDescription.setText(R.string.gallery_denied_explanation)
                }
            }
        }
    }

    internal class PhotoViewHolder(val rootView: View) : RecyclerView.ViewHolder(rootView) {
        val checkedOverlayView: FrameLayout = rootView.findViewById(R.id.checked_overlay)
        val thumbViews = listOf<ImageView>(
                rootView.findViewById(R.id.thumbnail1),
                rootView.findViewById(R.id.thumbnail2),
                rootView.findViewById(R.id.thumbnail3),
                rootView.findViewById(R.id.thumbnail4))
        val folderIcon: ImageView = rootView.findViewById(R.id.folder_icon)
    }

    private inner class GalleryAdapter internal constructor() : PagedListAdapter<ChosenPhoto, PhotoViewHolder>(CHOSEN_PHOTO_DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val v = LayoutInflater.from(this@GallerySettingsActivity)
                    .inflate(R.layout.gallery_chosen_photo_item, parent, false)
            val vh = PhotoViewHolder(v)

            v.layoutParams.height = itemSize
            v.setOnTouchListener { _, motionEvent ->
                if (motionEvent.actionMasked != MotionEvent.ACTION_CANCEL) {
                    lastTouchPosition = vh.adapterPosition
                    lastTouchX = motionEvent.x.toInt()
                    lastTouchY = motionEvent.y.toInt()
                }
                false
            }
            v.setOnClickListener {
                updatePosition = vh.adapterPosition
                if (updatePosition != RecyclerView.NO_POSITION) {
                    multiSelectionController.toggle(getItemId(updatePosition), true)
                }
            }

            return vh
        }

        override fun onBindViewHolder(vh: PhotoViewHolder, position: Int) {
            val chosenPhoto = getItem(position) ?: return
            vh.folderIcon.visibility = if (chosenPhoto.isTreeUri) View.VISIBLE else View.GONE
            val maxImages = vh.thumbViews.size
            val images = if (chosenPhoto.isTreeUri)
                getImagesFromTreeUri(chosenPhoto.uri, maxImages)
            else
                listOf(chosenPhoto.contentUri)
            val numImages = images.size
            val targetSize = if (numImages <= 1) itemSize else itemSize / 2
            for (h in 0 until numImages) {
                val thumbView = vh.thumbViews[h]
                thumbView.visibility = View.VISIBLE
                Glide.with(this@GallerySettingsActivity)
                        .load(images[h])
                        .override(targetSize)
                        .placeholder(placeholderDrawable)
                        .into(thumbView)
            }
            for (h in numImages until maxImages) {
                val thumbView = vh.thumbViews[h]
                // Show either just the one image or all the images even if
                // they are just placeholders
                thumbView.visibility = if (numImages <= 1) View.GONE else View.VISIBLE
                thumbView.setImageDrawable(placeholderDrawable)
            }
            val checked = multiSelectionController.isSelected(chosenPhoto.id)
            vh.rootView.setTag(R.id.gallery_viewtag_position, position)
            if (lastTouchPosition == vh.adapterPosition && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Handler().post {
                    if (!vh.checkedOverlayView.isAttachedToWindow) {
                        // Can't animate detached Views
                        vh.checkedOverlayView.visibility = if (checked) View.VISIBLE else View.GONE
                        return@post
                    }
                    if (checked) {
                        vh.checkedOverlayView.visibility = View.VISIBLE
                    }

                    // find the smallest radius that'll cover the item
                    val coverRadius = maxDistanceToCorner(
                            lastTouchX, lastTouchY,
                            0, 0, vh.rootView.width, vh.rootView.height)

                    val revealAnim = ViewAnimationUtils.createCircularReveal(
                            vh.checkedOverlayView,
                            lastTouchX,
                            lastTouchY,
                            if (checked) 0f else coverRadius,
                            if (checked) coverRadius else 0f)
                            .setDuration(150)

                    if (!checked) {
                        revealAnim.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                vh.checkedOverlayView.visibility = View.GONE
                            }
                        })
                    }
                    revealAnim.start()
                }
            } else {
                vh.checkedOverlayView.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }

        private fun maxDistanceToCorner(x: Int, y: Int, left: Int, top: Int, right: Int, bottom: Int): Float {
            var maxDistance = 0f
            maxDistance = Math.max(maxDistance, Math.hypot((x - left).toDouble(), (y - top).toDouble()).toFloat())
            maxDistance = Math.max(maxDistance, Math.hypot((x - right).toDouble(), (y - top).toDouble()).toFloat())
            maxDistance = Math.max(maxDistance, Math.hypot((x - left).toDouble(), (y - bottom).toDouble()).toFloat())
            maxDistance = Math.max(maxDistance, Math.hypot((x - right).toDouble(), (y - bottom).toDouble()).toFloat())
            return maxDistance
        }

        override fun getItemId(position: Int): Long {
            val chosenPhoto = getItem(position)
            return chosenPhoto?.id ?: -1
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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
            } catch (e: SecurityException) {
                // No longer can read this URI, which means no children from this URI
            } catch (e: NullPointerException) {
            }
        }
        return images
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        if (requestCode != REQUEST_CHOOSE_PHOTOS && requestCode != REQUEST_CHOOSE_FOLDER) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!addToolbar.isAttachedToWindow) {
                // Can't animate detached Views
                addToolbar.visibility = View.INVISIBLE
                addButton.visibility = View.VISIBLE
            } else {
                hideAddToolbar(true)
            }
        }

        if (resultCode != RESULT_OK) {
            return
        }

        if (result == null) {
            return
        }

        if (requestCode == REQUEST_CHOOSE_FOLDER) {
            getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, false)
            }
        }

        // Add chosen items
        val uris = HashSet<Uri>()
        val data = result.data
        if (data != null) {
            uris.add(data)
        }
        // When selecting multiple images, "Photos" returns the first URI in getData and all URIs
        // in getClipData.
        val clipData = result.clipData
        if (clipData != null) {
            (0 until clipData.itemCount).mapNotNullTo(uris) { clipData.getItemAt(it).uri }
        }

        if (uris.isEmpty()) {
            // Nothing to do, so we can avoid posting the runnable at all
            return
        }
        // Update chosen URIs
        GlobalScope.launch {
            GalleryDatabase.getInstance(this@GallerySettingsActivity)
                    .chosenPhotoDao()
                    .insertAll(this@GallerySettingsActivity, uris)
        }
    }

    override fun onChanged(chosenPhotos: PagedList<ChosenPhoto>?) {
        chosenPhotosAdapter.submitList(chosenPhotos)
        onDataSetChanged()
    }
}
