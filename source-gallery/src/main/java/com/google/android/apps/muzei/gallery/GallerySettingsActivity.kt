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
import android.annotation.TargetApi
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.arch.paging.PagedList
import android.arch.paging.PagedListAdapter
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.Settings
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.recyclerview.extensions.DiffCallback
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.util.SparseIntArray
import android.view.*
import android.widget.*
import androidx.content.edit
import androidx.database.getString
import com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_PUBLISH_NEXT_GALLERY_ITEM
import com.google.android.apps.muzei.gallery.GalleryArtSource.EXTRA_FORCE_URI
import com.google.android.apps.muzei.util.MultiSelectionController
import com.squareup.picasso.Picasso
import java.util.*

class GallerySettingsActivity : AppCompatActivity(), Observer<PagedList<ChosenPhoto>>,
        GalleryImportPhotosDialogFragment.OnRequestContentListener, MultiSelectionController.Callbacks {

    companion object {
        private const val SHARED_PREF_NAME = "GallerySettingsActivity"
        private const val SHOW_INTERNAL_STORAGE_MESSAGE = "show_internal_storage_message"
        private const val REQUEST_CHOOSE_PHOTOS = 1
        private const val REQUEST_CHOOSE_FOLDER = 2
        private const val REQUEST_STORAGE_PERMISSION = 3
        private const val STATE_SELECTION = "selection"

        private val sRotateMenuIdsByMin = SparseIntArray()
        private val sRotateMinsByMenuId = SparseIntArray()

        init {
            sRotateMenuIdsByMin.apply {
                put(0, R.id.action_rotate_interval_none)
                put(60, R.id.action_rotate_interval_1h)
                put(60 * 3, R.id.action_rotate_interval_3h)
                put(60 * 6, R.id.action_rotate_interval_6h)
                put(60 * 24, R.id.action_rotate_interval_24h)
                put(60 * 72, R.id.action_rotate_interval_72h)
            }
            for (i in 0 until sRotateMenuIdsByMin.size()) {
                sRotateMinsByMenuId.put(sRotateMenuIdsByMin.valueAt(i), sRotateMenuIdsByMin.keyAt(i))
            }
        }

        internal val CHOSEN_PHOTO_DIFF_CALLBACK: DiffCallback<ChosenPhoto> = object : DiffCallback<ChosenPhoto>() {
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

    private val mServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {}

        override fun onServiceDisconnected(name: ComponentName) {}
    }

    private val mViewModel : GallerySettingsViewModel by lazy {
        ViewModelProviders.of(this).get(GallerySettingsViewModel::class.java)
    }

    private val mChosenPhotosLiveData: LiveData<PagedList<ChosenPhoto>> by lazy {
        mViewModel.chosenPhotos
    }

    private val mSelectionToolbar : Toolbar by lazy {
        findViewById<Toolbar>(R.id.selection_toolbar)
    }

    private val lazyHandlerThread = lazy {
        HandlerThread("GallerySettingsActivity").apply {
            start()
        }
    }
    private val mHandler by lazy {
        Handler(lazyHandlerThread.value.looper)
    }
    private val mPhotoGridView : RecyclerView by lazy {
        findViewById<RecyclerView>(R.id.photo_grid).apply {
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
            }
        }
    }
    private var mItemSize = 10

    private val mMultiSelectionController = MultiSelectionController(STATE_SELECTION)

    private val mPlaceholderDrawable: ColorDrawable by lazy {
        ColorDrawable(ContextCompat.getColor(this,
                R.color.gallery_chosen_photo_placeholder))
    }

    private val mGetContentActivitiesLiveData: LiveData<List<ActivityInfo>> by lazy {
        mViewModel.getContentActivityInfoList
    }

    private var mUpdatePosition = -1
    private lateinit var mAddButton: View
    private val mAddToolbar : LinearLayout by lazy {
        findViewById<LinearLayout>(R.id.add_toolbar)
    }

    private var mLastTouchPosition: Int = 0
    private var mLastTouchX: Int = 0
    private var mLastTouchY: Int = 0

    private val mChosenPhotosAdapter = GalleryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gallery_activity)
        setSupportActionBar(findViewById(R.id.app_bar))

        bindService(Intent(this, GalleryArtSource::class.java)
                .setAction(GalleryArtSource.ACTION_BIND_GALLERY),
                mServiceConnection, Context.BIND_AUTO_CREATE)

        setupMultiSelect()

        val gridLayoutManager = GridLayoutManager(this, 1)
        mPhotoGridView.layoutManager = gridLayoutManager

        val vto = mPhotoGridView.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val width = (mPhotoGridView.width
                        - mPhotoGridView.paddingStart - mPhotoGridView.paddingEnd)
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
                mItemSize = (width - spacing * (numColumns - 1)) / numColumns

                // Complete setup
                gridLayoutManager.spanCount = numColumns
                mChosenPhotosAdapter.setHasStableIds(true)
                mPhotoGridView.adapter = mChosenPhotosAdapter

                mPhotoGridView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                tryUpdateSelection(false)
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(mPhotoGridView) { v, insets ->
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
        enableRandomImages.setOnClickListener { view -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION) }
        val permissionSettings = findViewById<Button>(R.id.gallery_edit_permission_settings)
        permissionSettings.setOnClickListener { view ->
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        mAddButton = findViewById<View>(R.id.add_fab).apply {
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
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            try {
                startActivityForResult(intent, REQUEST_CHOOSE_FOLDER)
                val preferences = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
                if (preferences.getBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, true)) {
                    Toast.makeText(this, R.string.gallery_internal_storage_message,
                            Toast.LENGTH_LONG).show()
                }
            } catch (e: ActivityNotFoundException) {
                Snackbar.make(mPhotoGridView, R.string.gallery_add_folder_error,
                        Snackbar.LENGTH_LONG).show()
                hideAddToolbar(true)
            }
        }
        mChosenPhotosLiveData.observe(this, this)
        mGetContentActivitiesLiveData.observe(this, Observer { supportInvalidateOptionsMenu() })
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
        }
        try {
            startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(mPhotoGridView, R.string.gallery_add_photos_error,
                    Snackbar.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                hideAddToolbar(true)
            }
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
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

    override fun onDestroy() {
        super.onDestroy()
        if (lazyHandlerThread.isInitialized()) {
            lazyHandlerThread.value.quitSafely()
        }
        unbindService(mServiceConnection)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.gallery_activity, menu)

        val rotateIntervalMin = GalleryArtSource.getSharedPreferences(this)
                .getInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN,
                        GalleryArtSource.DEFAULT_ROTATE_INTERVAL_MIN)
        val menuId = sRotateMenuIdsByMin.get(rotateIntervalMin)
        if (menuId != 0) {
            menu.findItem(menuId)?.run {
                isChecked = true
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        // Make sure the 'Import photos' MenuItem is set up properly based on the number of
        // activities that handle ACTION_GET_CONTENT
        // 0 = hide the MenuItem
        // 1 = show 'Import photos from APP_NAME' to go to the one app that exists
        // 2 = show 'Import photos...' to have the user pick which app to import photos from
        val getContentActivites = mGetContentActivitiesLiveData.value
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
        val itemId = item.itemId
        val rotateMin = sRotateMinsByMenuId.get(itemId, -1)
        if (rotateMin != -1) {
            GalleryArtSource.getSharedPreferences(this).edit()
                    .putInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN, rotateMin)
                    .apply()
            item.isChecked = true
            return true
        }

        when (itemId) {
            R.id.action_import_photos -> {
                mGetContentActivitiesLiveData.value?.run {
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
                mHandler.post {
                    GalleryDatabase.getInstance(this)
                            .chosenPhotoDao().deleteAll(this)
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
        mSelectionToolbar.setNavigationOnClickListener { mMultiSelectionController.reset(true) }

        mSelectionToolbar.inflateMenu(R.menu.gallery_selection)
        mSelectionToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_force_now -> {
                    val selection = mMultiSelectionController.selection
                    if (selection.size > 0) {
                        val selectedUri = ChosenPhoto.getContentUri(selection.iterator().next())
                        startService(
                                Intent(this, GalleryArtSource::class.java)
                                        .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                                        .putExtra(EXTRA_FORCE_URI, selectedUri))
                        Toast.makeText(this,
                                R.string.gallery_temporary_force_image,
                                Toast.LENGTH_SHORT).show()
                    }
                    mMultiSelectionController.reset(true)
                    return@setOnMenuItemClickListener true
                }
                R.id.action_remove -> {
                    val removePhotos = ArrayList(
                            mMultiSelectionController.selection)

                    mHandler.post {
                        // Remove chosen URIs
                        GalleryDatabase.getInstance(this).chosenPhotoDao()
                                .delete(this, removePhotos)
                    }

                    mMultiSelectionController.reset(true)
                    return@setOnMenuItemClickListener true
                }
                else -> return@setOnMenuItemClickListener false
            }
        }

        // Set up controller
        mMultiSelectionController.setCallbacks(this)
    }

    override fun onSelectionChanged(restored: Boolean, fromUser: Boolean) {
        tryUpdateSelection(!restored)
    }

    override fun onBackPressed() {
        when {
            mMultiSelectionController.selectedCount > 0 -> mMultiSelectionController.reset(true)
            mAddToolbar.visibility == View.VISIBLE -> hideAddToolbar(true)
            else -> super.onBackPressed()
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun showAddToolbar() {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime) / 2
        // Hide the add button
        mAddButton.animate()
                .scaleX(0f)
                .scaleY(0f)
                .translationY(resources.getDimension(R.dimen.gallery_fab_margin))
                .setDuration(duration.toLong())
                .withEndAction {
                    mAddButton.visibility = View.INVISIBLE
                    // Then show the toolbar
                    mAddToolbar.visibility = View.VISIBLE
                    ViewAnimationUtils.createCircularReveal(
                            mAddToolbar,
                            mAddToolbar.width / 2,
                            mAddToolbar.height / 2,
                            0f,
                            (mAddToolbar.width / 2).toFloat())
                            .setDuration(duration.toLong())
                            .start()
                }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun hideAddToolbar(showAddButton: Boolean) {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime) / 2
        // Hide the toolbar
        val hideAnimator = ViewAnimationUtils.createCircularReveal(
                mAddToolbar,
                mAddToolbar.width / 2,
                mAddToolbar.height / 2,
                (mAddToolbar.width / 2).toFloat(),
                0f).setDuration((if (showAddButton) duration else duration * 2).toLong())
        hideAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                mAddToolbar.visibility = View.INVISIBLE
                if (showAddButton) {
                    mAddButton.visibility = View.VISIBLE
                    mAddButton.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f).duration = duration.toLong()
                } else {
                    // Just reset the translationY
                    mAddButton.translationY = 0f
                }
            }
        })
        hideAnimator.start()
    }

    private fun tryUpdateSelection(allowAnimate: Boolean) {
        val selectionToolbarContainer = findViewById<View>(R.id.selection_toolbar_container)

        if (mUpdatePosition >= 0) {
            mChosenPhotosAdapter.notifyItemChanged(mUpdatePosition)
            mUpdatePosition = -1
        } else {
            mChosenPhotosAdapter.notifyDataSetChanged()
        }

        val selectedCount = mMultiSelectionController.selectedCount
        val toolbarVisible = selectedCount > 0
        if (selectedCount == 1) {
            // Double check to make sure we can force a URI for the selected URI
            val selectedId = mMultiSelectionController.selection.iterator().next()
            val liveData = GalleryDatabase.getInstance(this)
                    .chosenPhotoDao().getChosenPhoto(selectedId)
            liveData.observeForever(object : Observer<ChosenPhoto> {
                override fun onChanged(chosenPhoto: ChosenPhoto?) {
                    liveData.removeObserver(this)
                    val showForceNow = if (chosenPhoto?.isTreeUri == true) {
                        // Only show the force now icon if it isn't a tree URI or there is at least one image in the tree
                        !getImagesFromTreeUri(chosenPhoto.uri, 1).isEmpty()
                    } else true
                    if (mSelectionToolbar.isAttachedToWindow) {
                        mSelectionToolbar.menu.findItem(R.id.action_force_now).isVisible = showForceNow
                    }
                }
            })
        }
        // Hide the force now button until the callback above sets it
        mSelectionToolbar.menu.findItem(R.id.action_force_now).isVisible = false

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

                if (mAddToolbar.visibility == View.VISIBLE) {
                    hideAddToolbar(false)
                } else {
                    mAddButton.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .setDuration(duration.toLong())
                            .withEndAction { mAddButton.visibility = View.INVISIBLE }
                }
            } else {
                selectionToolbarContainer.animate()
                        .translationY((-selectionToolbarContainer.height).toFloat())
                        .setDuration(duration.toLong())
                        .withEndAction { selectionToolbarContainer.visibility = View.INVISIBLE }

                mAddButton.visibility = View.VISIBLE
                mAddButton.animate()
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
                val selectedId = mMultiSelectionController.selection.iterator().next()
                val liveData = GalleryDatabase.getInstance(this)
                        .chosenPhotoDao().getChosenPhoto(selectedId)
                liveData.observeForever(object : Observer<ChosenPhoto> {
                    override fun onChanged(chosenPhoto: ChosenPhoto?) {
                        liveData.removeObserver(this)
                        if (chosenPhoto?.isTreeUri == true && mSelectionToolbar.isAttachedToWindow) {
                            getDisplayNameForTreeUri(chosenPhoto.uri)?.takeUnless { it.isEmpty() }?.run {
                                mSelectionToolbar.title = this
                            }
                        }
                    }
                })
            }
            mSelectionToolbar.title = title
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getDisplayNameForTreeUri(treeUri: Uri): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri))
        try {
            contentResolver.query(documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null)?.use { data ->
                if (data.moveToNext()) {
                    return data.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
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
        val chosenPhotos = mChosenPhotosLiveData.value
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

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        mMultiSelectionController.restoreInstanceState(savedInstanceState)
    }

    internal class PhotoViewHolder(val mRootView: View) : RecyclerView.ViewHolder(mRootView) {
        val mCheckedOverlayView : FrameLayout = mRootView.findViewById(R.id.checked_overlay)
        val mThumbViews = listOf<ImageView>(
            mRootView.findViewById(R.id.thumbnail1),
            mRootView.findViewById(R.id.thumbnail2),
            mRootView.findViewById(R.id.thumbnail3),
            mRootView.findViewById(R.id.thumbnail4))
        val mFolderIcon : ImageView = mRootView.findViewById(R.id.folder_icon)
    }

    private inner class GalleryAdapter internal constructor() : PagedListAdapter<ChosenPhoto, PhotoViewHolder>(CHOSEN_PHOTO_DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val v = LayoutInflater.from(this@GallerySettingsActivity)
                    .inflate(R.layout.gallery_chosen_photo_item, parent, false)
            val vh = PhotoViewHolder(v)

            v.layoutParams.height = mItemSize
            v.setOnTouchListener { _, motionEvent ->
                if (motionEvent.actionMasked != MotionEvent.ACTION_CANCEL) {
                    mLastTouchPosition = vh.adapterPosition
                    mLastTouchX = motionEvent.x.toInt()
                    mLastTouchY = motionEvent.y.toInt()
                }
                false
            }
            v.setOnClickListener {
                mUpdatePosition = vh.adapterPosition
                if (mUpdatePosition != RecyclerView.NO_POSITION) {
                    mMultiSelectionController.toggle(getItemId(mUpdatePosition), true)
                }
            }

            return vh
        }

        override fun onBindViewHolder(vh: PhotoViewHolder, position: Int) {
            val chosenPhoto = getItem(position) ?: return
            vh.mFolderIcon.visibility = if (chosenPhoto.isTreeUri) View.VISIBLE else View.GONE
            val maxImages = vh.mThumbViews.size
            val images = if (chosenPhoto.isTreeUri)
                getImagesFromTreeUri(chosenPhoto.uri, maxImages)
            else
                listOf(chosenPhoto.contentUri)
            val numImages = images.size
            val targetSize = if (numImages <= 1) mItemSize else mItemSize / 2
            for (h in 0 until numImages) {
                val thumbView = vh.mThumbViews[h]
                thumbView.visibility = View.VISIBLE
                Picasso.with(this@GallerySettingsActivity)
                        .load(images[h])
                        .resize(targetSize, targetSize)
                        .centerCrop()
                        .placeholder(mPlaceholderDrawable)
                        .into(thumbView)
            }
            for (h in numImages until maxImages) {
                val thumbView = vh.mThumbViews[h]
                // Show either just the one image or all the images even if
                // they are just placeholders
                thumbView.visibility = if (numImages <= 1) View.GONE else View.VISIBLE
                thumbView.setImageDrawable(mPlaceholderDrawable)
            }
            val checked = mMultiSelectionController.isSelected(chosenPhoto.id)
            vh.mRootView.setTag(R.id.gallery_viewtag_position, position)
            if (mLastTouchPosition == vh.adapterPosition && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Handler().post {
                    if (!vh.mCheckedOverlayView.isAttachedToWindow) {
                        // Can't animate detached Views
                        vh.mCheckedOverlayView.visibility = if (checked) View.VISIBLE else View.GONE
                        return@post
                    }
                    if (checked) {
                        vh.mCheckedOverlayView.visibility = View.VISIBLE
                    }

                    // find the smallest radius that'll cover the item
                    val coverRadius = maxDistanceToCorner(
                            mLastTouchX, mLastTouchY,
                            0, 0, vh.mRootView.width, vh.mRootView.height)

                    val revealAnim = ViewAnimationUtils.createCircularReveal(
                            vh.mCheckedOverlayView,
                            mLastTouchX,
                            mLastTouchY,
                            if (checked) 0f else coverRadius,
                            if (checked) coverRadius else 0f)
                            .setDuration(150)

                    if (!checked) {
                        revealAnim.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                vh.mCheckedOverlayView.visibility = View.GONE
                            }
                        })
                    }
                    revealAnim.start()
                }
            } else {
                vh.mCheckedOverlayView.visibility = if (checked) View.VISIBLE else View.GONE
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

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
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
                                children.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                        val mimeType = children.getString(
                                children.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
                        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                            directories.add(documentId)
                        } else if (mimeType != null && mimeType.startsWith("image/")) {
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
            if (!mAddToolbar.isAttachedToWindow) {
                // Can't animate detached Views
                mAddToolbar.visibility = View.INVISIBLE
                mAddButton.visibility = View.VISIBLE
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
        if (result.data != null) {
            uris.add(result.data)
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
        mHandler.post {
            GalleryDatabase.getInstance(this).chosenPhotoDao()
                    .insertAll(this, uris)
        }
    }

    override fun onChanged(chosenPhotos: PagedList<ChosenPhoto>?) {
        mChosenPhotosAdapter.setList(chosenPhotos)
        onDataSetChanged()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        mMultiSelectionController.saveInstanceState(outState)
    }
}
