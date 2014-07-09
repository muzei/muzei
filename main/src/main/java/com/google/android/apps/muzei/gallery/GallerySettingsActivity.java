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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.apps.muzei.event.GalleryChosenUrisChangedEvent;
import com.google.android.apps.muzei.util.CheatSheet;
import com.google.android.apps.muzei.util.DrawInsetsFrameLayout;
import com.google.android.apps.muzei.util.MultiSelectionUtil;

import com.squareup.picasso.Picasso;

import net.nurik.roman.muzei.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.greenrobot.event.EventBus;

import static android.widget.AbsListView.MultiChoiceModeListener;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_ADD_CHOSEN_URIS;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_PUBLISH_NEXT_GALLERY_ITEM;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.ACTION_REMOVE_CHOSEN_URIS;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.EXTRA_FORCE_URI;
import static com.google.android.apps.muzei.gallery.GalleryArtSource.EXTRA_URIS;

public class GallerySettingsActivity extends Activity
        implements AdapterView.OnItemClickListener {
    private static final int REQUEST_CHOOSE_PHOTOS = 1;

    private GalleryStore mStore;
    private List<Uri> mChosenUris;

    private Handler mHandler = new Handler();
    private GridView mGridView;
    private int mItemSize = 10;

    private MultiSelectionUtil.Controller mMultiSelectionController;

    private ColorDrawable mPlaceholderDrawable;

    private static SparseIntArray sRotateMenuIdsByMin = new SparseIntArray();
    private static SparseIntArray sRotateMinsByMenuId = new SparseIntArray();

    static {
        sRotateMenuIdsByMin.put(0, R.id.action_rotate_interval_none);
        sRotateMenuIdsByMin.put(60 / 2, R.id.action_rotate_interval_30m);
        sRotateMenuIdsByMin.put(60, R.id.action_rotate_interval_1h);
        sRotateMenuIdsByMin.put(60 * 3, R.id.action_rotate_interval_3h);
        sRotateMenuIdsByMin.put(60 * 6, R.id.action_rotate_interval_6h);
        sRotateMenuIdsByMin.put(60 * 24, R.id.action_rotate_interval_24h);
        sRotateMenuIdsByMin.put(60 * 72, R.id.action_rotate_interval_72h);
        for (int i = 0; i < sRotateMenuIdsByMin.size(); i++) {
            sRotateMinsByMenuId.put(sRotateMenuIdsByMin.valueAt(i), sRotateMenuIdsByMin.keyAt(i));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gallery_settings_activity);

        mStore = GalleryStore.getInstance(this);
        mChosenUris = new ArrayList<Uri>(mStore.getChosenUris());

        mPlaceholderDrawable = new ColorDrawable(getResources().getColor(
                R.color.gallery_settings_chosen_photo_placeholder));

        mGridView = (GridView) findViewById(R.id.photo_grid);
        mGridView.setAdapter(mChosenPhotosAdapter);
        mGridView.setEmptyView(findViewById(android.R.id.empty));
        mGridView.setOnItemClickListener(this);
        mMultiSelectionController = MultiSelectionUtil.attachMultiSelectionController(
                mGridView, this, mMultiChoiceModeListener);

        final ViewTreeObserver vto = mGridView.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            int lastWidth = -1;

            @Override
            public void onGlobalLayout() {
                int width = mGridView.getWidth()
                        - mGridView.getPaddingLeft() - mGridView.getPaddingRight();
                if (width == lastWidth || width <= 0) {
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
                mGridView.setNumColumns(numColumns);

                mGridView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });

        final DrawInsetsFrameLayout insetsLayout = (DrawInsetsFrameLayout)
                findViewById(R.id.draw_insets_frame_layout);
        insetsLayout.setOnInsetsCallback(new DrawInsetsFrameLayout.OnInsetsCallback() {
            @Override
            public void onInsetsChanged(Rect insets) {
                mGridView.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                insetsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            }
        });

        View addButton = findViewById(R.id.add_photos_button);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseMorePhotos();
            }
        });
        CheatSheet.setup(addButton);

        EventBus.getDefault().register(this);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mMultiSelectionController.tryRestoreInstanceState(savedInstanceState);
    }

    private BaseAdapter mChosenPhotosAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return mChosenUris.size();
        }

        @Override
        public Uri getItem(int position) {
            return mChosenUris.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mChosenUris.get(position).hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup container) {
            if (convertView == null) {
                convertView = LayoutInflater.from(GallerySettingsActivity.this)
                        .inflate(R.layout.gallery_settings_chosen_photo_item, container, false);
                convertView.getLayoutParams().height = mItemSize;
            }

            File storedFile = GalleryArtSource.getStoredFileForUri(
                    getApplicationContext(), getItem(position));
            ImageView thumbnailView = (ImageView) convertView.findViewById(R.id.thumbnail);
            Picasso.with(GallerySettingsActivity.this)
                    .load(Uri.fromFile(storedFile))
                    .resize(mItemSize, mItemSize)
                    .centerCrop()
                    .placeholder(mPlaceholderDrawable)
                    .into(thumbnailView);
            convertView.setTag(R.id.viewtag_position, position);
            return convertView;
        }
    };

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
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

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.gallery_settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        int rotateMin = sRotateMinsByMenuId.get(itemId, -1);
        if (rotateMin != -1) {
            GalleryArtSource.getSharedPreferences(this).edit()
                    .putInt(GalleryArtSource.PREF_ROTATE_INTERVAL_MIN, rotateMin)
                    .apply();
            invalidateOptionsMenu();
            return true;
        }
        switch (itemId) {
            case R.id.action_clear_photos:
                startService(new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                        .setAction(ACTION_REMOVE_CHOSEN_URIS));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void chooseMorePhotos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Documents API
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS);

        } else {
            // Older API
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_CHOOSE_PHOTOS);
        }
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
        ArrayList<Uri> uris = new ArrayList<Uri>();
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
        mChosenUris = new ArrayList<Uri>(mStore.getChosenUris());
        mChosenPhotosAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mMultiSelectionController != null) {
            mMultiSelectionController.saveInstanceState(outState);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        Uri imageUri = mChosenUris.get(position);
        startService(new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                .setAction(ACTION_PUBLISH_NEXT_GALLERY_ITEM)
                .putExtra(EXTRA_FORCE_URI, imageUri));
        Toast.makeText(this, R.string.gallery_source_temporary_force_image, Toast.LENGTH_SHORT).show();
    }

    private MultiChoiceModeListener mMultiChoiceModeListener = new MultiChoiceModeListener() {
        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            getMenuInflater().inflate(R.menu.gallery_settings_selection, menu);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
            switch (menuItem.getItemId()) {
                case R.id.action_remove:
                    SparseBooleanArray checkedPositionsBool = mGridView.getCheckedItemPositions();
                    final ArrayList<Uri> removeUris = new ArrayList<Uri>();
                    for (int i = checkedPositionsBool.size() - 1; i >= 0; i--) {
                        int pos = checkedPositionsBool.keyAt(i);
                        if (checkedPositionsBool.valueAt(i) && pos < mChosenUris.size()) {
                            removeUris.add(mChosenUris.get(pos));
                            mGridView.setItemChecked(pos, false); // Workaround for AbsListView bug
                        }
                    }

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            startService(
                                    new Intent(GallerySettingsActivity.this, GalleryArtSource.class)
                                            .setAction(ACTION_REMOVE_CHOSEN_URIS)
                                            .putParcelableArrayListExtra(EXTRA_URIS, removeUris));
                        }
                    });

                    actionMode.finish();
                    return true;
            }
            return false;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode actionMode, int position, long itemId,
                boolean checked) {
            actionMode.setTitle(Integer.toString(mGridView.getCheckedItemCount()));
        }
    };
}
