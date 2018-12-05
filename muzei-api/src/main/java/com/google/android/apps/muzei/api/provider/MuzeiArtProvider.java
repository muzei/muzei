/*
 * Copyright 2018 Google Inc.
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
package com.google.android.apps.muzei.api.provider;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.RecentArtworkIdsConverter;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_LAST_LOADED_TIME;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_MAX_LOADED_ARTWORK_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_OPEN_ARTWORK_INFO_SUCCESS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_RECENT_ARTWORK_IDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_COMMANDS;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_INVALID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_OPEN_ARTWORK_INFO;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_TRIGGER_COMMAND;

/**
 * Base class for a Muzei Live Wallpaper artwork provider. Art providers are a way for other apps to
 * feed wallpapers (called {@linkplain Artwork artworks}) to the Muzei Live Wallpaper.
 * <h3>Subclassing {@link MuzeiArtProvider}</h3>
 * Subclasses must implement at least the {@link #onLoadRequested(boolean) onLoadRequested} callback
 * method, which is called whenever Muzei has displayed all available artwork from your provider.
 * It is strongly recommended to load new artwork at this point and add it via
 * {@link #addArtwork(Artwork)} so that users can continue to move to the next artwork.
 * <p> All artwork added via {@link #addArtwork(Artwork)} is available to Muzei. Muzei controls how
 * often the artwork changes and the order that it proceeds through the artwork. As a
 * convenience, you can use {@link #setArtwork(Artwork)} to remove all artwork and set just a
 * single new {@link Artwork}. You can, of course, can and should use
 * {@link ContentResolver#delete(Uri, String, String[])} with {@link #getContentUri()} to delete
 * specific artwork based on criteria of your choosing.
 * <p>
 * Many operations are also available in {@link ProviderContract.Artwork}, allowing you to add,
 * update, delete, and query for artwork from anywhere in your app.
 * <h3>Registering your provider</h3>
 * Each provider must be added to your application's <code>AndroidManifest.xml</code> file via a
 * <code>&lt;provider&gt;</code> element.
 * <p>
 * The Muzei app discover available providers using Android's {@link Intent} mechanism. Ensure
 * that your <code>provider</code> definition includes an <code>&lt;intent-filter&gt;</code> with
 * an action of {@link #ACTION_MUZEI_ART_PROVIDER}. It is strongly recommended to protect access
 * to your provider's data by adding the {@link #ACCESS_PERMISSION}, which will
 * ensure that only your app and Muzei can access your data.
 * <p>
 * Lastly, there are a few <code>&lt;meta-data&gt;</code> elements that you should add to your
 * provider definition:
 * <ul>
 * <li><code>settingsActivity</code> (optional): if present, should be the qualified
 * component name for a configuration activity in the provider's package that Muzei can offer
 * to the user for customizing the extension. This activity must be exported.</li>
 * <li><code>setupActivity</code> (optional): if present, should be the qualified
 * component name for an initial setup activity that must be ran before the provider can be
 * activated. It will be started with {@link android.app.Activity#startActivityForResult} and must
 * return {@link android.app.Activity#RESULT_OK} for the provider to be activated. This activity
 * must be exported.</li>
 * </ul>
 * <h3>Example</h3>
 * Below is an example provider declaration in the manifest:
 * <pre class="prettyprint">
 * &lt;provider android:name=".ExampleArtSource"
 *     android:authority="com.example.artprovider"
 *     android:label="@string/source_title"
 *     android:description="@string/source_description"
 *     android:permission="com.google.android.apps.muzei.api.ACCESS_PROVIDER"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="com.google.android.apps.muzei.api.MuzeiArtProvider" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;!-- A settings activity is optional --&gt;
 *     &lt;meta-data android:name="settingsActivity"
 *         android:value=".ExampleSettingsActivity" /&gt;
 * &lt;/provider&gt;
 * </pre>
 * <p>
 * If a <code>settingsActivity</code> meta-data element is present, an activity with the given
 * component name should be defined and exported in the application's manifest as well. Muzei
 * will set the {@link #EXTRA_FROM_MUZEI} extra to true in the launch intent for this
 * activity. An example is shown below:
 * <pre class="prettyprint">
 * &lt;activity android:name=".ExampleSettingsActivity"
 *     android:label="@string/title_settings"
 *     android:exported="true" /&gt;
 * </pre>
 * <p>
 * Finally, below is a simple example {@link MuzeiArtProvider} subclass that publishes a single,
 * static artwork:
 * <pre class="prettyprint">
 * public class ExampleArtSource extends MuzeiArtProvider {
 * protected void onLoadRequested(boolean initial) {
 *   setArtwork(new Artwork.Builder()
 *     .persistentUri(Uri.parse("http://example.com/image.jpg"))
 *     .title("Example image")
 *     .byline("Unknown person, c. 1980")
 *     .webUri(Uri.parse("http://example.com/imagedetails.html"))
 *     .build());
 *   }
 * }
 * </pre>
 * As onLoadRequested can be called at any time (including when offline), it is
 * strongly recommended to use the callback of onLoadRequested to kick off
 * a load operation using WorkManager, JobScheduler, or a comparable API. These
 * other components can then use a {@link ProviderClient} and
 * {@link ProviderClient#addArtwork(Artwork)} to add Artwork to the MuzeiArtProvider.
 * <h3>Additional notes</h3>
 * Providers can also expose additional user-facing commands (such as 'Share artwork') by
 * returning one or more {@link UserCommand commands} from {@link #getCommands(Artwork)}. To handle
 * commands, override the {@link #onCommand(Artwork, int)} callback method.
 * <p>
 * Providers can provide a dynamic description of the current configuration (e.g.
 * 'Popular photos tagged "landscape"'), by overriding {@link #getDescription()}. By default,
 * the <code>android:description</code> element of the provider element in the manifest will be
 * used.
 * <p>
 * All artwork should support opening an Activity to view more details about the artwork.
 * You can provider your own functionality by overriding {@link #openArtworkInfo(Artwork)}.
 * <p>
 * If custom behavior is needed to retrieve the artwork's binary data (for example,
 * authentication with a remote server), this behavior can be added to
 * {@link #openFile(Artwork)}. If you already have binary data available locally for your
 * artwork, you can also write it directly via {@link ContentResolver#openOutputStream(Uri)}.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
public abstract class MuzeiArtProvider extends ContentProvider implements ProviderClient {
    private static final String TAG = "MuzeiArtProvider";
    private static final boolean DEBUG = false;
    private static final int MAX_RECENT_ARTWORK = 100;
    /**
     * Permission that can be used with your {@link MuzeiArtProvider} to ensure that only your app
     * and Muzei can read and write its data.
     * <p>
     * This is a signature permission that only Muzei can hold.
     * </p>
     */
    @SuppressWarnings({"WeakerAccess", "unused"})
    public static final String ACCESS_PERMISSION
            = "com.google.android.apps.muzei.api.ACCESS_PROVIDER";
    /**
     * The {@link Intent} action representing a Muzei art provider. This provider should
     * declare an <code>&lt;intent-filter&gt;</code> for this action in order to register with
     * Muzei.
     */
    public static final String ACTION_MUZEI_ART_PROVIDER
            = "com.google.android.apps.muzei.api.MuzeiArtProvider";
    /**
     * Boolean extra that will be set to true when Muzei starts provider settings and setup
     * activities.
     * <p>
     * Check for this extra in your activity if you need to adjust your UI depending on
     * whether or not the user came from Muzei.
     */
    public static final String EXTRA_FROM_MUZEI
            = "com.google.android.apps.muzei.api.extra.FROM_MUZEI_SETTINGS";
    private static final String PREF_MAX_LOADED_ARTWORK_ID = "maxLoadedArtworkId";
    private static final String PREF_LAST_LOADED_TIME = "lastLoadTime";
    private static final String PREF_RECENT_ARTWORK_IDS = "recentArtworkIds";

    private static final String TABLE_NAME = "artwork";
    /**
     * An identity all column projection mapping for artwork
     */
    private final HashMap<String, String> allArtworkColumnProjectionMap =
            MuzeiArtProvider.buildAllArtworkColumnProjectionMap();

    /**
     * Creates and initializes a column project for all columns for artwork
     *
     * @return The all column projection map for artwork
     */
    private static HashMap<String, String> buildAllArtworkColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(ProviderContract.Artwork.TOKEN,
                ProviderContract.Artwork.TOKEN);
        allColumnProjectionMap.put(ProviderContract.Artwork.TITLE,
                ProviderContract.Artwork.TITLE);
        allColumnProjectionMap.put(ProviderContract.Artwork.BYLINE,
                ProviderContract.Artwork.BYLINE);
        allColumnProjectionMap.put(ProviderContract.Artwork.ATTRIBUTION,
                ProviderContract.Artwork.ATTRIBUTION);
        allColumnProjectionMap.put(ProviderContract.Artwork.PERSISTENT_URI,
                ProviderContract.Artwork.PERSISTENT_URI);
        allColumnProjectionMap.put(ProviderContract.Artwork.WEB_URI,
                ProviderContract.Artwork.WEB_URI);
        allColumnProjectionMap.put(ProviderContract.Artwork.METADATA,
                ProviderContract.Artwork.METADATA);
        allColumnProjectionMap.put(ProviderContract.Artwork.DATA,
                ProviderContract.Artwork.DATA);
        allColumnProjectionMap.put(ProviderContract.Artwork.DATE_ADDED,
                ProviderContract.Artwork.DATE_ADDED);
        allColumnProjectionMap.put(ProviderContract.Artwork.DATE_MODIFIED,
                ProviderContract.Artwork.DATE_MODIFIED);
        return allColumnProjectionMap;
    }

    private DatabaseHelper databaseHelper;
    private String authority;
    private Uri contentUri;

    private final ThreadLocal<Boolean> applyingBatch = new ThreadLocal<>();
    private final ThreadLocal<Set<Uri>> changedUris = new ThreadLocal<>();

    private boolean applyingBatch() {
        return applyingBatch.get() != null && applyingBatch.get();
    }

    private void onOperationComplete() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        ContentResolver contentResolver = context.getContentResolver();
        for (Uri uri : changedUris.get()) {
            Log.d(TAG, "Notified for batch change on " + uri);
            contentResolver.notifyChange(uri, null);
        }
    }

    @Override
    @NonNull
    public final Uri getContentUri() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("getContentUri() should not be called before onCreate()");
        }
        if (contentUri == null) {
            contentUri = ProviderContract.getProviderClient(context, getClass()).getContentUri();
        }
        return contentUri;
    }

    @Override
    @Nullable
    public final Artwork getLastAddedArtwork() {
        try (Cursor data = query(contentUri, null, null, null,
                BaseColumns._ID + " DESC")) {
            return data.moveToFirst() ? Artwork.fromCursor(data) : null;
        }
    }

    @Override
    @Nullable
    public final Uri addArtwork(@NonNull Artwork artwork) {
        return insert(contentUri, artwork.toContentValues());
    }

    @Override
    @NonNull
    public List<Uri> addArtwork(@NonNull final Iterable<Artwork> artwork) {
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (Artwork art : artwork) {
            operations.add(ContentProviderOperation.newInsert(contentUri)
                    .withValues(art.toContentValues())
                    .build());
        }
        ArrayList<Uri> resultUris = new ArrayList<>(operations.size());
        try {
            ContentProviderResult[] results = applyBatch(operations);
            for (ContentProviderResult result : results) {
                resultUris.add(result.uri);
            }
        } catch (OperationApplicationException ignored) {
        }
        return resultUris;
    }

    @Override
    @Nullable
    public final Uri setArtwork(@NonNull Artwork artwork) {
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newInsert(contentUri)
                .withValues(artwork.toContentValues())
                .build());
        operations.add(ContentProviderOperation.newDelete(contentUri)
                .withSelection(BaseColumns._ID + " != ?", new String[1])
                .withSelectionBackReference(0, 0)
                .build());
        try {
            ContentProviderResult[] results = applyBatch(operations);
            return results[0].uri;
        } catch (OperationApplicationException e) {
            Log.e(TAG, "setArtwork failed", e);
            return null;
        }
    }

    @Override
    @NonNull
    public List<Uri> setArtwork(@NonNull final Iterable<Artwork> artwork) {
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (Artwork art : artwork) {
            operations.add(ContentProviderOperation.newInsert(contentUri)
                    .withValues(art.toContentValues())
                    .build());
        }
        long currentTime = System.currentTimeMillis();
        ArrayList<Uri> resultUris = new ArrayList<>(operations.size());
        try {
            ContentProviderResult[] results = applyBatch(operations);
            for (ContentProviderResult result : results) {
                resultUris.add(result.uri);
            }
            ArrayList<ContentProviderOperation> deleteOperations = new ArrayList<>();
            try (Cursor data = query(
                    contentUri,
                    new String[] { BaseColumns._ID },
                    ProviderContract.Artwork.DATE_MODIFIED + "<?",
                    new String[] { Long.toString(currentTime)},
                    null)) {
                while (data.moveToNext()) {
                    Uri artworkUri = ContentUris.withAppendedId(contentUri,
                            data.getLong(0));
                    if (!resultUris.contains(artworkUri)) {
                        deleteOperations.add(ContentProviderOperation
                                .newDelete(artworkUri)
                                .build());
                    }
                }
            }
            if (!deleteOperations.isEmpty()) {
                applyBatch(deleteOperations);
            }
        } catch (OperationApplicationException ignored) {
        }
        return resultUris;
    }

    @CallSuper
    @Override
    @Nullable
    public Bundle call(
            @NonNull final String method,
            @Nullable final String arg,
            @Nullable final Bundle extras
    ) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        long token = Binder.clearCallingIdentity();
        if (DEBUG) {
            Log.d(TAG, "Received command " + method + " with arg \"" + arg + "\" and extras " + extras);
        }
        try {
            switch (method) {
                case METHOD_REQUEST_LOAD:
                    try (Cursor data = databaseHelper.getReadableDatabase().query(TABLE_NAME,
                            null, null, null, null, null, null,
                            "1")) {
                        onLoadRequested(data == null || data.getCount() == 0);
                    }
                    break;
                case METHOD_MARK_ARTWORK_INVALID:
                    try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                            null, null, null, null)) {
                        if (data != null && data.moveToNext()) {
                            onInvalidArtwork(Artwork.fromCursor(data));
                        }
                    }
                    break;
                case METHOD_MARK_ARTWORK_LOADED:
                    try (Cursor data = query(contentUri,
                            null, null, null, null)) {
                        SharedPreferences prefs = context.getSharedPreferences(authority, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = prefs.edit();
                        // See if we need to update the maxLoadedArtworkId
                        long currentMaxId = prefs.getLong(PREF_MAX_LOADED_ARTWORK_ID, 0L);
                        long loadedId = ContentUris.parseId(Uri.parse(arg));
                        if (loadedId > currentMaxId) {
                            editor.putLong(PREF_MAX_LOADED_ARTWORK_ID, loadedId);
                        }
                        // Update the last loaded time
                        editor.putLong(PREF_LAST_LOADED_TIME, System.currentTimeMillis());
                        // Update the list of recent artwork ids
                        ArrayDeque<Long> recentArtworkIds = RecentArtworkIdsConverter.fromString(
                                prefs.getString(PREF_RECENT_ARTWORK_IDS, ""));
                        // Remove the loadedId if it exists in the list already
                        recentArtworkIds.remove(loadedId);
                        // Then add the loadedId to the end of the list
                        recentArtworkIds.addLast(loadedId);
                        int maxSize = Math.min(Math.max(data.getCount(), 1), MAX_RECENT_ARTWORK);
                        while (recentArtworkIds.size() > maxSize) {
                            removeAutoCachedFile(recentArtworkIds.removeFirst());
                        }
                        editor.putString(PREF_RECENT_ARTWORK_IDS,
                                RecentArtworkIdsConverter.idsListToString(recentArtworkIds));
                        editor.apply();
                    }
                    break;
                case METHOD_GET_LOAD_INFO: {
                    SharedPreferences prefs = context.getSharedPreferences(authority, Context.MODE_PRIVATE);
                    Bundle bundle = new Bundle();
                    bundle.putLong(KEY_MAX_LOADED_ARTWORK_ID, prefs.getLong(PREF_MAX_LOADED_ARTWORK_ID, 0L));
                    bundle.putLong(KEY_LAST_LOADED_TIME, prefs.getLong(PREF_LAST_LOADED_TIME, 0L));
                    bundle.putString(KEY_RECENT_ARTWORK_IDS, prefs.getString(PREF_RECENT_ARTWORK_IDS, ""));
                    if (DEBUG) {
                        Log.d(TAG, "For " + METHOD_GET_LOAD_INFO + " returning " + bundle);
                    }
                    return bundle;
                }
                case METHOD_GET_DESCRIPTION: {
                    Bundle bundle = new Bundle();
                    bundle.putString(KEY_DESCRIPTION, getDescription());
                    return bundle;
                }
                case METHOD_GET_COMMANDS:
                    try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                            null, null, null, null)) {
                        if (data != null && data.moveToNext()) {
                            Bundle bundle = new Bundle();
                            List<UserCommand> userCommands = getCommands(Artwork.fromCursor(data));
                            JSONArray commandsSerialized = new JSONArray();
                            for (UserCommand command : userCommands) {
                                commandsSerialized.put(command.serialize());
                            }
                            bundle.putString(KEY_COMMANDS, commandsSerialized.toString());
                            if (DEBUG) {
                                Log.d(TAG, "For " + METHOD_GET_COMMANDS + " returning " + bundle);
                            }
                            return bundle;
                        }
                    }
                    break;
                case METHOD_TRIGGER_COMMAND:
                    if (extras != null) {
                        try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                                null, null, null, null)) {
                            if (data != null && data.moveToNext()) {
                                onCommand(Artwork.fromCursor(data), extras.getInt(KEY_COMMAND));
                            }
                        }
                    }
                    break;
                case METHOD_OPEN_ARTWORK_INFO:
                    try (Cursor data = context.getContentResolver().query(Uri.parse(arg),
                            null, null, null, null)) {
                        if (data != null && data.moveToNext()) {
                            Bundle bundle = new Bundle();
                            boolean success = openArtworkInfo(Artwork.fromCursor(data));
                            bundle.putBoolean(KEY_OPEN_ARTWORK_INFO_SUCCESS, success);
                            if (DEBUG) {
                                Log.d(TAG, "For " + METHOD_OPEN_ARTWORK_INFO + " returning " + bundle);
                            }
                            return bundle;
                        }
                    }
                    break;
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Callback method when the user has viewed all of the available artwork. This should be used
     * as a cue to load more artwork so that the user has a constant stream of new artwork.
     * <p>
     * Muzei will always prefer to show unseen artwork, but will automatically cycle through all
     * of the available artwork if no new artwork is found (i.e., if you don't load new artwork
     * after receiving this callback).
     *
     * @param initial true when there is no artwork available, such as is the case when this is
     *                the initial load of this MuzeiArtProvider.
     */
    protected abstract void onLoadRequested(boolean initial);

    /**
     * Called when Muzei failed to load the given artwork, usually due to an incompatibility
     * in supported image format. The default behavior is to delete the artwork.
     * <p>
     * If you only support a single artwork, you should use this callback as an opportunity
     * to provide an alternate version of the artwork or a backup image to avoid repeatedly
     * loading the same artwork, just to mark it as invalid and be left with no valid artwork.
     *
     * @param artwork Artwork that Muzei has failed to load
     */
    protected void onInvalidArtwork(@NonNull Artwork artwork) {
        Uri artworkUri = ContentUris.withAppendedId(contentUri, artwork.getId());
        delete(artworkUri, null, null);
    }

    /**
     * Gets the longer description for the current state of this MuzeiArtProvider. For example,
     * 'Popular photos tagged "landscape"'). The default implementation returns the
     * <code>android:description</code> element of the provider element in the manifest.
     *
     * @return The description that should be shown when this provider is selected
     */
    @NonNull
    protected String getDescription() {
        Context context = getContext();
        if (context == null) {
            return "";
        }
        try {
            @SuppressLint("InlinedApi")
            ProviderInfo info = context.getPackageManager().getProviderInfo(
                    new ComponentName(context, getClass()),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return info.descriptionRes != 0 ? context.getString(info.descriptionRes) : "";
        } catch (PackageManager.NameNotFoundException e) {
            // Wtf?
            return "";
        }
    }

    /**
     * Retrieve the list of commands available for the given artwork.
     *
     * @param artwork The associated artwork that can be used to customize the list of available
     *                commands.
     * @return A List of {@link UserCommand commands} that the user can trigger.
     * @see #onCommand(Artwork, int)
     */
    @NonNull
    protected List<UserCommand> getCommands(@NonNull final Artwork artwork) {
        return new ArrayList<>();
    }

    /**
     * Callback method indicating that the user has selected a command.
     *
     * @param artwork The artwork at the time when this command was triggered.
     * @param id      the ID of the command the user has chosen.
     * @see #getCommands(Artwork)
     */
    protected void onCommand(@NonNull final Artwork artwork, int id) {
    }

    /**
     * Callback when the user wishes to see more information about the given artwork. The default
     * implementation opens the {@link ProviderContract.Artwork#WEB_URI web uri} of the artwork.
     *
     * @param artwork The artwork the user wants to see more information about.
     * @return True if the artwork info was successfully opened.
     */
    protected boolean openArtworkInfo(@NonNull Artwork artwork) {
        if (artwork.getWebUri() != null && getContext() != null) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, artwork.getWebUri());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Could not open " + artwork.getWebUri() + ", artwork info for "
                        + ContentUris.withAppendedId(contentUri, artwork.getId()), e);
            }
        }
        return false;
    }

    @CallSuper
    @Override
    public boolean onCreate() {
        authority = getContentUri().getAuthority();
        String databaseName = authority.substring(authority.lastIndexOf('.') + 1);
        databaseHelper = new DatabaseHelper(getContext(), databaseName);
        return true;
    }

    @NonNull
    @Override
    public final Cursor query(@NonNull final Uri uri,
            @Nullable final String[] projection,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs,
            @Nullable final String sortOrder
    ) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            throw new IllegalStateException("Called query() before onCreate()");
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_NAME);
        qb.setProjectionMap(allArtworkColumnProjectionMap);
        qb.setStrict(true);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        if (!uri.equals(contentUri)) {
            // Appends "_ID = <id>" to the where clause, so that it selects the single artwork
            qb.appendWhere(BaseColumns._ID + "=" + uri.getLastPathSegment());
        }
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = ProviderContract.Artwork.DATE_ADDED + " DESC";
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs,
                null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    @NonNull
    @Override
    public final String getType(@NonNull final Uri uri) {
        if (uri.equals(contentUri)) {
            return "vnd.android.cursor.dir/vnd." + authority + "." + TABLE_NAME;
        } else {
            return "vnd.android.cursor.item/vnd." + authority + "." + TABLE_NAME;
        }
    }

    @NonNull
    @Override
    public final ContentProviderResult[] applyBatch(
            @NonNull final ArrayList<ContentProviderOperation> operations
    ) throws OperationApplicationException {
        changedUris.set(new HashSet<Uri>());
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        ContentProviderResult[] results;
        db.beginTransaction();
        try {
            applyingBatch.set(true);
            results = super.applyBatch(operations);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            applyingBatch.set(false);
            onOperationComplete();
        }
        return results;
    }

    @Override
    public final int bulkInsert(@NonNull final Uri uri, @NonNull final ContentValues[] values) {
        changedUris.set(new HashSet<Uri>());
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        int numberInserted;
        db.beginTransaction();
        try {
            applyingBatch.set(true);
            numberInserted = super.bulkInsert(uri, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            applyingBatch.set(false);
            onOperationComplete();
        }
        return numberInserted;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull final Uri uri, @Nullable ContentValues values) {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Called insert() before onCreate()");
        }
        if (values == null) {
            values = new ContentValues();
        }
        if (values.containsKey(ProviderContract.Artwork.TOKEN)) {
            String token = values.getAsString(ProviderContract.Artwork.TOKEN);
            if (TextUtils.isEmpty(token)) {
                // Treat empty strings as null
                if (token != null) {
                    Log.w(TAG, ProviderContract.Artwork.TOKEN + " must be non-empty if included");
                }
                values.remove(token);
            } else {
                try (Cursor existingData = query(contentUri,
                        null,
                        ProviderContract.Artwork.TOKEN + "=?",
                        new String[]{token},
                        null)) {
                    if (existingData.moveToFirst()) {
                        // If there's already a row with the same token, update it rather than
                        // inserting a new row

                        // But first check whether there's actually anything changing
                        String title = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.TITLE));
                        String byline = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.BYLINE));
                        String attribution = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.ATTRIBUTION));
                        String persistentUri = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.PERSISTENT_URI));
                        String webUri = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.WEB_URI));
                        String metadata = existingData.getString(existingData.getColumnIndex(
                                ProviderContract.Artwork.METADATA));
                        boolean noChange = TextUtils.equals(title, values.getAsString(
                                ProviderContract.Artwork.TITLE)) &&
                                TextUtils.equals(byline, values.getAsString(
                                        ProviderContract.Artwork.BYLINE)) &&
                                TextUtils.equals(attribution, values.getAsString(
                                        ProviderContract.Artwork.ATTRIBUTION)) &&
                                TextUtils.equals(persistentUri, values.getAsString(
                                        ProviderContract.Artwork.PERSISTENT_URI)) &&
                                TextUtils.equals(webUri, values.getAsString(
                                        ProviderContract.Artwork.WEB_URI)) &&
                                TextUtils.equals(metadata, values.getAsString(
                                        ProviderContract.Artwork.METADATA));
                        long id = existingData.getLong(existingData.getColumnIndex(
                                BaseColumns._ID));
                        Uri updateUri = ContentUris.withAppendedId(contentUri, id);
                        if (noChange) {
                            // Just update the DATE_MODIFIED and don't send a notifyChange()
                            values.clear();
                            values.put(ProviderContract.Artwork.DATE_MODIFIED,
                                    System.currentTimeMillis());
                            final SQLiteDatabase db = databaseHelper.getWritableDatabase();
                            db.update(TABLE_NAME, values, BaseColumns._ID + "=?",
                                    new String[] { Long.toString(id) });
                        } else {
                            // Do a full update
                            update(updateUri, values, null, null);
                        }
                        return updateUri;
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        values.put(ProviderContract.Artwork.DATE_ADDED, now);
        values.put(ProviderContract.Artwork.DATE_MODIFIED, now);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        db.beginTransaction();
        long rowId = db.insert(TABLE_NAME,
                ProviderContract.Artwork.DATE_ADDED, values);
        if (rowId <= 0) {
            // Insert failed, not much we can do about that
            db.endTransaction();
            return null;
        }
        // Add the DATA column pointing at the correct location
        boolean hasPersistentUri = values.containsKey(ProviderContract.Artwork.PERSISTENT_URI)
                && !TextUtils.isEmpty(values.getAsString(ProviderContract.Artwork.PERSISTENT_URI));
        File directory;
        if (hasPersistentUri) {
            directory = new File(context.getCacheDir(), "muzei_" + authority);
        } else {
            directory = new File(context.getFilesDir(), "muzei_" + authority);
        }
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        File artwork = new File(directory, Long.toString(rowId));
        ContentValues dataValues = new ContentValues();
        dataValues.put(ProviderContract.Artwork.DATA, artwork.getAbsolutePath());
        db.update(TABLE_NAME, dataValues, BaseColumns._ID + "=" + rowId, null);
        db.setTransactionSuccessful();
        db.endTransaction();
        // Creates a URI with the artwork ID pattern and the new row ID appended to it.
        final Uri artworkUri = ContentUris.withAppendedId(contentUri, rowId);
        if (applyingBatch()) {
            changedUris.get().add(artworkUri);
        } else {
            Log.d(TAG, "Notified for insert on " + artworkUri);
            context.getContentResolver().notifyChange(artworkUri, null);
        }
        return artworkUri;
    }

    @Override
    public final int delete(
            @NonNull final Uri uri,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs
    ) {
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;
        String finalWhere = selection;
        if (!contentUri.equals(uri)) {
            finalWhere = BaseColumns._ID + " = " + uri.getLastPathSegment();
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null) {
                finalWhere = finalWhere + " AND " + selection;
            }
        }
        // Delete all of the files associated with the rows being deleted
        try (Cursor rowsToDelete = query(contentUri, new String[]{ProviderContract.Artwork.DATA},
                finalWhere, selectionArgs, null)) {
            while (rowsToDelete.moveToNext()) {
                String fileName = rowsToDelete.getString(0);
                File file = fileName != null ? new File(fileName) : null;
                if (file != null && file.exists()) {
                    if (!file.delete()) {
                        Log.w(TAG, "Unable to delete " + file);
                    }
                }
            }
        }
        // Then delete the rows themselves
        count = db.delete(TABLE_NAME, finalWhere, selectionArgs);
        if (count > 0 && getContext() != null) {
            if (applyingBatch()) {
                changedUris.get().add(uri);
            } else {
                Log.d(TAG, "Notified for delete on " + uri);
                getContext().getContentResolver().notifyChange(uri, null);
            }
        }
        return count;
    }

    @Override
    public final int update(
            @NonNull final Uri uri,
            @Nullable final ContentValues values,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs
    ) {
        if (values == null) {
            return 0;
        }
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;
        String finalWhere = selection;
        if (!contentUri.equals(uri)) {
            finalWhere = BaseColumns._ID + " = " + uri.getLastPathSegment();
            // If there were additional selection criteria, append them to the final WHERE clause
            if (selection != null) {
                finalWhere = finalWhere + " AND " + selection;
            }
        }
        // TOKEN, DATA and DATE_ADDED cannot be changed
        values.remove(ProviderContract.Artwork.TOKEN);
        values.remove(ProviderContract.Artwork.DATA);
        values.remove(ProviderContract.Artwork.DATE_ADDED);
        // Update the DATE_MODIFIED
        values.put(ProviderContract.Artwork.DATE_MODIFIED, System.currentTimeMillis());
        count = db.update(TABLE_NAME, values, finalWhere, selectionArgs);
        if (count > 0 && getContext() != null) {
            if (applyingBatch()) {
                changedUris.get().add(uri);
            } else {
                Log.d(TAG, "Notified for update on " + uri);
                getContext().getContentResolver().notifyChange(uri, null);
            }
        }
        return count;
    }

    @Nullable
    @Override
    public final ParcelFileDescriptor openFile(
            @NonNull final Uri uri,
            @NonNull final String mode
    ) throws FileNotFoundException {
        Artwork artwork;
        try (Cursor data = query(uri,
                null, null, null, null)) {
            if (!data.moveToFirst()) {
                throw new FileNotFoundException("Could not get persistent uri for " + uri);
            }
            artwork = Artwork.fromCursor(data);
        }
        if (!isArtworkValid(artwork)) {
            onInvalidArtwork(artwork);
            throw new SecurityException("Artwork " + artwork + " was marked as invalid");
        }
        if (!artwork.getData().exists() && mode.equals("r")) {
            // Download the image from the persistent URI for read-only operations
            // rather than throw a FileNotFoundException
            File directory = artwork.getData().getParentFile();
            // Ensure that the parent directory of the artwork exists
            // as otherwise FileOutputStream will fail
            if (!directory.exists() && !directory.mkdirs()) {
                throw new FileNotFoundException("Unable to create directory " +
                        directory + " for " + artwork);
            }
            try (InputStream in = openFile(artwork);
                 FileOutputStream out = new FileOutputStream(artwork.getData())) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            } catch (Exception e) {
                if (!(e instanceof IOException)) {
                    Log.e(TAG, "Unable to open artwork " + artwork + " for " + uri, e);
                    onInvalidArtwork(artwork);
                }
                // Delete the file in cases of an error so that we will try again from scratch next time.
                if (artwork.getData().exists() && !artwork.getData().delete()) {
                    Log.w(TAG, "Error deleting partially downloaded file after error", e);
                }
                throw new FileNotFoundException("Could not download artwork " + artwork
                        + " for " + uri + ": " + e.getMessage());
            }
        }
        return ParcelFileDescriptor.open(artwork.getData(), ParcelFileDescriptor.parseMode(mode));
    }

    private void removeAutoCachedFile(long artworkId) {
        Uri artworkUri = ContentUris.withAppendedId(contentUri, artworkId);
        try (Cursor data = query(artworkUri, null, null, null, null)) {
            if (!data.moveToFirst()) {
                return;
            }
            Artwork artwork = Artwork.fromCursor(data);
            //noinspection ConstantConditions
            if (artwork.getPersistentUri() != null && artwork.getData().exists()) {
                //noinspection ResultOfMethodCallIgnored
                artwork.getData().delete();
            }
        }
    }

    /**
     * Called every time an image is loaded (even if there is a cached
     * image available). This gives you an opportunity to circumvent the
     * typical loading process and remove previously cached artwork on
     * demand. The default implementation always returns <code>true</code>.
     * <p>
     * In most cases, you should proactively delete Artwork that you know
     * is not valid rather than wait for this callback since at this point
     * the user is specifically waiting for the image to appear.
     * <p>
     * The MuzeiArtProvider will call {@link #onInvalidArtwork(Artwork)} for you
     * if you return <code>false</code>false - there is no need to call this
     * manually from within this method.
     * @param artwork The Artwork to confirm
     * @return Whether the Artwork is valid and should be loaded
     */
    public boolean isArtworkValid(@NonNull Artwork artwork) {
        return true;
    }

    /**
     * Provide an InputStream to the binary data associated with artwork that has not yet been
     * cached. The default implementation retrieves the image from the
     * {@link Artwork#getPersistentUri() persistent URI} and supports URI schemes in the following
     * formats:
     * <ul>
     * <li><code>content://...</code>.</li>
     * <li><code>android.resource://...</code>.</li>
     * <li><code>file://...</code>.</li>
     * <li><code>file:///android_asset/...</code>.</li>
     * <li><code>http://...</code> or <code>https://...</code>.</li>
     * </ul>
     * Throwing any exception other than an {@link IOException} will be considered a permanent
     * error that will result in a call to {@link #onInvalidArtwork(Artwork)}.
     *
     * @param artwork The Artwork to open
     * @return A valid {@link InputStream} for the artwork's image
     * @throws IOException if an error occurs while opening the image. The request will be retried
     *                     automatically.
     */
    @NonNull
    protected InputStream openFile(@NonNull Artwork artwork) throws IOException {
        Context context = getContext();
        if (context == null) {
            throw new IOException();
        }
        Uri persistentUri = artwork.getPersistentUri();
        if (persistentUri == null) {
            throw new IllegalStateException("Got null persistent URI for " + artwork + ". +" +
                    "The default implementation of openFile() requires a persistent URI. " +
                    "You must override this method or write the binary data directly " +
                    "to the artwork's data file.");
        }
        String scheme = persistentUri.getScheme();
        if (scheme == null) {
            throw new IOException("Uri had no scheme");
        }
        InputStream in = null;
        if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            in = context.getContentResolver().openInputStream(persistentUri);
        } else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            List<String> segments = persistentUri.getPathSegments();
            if (segments != null && segments.size() > 1
                    && "android_asset".equals(segments.get(0))) {
                StringBuilder assetPath = new StringBuilder();
                for (int i = 1; i < segments.size(); i++) {
                    if (i > 1) {
                        assetPath.append("/");
                    }
                    assetPath.append(segments.get(i));
                }
                in = context.getAssets().open(assetPath.toString());
            } else {
                in = new FileInputStream(new File(persistentUri.getPath()));
            }
        } else if ("http".equals(scheme) || "https".equals(scheme)) {
            URL url = new URL(persistentUri.toString());
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            int responseCode = urlConnection.getResponseCode();
            if (!(responseCode >= 200 && responseCode < 300)) {
                throw new IOException("HTTP error response " + responseCode);
            }
            in = urlConnection.getInputStream();
        }
        if (in == null) {
            throw new FileNotFoundException("Null input stream for URI: " + persistentUri);
        }
        return in;
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final int DATABASE_VERSION = 1;

        /**
         * Creates a new DatabaseHelper
         *
         * @param context context of this database
         */
        DatabaseHelper(final Context context, String databaseName) {
            super(context, databaseName, null, DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with table name and column names taken from the
         * MuzeiContract class.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + ProviderContract.Artwork.TOKEN + " TEXT,"
                    + ProviderContract.Artwork.TITLE + " TEXT,"
                    + ProviderContract.Artwork.BYLINE + " TEXT,"
                    + ProviderContract.Artwork.ATTRIBUTION + " TEXT,"
                    + ProviderContract.Artwork.PERSISTENT_URI + " TEXT,"
                    + ProviderContract.Artwork.WEB_URI + " TEXT,"
                    + ProviderContract.Artwork.METADATA + " TEXT,"
                    + ProviderContract.Artwork.DATA + " TEXT,"
                    + ProviderContract.Artwork.DATE_ADDED + " INTEGER NOT NULL,"
                    + ProviderContract.Artwork.DATE_MODIFIED + " INTEGER NOT NULL);");
        }

        /**
         * Upgrades the database.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        }
    }
}
