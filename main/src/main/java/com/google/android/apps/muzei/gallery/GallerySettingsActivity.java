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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.apps.muzei.event.GalleryChosenUrisChangedEvent;
import com.google.android.apps.muzei.util.CheatSheet;
import com.google.android.apps.muzei.util.DrawInsetsFrameLayout;
import com.google.android.apps.muzei.util.MathUtil;
import com.google.android.apps.muzei.util.MultiSelectionController;
import com.squareup.picasso.Picasso;

import net.nurik.roman.muzei.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.greenrobot.event.EventBus;

import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_ADD_CHOSEN_URIS;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_PUBLISH_NEXT_GALLERY_ITEM;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_REMOVE_CHOSEN_URIS;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.EXTRA_FORCE_URI;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.EXTRA_URIS;

public class GallerySettingsActivity extends ActionBarActivity {
    private static final int REQUEST_CHOOSE_PHOTOS = 1;
    private static final String STATE_SELECTION = "selection";

    private GalleryStore mStore;
    private List<Uri> mChosenUris;

    private Toolbar mSelectionToolbar;

    private Handler mHandler = new Handler();
    private RecyclerView mPhotoGridView;
    private int mItemSize = 10;

    private MultiSelectionController<Uri> mMultiSelectionController
            = new MultiSelectionController<>(STATE_SELECTION);

    private ColorDrawable mPlaceholderDrawable;

    private static SparseIntArray sRotateMenuIdsByMin = new SparseIntArray();
    private static SparseIntArray sRotateMinsByMenuId = new SparseIntArray();

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

    private boolean mLastSelectionUpdateFromUser;
    private int mUpdatePosition = -1;
    private View mAddButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gallery_settings_activity);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setupAppBar();

        mStore = GalleryStore.getInstance(this);
        mChosenUris = new ArrayList<>(mStore.getChosenUris());
        onDataSetChanged();

        mPlaceholderDrawable = new ColorDrawable(getResources().getColor(
                R.color.gallery_settings_chosen_photo_placeholder));

        mPhotoGridView = (RecyclerView) findViewById(R.id.photo_grid);
        mPhotoGridView.setItemAnimator(new DefaultItemAnimator());
        mPhotoGridView.setHasFixedSize(true);
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
                        R.dimen.gallery_settings_chosen_photo_grid_max_item_size);
                int numColumns = 1;
                while (true) {
                    if (width / numColumns > maxItemWidth) {
                        ++numColumns;
                    } else {
                        break;
                    }
                }

                int spacing = getResources().getDimensionPixelSize(
                        R.dimen.gallery_settings_chosen_photo_grid_spacing);
                mItemSize = (width - spacing * (numColumns - 1)) / numColumns;

                // Complete setup
                gridLayoutManager.setSpanCount(numColumns);
                mPhotoGridView.setAdapter(mChosenPhotosAdapter);

                mPhotoGridView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                tryUpdateSelection(false, false);
            }
        });

        final DrawInsetsFrameLayout insetsLayout = (DrawInsetsFrameLayout)
                findViewById(R.id.draw_insets_frame_layout);
        insetsLayout.setOnInsetsCallback(new DrawInsetsFrameLayout.OnInsetsCallback() {
            @Override
            public void onInsetsChanged(Rect insets) {
                insetsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);

                TypedValue tv = new TypedValue();
                getTheme().resolveAttribute(R.attr.actionBarSize, tv, true);
                mPhotoGridView.setPadding(
                        insets.left,
                        insets.top + (int) tv.getDimension(getResources().getDisplayMetrics()),
                        insets.right,
                        insets.bottom + getResources().getDimensionPixelSize(
                                R.dimen.gallery_settings_fab_space));

                findViewById(R.id.selection_toolbar_container).setPadding(
                        insets.left, insets.top, insets.right, 0);
            }
        });

        mAddButton = findViewById(R.id.add_photos_button);
        mAddButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseMorePhotos();
            }
        });
        CheatSheet.setup(mAddButton);

        EventBus.getDefault().register(this);
    }

    private void setupAppBar() {
        Toolbar appBar = (Toolbar) findViewById(R.id.app_bar);
        appBar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onNavigateUp();
            }
        });

        appBar.inflateMenu(R.menu.gallery_settings);

        int rotateIntervalMin = GalleryArtSource.getSharedPreferences(this)
                .getInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN,
                        GalleryArtSource.DEFAULT_ROTATE_INTERVAL_MIN);
        int menuId = sRotateMenuIdsByMin.get(rotateIntervalMin);
        if (menuId != 0) {
            MenuItem item = appBar.getMenu().findItem(menuId);
            if (item != null) {
                item.setChecked(true);
            }
        }

        appBar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                int rotateMin = sRotateMinsByMenuId.get(itemId, -1);
                if (rotateMin != -1) {
                    GalleryArtSource.getSharedPreferences(GallerySettingsActivity.this).edit()
                            .putInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN, rotateMin)
                            .apply();
                    item.setChecked(true);
                    return true;
                }

                switch (itemId) {
                    case R.id.action_clear_photos:
                        startService(new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                                .setAction(ACTION_REMOVE_CHOSEN_URIS));
                        return true;
                }

                return false;
            }
        });
    }

    private int mLastTouchPosition;
    private int mLastTouchX, mLastTouchY;

    private void setupMultiSelect() {
        // Set up toolbar
        mSelectionToolbar = (Toolbar) findViewById(R.id.selection_toolbar);

        mSelectionToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mMultiSelectionController.reset(true);
            }
        });

        mSelectionToolbar.inflateMenu(R.menu.gallery_settings_selection);
        mSelectionToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.action_force_now:
                        Set<Uri> selection = mMultiSelectionController.getSelection();
                        if (selection.size() > 0) {
                            startService(
                                    new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                                    .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                                    .putExtra(EXTRA_FORCE_URI, selection.iterator().next()));
                            Toast.makeText(GallerySettingsActivity.this,
                                    R.string.gallery_source_temporary_force_image,
                                    Toast.LENGTH_SHORT).show();
                        }
                        mMultiSelectionController.reset(true);
                        return true;

                    case R.id.action_remove:
                        final ArrayList<Uri> removeUris = new ArrayList<>(
                                mMultiSelectionController.getSelection());

                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                startService(
                                        new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                                                .setAction(ACTION_REMOVE_CHOSEN_URIS)
                                                .putParcelableArrayListExtra(EXTRA_URIS, removeUris));
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
                tryUpdateSelection(!restored, fromUser);
            }
        });
    }

    private void tryUpdateSelection(boolean allowAnimate, boolean fromUser) {
        mLastSelectionUpdateFromUser = fromUser;
        final View selectionToolbarContainer = findViewById(R.id.selection_toolbar_container);

        if (mUpdatePosition >= 0) {
            mChosenPhotosAdapter.notifyItemChanged(mUpdatePosition);
            mUpdatePosition = -1;
        } else {
            mChosenPhotosAdapter.notifyDataSetChanged();
        }

        int selectedCount = mMultiSelectionController.getSelectedCount();
        final boolean toolbarVisible = selectedCount > 0;
        mSelectionToolbar.getMenu().findItem(R.id.action_force_now).setVisible(
                selectedCount < 2);

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
            mSelectionToolbar.setTitle(Integer.toString(selectedCount));
        }
    }

    private void onDataSetChanged() {
        findViewById(android.R.id.empty)
                .setVisibility(mChosenUris.size() > 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mMultiSelectionController.restoreInstanceState(savedInstanceState);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View mRootView;
        ImageView mThumbView;
        View mCheckedOverlayView;

        public ViewHolder(View root) {
            super(root);
            mRootView = root;
            mThumbView = (ImageView) root.findViewById(R.id.thumbnail);
            mCheckedOverlayView = root.findViewById(R.id.checked_overlay);
        }
    }

    private RecyclerView.Adapter<ViewHolder> mChosenPhotosAdapter
            = new RecyclerView.Adapter<ViewHolder>() {
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(GallerySettingsActivity.this)
                    .inflate(R.layout.gallery_settings_chosen_photo_item, parent, false);
            final ViewHolder vh = new ViewHolder(v);

            v.getLayoutParams().height = mItemSize;
            v.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (motionEvent.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                        mLastTouchPosition = vh.getPosition();
                        mLastTouchX = (int) motionEvent.getX();
                        mLastTouchY = (int) motionEvent.getY();
                    }
                    return false;
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mUpdatePosition = vh.getPosition();
                    mMultiSelectionController.toggle(mChosenUris.get(mUpdatePosition), true);
                }
            });

            return vh;
        }

        @Override
        public void onBindViewHolder(final ViewHolder vh, int position) {
            Uri imageUri = mChosenUris.get(position);
            File storedFile = GalleryArtSource.getStoredFileForUri(
                    getApplicationContext(), imageUri);
            Picasso.with(GallerySettingsActivity.this)
                    .load(Uri.fromFile(storedFile))
                    .resize(mItemSize, mItemSize)
                    .centerCrop()
                    .placeholder(mPlaceholderDrawable)
                    .into(vh.mThumbView);
            final boolean checked = mMultiSelectionController.isSelected(imageUri);
            vh.mRootView.setTag(R.id.viewtag_position, position);
            if (mLastTouchPosition == vh.getPosition()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (checked) {
                            vh.mCheckedOverlayView.setVisibility(View.VISIBLE);
                        }

                        // find the smallest radius that'll cover the item
                        int width = vh.mRootView.getWidth();
                        int height = vh.mRootView.getHeight();
                        float coverRadius = 0;
                        coverRadius = Math.max(coverRadius,
                                MathUtil.dist(mLastTouchX, mLastTouchY));
                        coverRadius = Math.max(coverRadius,
                                MathUtil.dist(width - mLastTouchX, mLastTouchY));
                        coverRadius = Math.max(coverRadius,
                                MathUtil.dist(mLastTouchX, height - mLastTouchY));
                        coverRadius = Math.max(coverRadius,
                                MathUtil.dist(width - mLastTouchX, height - mLastTouchY));

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

        @Override
        public int getItemCount() {
            return mChosenUris.size();
        }

        @Override
        public long getItemId(int position) {
            return mChosenUris.get(position).hashCode();
        }
    };

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void chooseMorePhotos() {
        // NOTE: No need to use the Document Storage framework (OPEN_DOCUMENT)
        // since we only need temporary access to the photo (we make a copy).
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        if (requestCode != REQUEST_CHOOSE_PHOTOS || resultCode != RESULT_OK) {
            super.onActivityResult(requestCode, resultCode, result);
            return;
        }

        if (result == null) {
            return;
        }

        // Add chosen items
        ArrayList<Uri> uris = new ArrayList<>();
        if (result.getData() != null) {
            uris.add(result.getData());
        } else {
            ClipData clipData = result.getClipData();
            if (clipData != null) {
                int count = clipData.getItemCount();
                for (int i = 0; i < count; i++) {
                    uris.add(clipData.getItemAt(i).getUri());
                }
            }
        }

        // Update chosen URIs
        startService(new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                .setAction(ACTION_ADD_CHOSEN_URIS)
                .putParcelableArrayListExtra(EXTRA_URIS, uris));
    }

    public void onEventMainThread(GalleryChosenUrisChangedEvent e) {
        // Figure out what was removed and what was added.
        // Only support structural change events for appends and removes for now.
        List<Uri> newChosenUris = new ArrayList<>(mStore.getChosenUris());
        if (newChosenUris.size() >= mChosenUris.size()) {
            // items added or equal
            int i;

            boolean isAppend = true;
            for (i = 0; i < mChosenUris.size(); i++) {
                if (!mChosenUris.get(i).equals(newChosenUris.get(i))) {
                    isAppend = false;
                }
            }

            if (isAppend) {
                mChosenPhotosAdapter.notifyItemRangeInserted(mChosenUris.size(),
                        newChosenUris.size() - mChosenUris.size());
            } else {
                // not an append, don't handle this case intelligently
                mChosenPhotosAdapter.notifyDataSetChanged();
            }
        } else {
            // TODO: handle case where 2 items removed, 1 added
            // items removed
            Set<Uri> currentUris = new HashSet<>(mChosenUris);
            Set<Uri> removedUris = currentUris;
            removedUris.removeAll(newChosenUris);
            List<Integer> indices = new ArrayList<>();
            for (Uri uri : removedUris) {
                int index = mChosenUris.indexOf(uri);
                if (index >= 0) {
                    indices.add(index);
                }
            }

            Collections.sort(indices);
            Collections.reverse(indices);
            for (Integer index : indices) {
                mChosenPhotosAdapter.notifyItemRemoved(index);
            }
        }

        mChosenUris = new ArrayList<>(mStore.getChosenUris());
        onDataSetChanged();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mMultiSelectionController.saveInstanceState(outState);
    }
}
