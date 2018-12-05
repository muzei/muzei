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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.ATTRIBUTION;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.BYLINE;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATA;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATE_ADDED;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.DATE_MODIFIED;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.METADATA;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.PERSISTENT_URI;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.TITLE;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.TOKEN;
import static com.google.android.apps.muzei.api.provider.ProviderContract.Artwork.WEB_URI;

/**
 * Artwork associated with a {@link MuzeiArtProvider}.
 * <p>
 * Artwork can be constructed using the empty constructor and the setter
 * methods found on the Artwork object itself or by using the {@link Builder}.
 * </p>
 * <p>
 * Artwork can then be added to a {@link MuzeiArtProvider} by calling
 * {@link MuzeiArtProvider#addArtwork(Artwork) addArtwork(Artwork)} directly
 * from within a MuzeiArtProvider or by creating a {@link ProviderClient} and calling
 * {@link ProviderClient#addArtwork(Artwork)} from anywhere in your application.
 * </p>
 * <p>
 * The static {@link Artwork#fromCursor(Cursor)} method allows you to convert
 * a row retrieved from a {@link MuzeiArtProvider} into Artwork instance.
 */
public class Artwork {
    private static DateFormat DATE_FORMAT;

    private static DateFormat getDateFormat() {
        if (DATE_FORMAT == null) {
            DATE_FORMAT = SimpleDateFormat.getDateTimeInstance();
        }
        return DATE_FORMAT;
    }

    private long id;
    private String token;
    private String title;
    private String byline;
    private String attribution;
    private Uri persistentUri;
    private Uri webUri;
    private String metadata;
    private File data;
    private Date dateAdded;
    private Date dateModified;

    /**
     * Creates an empty Artwork instance.
     */
    public Artwork() {
    }

    /**
     * Returns the ID assigned to this Artwork by its {@link MuzeiArtProvider}.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the artwork's ID.
     */
    public long getId() {
        return id;
    }

    void setId(long id) {
        this.id = id;
    }

    /**
     * Returns the token that uniquely defines the artwork.
     *
     * @return the artwork's unique token, or null if it doesn't have one.
     * @see Builder#token
     */
    @Nullable
    public String getToken() {
        return token;
    }

    /**
     * Sets the artwork's opaque application-specific identifier.
     *
     * @param token the artwork's opaque application-specific identifier.
     */
    public void setToken(@Nullable String token) {
        this.token = token;
    }

    /**
     * Returns the artwork's user-visible title.
     *
     * @return the artwork's user-visible title, or null if it doesn't have one.
     * @see Builder#title
     */
    @Nullable
    public String getTitle() {
        return title;
    }

    /**
     * Sets the artwork's user-visible title.
     *
     * @param title the artwork's user-visible title.
     */
    public void setTitle(@Nullable String title) {
        this.title = title;
    }

    /**
     * Returns the artwork's user-visible byline, usually containing the author and date.
     * This is generally used as a secondary source of information after the
     * {@link #getTitle title}.
     *
     * @return the artwork's user-visible byline, or null if it doesn't have one.
     */
    @Nullable
    public String getByline() {
        return byline;
    }

    /**
     * Sets the artwork's user-visible byline, usually containing the author and date.
     * This is generally used as a secondary source of information after the
     * {@link #getTitle title}.
     *
     * @param byline the artwork's user-visible byline.
     */
    public void setByline(@Nullable String byline) {
        this.byline = byline;
    }

    /**
     * Returns the artwork's user-visible attribution text.
     * This is generally used as a tertiary source of information after the
     * {@link #getTitle title} and the {@link #getByline byline}.
     *
     * @return the artwork's user-visible attribution text, or null if it doesn't have any.
     */
    @Nullable
    public String getAttribution() {
        return attribution;
    }

    /**
     * Sets the artwork's user-visible attribution text.
     * This is generally used as a tertiary source of information after the
     * {@link #getTitle title} and the {@link #getByline byline}.
     *
     * @param attribution the artwork's user-visible attribution text.
     */
    public void setAttribution(@Nullable String attribution) {
        this.attribution = attribution;
    }

    /**
     * Returns the artwork's persistent URI.
     * This is used to redownload the artwork automatically if the cache is cleared.
     *
     * @return the artwork's persistent URI, or null if it doesn't have any.
     */
    @Nullable
    public Uri getPersistentUri() {
        return persistentUri;
    }

    /**
     * Sets the artwork's persistent URI, which must resolve to a JPEG or PNG image, ideally
     * under 5MB.
     * <p>When a persistent URI is present, your {@link MuzeiArtProvider} will store
     * downloaded images in the {@link Context#getCacheDir() cache directory} and automatically
     * re-download the image as needed. If it is not present, then you must write the image
     * directly  to the {@link MuzeiArtProvider} with
     * {@link android.content.ContentResolver#openOutputStream(Uri)} and the images will be
     * stored in the {@link Context#getFilesDir()} as it assumed that there is no
     * way to re-download the artwork.
     *
     * @param persistentUri the artwork's persistent URI. Your app should have long-lived
     *                      access to this URI.
     * @see MuzeiArtProvider#openFile(Artwork)
     */
    public void setPersistentUri(@Nullable Uri persistentUri) {
        this.persistentUri = persistentUri;
    }

    /**
     * Returns the artwork's web URI.
     * This is used by default in {@link MuzeiArtProvider#openArtworkInfo(Artwork)} to
     * allow the user to view more details about the artwork.
     *
     * @return the artwork's web URI, or null if it doesn't exist
     */
    @Nullable
    public Uri getWebUri() {
        return webUri;
    }

    /**
     * Sets the artwork's web URI. This is used by default in
     * {@link MuzeiArtProvider#openArtworkInfo(Artwork)} to allow the user to view more details
     * about the artwork.
     *
     * @param webUri a Uri to more details about the artwork.
     * @see MuzeiArtProvider#openArtworkInfo(Artwork)
     */
    public void setWebUri(@Nullable Uri webUri) {
        this.webUri = webUri;
    }

    /**
     * Returns the provider specific metadata about the artwork.
     * This is not used by Muzei at all, so can contain any data that makes it easier to query
     * or otherwise work with your Artwork.
     *
     * @return the artwork's metadata, or null if it doesn't exist
     */
    @Nullable
    public String getMetadata() {
        return metadata;
    }

    /**
     * Sets the provider specific metadata about the artwork.
     * This is not used by Muzei at all, so can contain any data that makes it easier to query
     * or otherwise work with your Artwork.
     *
     * @param metadata any provider specific data associated with the artwork
     */
    public void setMetadata(@Nullable String metadata) {
        this.metadata = metadata;
    }

    /**
     * Returns the {@link File} where a local copy of this artwork is stored.
     * When first inserted, this file most certainly does not exist and will be
     * created from the InputStream returned by {@link MuzeiArtProvider#openFile(Artwork)}.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the local {@link File} where the artwork will be stored
     * @throws IllegalStateException if the Artwork was not retrieved from a
     * {@link MuzeiArtProvider}
     */
    @NonNull
    public File getData() {
        if (dateAdded == null) {
            throw new IllegalStateException("Only Artwork retrieved from a MuzeiArtProvider "
                    + "has a data File");
        }
        return data;
    }

    void setData(@NonNull File data) {
        this.data = data;
    }

    /**
     * Returns the date this artwork was initially added to its {@link MuzeiArtProvider}.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the date this artwork was initially added
     * @throws IllegalStateException if the Artwork was not retrieved from a
     * {@link MuzeiArtProvider}
     */
    @NonNull
    public Date getDateAdded() {
        if (dateAdded == null) {
            throw new IllegalStateException("Only Artwork retrieved from a MuzeiArtProvider "
                    + "has a date added");
        }
        return dateAdded;
    }

    void setDateAdded(@NonNull Date dateAdded) {
        this.dateAdded = dateAdded;
    }

    /**
     * Returns the date of the last modification of the artwork (i.e., the last time it was
     * updated). This will initially be equal to the {@link #getDateAdded() date added}.
     * <p>
     * Note: this will only be available if the artwork is retrieved from a
     * {@link MuzeiArtProvider}.
     *
     * @return the date this artwork was last modified
     * @throws IllegalStateException if the Artwork was not retrieved from a
     * {@link MuzeiArtProvider}
     */
    @NonNull
    public Date getDateModified() {
        if (dateAdded == null) {
            throw new IllegalStateException("Only Artwork retrieved from a MuzeiArtProvider "
                    + "has a date modified");
        }
        return dateModified;
    }

    void setDateModified(@NonNull Date dateModified) {
        this.dateModified = dateModified;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Artwork #");
        sb.append(id);

        if (token != null && !token.isEmpty() && (persistentUri == null ||
                !persistentUri.toString().equals(token))) {
            sb.append("+");
            sb.append(token);
        }
        sb.append(" (");
        sb.append(persistentUri);
        if (persistentUri != null && !persistentUri.equals(webUri)) {
            sb.append(", ");
            sb.append(webUri);
        }
        sb.append(")");
        sb.append(": ");
        boolean appended = false;
        if (title != null && !title.isEmpty()) {
            sb.append(title);
            appended = true;
        }
        if (byline != null && !byline.isEmpty()) {
            if (appended) {
                sb.append(" by ");
            }
            sb.append(byline);
            appended = true;
        }
        if (attribution != null && !attribution.isEmpty()) {
            if (appended) {
                sb.append(", ");
            }
            sb.append(attribution);
            appended = true;
        }
        if (metadata != null) {
            if (appended) {
                sb.append("; ");
            }
            sb.append("Metadata=");
            sb.append(metadata);
            appended = true;
        }
        if (dateAdded != null) {
            if (appended) {
                sb.append(", ");
            }
            sb.append("Added on ");
            sb.append(getDateFormat().format(dateAdded));
            appended = true;
        }
        if (dateModified != null && !dateModified.equals(dateAdded)) {
            if (appended) {
                sb.append(", ");
            }
            sb.append("Last modified on ");
            sb.append(getDateFormat().format(dateModified));
        }

        return sb.toString();
    }

    /**
     * Converts the current row of the given Cursor to an Artwork object. The
     * assumption is that this Cursor was retrieve from a {@link MuzeiArtProvider}
     * and has the columns listed in {@link ProviderContract.Artwork}.
     *
     * @param data A Cursor retrieved from a {@link MuzeiArtProvider}, already
     *             positioned at the correct row you wish to convert.
     * @return a valid Artwork with values filled in from the
     * {@link ProviderContract.Artwork} columns.
     */
    @NonNull
    public static Artwork fromCursor(@NonNull Cursor data) {
        Artwork artwork =
                new Artwork.Builder()
                        .token(data.getString(data.getColumnIndex(TOKEN)))
                        .title(data.getString(data.getColumnIndex(TITLE)))
                        .byline(data.getString(data.getColumnIndex(BYLINE)))
                        .attribution(data.getString(data.getColumnIndex(ATTRIBUTION)))
                        .metadata(data.getString(data.getColumnIndex(METADATA)))
                        .build();
        String persistentUri = data.getString(data.getColumnIndex(PERSISTENT_URI));
        if (!TextUtils.isEmpty(persistentUri)) {
            artwork.persistentUri = Uri.parse(persistentUri);
        }
        String webUri = data.getString(data.getColumnIndex(WEB_URI));
        if (!TextUtils.isEmpty(webUri)) {
            artwork.webUri = Uri.parse(webUri);
        }
        artwork.id = data.getLong(data.getColumnIndex(BaseColumns._ID));
        artwork.data = new File(data.getString(data.getColumnIndex(DATA)));
        artwork.dateAdded = new Date(data.getLong(data.getColumnIndex(DATE_ADDED)));
        artwork.dateModified = new Date(data.getLong(data.getColumnIndex(DATE_MODIFIED)));
        return artwork;
    }

    @NonNull
    ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(TOKEN, getToken());
        values.put(TITLE, getTitle());
        values.put(BYLINE, getByline());
        values.put(ATTRIBUTION, getAttribution());
        if (getPersistentUri() != null) {
            values.put(PERSISTENT_URI, getPersistentUri().toString());
        }
        if (getWebUri() != null) {
            values.put(WEB_URI, getWebUri().toString());
        }
        values.put(METADATA, getMetadata());
        return values;
    }

    /**
     * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a>-style, <a
     * href="http://en.wikipedia.org/wiki/Fluent_interface">fluent interface</a> for creating
     * {@link Artwork} objects. Example usage is below.
     * <pre class="prettyprint">
     * Artwork artwork = new Artwork.Builder()
     *   .persistentUri(Uri.parse("http://example.com/image.jpg"))
     *   .title("Example image")
     *   .byline("Unknown person, c. 1980")
     *   .attribution("Copyright (C) Unknown person, 1980")
     *   .build();
     * </pre>
     */
    public static class Builder {
        private final Artwork mArtwork;

        public Builder() {
            mArtwork = new Artwork();
        }

        /**
         * Sets the artwork's opaque application-specific identifier.
         *
         * @param token the artwork's opaque application-specific identifier.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder token(@Nullable String token) {
            mArtwork.token = token;
            return this;
        }

        /**
         * Sets the artwork's user-visible title.
         *
         * @param title the artwork's user-visible title.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder title(@Nullable String title) {
            mArtwork.title = title;
            return this;
        }

        /**
         * Sets the artwork's user-visible byline, usually containing the author and date.
         * This is generally used as a secondary source of information after the
         * {@link #title} title}.
         *
         * @param byline the artwork's user-visible byline.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder byline(@Nullable String byline) {
            mArtwork.byline = byline;
            return this;
        }

        /**
         * Sets the artwork's user-visible attribution text.
         * This is generally used as a tertiary source of information after the
         * {@link #title  title} and the {@link #byline byline}.
         *
         * @param attribution the artwork's user-visible attribution text.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder attribution(@Nullable String attribution) {
            mArtwork.attribution = attribution;
            return this;
        }

        /**
         * Sets the artwork's persistent URI, which must resolve to a JPEG or PNG image, ideally
         * under 5MB.
         * <p>When a persistent URI is present, your {@link MuzeiArtProvider} will store
         * downloaded images in the {@link Context#getCacheDir() cache directory} and automatically
         * re-download the image as needed. If it is not present, then you must write the image
         * directly to the {@link MuzeiArtProvider} with
         * {@link android.content.ContentResolver#openOutputStream(Uri)} and the images will be
         * stored in the {@link Context#getFilesDir()} as it assumed that there is no
         * way to re-download the artwork.
         *
         * @param persistentUri the artwork's persistent URI. Your app should have long-lived
         *                      access to this URI.
         * @return this {@link Builder}.
         * @see MuzeiArtProvider#openFile(Artwork)
         */
        @NonNull
        public Builder persistentUri(@Nullable Uri persistentUri) {
            mArtwork.persistentUri = persistentUri;
            return this;
        }

        /**
         * Sets the artwork's web URI. This is used by default in
         * {@link MuzeiArtProvider#openArtworkInfo(Artwork)}
         * to allow the user to view more details about the artwork.
         *
         * @param webUri a Uri to more details about the artwork.
         * @return this {@link Builder}.
         * @see MuzeiArtProvider#openArtworkInfo(Artwork)
         */
        @NonNull
        public Builder webUri(@Nullable Uri webUri) {
            mArtwork.webUri = webUri;
            return this;
        }

        /**
         * Sets the provider specific metadata about the artwork.
         * This is not used by Muzei at all, so can contain any data that makes it easier to query
         * or otherwise work with your Artwork.
         *
         * @param metadata any provider specific data associated with the artwork
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder metadata(@Nullable String metadata) {
            mArtwork.metadata = metadata;
            return this;
        }

        /**
         * Creates and returns the final Artwork object. Once this method is called, it is not
         * valid to further use this {@link Artwork.Builder} object.
         *
         * @return the final constructed {@link Artwork}.
         */
        @NonNull
        public Artwork build() {
            return mArtwork;
        }
    }
}
