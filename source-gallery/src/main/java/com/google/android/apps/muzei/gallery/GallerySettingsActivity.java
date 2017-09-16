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

package com.google.android.apps.muzei.gallery;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.ViewModelProviders;
import android.arch.paging.PagedList;
import android.arch.paging.PagedListAdapter;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.OnApplyWindowInsetsListener;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.WindowInsetsCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.recyclerview.extensions.DiffCallback;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;

import com.google.android.apps.muzei.util.MultiSelectionController;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_PUBLISH_NEXT_GALLERY_ITEM;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.EXTRA_FORCE_URI;

public class GallerySettingsActivity extends AppCompatActivity
        implements Observer<PagedList<ChosenPhoto>>,
        GalleryImportPhotosDialogFragment.OnRequestContentListener {
    private static final String SHARED_PREF_NAME = "GallerySettingsActivity";
    private static final String SHOW_INTERNAL_STORAGE_MESSAGE = "show_internal_storage_message";
    private static final int REQUEST_CHOOSE_PHOTOS = 1;
    private static final int REQUEST_CHOOSE_FOLDER = 2;
    private static final int REQUEST_STORAGE_PERMISSION = 3;
    private static final String STATE_SELECTION = "selection";
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
        }
    };

    private LiveData<PagedList<ChosenPhoto>> mChosenPhotosLiveData;

    private Toolbar mSelectionToolbar;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private RecyclerView mPhotoGridView;
    private int mItemSize = 10;

    private final MultiSelectionController mMultiSelectionController
            = new MultiSelectionController(STATE_SELECTION);

    private ColorDrawable mPlaceholderDrawable;

    private static final SparseIntArray sRotateMenuIdsByMin = new SparseIntArray();
    private static final SparseIntArray sRotateMinsByMenuId = new SparseIntArray();

    static {
        sRotateMenuIdsByMin.put(0, R.id.action_rotate_interval_none);
        sRotateMenuIdsByMin.put(60, R.id.action_rotate_interval_1h);
        sRotateMenuIdsByMin.put(60 * 3, R.id.action_rotate_interval_3h);
        sRotateMenuIdsByMin.put(60 * 6, R.id.action_rotate_interval_6h);
        sRotateMenuIdsByMin.put(60 * 24, R.id.action_rotate_interval_24h);
        sRotateMenuIdsByMin.put(60 * 72, R.id.action_rotate_interval_72h);
        for (int i = 0; i < sRotateMenuIdsByMin.size(); i++) {
            sRotateMinsByMenuId.put(sRotateMenuIdsByMin.valueAt(i), sRotateMenuIdsByMin.keyAt(i));
        }
    }

    private LiveData<List<ActivityInfo>> mGetContentActivitiesLiveData;

    private int mUpdatePosition = -1;
    private View mAddButton;
    private View mAddToolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gallery_activity);
        Toolbar appBar = findViewById(R.id.app_bar);
        setSupportActionBar(appBar);

        bindService(new Intent(this, GalleryArtSource.class).setAction(GalleryArtSource.ACTION_BIND_GALLERY),
                mServiceConnection, BIND_AUTO_CREATE);

        mPlaceholderDrawable = new ColorDrawable(ContextCompat.getColor(this,
                R.color.gallery_chosen_photo_placeholder));

        mPhotoGridView = findViewById(R.id.photo_grid);
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        mPhotoGridView.setItemAnimator(itemAnimator);
        setupMultiSelect();

        final GridLayoutManager gridLayoutManager = new GridLayoutManager(
                GallerySettingsActivity.this, 1);
        mPhotoGridView.setLayoutManager(gridLayoutManager);

        final ViewTreeObserver vto = mPhotoGridView.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int width = mPhotoGridView.getWidth()
                        - mPhotoGridView.getPaddingStart() - mPhotoGridView.getPaddingEnd();
                if (width <= 0) {
                    return;
                }

                // Compute number of columns
                int maxItemWidth = getResources().getDimensionPixelSize(
                        R.dimen.gallery_chosen_photo_grid_max_item_size);
                int numColumns = 1;
                while (true) {
                    if (width / numColumns > maxItemWidth) {
                        ++numColumns;
                    } else {
                        break;
                    }
                }

                int spacing = getResources().getDimensionPixelSize(
                        R.dimen.gallery_chosen_photo_grid_spacing);
                mItemSize = (width - spacing * (numColumns - 1)) / numColumns;

                // Complete setup
                gridLayoutManager.setSpanCount(numColumns);
                mChosenPhotosAdapter.setHasStableIds(true);
                mPhotoGridView.setAdapter(mChosenPhotosAdapter);

                mPhotoGridView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                tryUpdateSelection(false);
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(mPhotoGridView, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(final View v, final WindowInsetsCompat insets) {
                int gridSpacing = getResources()
                        .getDimensionPixelSize(R.dimen.gallery_chosen_photo_grid_spacing);
                ViewCompat.onApplyWindowInsets(v, insets.replaceSystemWindowInsets(
                        insets.getSystemWindowInsetLeft() + gridSpacing,
                        gridSpacing,
                        insets.getSystemWindowInsetRight() + gridSpacing,
                        insets.getSystemWindowInsetBottom() + insets.getSystemWindowInsetTop() + gridSpacing +
                                getResources().getDimensionPixelSize(R.dimen.gallery_fab_space)));

                return insets;
            }
        });

        Button enableRandomImages = findViewById(R.id.gallery_enable_random);
        enableRandomImages.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                ActivityCompat.requestPermissions(GallerySettingsActivity.this, new String[] {
                        Manifest.permission.READ_EXTERNAL_STORAGE }, REQUEST_STORAGE_PERMISSION);
            }
        });
        Button permissionSettings = findViewById(R.id.gallery_edit_permission_settings);
        permissionSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        });
        mAddButton = findViewById(R.id.add_fab);
        mAddButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // On Lollipop and higher, we show the add toolbar to allow users to add either
                    // individual photos or a whole directory
                    showAddToolbar();
                } else {
                    requestPhotos();
                }
            }
        });
        mAddToolbar = findViewById(R.id.add_toolbar);
        findViewById(R.id.add_photos).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                requestPhotos();
            }
        });
        findViewById(R.id.add_folder).setOnClickListener(new View.OnClickListener() {
            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onClick(final View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try {
                    startActivityForResult(intent, REQUEST_CHOOSE_FOLDER);
                    SharedPreferences preferences = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE);
                    if (preferences.getBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, true)) {
                        Toast.makeText(GallerySettingsActivity.this, R.string.gallery_internal_storage_message,
                                Toast.LENGTH_LONG).show();
                    }
                } catch (ActivityNotFoundException e) {
                    Snackbar.make(mPhotoGridView, R.string.gallery_add_folder_error,
                            Snackbar.LENGTH_LONG).show();
                    hideAddToolbar(true);
                }
            }
        });
        GallerySettingsViewModel viewModel = ViewModelProviders.of(this)
                .get(GallerySettingsViewModel.class);
        mChosenPhotosLiveData = viewModel.getChosenPhotos();
        mChosenPhotosLiveData.observe(this, this);
        mGetContentActivitiesLiveData = viewModel.getGetContentActivityInfoList();
        mGetContentActivitiesLiveData.observe(this, new Observer<List<ActivityInfo>>() {
            @Override
            public void onChanged(@Nullable List<ActivityInfo> activityInfos) {
                supportInvalidateOptionsMenu();
            }
        });
    }

    private void requestPhotos() {
        // Use ACTION_OPEN_DOCUMENT by default for adding photos.
        // This allows us to use persistent URI permissions to access the underlying photos
        // meaning we don't need to use additional storage space and will pull in edits automatically
        // in addition to syncing deletions.
        // (There's a separate 'Import photos' option which uses ACTION_GET_CONTENT to support legacy apps)
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS);
        } catch (ActivityNotFoundException e) {
            Snackbar.make(mPhotoGridView, R.string.gallery_add_photos_error,
                    Snackbar.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                hideAddToolbar(true);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
            @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) {
            return;
        }
        onDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permissions might have changed in the background
        onDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
        }
        unbindService(mServiceConnection);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.gallery_activity, menu);

        int rotateIntervalMin = GalleryArtSource.getSharedPreferences(this)
                .getInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN,
                        GalleryArtSource.DEFAULT_ROTATE_INTERVAL_MIN);
        int menuId = sRotateMenuIdsByMin.get(rotateIntervalMin);
        if (menuId != 0) {
            MenuItem item = menu.findItem(menuId);
            if (item != null) {
                item.setChecked(true);
            }
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        super.onPrepareOptionsMenu(menu);
        // Make sure the 'Import photos' MenuItem is set up properly based on the number of
        // activities that handle ACTION_GET_CONTENT
        // 0 = hide the MenuItem
        // 1 = show 'Import photos from APP_NAME' to go to the one app that exists
        // 2 = show 'Import photos...' to have the user pick which app to import photos from
        List<ActivityInfo> getContentActivites = mGetContentActivitiesLiveData.getValue();
        if (getContentActivites == null) {
            // We'll get another chance when the list is populated
            return false;
        }
        // Hide the 'Import photos' action if there are no activities found
        MenuItem importPhotosMenuItem = menu.findItem(R.id.action_import_photos);
        importPhotosMenuItem.setVisible(!getContentActivites.isEmpty());
        // If there's only one app that supports ACTION_GET_CONTENT, tell the user what that app is
        if (getContentActivites.size() == 1) {
            importPhotosMenuItem.setTitle(getString(R.string.gallery_action_import_photos_from,
                    getContentActivites.get(0).loadLabel(getPackageManager())));
        } else {
            importPhotosMenuItem.setTitle(R.string.gallery_action_import_photos);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int itemId = item.getItemId();
        int rotateMin = sRotateMinsByMenuId.get(itemId, -1);
        if (rotateMin != -1) {
            GalleryArtSource.getSharedPreferences(GallerySettingsActivity.this).edit()
                    .putInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN, rotateMin)
                    .apply();
            item.setChecked(true);
            return true;
        }

        if (itemId == R.id.action_import_photos) {
            List<ActivityInfo> getContentActivities = mGetContentActivitiesLiveData.getValue();
            if (getContentActivities == null || getContentActivities.isEmpty()) {
                // Ignore
                return true;
            } else if (getContentActivities.size() == 1) {
                // Just start the one ACTION_GET_CONTENT app
                requestGetContent(getContentActivities.get(0));
            } else {
                // Let the user pick which app they want to import photos from
                GalleryImportPhotosDialogFragment.createInstance()
                        .show(getSupportFragmentManager(), GalleryImportPhotosDialogFragment.TAG);
            }
            return true;
        } else if (itemId == R.id.action_clear_photos) {
            runOnHandlerThread(new Runnable() {
                @Override
                public void run() {
                    GalleryDatabase.getInstance(GallerySettingsActivity.this)
                            .chosenPhotoDao().deleteAll(GallerySettingsActivity.this);
                }
            });
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void requestGetContent(ActivityInfo info) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setClassName(info.packageName, info.name);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS);
    }

    private void runOnHandlerThread(Runnable runnable) {
        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread("GallerySettingsActivity");
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
        }
        mHandler.post(runnable);
    }

    private int mLastTouchPosition;
    private int mLastTouchX, mLastTouchY;

    private void setupMultiSelect() {
        // Set up toolbar
        mSelectionToolbar = findViewById(R.id.selection_toolbar);

        mSelectionToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mMultiSelectionController.reset(true);
            }
        });

        mSelectionToolbar.inflateMenu(R.menu.gallery_selection);
        mSelectionToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.action_force_now) {
                    Set<Long> selection = mMultiSelectionController.getSelection();
                    if (selection.size() > 0) {
                        Uri selectedUri = ChosenPhoto.getContentUri(selection.iterator().next());
                        startService(
                                new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                                        .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                                        .putExtra(EXTRA_FORCE_URI, selectedUri));
                        Toast.makeText(GallerySettingsActivity.this,
                                R.string.gallery_temporary_force_image,
                                Toast.LENGTH_SHORT).show();
                    }
                    mMultiSelectionController.reset(true);
                    return true;
                } else if (itemId == R.id.action_remove) {
                    final ArrayList<Long> removePhotos = new ArrayList<>(
                            mMultiSelectionController.getSelection());

                    runOnHandlerThread(new Runnable() {
                        @Override
                        public void run() {
                            // Remove chosen URIs
                            GalleryDatabase.getInstance(GallerySettingsActivity.this).chosenPhotoDao()
                                    .delete(GallerySettingsActivity.this, removePhotos);
                        }
                    });

                    mMultiSelectionController.reset(true);
                    return true;
                }
                return false;
            }
        });

        // Set up controller
        mMultiSelectionController.setCallbacks(new MultiSelectionController.Callbacks() {
            @Override
            public void onSelectionChanged(boolean restored, boolean fromUser) {
                tryUpdateSelection(!restored);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (mMultiSelectionController.getSelectedCount() > 0) {
            mMultiSelectionController.reset(true);
        } else if (mAddToolbar.getVisibility() == View.VISIBLE) {
            hideAddToolbar(true);
        } else {
            super.onBackPressed();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void showAddToolbar() {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        final int duration = getResources().getInteger(android.R.integer.config_shortAnimTime) / 2;
        // Hide the add button
        mAddButton.animate()
                .scaleX(0f)
                .scaleY(0f)
                .translationY(getResources().getDimension(R.dimen.gallery_fab_margin))
                .setDuration(duration)
                .withEndAction(new Runnable() {
                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public void run() {
                        mAddButton.setVisibility(View.INVISIBLE);
                        // Then show the toolbar
                        mAddToolbar.setVisibility(View.VISIBLE);
                        ViewAnimationUtils.createCircularReveal(
                                mAddToolbar,
                                mAddToolbar.getWidth() / 2,
                                mAddToolbar.getHeight() / 2,
                                0,
                                mAddToolbar.getWidth() / 2)
                                .setDuration(duration)
                                .start();
                    }
                });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void hideAddToolbar(final boolean showAddButton) {
        // Divide by two since we're doing two animations but we want the total time to the short animation time
        final int duration = getResources().getInteger(android.R.integer.config_shortAnimTime) / 2;
        // Hide the toolbar
        Animator hideAnimator = ViewAnimationUtils.createCircularReveal(
                mAddToolbar,
                mAddToolbar.getWidth() / 2,
                mAddToolbar.getHeight() / 2,
                mAddToolbar.getWidth() / 2,
                0).setDuration(showAddButton ? duration : duration * 2);
        hideAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animation) {
                mAddToolbar.setVisibility(View.INVISIBLE);
                if (showAddButton) {
                    mAddButton.setVisibility(View.VISIBLE);
                    mAddButton.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0)
                            .setDuration(duration);
                } else {
                    // Just reset the translationY
                    mAddButton.setTranslationY(0);
                }
            }
        });
        hideAnimator.start();
    }

    private void tryUpdateSelection(boolean allowAnimate) {
        final View selectionToolbarContainer = findViewById(R.id.selection_toolbar_container);

        if (mUpdatePosition >= 0) {
            mChosenPhotosAdapter.notifyItemChanged(mUpdatePosition);
            mUpdatePosition = -1;
        } else {
            mChosenPhotosAdapter.notifyDataSetChanged();
        }

        int selectedCount = mMultiSelectionController.getSelectedCount();
        final boolean toolbarVisible = selectedCount > 0;
        if (selectedCount == 1) {
            // Double check to make sure we can force a URI for the selected URI
            Long selectedId = mMultiSelectionController.getSelection().iterator().next();
            final LiveData<ChosenPhoto> liveData = GalleryDatabase.getInstance(this)
                    .chosenPhotoDao().getChosenPhoto(selectedId);
            liveData.observeForever(new Observer<ChosenPhoto>() {
                @Override
                public void onChanged(@Nullable ChosenPhoto chosenPhoto) {
                    liveData.removeObserver(this);
                    boolean showForceNow = true;
                    if (chosenPhoto != null && chosenPhoto.isTreeUri) {
                        // Only show the force now icon if it isn't a tree URI or there is at least one image in the tree
                        showForceNow = !getImagesFromTreeUri(chosenPhoto.uri, 1).isEmpty();
                    }
                    if (mSelectionToolbar.isAttachedToWindow()) {
                        mSelectionToolbar.getMenu().findItem(R.id.action_force_now).setVisible(
                                showForceNow);
                    }
                }
            });
        }
        // Hide the force now button until the callback above sets it
        mSelectionToolbar.getMenu().findItem(R.id.action_force_now).setVisible(
                false);

        Boolean previouslyVisible = (Boolean) selectionToolbarContainer.getTag(0xDEADBEEF);
        if (previouslyVisible == null) {
            previouslyVisible = Boolean.FALSE;
        }

        if (previouslyVisible != toolbarVisible) {
            selectionToolbarContainer.setTag(0xDEADBEEF, toolbarVisible);

            int duration = allowAnimate
                    ? getResources().getInteger(android.R.integer.config_shortAnimTime)
                    : 0;
            if (toolbarVisible) {
                selectionToolbarContainer.setVisibility(View.VISIBLE);
                selectionToolbarContainer.setTranslationY(
                        -selectionToolbarContainer.getHeight());
                selectionToolbarContainer.animate()
                        .translationY(0f)
                        .setDuration(duration)
                        .withEndAction(null);

                if (mAddToolbar.getVisibility() == View.VISIBLE) {
                    hideAddToolbar(false);
                } else {
                    mAddButton.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .setDuration(duration)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    mAddButton.setVisibility(View.INVISIBLE);
                                }
                            });
                }
            } else {
                selectionToolbarContainer.animate()
                        .translationY(-selectionToolbarContainer.getHeight())
                        .setDuration(duration)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                selectionToolbarContainer.setVisibility(View.INVISIBLE);
                            }
                        });

                mAddButton.setVisibility(View.VISIBLE);
                mAddButton.animate()
                        .scaleY(1f)
                        .scaleX(1f)
                        .setDuration(duration)
                        .withEndAction(null);
            }
        }

        if (toolbarVisible) {
            String title = Integer.toString(selectedCount);
            if (selectedCount == 1) {
                // If they've selected a tree URI, show the DISPLAY_NAME instead of just '1'
                Long selectedId = mMultiSelectionController.getSelection().iterator().next();
                final LiveData<ChosenPhoto> liveData = GalleryDatabase.getInstance(this)
                        .chosenPhotoDao().getChosenPhoto(selectedId);
                liveData.observeForever(new Observer<ChosenPhoto>() {
                    @Override
                    public void onChanged(@Nullable ChosenPhoto chosenPhoto) {
                        liveData.removeObserver(this);
                        if (chosenPhoto != null && chosenPhoto.isTreeUri) {
                            String displayName = getDisplayNameForTreeUri(chosenPhoto.uri);
                            if (!TextUtils.isEmpty(displayName) && mSelectionToolbar.isAttachedToWindow()) {
                                mSelectionToolbar.setTitle(displayName);
                            }
                        }
                    }
                });
            }
            mSelectionToolbar.setTitle(title);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private String getDisplayNameForTreeUri(Uri treeUri) {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
        Cursor data = getContentResolver().query(documentUri,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null);
        String displayName = null;
        if (data != null && data.moveToNext()) {
            displayName = data.getString(data.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
        }
        if (data != null) {
            data.close();
        }
        return displayName;
    }

    private void onDataSetChanged() {
        View emptyView = findViewById(android.R.id.empty);
        TextView emptyDescription = findViewById(R.id.empty_description);
        List<ChosenPhoto> chosenPhotos = mChosenPhotosLiveData.getValue();
        if (chosenPhotos != null && !chosenPhotos.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            // We have at least one image, so consider the Gallery source properly setup
            setResult(RESULT_OK);
        } else {
            // No chosen images, show the empty View
            emptyView.setVisibility(View.VISIBLE);
            ViewAnimator animator = findViewById(R.id.empty_animator);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted, we can show the random camera photos image
                animator.setDisplayedChild(0);
                emptyDescription.setText(R.string.gallery_empty);
                setResult(RESULT_OK);
            } else {
                // We have no images until they enable the permission
                setResult(RESULT_CANCELED);
                if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    // We should show rationale on why they should enable the storage permission and
                    // random camera photos
                    animator.setDisplayedChild(1);
                    emptyDescription.setText(R.string.gallery_permission_rationale);
                } else {
                    // The user has permanently denied the storage permission. Give them a link to app settings
                    animator.setDisplayedChild(2);
                    emptyDescription.setText(R.string.gallery_denied_explanation);
                }
            }
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mMultiSelectionController.restoreInstanceState(savedInstanceState);
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final View mRootView;
        final View mCheckedOverlayView;
        final List<ImageView> mThumbViews = new ArrayList<>();
        final View mFolderIcon;

        PhotoViewHolder(View root) {
            super(root);
            mRootView = root;
            mCheckedOverlayView = root.findViewById(R.id.checked_overlay);
            mThumbViews.add((ImageView) root.findViewById(R.id.thumbnail1));
            mThumbViews.add((ImageView) root.findViewById(R.id.thumbnail2));
            mThumbViews.add((ImageView) root.findViewById(R.id.thumbnail3));
            mThumbViews.add((ImageView) root.findViewById(R.id.thumbnail4));
            mFolderIcon = root.findViewById(R.id.folder_icon);
        }
    }

    private final GalleryAdapter mChosenPhotosAdapter = new GalleryAdapter();

    private class GalleryAdapter extends PagedListAdapter<ChosenPhoto, PhotoViewHolder>
    {
        GalleryAdapter() {
            super(CHOSEN_PHOTO_DIFF_CALLBACK);
        }

        @Override
        public PhotoViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(GallerySettingsActivity.this)
                    .inflate(R.layout.gallery_chosen_photo_item, parent, false);
            final PhotoViewHolder vh = new PhotoViewHolder(v);

            v.getLayoutParams().height = mItemSize;
            v.setOnTouchListener(new View.OnTouchListener() {
                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (motionEvent.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                        mLastTouchPosition = vh.getAdapterPosition();
                        mLastTouchX = (int) motionEvent.getX();
                        mLastTouchY = (int) motionEvent.getY();
                    }
                    return false;
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mUpdatePosition = vh.getAdapterPosition();
                    if (mUpdatePosition != RecyclerView.NO_POSITION) {
                        mMultiSelectionController.toggle(getItemId(mUpdatePosition), true);
                    }
                }
            });

            return vh;
        }

        @Override
        public void onBindViewHolder(final PhotoViewHolder vh, int position) {
            ChosenPhoto chosenPhoto = getItem(position);
            vh.mFolderIcon.setVisibility(chosenPhoto != null && chosenPhoto.isTreeUri ? View.VISIBLE : View.GONE);
            int maxImages = vh.mThumbViews.size();
            List<Uri> images = chosenPhoto == null ? new ArrayList<Uri>() : chosenPhoto.isTreeUri
                    ? getImagesFromTreeUri(chosenPhoto.uri, maxImages)
                    : Collections.singletonList(chosenPhoto.getContentUri());
            int numImages = images.size();
            int targetSize = numImages <= 1 ? mItemSize : mItemSize / 2;
            for (int h=0; h<numImages; h++) {
                ImageView thumbView = vh.mThumbViews.get(h);
                thumbView.setVisibility(View.VISIBLE);
                Picasso.with(GallerySettingsActivity.this)
                        .load(images.get(h))
                        .resize(targetSize, targetSize)
                        .centerCrop()
                        .placeholder(mPlaceholderDrawable)
                        .into(thumbView);
            }
            for (int h=numImages; h<maxImages; h++) {
                ImageView thumbView = vh.mThumbViews.get(h);
                // Show either just the one image or all the images even if
                // they are just placeholders
                thumbView.setVisibility(numImages <= 1 ? View.GONE : View.VISIBLE);
                thumbView.setImageDrawable(mPlaceholderDrawable);
            }
            final boolean checked = chosenPhoto != null && mMultiSelectionController.isSelected(chosenPhoto.id);
            vh.mRootView.setTag(R.id.gallery_viewtag_position, position);
            if (mLastTouchPosition == vh.getAdapterPosition()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                new Handler().post(new Runnable() {
                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public void run() {
                        if (!vh.mCheckedOverlayView.isAttachedToWindow()) {
                            // Can't animate detached Views
                            vh.mCheckedOverlayView.setVisibility(
                                    checked ? View.VISIBLE : View.GONE);
                            return;
                        }
                        if (checked) {
                            vh.mCheckedOverlayView.setVisibility(View.VISIBLE);
                        }

                        // find the smallest radius that'll cover the item
                        float coverRadius = maxDistanceToCorner(
                                mLastTouchX, mLastTouchY,
                                0, 0, vh.mRootView.getWidth(), vh.mRootView.getHeight());

                        Animator revealAnim = ViewAnimationUtils.createCircularReveal(
                                vh.mCheckedOverlayView,
                                mLastTouchX,
                                mLastTouchY,
                                checked ? 0 : coverRadius,
                                checked ? coverRadius : 0)
                                .setDuration(150);

                        if (!checked) {
                            revealAnim.addListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    vh.mCheckedOverlayView.setVisibility(View.GONE);
                                }
                            });
                        }
                        revealAnim.start();
                    }
                });
            } else {
                vh.mCheckedOverlayView.setVisibility(
                        checked ? View.VISIBLE : View.GONE);
            }
        }

        private float maxDistanceToCorner(int x, int y, int left, int top, int right, int bottom) {
            float maxDistance = 0;
            maxDistance = Math.max(maxDistance, (float) Math.hypot(x - left, y - top));
            maxDistance = Math.max(maxDistance, (float) Math.hypot(x - right, y - top));
            maxDistance = Math.max(maxDistance, (float) Math.hypot(x - left, y - bottom));
            maxDistance = Math.max(maxDistance, (float) Math.hypot(x - right, y - bottom));
            return maxDistance;
        }

        @Override
        public long getItemId(int position) {
            ChosenPhoto chosenPhoto = getItem(position);
            return chosenPhoto != null ? chosenPhoto.id : -1;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private List<Uri> getImagesFromTreeUri(final Uri treeUri, final int maxImages) {
        List<Uri> images = new ArrayList<>();
        Queue<String> directories = new LinkedList<>();
        directories.add(DocumentsContract.getTreeDocumentId(treeUri));
        while (images.size() < maxImages && !directories.isEmpty()) {
            String parentDocumentId = directories.poll();
            final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    parentDocumentId);
            Cursor children;
            try {
                children = getContentResolver().query(childrenUri,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                        null, null, null);
            } catch (SecurityException e) {
                // No longer can read this URI, which means no images from this URI
                // This a temporary state as the next onLoadFinished() will remove this item entirely
                children = null;
            }
            if (children == null) {
                continue;
            }
            while (children.moveToNext()) {
                String documentId = children.getString(
                        children.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                String mimeType = children.getString(
                        children.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    directories.add(documentId);
                } else if (mimeType != null && mimeType.startsWith("image/")) {
                    // Add images to the list
                    images.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
                }
                if (images.size() == maxImages) {
                    break;
                }
            }
            children.close();
        }
        return images;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != REQUEST_CHOOSE_PHOTOS && requestCode != REQUEST_CHOOSE_FOLDER) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!mAddToolbar.isAttachedToWindow()) {
                // Can't animate detached Views
                mAddToolbar.setVisibility(View.INVISIBLE);
                mAddButton.setVisibility(View.VISIBLE);
            } else {
                hideAddToolbar(true);
            }
        }

        if (resultCode != RESULT_OK) {
            return;
        }

        if (result == null) {
            return;
        }

        if (requestCode == REQUEST_CHOOSE_FOLDER) {
            SharedPreferences preferences = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE);
            preferences.edit().putBoolean(SHOW_INTERNAL_STORAGE_MESSAGE, false).apply();
        }

        // Add chosen items
        final Set<Uri> uris = new HashSet<>();
        if (result.getData() != null) {
            uris.add(result.getData());
        }
        // When selecting multiple images, "Photos" returns the first URI in getData and all URIs
        // in getClipData.
        ClipData clipData = result.getClipData();
        if (clipData != null) {
            int count = clipData.getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }

        if (uris.isEmpty()) {
            // Nothing to do, so we can avoid posting the runnable at all
            return;
        }
        // Update chosen URIs
        runOnHandlerThread(new Runnable() {
            @Override
            public void run() {
                GalleryDatabase.getInstance(GallerySettingsActivity.this).chosenPhotoDao()
                        .insertAll(GallerySettingsActivity.this, uris);
            }
        });
    }

    static final DiffCallback<ChosenPhoto> CHOSEN_PHOTO_DIFF_CALLBACK = new DiffCallback<ChosenPhoto>() {
        @Override
        public boolean areItemsTheSame(@NonNull final ChosenPhoto oldItem, @NonNull final ChosenPhoto newItem) {
            Uri oldImageUri = oldItem.uri;
            Uri newImageUri = newItem.uri;
            return oldImageUri.equals(newImageUri);
        }

        @Override
        public boolean areContentsTheSame(@NonNull final ChosenPhoto oldItem, @NonNull final ChosenPhoto newItem) {
            // If the items are the same (same image URI), then they are equivalent and
            // no change animation is needed
            return true;
        }
    };

    @Override
    public void onChanged(@Nullable final PagedList<ChosenPhoto> chosenPhotos) {
        mChosenPhotosAdapter.setList(chosenPhotos);
        onDataSetChanged();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mMultiSelectionController.saveInstanceState(outState);
    }
}
