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

package com.google.android.apps.muzei.api;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.annotation.RequiresPermission;
import android.support.annotation.StringDef;
import android.support.annotation.WorkerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contract between Muzei and applications, containing the definitions for all supported URIs and
 * columns as well as helper methods to make it easier to work with the provided data.
 */
public class MuzeiContract {
    /**
     * To insert new artwork and update their source information, apps must hold this permission by declaring
     * it in their manifest:
     * <pre>
     *     &lt;uses-permission android:name="com.google.android.apps.muzei.WRITE_PROVIDER" /&gt;
     * </pre>
     * If you are using Gradle, this permission is automatically added to your manifest.
     */
    public static final String WRITE_PERMISSION = "com.google.android.apps.muzei.WRITE_PROVIDER";
    /**
     * Base authority for this content provider
     */
    public static final String AUTHORITY = "com.google.android.apps.muzei";
    /**
     * The scheme part for this provider's URI
     */
    private static final String SCHEME = "content://";

    /**
     * This class cannot be instantiated
     */
    private MuzeiContract() {
    }

    /**
     * Constants and helper methods for the Artwork table, providing access to the current artwork.
     *
     * <p>The Artwork table contains the details of the artwork that has been loaded by Muzei.
     * It also provides direct access to the cached image already downloaded by Muzei,
     * ensuring that you do not need to do additional networks requests or have internet access
     * when retrieving previously loaded artwork.
     *
     * <h3>Working with the Artwork table</h3>
     *
     * Querying {@link #CONTENT_URI} will return either zero rows (in cases where the user has never
     * activated Muzei before) or a row for each previously loaded artwork with all of the details needed to create an
     * {@link com.google.android.apps.muzei.api.Artwork Artwork} object. The helper method
     * {@link #getCurrentArtwork(Context)} does builds an Artwork object for the most recent artwork, although
     * you can certainly query the {@link #CONTENT_URI} directly and either use the columns directly or
     * use {@link com.google.android.apps.muzei.api.Artwork#fromCursor(android.database.Cursor) Artwork.fromCursor(Cursor)}
     * to parse the Cursor for you.
     *
     * <p>If instead you use {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream(Uri)},
     * you'll get an InputStream for the cached image Bitmap. This can
     * then be passed to {@link BitmapFactory#decodeStream(java.io.InputStream) BitmapFactory.decodeStream(InputStream)} or
     * similar methods to retrieve the Bitmap itself. The helper method
     * {@link #getCurrentArtworkBitmap(Context)} does this operation, although note
     * that this may return a very large Bitmap so following the
     * <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently training</a>
     * advice is highly suggested.
     *
     * <h3>Listening for changes</h3>
     *
     * Just like any {@link android.content.ContentProvider ContentProvider}, listening for changes can be done by
     * implementing a {@link android.database.ContentObserver ContentObserver} on {@link #CONTENT_URI} or by using
     * any of the helper classes such as {@link android.content.CursorLoader CursorLoader} to listen for updates.
     *
     * <p>However, if you want to receive updates while in the background and do not want to maintain
     * a constantly running {@link android.database.ContentObserver ContentObserver}, you can instead listen for the
     * {@link #ACTION_ARTWORK_CHANGED} broadcast, sent out immediately after an update is made:
     *
     * <pre class="prettyprint">
     * &lt;receiver android:name=".ExampleArtworkUpdateReceiver"&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action android:name="com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED" /&gt;
     *     &lt;/intent-filter&gt;
     * &lt;/receiver&gt;
     * </pre>
     *
     * No data is sent alongside the broadcast, but this can be used to kick off an
     * {@link android.app.IntentService IntentService} to retrieve the latest artwork or start
     * other processing.
     */
    public static final class Artwork implements BaseColumns {
        /**
         * The set of valid font types to use to display artwork meta info.
         *
         * @see #COLUMN_NAME_META_FONT
         * @see #META_FONT_TYPE_DEFAULT
         * @see #META_FONT_TYPE_ELEGANT
         */
        @Retention(RetentionPolicy.SOURCE)
        @StringDef({META_FONT_TYPE_DEFAULT, META_FONT_TYPE_ELEGANT})
        public @interface MetaFontType {}
        /**
         * The default font type for {@link #COLUMN_NAME_META_FONT}
         */
        public static final String META_FONT_TYPE_DEFAULT = "";
        /**
         * An elegant alternate font type for {@link #COLUMN_NAME_META_FONT}
         */
        public static final String META_FONT_TYPE_ELEGANT = "elegant";
        /**
         * Column name for the flattened {@link ComponentName} of the source that is providing
         * this wallpaper
         * <p>Type: TEXT in the format of {@link ComponentName#flattenToShortString()}
         */
        public static final String COLUMN_NAME_SOURCE_COMPONENT_NAME = "sourceComponentName";
        /**
         * Column name of the artwork image URI. In almost all cases you should use
         * {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream(CONTENT_URI)}
         * to retrieve the already downloaded artwork.
         * <p>Type: TEXT (URI)
         */
        public static final String COLUMN_NAME_IMAGE_URI = "imageUri";
        /**
         * Column name for the artwork's title.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_TITLE = "title";
        /**
         * Column name for the artwork's byline (such as author and date).
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_BYLINE = "byline";
        /**
         * Column name for the artwork's attribution info.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_ATTRIBUTION = "attribution";
        /**
         * Column name for the artwork's opaque application-specific identifier.
         * This is generally only useful to the app that published the artwork and should
         * not be relied upon by other apps.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_TOKEN = "token";
        /**
         * Column name for the artwork's view Intent, encoded via
         * {@link android.content.Intent#toUri(int) Intent.toUri(Intent.URI_INTENT_SCHEME)} and can
         * be decoded via
         * {@link android.content.Intent#parseUri(String, int) Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME)}
         * <p>Type: TEXT (Intent encoded URI)
         */
        public static final String COLUMN_NAME_VIEW_INTENT = "viewIntent";
        /**
         * Column name for the font type to use to display artwork meta info.
         * <p>Type: TEXT (one of {@link #META_FONT_TYPE_DEFAULT} or {@link #META_FONT_TYPE_ELEGANT})
         */
        public static final String COLUMN_NAME_META_FONT = "metaFont";
        /**
         * Column name for when this artwork was added.
         * This will be automatically added for you by Muzei.
         * <p>Type: LONG (in milliseconds)
         */
        public static final String COLUMN_NAME_DATE_ADDED = "date_added";
        /**
         * The MIME type of {@link #CONTENT_URI} providing artwork.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.artwork";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a single piece of artwork.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.android.apps.muzei.artwork";
        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = COLUMN_NAME_DATE_ADDED + " DESC";
        /**
         * The table name offered by this provider.
         */
        public static final String TABLE_NAME = "artwork";

        /**
         * This class cannot be instantiated
         */
        private Artwork() {
        }

        /**
         * The content:// style URL for this table. This is the main entry point for queries and for
         * opening an {@link java.io.InputStream InputStream} to the current artwork's image.
         * <p>
         * All apps can {@link ContentResolver#query query} for artwork, but only apps holding
         * {@link #WRITE_PERMISSION} can {@link ContentResolver#insert insert} new artwork.
         *
         * @see #getCurrentArtwork
         * @see #getCurrentArtworkBitmap
         * @see #createArtwork
         */
        @RequiresPermission.Write(@RequiresPermission(WRITE_PERMISSION))
        public static final Uri CONTENT_URI = Uri.parse(MuzeiContract.SCHEME + MuzeiContract.AUTHORITY
                + "/" + Artwork.TABLE_NAME);
        /**
         * Intent action that will be broadcast when the artwork is changed. This happens immediately after the
         * ContentProvider is updated with data and should be considered the signal that you can retrieve the new
         * artwork.
         */
        public static final String ACTION_ARTWORK_CHANGED = "com.google.android.apps.muzei.ACTION_ARTWORK_CHANGED";

        /**
         * Create a new {@link ArtworkPublishRequest} associated with the given {@link MuzeiArtSource}.
         * You must call one of the <code>publish()</code> methods on the returned {@link ArtworkPublishRequest}
         * to complete uploading the artwork.
         *
         * @param context Context used to publish the artwork
         * @param source {@link MuzeiArtSource} that this artwork should be associated with
         *
         * @return a {@link ArtworkPublishRequest} that should be filled in and then used to <code>publish</code>
         * the artwork
         */
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        public static ArtworkPublishRequest createArtwork(Context context,
                                                          Class<? extends MuzeiArtSource> source) {
            return new ArtworkPublishRequest(context, new ComponentName(context, source));
        }

        /**
         * Create a new {@link ArtworkPublishRequest} associated with the given {@link MuzeiArtSource}.
         * You must call one of the <code>publish()</code> methods on the returned {@link ArtworkPublishRequest}
         * to complete uploading the artwork.
         *
         * @param context Context used to publish the artwork
         * @param source {@link MuzeiArtSource} that this artwork should be associated with
         *
         * @return a {@link ArtworkPublishRequest} that should be filled in and then used to <code>publish</code>
         * the artwork
         */
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        public static ArtworkPublishRequest createArtwork(@NonNull Context context, @NonNull ComponentName source) {
            return new ArtworkPublishRequest(context, source);
        }

        /**
         * Class which allows you to set the optional fields associated with a piece of artwork in preparation for
         * publishing with one of the <code>publish()</code> methods.
         *
         * <p> The <code>publish()</code> methods cannot be called on the main thread since they directly read
         * and write the image data to the ContentProviders set in {@link #contentAuthorities}.
         *
         * <p> Create an artwork publish request with {@link #createArtwork(Context, Class)} or
         * {@link #createArtwork(Context, ComponentName)}.
         *
         * <p> All methods are optional and can be chained together as seen below, although it is strongly recommended
         * to at least set a {@link #title} and {@link #byline}.
         *
         * <pre class="prettyprint">
         * MuzeiContract.Artwork.createArtwork(context, ExampleArtSource.class)
         *     .title("Example image")
         *     .byline("Unknown person, c. 1980")
         *     .viewIntent(new Intent(Intent.ACTION_VIEW,
         *         Uri.parse("http://example.com/imagedetails.html")))
         *     .publish(Uri.parse("http://example.com/image.jpg"));
         * </pre>
         */
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        public static class ArtworkPublishRequest {
            private static final int DEFAULT_READ_TIMEOUT = 30000; // in milliseconds
            private static final int DEFAULT_CONNECT_TIMEOUT = 15000; // in milliseconds

            private final ContentResolver contentResolver;
            private final AssetManager assetManager;
            private final ContentValues values = new ContentValues();
            private final ArrayList<Uri> contentUris = new ArrayList<>();

            ArtworkPublishRequest(@NonNull Context context, Class<? extends MuzeiArtSource> source) {
                this(context, new ComponentName(context, source));
            }

            ArtworkPublishRequest(@NonNull Context context, @NonNull ComponentName source) {
                contentResolver = context.getContentResolver();
                assetManager = context.getAssets();
                values.put(Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME,
                        source.flattenToShortString());
                // Default to writing to only the standard Muzei CONTENT_URI
                contentUris.add(CONTENT_URI);
            }

            /**
             * Override the default authority of {@link MuzeiContract#AUTHORITY} with a custom set.
             * The artwork will be written to all of the provided ContentProviders.
             *
             * @param authorities the list of authorities to write the artwork to. Defaults to only
             *                    {@link MuzeiContract#AUTHORITY}.
             *
             * @return this {@link ArtworkPublishRequest}.
             */
            public ArtworkPublishRequest contentAuthorities(String... authorities) {
                contentUris.clear();
                for (String authority : authorities) {
                    contentUris.add(Uri.parse(SCHEME + authority + "/" + Artwork.TABLE_NAME));
                }
                return this;
            }

            /**
             * Sets the artwork's user-visible title.
             *
             * @param title the artwork's user-visible title.
             *
             * @return this {@link ArtworkPublishRequest}.
             */
            public ArtworkPublishRequest title(String title) {
                values.put(Artwork.COLUMN_NAME_TITLE, title);
                return this;
            }

            /**
             * Sets the artwork's user-visible byline, usually containing the author and date.
             * This is generally used as a secondary source of information after the
             * {@link #title title}.
             *
             * @param byline the artwork's user-visible byline.
             *
             * @return this {@link ArtworkPublishRequest}.
             */
            public ArtworkPublishRequest byline(String byline) {
                values.put(Artwork.COLUMN_NAME_BYLINE, byline);
                return this;
            }

            /**
             * Sets the artwork's user-visible attribution text.
             * This is generally used as a tertiary source of information after the
             * {@link #title title} and the {@link #byline byline}.
             *
             * @param attribution the artwork's user-visible attribution text.
             *
             * @return this {@link ArtworkPublishRequest}.
             */
            public ArtworkPublishRequest attribution(String attribution) {
                values.put(Artwork.COLUMN_NAME_ATTRIBUTION, attribution);
                return this;
            }

            /**
             * Sets the artwork's opaque application-specific identifier.
             *
             * <p> If this is non-null, it will be used to
             * de-duplicate artwork published with {@link #publish(Bitmap)} or {@link #publish(InputStream)}
             *
             * @param token the artwork's opaque application-specific identifier.
             *
             * @return this {@link ArtworkPublishRequest}.
             */
            public ArtworkPublishRequest token(String token) {
                values.put(Artwork.COLUMN_NAME_TOKEN, token);
                return this;
            }

            /**
             * Sets the activity {@link Intent} that will be
             * {@linkplain Context#startActivity(Intent) started} when
             * the user clicks for more details about the artwork.
             *
             * <p> The activity that this intent resolves to must have <code>android:exported</code>
             * set to <code>true</code>.
             *
             * <p> Muzei will automatically add {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}
             * to allow reading any  attached data URI, but note that all extras will be lost
             * when Muzei uses {@link Intent#toUri(int)} to serialize the Intent.
             *
             * <p> Because artwork objects can be persisted across device reboots,
             * {@linkplain android.app.PendingIntent pending intents}, which would alleviate the
             * exported requirement, are not currently supported.
             *
             * @param viewIntent the activity {@link Intent} that will be
             * {@linkplain Context#startActivity(Intent) started} when the user clicks
             * for more details about the artwork.
             *
             * @return this {@link ArtworkPublishRequest}.
             */
            public ArtworkPublishRequest viewIntent(Intent viewIntent) {
                if (viewIntent != null) {
                    values.put(Artwork.COLUMN_NAME_VIEW_INTENT,
                            viewIntent.toUri(Intent.URI_INTENT_SCHEME));
                } else {
                    values.remove(Artwork.COLUMN_NAME_VIEW_INTENT);
                }
                return this;
            }

            /**
             * Sets the font type to use for showing metadata. If unset,
             * {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT}
             * will be used by default.
             *
             * @param metaFont the font type to use for showing metadata.
             *
             * @return this {@link ArtworkPublishRequest}.
             *
             * @see MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT
             * @see MuzeiContract.Artwork#META_FONT_TYPE_ELEGANT
             */
            public ArtworkPublishRequest metaFont(@MetaFontType String metaFont) {
                values.put(Artwork.COLUMN_NAME_META_FONT, metaFont);
                return this;
            }

            /**
             * Load the artwork from an image URI, which must resolve to a JPEG or PNG image, ideally
             * under 5MB. Supported URI schemes are:
             *
             * <ul>
             * <li><code>content://...</code>.</li>
             * <li><code>file://...</code>.</li>
             * <li><code>file:///android_asset/...</code>.</li>
             * <li><code>http://...</code> or <code>https://...</code>.</li>
             * </ul>
             *
             * <p> If Muzei has previously loaded from the same imageUri, it will reuse that image rather than
             * load the image again.
             *
             * <p> If you need more control over loading the image, use {@link #publish(InputStream)}.
             *
             * <p> This method must not be called on the main thread since it directly writes the Bitmap data
             * to the ContentProviders set in {@link #contentAuthorities}.
             *
             * @param imageUri the URI of the image to load.
             *
             * @throws IOException if an error occurs while publishing the image.
             */
            @RequiresPermission(WRITE_PERMISSION)
            @WorkerThread
            public void publish(@NonNull Uri imageUri) throws IOException {
                values.put(Artwork.COLUMN_NAME_IMAGE_URI, imageUri.toString());
                try (InputStream in = openUri(imageUri)) {
                    publish(in);
                }
            }

            /**
             * Load the artwork from a Bitmap.
             *
             * <p> Each Bitmap is considered a unique image (i.e., never published prior). To de-duplicate
             * the same Bitmap, set the same {@link #token} with each publish. This will cause Muzei to reuse
             * that image rather than load the image again.
             *
             * <p> This method must not be called on the main thread since it directly writes the Bitmap data
             * to the ContentProviders set in {@link #contentAuthorities}.
             *
             * @param bitmap the artwork to display.
             *
             * @throws IOException if an error occurs while publishing the image.
             */
            @RequiresPermission(WRITE_PERMISSION)
            @WorkerThread
            public void publish(@NonNull Bitmap bitmap) throws IOException {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    throw new IllegalStateException("publish cannot be called on the main thread");
                }
                for (Uri contentUri : contentUris) {
                    Uri artworkUri = contentResolver.insert(contentUri, values);
                    try (OutputStream out = contentResolver.openOutputStream(artworkUri)) {
                        if (out == null) {
                            continue;
                        }
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    }
                }
            }

            /**
             * Load the artwork from an InputStream.
             *
             * <p> Each InputStream is considered a unique image (i.e., never published prior). To de-duplicate
             * the same InputStream, set the same {@link #token} with each publish. This will cause Muzei to reuse
             * that image rather than load the image again.
             *
             * <p> This method must not be called on the main thread since it directly writes the Bitmap data
             * to the ContentProviders set in {@link #contentAuthorities}.
             *
             * @param in An InputStream pointing to a valid JPEG or PNG image.
             *
             * @throws IOException if an error occurs while publishing the image.
             */
            @RequiresPermission(WRITE_PERMISSION)
            @WorkerThread
            public void publish(@NonNull InputStream in) throws IOException {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    throw new IllegalStateException("publish cannot be called on the main thread");
                }
                for (Uri contentUri : contentUris) {
                    Uri artworkUri = contentResolver.insert(contentUri, values);
                    try (OutputStream out = contentResolver.openOutputStream(artworkUri)) {
                        if (out == null) {
                            continue;
                        }
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) > 0) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                    }
                }
            }

            private InputStream openUri(@NonNull Uri uri)
                    throws IOException {
                String scheme = uri.getScheme();
                if (scheme == null) {
                    throw new IOException("Uri had no scheme");
                }

                InputStream in = null;
                if ("content".equals(scheme)) {
                    try {
                        in = contentResolver.openInputStream(uri);
                    } catch (SecurityException e) {
                        throw new FileNotFoundException("No access to " + uri + ": " + e.toString());
                    }

                } else if ("file".equals(scheme)) {
                    List<String> segments = uri.getPathSegments();
                    if (segments != null && segments.size() > 1
                            && "android_asset".equals(segments.get(0))) {
                        StringBuilder assetPath = new StringBuilder();
                        for (int i = 1; i < segments.size(); i++) {
                            if (i > 1) {
                                assetPath.append("/");
                            }
                            assetPath.append(segments.get(i));
                        }
                        in = assetManager.open(assetPath.toString());
                    } else {
                        in = new FileInputStream(new File(uri.getPath()));
                    }

                } else if ("http".equals(scheme) || "https".equals(scheme)) {
                    URL url = new URL(uri.toString());
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setReadTimeout(DEFAULT_READ_TIMEOUT);
                    conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
                    conn.connect();
                    int responseCode = conn.getResponseCode();
                    if (!(responseCode >= 200 && responseCode < 300)) {
                        throw new IOException("HTTP error response " + responseCode);
                    }
                    in = conn.getInputStream();
                }

                if (in == null) {
                    throw new FileNotFoundException("Null input stream for URI: " + uri);
                }

                return in;
            }
        }

        /**
         * Returns the current Muzei {@link com.google.android.apps.muzei.api.Artwork Artwork}.
         *
         * @param context the context to retrieve a ContentResolver.
         *
         * @return the current {@link com.google.android.apps.muzei.api.Artwork Artwork}
         * or null if one could not be found.
         */
        public static com.google.android.apps.muzei.api.Artwork getCurrentArtwork(Context context) {
            ContentResolver contentResolver = context.getContentResolver();
            Cursor cursor = contentResolver.query(CONTENT_URI, null, null, null, null);
            try {
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                return com.google.android.apps.muzei.api.Artwork.fromCursor(cursor);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        /**
         * Naively gets the current artwork image without any subsampling or optimization for output size
         * <p>Note that this may return a very large Bitmap so following the
         * <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently training</a>
         * is highly recommended.
         *
         * @param context the context to retrieve a ContentResolver.
         *
         * @return A Bitmap of the current artwork or null if the image could not be decoded.
         *
         * @throws FileNotFoundException If no cached artwork image was found.
         */
        @WorkerThread
        public static Bitmap getCurrentArtworkBitmap(Context context) throws FileNotFoundException {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw new IllegalStateException("getCurrentArtworkBitmap cannot be called on the main thread");
            }
            ContentResolver contentResolver = context.getContentResolver();
            return BitmapFactory.decodeStream(contentResolver.openInputStream(CONTENT_URI));
        }
    }

    /**
     * Constants and helper methods for the Sources table, providing access to sources' information.
     */
    public static final class Sources implements BaseColumns {
        /**
         * Column name for the flattened {@link ComponentName} of this source
         * <p>Type: TEXT in the format of {@link ComponentName#flattenToShortString()}
         */
        public static final String COLUMN_NAME_COMPONENT_NAME = "component_name";
        /**
         * Column name for the flag indicating if the source is currently selected
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_NAME_IS_SELECTED = "selected";
        /**
         * Column name for the source's description.
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_DESCRIPTION = "description";
        /**
         * Column name for the flag indicating if the source wants callbacks for network connectivity changes
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_NAME_WANTS_NETWORK_AVAILABLE = "network";
        /**
         * Column name for the flag indicating if the source supports a 'Next Artwork' action
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND = "supports_next_artwork";
        /**
         * Column name for the commands the source supports
         * <p>Type: TEXT
         */
        public static final String COLUMN_NAME_COMMANDS = "commands";
        /**
         * The MIME type of {@link #CONTENT_URI} providing sources.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.android.apps.muzei.source";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a single source.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.android.apps.muzei.source";
        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = Sources.COLUMN_NAME_IS_SELECTED + " DESC," +
                Sources.COLUMN_NAME_COMPONENT_NAME;
        /**
         * The table name offered by this provider.
         */
        public static final String TABLE_NAME = "sources";

        /**
         * This class cannot be instantiated
         */
        private Sources() {
        }

        /**
         * The content:// style URL for this table.
         * <p>
         * All apps can {@link ContentResolver#query query} for source info, but only apps holding
         * {@link #WRITE_PERMISSION} can {@link ContentResolver#update update} their source info.
         *
         * @see #updateSource
         */
        @RequiresPermission.Write(@RequiresPermission(WRITE_PERMISSION))
        public static final Uri CONTENT_URI = Uri.parse(MuzeiContract.SCHEME + MuzeiContract.AUTHORITY
                + "/" + Sources.TABLE_NAME);
        /**
         * Intent action that will be broadcast when the source info is changed. This happens immediately after the
         * ContentProvider is updated with data and should be considered the signal that you can retrieve the new
         * source info.
         */
        public static final String ACTION_SOURCE_CHANGED = "com.google.android.apps.muzei.ACTION_SOURCE_CHANGED";

        /**
         * Create a new {@link SourceUpdateRequest} associated with the given {@link MuzeiArtSource}.
         * You must call {@link SourceUpdateRequest#send()} on the returned {@link SourceUpdateRequest}
         * to complete updating the source information.
         *
         * @param context Context used to update the source
         * @param source {@link MuzeiArtSource} for this source
         *
         * @return a {@link SourceUpdateRequest} that should be filled in and then used to
         * {@link SourceUpdateRequest#send()} the source update
         */
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        public static SourceUpdateRequest updateSource(@NonNull Context context,
                Class<? extends MuzeiArtSource> source) {
            return new SourceUpdateRequest(context, new ComponentName(context, source));
        }

        /**
         * Create a new {@link SourceUpdateRequest} associated with the given {@link MuzeiArtSource}.
         * You must call {@link SourceUpdateRequest#send()} on the returned {@link SourceUpdateRequest}
         * to complete updating the source information.
         *
         * @param context Context used to update the source
         * @param source {@link MuzeiArtSource} for this source
         *
         * @return a {@link SourceUpdateRequest} that should be filled in and then used to
         * {@link SourceUpdateRequest#send()} the source update
         */
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        public static SourceUpdateRequest updateSource(@NonNull Context context, @NonNull ComponentName source) {
            return new SourceUpdateRequest(context, source);
        }

        /**
         * Class which allows you to set the optional source information fields before calling
         * {@link #send()}.
         *
         * <p> {@link #send()} should not be called on the main thread since it directly
         * writes to the ContentProviders set in {@link #contentAuthorities}.
         *
         * <p> Create a source update request with {@link #updateSource(Context, Class)} or
         * {@link #updateSource(Context, ComponentName)}.
         *
         * <p> All methods are optional and can be chained together as seen below.
         *
         * <pre class="prettyprint">
         * MuzeiContract.Sources.updateSource(context, ExampleArtSource.class)
         *     .description("Popular photos tagged \"landscape\"")
         *     .supportsNextArtworkCommand(true)
         *     .send();
         * </pre>
         */
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        public static class SourceUpdateRequest {
            private final ContentResolver contentResolver;
            private final ContentValues values = new ContentValues();
            private final ArrayList<Uri> contentUris = new ArrayList<>();

            SourceUpdateRequest(Context context, ComponentName source) {
                contentResolver = context.getContentResolver();
                values.put(Sources.COLUMN_NAME_COMPONENT_NAME,
                        source.flattenToShortString());
                // Default to writing to only the standard Muzei CONTENT_URI
                contentUris.add(CONTENT_URI);
            }

            /**
             * Override the default authority of {@link MuzeiContract#AUTHORITY} with a custom set.
             * The updated source information will be written to all of the provided ContentProviders.
             *
             * @param authorities the list of authorities to update with the new the source information.
             *                    Defaults to only {@link MuzeiContract#AUTHORITY}
             *
             * @return this {@link SourceUpdateRequest}.
             */
            public SourceUpdateRequest contentAuthorities(String... authorities) {
                contentUris.clear();
                for (String authority : authorities) {
                    contentUris.add(Uri.parse(SCHEME + authority + "/" + Sources.TABLE_NAME));
                }
                return this;
            }

            /**
             * Sets the current source description of the current configuration. For example, 'Popular photos
             * tagged "landscape"'). If no description is provided, the <code>android:description</code>
             * element of the source's service element in the manifest will be used.
             *
             * @param description the new description to be shown when the source is selected.
             *
             * @return this {@link SourceUpdateRequest}.
             */
            public SourceUpdateRequest description(String description) {
                values.put(Sources.COLUMN_NAME_DESCRIPTION, description);
                return this;
            }

            /**
             * Indicates that the source is interested (or no longer interested) in getting notified via
             * {@link MuzeiArtSource#onNetworkAvailable()} when a network connection becomes available.
             *
             * <p> Note that this request will be ignored if you target
             * {@link android.os.Build.VERSION_CODES#N} or higher to mirror the
             * <a href="https://developer.android.com/about/versions/nougat/android-7.0-changes.html#bg-opt">
             *     Background Optimizations changes in Android 7.0</a>.
             *
             * @param wantsNetworkAvailable if the source wants to be notified when a network
             *                              connection becomes available.
             *
             * @return this {@link SourceUpdateRequest}.
             */
            public SourceUpdateRequest wantsNetworkAvailable(boolean wantsNetworkAvailable) {
                values.put(Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE, wantsNetworkAvailable);
                return this;
            }

            /**
             * Sets whether the source supports the built in 'Next Artwork' command which allows
             * users to quickly move to the next artwork.
             *
             * @param supportsNextArtworkCommand if the source supports the 'Next Artwork' built in command.
             *
             * @return this {@link SourceUpdateRequest}.
             */
            public SourceUpdateRequest supportsNextArtworkCommand(boolean supportsNextArtworkCommand) {
                values.put(Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND, supportsNextArtworkCommand);
                return this;
            }

            /**
             * Sets the list of custom defined user-visible commands for the source.
             * Custom commands must have identifiers below {@link MuzeiArtSource#MAX_CUSTOM_COMMAND_ID}.
             *
             * @param commands the new set of user-visible commands the source supports.
             *
             * @return this {@link SourceUpdateRequest}.
             *
             * @see MuzeiArtSource#MAX_CUSTOM_COMMAND_ID
             * @see #supportsNextArtworkCommand(boolean)
             */
            public SourceUpdateRequest userCommands(UserCommand... commands) {
                return userCommands(Arrays.asList(commands));
            }

            /**
             * Sets the list of custom defined user-visible commands for the source.
             * Custom commands must have identifiers below {@link MuzeiArtSource#MAX_CUSTOM_COMMAND_ID}.
             *
             * @param commands the new set of user-visible commands the source supports.
             *
             * @return this {@link SourceUpdateRequest}.
             *
             * @see MuzeiArtSource#MAX_CUSTOM_COMMAND_ID
             * @see #supportsNextArtworkCommand(boolean)
             */
            public SourceUpdateRequest userCommands(List<UserCommand> commands) {
                JSONArray commandsSerialized = new JSONArray();
                for (UserCommand command : commands) {
                    if (command.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                        // Built in commands shouldn't be in this list, but we'll handle it
                        // as a convenience
                        supportsNextArtworkCommand(true);
                    } else if (command.getId() > MuzeiArtSource.MAX_CUSTOM_COMMAND_ID) {
                        throw new IllegalArgumentException("Command " + command.getTitle() +
                                " has a ID of " + command.getId() +
                                " which is higher than the maximum value of " +
                                MuzeiArtSource.MAX_CUSTOM_COMMAND_ID);
                    } else {
                        commandsSerialized.put(command.serialize());
                    }
                }
                values.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS, commandsSerialized.toString());
                return this;
            }

            /**
             * Send the updated source information to the set {@link #contentAuthorities content authorities}.
             */
            public void send() {
                for (Uri contentUri : contentUris) {
                    contentResolver.update(contentUri, values,
                            Sources.COLUMN_NAME_COMPONENT_NAME + "=?",
                            new String[] {values.getAsString(Sources.COLUMN_NAME_COMPONENT_NAME)});
                }
            }
        }

        /**
         * Parse the commands found in the {@link #COLUMN_NAME_COMMANDS} field into a List of {@link UserCommand}s.
         *
         * @param commandsString The serialized commands found in {@link #COLUMN_NAME_COMMANDS}.
         *
         * @return A deserialized List of {@link UserCommand}s.
         */
        @NonNull
        public static List<UserCommand> parseCommands(String commandsString) {
            ArrayList<UserCommand> commands = new ArrayList<>();
            if (commandsString == null) {
                return commands;
            }
            try {
                JSONArray commandArray = new JSONArray(commandsString);
                for (int h=0; h<commandArray.length(); h++) {
                    commands.add(UserCommand.deserialize(commandArray.getString(h)));
                }
            } catch (JSONException e) {
                Log.e(MuzeiContract.Sources.class.getSimpleName(), "Error parsing commands from " + commandsString, e);
            }
            return commands;
        }
    }
}
