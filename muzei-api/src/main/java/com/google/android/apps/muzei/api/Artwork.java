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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Date;

import androidx.annotation.NonNull;

/**
 * A serializable object representing a single artwork produced by a {@link MuzeiArtSource}. An
 * artwork is simple an {@linkplain Artwork.Builder#imageUri(Uri) image} along with
 * some metadata.
 *
 * <p> To create an instance, use the {@link Artwork.Builder} class.
 */
public class Artwork {
    /**
     * @deprecated use {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT}
     */
    @Deprecated
    public static final String FONT_TYPE_DEFAULT = MuzeiContract.Artwork.META_FONT_TYPE_DEFAULT;
    /**
     * @deprecated use {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_ELEGANT}
     */
    @Deprecated
    public static final String FONT_TYPE_ELEGANT = MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT;

    private static final String KEY_COMPONENT_NAME = "componentName";
    private static final String KEY_IMAGE_URI = "imageUri";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BYLINE = "byline";
    private static final String KEY_ATTRIBUTION = "attribution";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_VIEW_INTENT = "viewIntent";
    private static final String KEY_DETAILS_URI = "detailsUri";
    private static final String KEY_META_FONT = "metaFont";
    private static final String KEY_DATE_ADDED = "dateAdded";

    private ComponentName mComponentName;
    private Uri mImageUri;
    private String mTitle;
    private String mByline;
    private String mAttribution;
    private String mToken;
    private Intent mViewIntent;
    private @MuzeiContract.Artwork.MetaFontType String mMetaFont;
    private Date mDateAdded;

    private Artwork() {
    }

    /**
     * Returns the {@link ComponentName} of the art source providing this artwork.
     *
     * @return a {@link ComponentName} of the {@link MuzeiArtSource} providing this artwork.
     *
     * @see Artwork.Builder#componentName(ComponentName)
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Returns the artwork's image URI.
     *
     * @return the artwork's image URI, or null if it doesn't have one.
     *
     * @see Artwork.Builder#imageUri
     */
    public Uri getImageUri() {
        return mImageUri;
    }

    /**
     * Returns the artwork's user-visible title.
     *
     * @return the artwork's user-visible title, or null if it doesn't have one.
     *
     * @see Artwork.Builder#title
     */
    public String getTitle() {
        return mTitle;
    }

    /**
     * Returns the artwork's user-visible byline, usually containing the author and date.
     * This is generally used as a secondary source of information after the {@link #getTitle title}.
     *
     * @return the artwork's user-visible byline, or null if it doesn't have one.
     *
     * @see Artwork.Builder#byline
     */
    public String getByline() {
        return mByline;
    }

    /**
     * Returns the artwork's user-visible attribution text.
     * This is generally used as a tertiary source of information after the
     * {@link #getTitle title} and the {@link #getByline byline}.
     *
     * @return the artwork's user-visible attribution text, or null if it doesn't have any.
     *
     * @see Artwork.Builder#attribution
     */
    public String getAttribution() {
        return mAttribution;
    }

    /**
     * Returns the artwork's opaque application-specific identifier. This is generally only useful
     * to the app that published the artwork and should not be relied upon by other apps.
     *
     * @return the artwork's opaque application-specific identifier, or null if it doesn't have
     * one.
     *
     * @see Artwork.Builder#token
     */
    public String getToken() {
        return mToken;
    }

    /**
     * Returns the activity {@link Intent} that will be started when the user clicks
     * for more details about the artwork. This should point to an exported
     * {@link android.app.Activity}. Muzei will automatically add
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} to allow reading any attached data URI,
     * but note that all extras will be lost when Muzei uses {@link Intent#toUri(int)}.
     *
     *
     * @return the activity {@link Intent} that will be
     * {@linkplain Context#startActivity(Intent) started} when the user clicks
     * for more details about the artwork, or null if the artwork doesn't have one.
     *
     * @see Artwork.Builder#viewIntent
     */
    public Intent getViewIntent() {
        return mViewIntent;
    }

    /**
     * Returns the font type to use for showing metadata. This should be treated as
     * {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT}
     * if null.
     *
     * @return the font type to use for showing metadata.
     *
     * @see Artwork.Builder#metaFont
     */
    @MuzeiContract.Artwork.MetaFontType
    public String getMetaFont() {
        return mMetaFont;
    }

    /**
     * Returns when this artwork was added to Muzei. This is automatically generated by Muzei.
     *
     * @return when this artwork was added to Muzei.
     *
     * @see Artwork.Builder#dateAdded
     */
    public Date getDateAdded() {
        return mDateAdded;
    }

    /**
     * Sets the {@link ComponentName} of the {@link MuzeiArtSource} providing this artwork.
     * This will automatically be set for you by Muzei.
     *
     * @param context context to use to construct the {@link ComponentName}.
     * @param source the {@link MuzeiArtSource} providing this artwork.
     */
    public void setComponentName(Context context, Class<? extends MuzeiArtSource> source) {
        mComponentName = new ComponentName(context, source);
    }

    /**
     * Sets the {@link ComponentName} of the {@link MuzeiArtSource} providing this artwork.
     * This will automatically be set for you by Muzei.
     *
     * @param source the {@link MuzeiArtSource} providing this artwork.
     *
     * @see Artwork.Builder#componentName(ComponentName)
     */
    public void setComponentName(ComponentName source) {
        mComponentName = source;
    }

    /**
     * Sets the artwork's image URI.
     *
     * @param imageUri the artwork's image URI.
     *
     * @see Artwork.Builder#imageUri
     */
    public void setImageUri(Uri imageUri) {
        mImageUri = imageUri;
    }

    /**
     * Sets the artwork's user-visible title.
     *
     * @param title the artwork's user-visible title.
     *
     * @see Artwork.Builder#title
     */
    public void setTitle(String title) {
        mTitle = title;
    }

    /**
     * Sets the artwork's user-visible byline, usually containing the author and date.
     * This is generally used as a secondary source of information after the {@link #setTitle} title}.
     *
     * @param byline the artwork's user-visible byline.
     *
     * @see Artwork.Builder#byline
     */
    public void setByline(String byline) {
        mByline = byline;
    }

    /**
     * Sets the artwork's user-visible attribution text.
     * This is generally used as a tertiary source of information after the
     * {@link #setTitle  title} and the {@link #setByline byline}.
     *
     * @param attribution the artwork's user-visible attribution text.
     *
     * @see Artwork.Builder#attribution
     */
    public void setAttribution(String attribution) {
        mAttribution = attribution;
    }

    /**
     * Sets the artwork's opaque application-specific identifier.
     *
     * @param token the artwork's opaque application-specific identifier.
     *
     * @see Artwork.Builder#token
     */
    public void setToken(String token) {
        mToken = token;
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
     *                   {@linkplain Context#startActivity(Intent) started} when the user clicks
     *                   for more details about the artwork.
     *
     * @see Artwork.Builder#viewIntent
     */
    public void setViewIntent(Intent viewIntent) {
        mViewIntent = viewIntent;
    }

    /**
     * Sets the font type to use for showing metadata. If unset,
     * {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT}
     * will be used by default.
     *
     * @param metaFont the font type to use for showing metadata.
     *
     * @see Artwork.Builder#metaFont
     */
    public void setMetaFont(@MuzeiContract.Artwork.MetaFontType String metaFont) {
        mMetaFont = metaFont;
    }

    /**
     * Sets when this artwork was added to Muzei. This will be done automatically for you.
     *
     * @param dateAdded when this artwork was added to Muzei.
     *
     * @see Artwork.Builder#dateAdded
     */
    public void setDateAdded(Date dateAdded) {
        mDateAdded = dateAdded;
    }

    /**
     * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a>-style, <a
     * href="http://en.wikipedia.org/wiki/Fluent_interface">fluent interface</a> for creating {@link
     * Artwork} objects. Example usage is below.
     *
     * <pre class="prettyprint">
     * Artwork artwork = new Artwork.Builder()
     *         .imageUri(Uri.parse("http://example.com/image.jpg"))
     *         .title("Example image")
     *         .byline("Unknown person, c. 1980")
     *         .attribution("Copyright (C) Unknown person, 1980")
     *         .viewIntent(new Intent(Intent.ACTION_VIEW,
     *                 Uri.parse("http://example.com/imagedetails.html")))
     *         .build();
     * </pre>
     *
     * The only required field is {@linkplain #imageUri(Uri) the image URI}, but you
     * should really provide all the metadata, especially title, byline, and view intent.
     */
    public static class Builder {
        private final Artwork mArtwork;

        public Builder() {
            mArtwork = new Artwork();
        }

        /**
         * Sets the {@link ComponentName} of the {@link MuzeiArtSource} providing this artwork.
         * This will automatically be set for you by Muzei.
         *
         * @param context context to use to construct the {@link ComponentName}
         * @param source the {@link MuzeiArtSource} providing this artwork
         *
         * @return this {@link Builder}.
         */
        public Builder componentName(Context context, Class<? extends MuzeiArtSource> source) {
            mArtwork.mComponentName = new ComponentName(context, source);
            return this;
        }

        /**
         * Sets the {@link ComponentName} of the {@link MuzeiArtSource} providing this artwork.
         * This will automatically be set for you by Muzei.
         *
         * @param source the {@link MuzeiArtSource} providing this artwork.
         *
         * @return this {@link Builder}.
         */
        public Builder componentName(ComponentName source) {
            mArtwork.mComponentName = source;
            return this;
        }

        /**
         * Sets the artwork's image URI, which must resolve to a JPEG or PNG image, ideally
         * under 5MB. Supported URI schemes are:
         *
         * <ul>
         * <li><code>content://...</code>. Content URIs must be public (i.e. not require
         * permissions). To build a file-based content provider, see the
         * <a href="https://developer.android.com/reference/android/support/v4/content/FileProvider.html">FileProvider</a>
         * class in the Android support library.</li>
         * <li><code>android.resource://...</code>. Resource URLs are recommended to be identified
         * by resource type name and entry name instead of resource ID, to be more reliable after
         * source app updates.</li>
         * <li><code>http://...</code> or <code>https://...</code>. These URLs must be
         * publicly accessible (i.e. not require authentication of any kind).</li>
         * </ul>
         *
         * While Muzei will download and cache the artwork, these URIs should be as long-lived as
         * possible, since in the event Muzei's cache is wiped out, it will attempt to fetch the
         * image again. Also, given that the device may not be connected to the network at the time
         * an artwork is {@linkplain MuzeiArtSource#publishArtwork(Artwork) published}, the time
         * the URI may be fetched significantly after the artwork is published.
         *
         * @param imageUri the artwork's image URI
         *
         * @return this {@link Builder}.
         */
        public Builder imageUri(Uri imageUri) {
            mArtwork.mImageUri = imageUri;
            return this;
        }

        /**
         * Sets the artwork's user-visible title.
         *
         * @param title the artwork's user-visible title.
         *
         * @return this {@link Builder}.
         */
        public Builder title(String title) {
            mArtwork.mTitle = title;
            return this;
        }

        /**
         * Sets the artwork's user-visible byline, usually containing the author and date.
         * This is generally used as a secondary source of information after the {@link #title} title}.
         *
         * @param byline the artwork's user-visible byline.
         *
         * @return this {@link Builder}.
         */
        public Builder byline(String byline) {
            mArtwork.mByline = byline;
            return this;
        }

        /**
         * Sets the artwork's user-visible attribution text.
         * This is generally used as a tertiary source of information after the
         * {@link #setTitle  title} and the {@link #byline byline}.
         *
         * @param attribution the artwork's user-visible attribution text.
         *
         * @return this {@link Builder}.
         */
        public Builder attribution(String attribution) {
            mArtwork.mAttribution = attribution;
            return this;
        }

        /**
         * Sets the artwork's opaque application-specific identifier.
         *
         * @param token the artwork's opaque application-specific identifier.
         *
         * @return this {@link Builder}.
         */
        public Builder token(String token) {
            mArtwork.mToken = token;
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
         *                   {@linkplain Context#startActivity(Intent) started} when the user clicks
         *                   for more details about the artwork.
         *
         * @return this {@link Builder}.
         */
        public Builder viewIntent(Intent viewIntent) {
            mArtwork.mViewIntent = viewIntent;
            return this;
        }

        /**
         * Sets the font type to use for showing metadata. If unset,
         * {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT}
         * will be used by default.
         *
         * @param metaFont the font type to use for showing metadata.
         *
         * @return this {@link Builder}.
         *
         * @see com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_DEFAULT
         * @see com.google.android.apps.muzei.api.MuzeiContract.Artwork#META_FONT_TYPE_ELEGANT
         */
        public Builder metaFont(@MuzeiContract.Artwork.MetaFontType String metaFont) {
            mArtwork.mMetaFont = metaFont;
            return this;
        }

        /**
         * Sets when this artwork was added to Muzei. This will be done automatically for you.
         *
         * @param dateAdded when this artwork was added to Muzei.
         *
         * @return this {@link Builder}.
         */
        public Builder dateAdded(Date dateAdded) {
            mArtwork.mDateAdded = dateAdded;
            return this;
        }

        /**
         * Creates and returns the final Artwork object. Once this method is called, it is not valid
         * to further use this {@link Artwork.Builder} object.
         *
         * @return the final constructed {@link Artwork}.
         */
        public Artwork build() {
            return mArtwork;
        }
    }

    /**
     * Serializes this artwork object to a {@link Bundle} representation.
     *
     * @return a serialized version of the artwork.
     *
     * @see #fromBundle
     */
    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_COMPONENT_NAME, (mComponentName != null) ? mComponentName.flattenToShortString() : null);
        bundle.putString(KEY_IMAGE_URI, (mImageUri != null) ? mImageUri.toString() : null);
        bundle.putString(KEY_TITLE, mTitle);
        bundle.putString(KEY_BYLINE, mByline);
        bundle.putString(KEY_ATTRIBUTION, mAttribution);
        bundle.putString(KEY_TOKEN, mToken);
        bundle.putString(KEY_VIEW_INTENT, (mViewIntent != null)
                ? mViewIntent.toUri(Intent.URI_INTENT_SCHEME) : null);
        bundle.putString(KEY_META_FONT, mMetaFont);
        bundle.putLong(KEY_DATE_ADDED, mDateAdded != null ? mDateAdded.getTime() : 0);
        return bundle;
    }

    /**
     * Deserializes an artwork object from a {@link Bundle}.
     *
     * @param bundle Bundle generated by {@link #toBundle} to deserialize.
     *
     * @return the artwork from the given {@link Bundle}
     */
    @NonNull
    public static Artwork fromBundle(@NonNull Bundle bundle) {
        @SuppressWarnings("WrongConstant") // Assume the KEY_META_FONT is valid
        Builder builder = new Builder()
                .title(bundle.getString(KEY_TITLE))
                .byline(bundle.getString(KEY_BYLINE))
                .attribution(bundle.getString(KEY_ATTRIBUTION))
                .token(bundle.getString(KEY_TOKEN))
                .metaFont(bundle.getString(KEY_META_FONT))
                .dateAdded(new Date(bundle.getLong(KEY_DATE_ADDED, 0)));

        String componentName = bundle.getString(KEY_COMPONENT_NAME);
        if (!TextUtils.isEmpty(componentName)) {
            builder.componentName(ComponentName.unflattenFromString(componentName));
        }

        String imageUri = bundle.getString(KEY_IMAGE_URI);
        if (!TextUtils.isEmpty(imageUri)) {
            builder.imageUri(Uri.parse(imageUri));
        }

        try {
            String viewIntent = bundle.getString(KEY_VIEW_INTENT);
            if (!TextUtils.isEmpty(viewIntent)) {
                builder.viewIntent(Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME));
            }
        } catch (URISyntaxException ignored) {
        }

        return builder.build();
    }

    /**
     * Serializes this artwork object to a {@link JSONObject} representation.
     *
     * @return the JSON representation of the artwork.
     *
     * @throws JSONException if there is an error creating the JSON representation.
     */
    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(KEY_COMPONENT_NAME, (mComponentName != null) ? mComponentName.flattenToShortString() : null);
        jsonObject.put(KEY_IMAGE_URI, (mImageUri != null) ? mImageUri.toString() : null);
        jsonObject.put(KEY_TITLE, mTitle);
        jsonObject.put(KEY_BYLINE, mByline);
        jsonObject.put(KEY_ATTRIBUTION, mAttribution);
        jsonObject.put(KEY_TOKEN, mToken);
        jsonObject.put(KEY_VIEW_INTENT, (mViewIntent != null)
                ? mViewIntent.toUri(Intent.URI_INTENT_SCHEME) : null);
        jsonObject.put(KEY_META_FONT, mMetaFont);
        jsonObject.put(KEY_DATE_ADDED, mDateAdded != null ? mDateAdded.getTime() : 0);
        return jsonObject;
    }

    /**
     * Deserializes an artwork object from a {@link JSONObject}.
     *
     * @param jsonObject JSON representation generated by {@link #toJson} to deserialize.
     *
     * @return the artwork from the given {@link JSONObject}
     */
    @NonNull
    public static Artwork fromJson(@NonNull JSONObject jsonObject) {
        @SuppressWarnings("WrongConstant") // Assume the KEY_META_FONT is valid
        Builder builder = new Builder()
                .title(jsonObject.optString(KEY_TITLE))
                .byline(jsonObject.optString(KEY_BYLINE))
                .attribution(jsonObject.optString(KEY_ATTRIBUTION))
                .token(jsonObject.optString(KEY_TOKEN))
                .metaFont(jsonObject.optString(KEY_META_FONT))
                .dateAdded(new Date(jsonObject.optLong(KEY_DATE_ADDED, 0)));

        String componentName = jsonObject.optString(KEY_COMPONENT_NAME);
        if (!TextUtils.isEmpty(componentName)) {
            builder.componentName(ComponentName.unflattenFromString(componentName));
        }

        String imageUri = jsonObject.optString(KEY_IMAGE_URI);
        if (!TextUtils.isEmpty(imageUri)) {
            builder.imageUri(Uri.parse(imageUri));
        }

        try {
            String viewIntent = jsonObject.optString(KEY_VIEW_INTENT);
            String detailsUri = jsonObject.optString(KEY_DETAILS_URI);
            if (!TextUtils.isEmpty(viewIntent)) {
                builder.viewIntent(Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME));
            } else if (!TextUtils.isEmpty(detailsUri)) {
                builder.viewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(detailsUri)));
            }
        } catch (URISyntaxException ignored) {
        }

        return builder.build();
    }

    /**
     * Serializes this artwork object to a {@link ContentValues} representation.
     *
     * @return a {@link ContentValues} appropriate to insert into
     * {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#CONTENT_URI}.
     */
    @NonNull
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME, (mComponentName != null)
                ? mComponentName.flattenToShortString() : null);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI, (mImageUri != null)
                ? mImageUri.toString() : null);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_TITLE, mTitle);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_BYLINE, mByline);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION, mAttribution);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_TOKEN, mToken);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT, (mViewIntent != null)
                ? mViewIntent.toUri(Intent.URI_INTENT_SCHEME) : null);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_META_FONT, mMetaFont);
        values.put(MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED, mDateAdded != null ?
                mDateAdded.getTime() : 0);
        return values;
    }

    /**
     * Deserializes an artwork object from a {@link Cursor} retrieved from
     * {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#CONTENT_URI}.
     *
     * @param cursor a {@link Cursor} retrieved from
     *               {@link com.google.android.apps.muzei.api.MuzeiContract.Artwork#CONTENT_URI},
     *               set at the correct position.
     *
     * @return the artwork from the current position of the Cursor.
     */
    @NonNull
    public static Artwork fromCursor(@NonNull Cursor cursor) {
        Builder builder = new Builder();
        int componentNameColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME);
        if (componentNameColumnIndex != -1) {
            builder.componentName(ComponentName.unflattenFromString(cursor.getString(componentNameColumnIndex)));
        }
        int imageUriColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI);
        if (imageUriColumnIndex != -1) {
            String uriString = cursor.getString(imageUriColumnIndex);
            if (!TextUtils.isEmpty(uriString)) {
                builder.imageUri(Uri.parse(uriString));
            }
        }
        int titleColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_TITLE);
        if (titleColumnIndex != -1) {
            builder.title(cursor.getString(titleColumnIndex));
        }
        int bylineColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_BYLINE);
        if (bylineColumnIndex != -1) {
            builder.byline(cursor.getString(bylineColumnIndex));
        }
        int attributionColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION);
        if (attributionColumnIndex != -1) {
            builder.attribution(cursor.getString(attributionColumnIndex));
        }
        int tokenColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_TOKEN);
        if (tokenColumnIndex != -1) {
            builder.token(cursor.getString(tokenColumnIndex));
        }
        int viewIntentColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
        if (viewIntentColumnIndex != -1) {
            try {
                String viewIntent = cursor.getString(viewIntentColumnIndex);
                if (!TextUtils.isEmpty(viewIntent)) {
                    builder.viewIntent(Intent.parseUri(viewIntent, Intent.URI_INTENT_SCHEME));
                }
            } catch (URISyntaxException ignored) {
            }
        }
        int metaFontColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_META_FONT);
        if (metaFontColumnIndex != -1) {
            // Assume the COLUMN_NAME_META_FONT value is valid
            //noinspection WrongConstant
            builder.metaFont(cursor.getString(metaFontColumnIndex));
        }
        int dateAddedColumnIndex = cursor.getColumnIndex(MuzeiContract.Artwork.COLUMN_NAME_DATE_ADDED);
        if (dateAddedColumnIndex != -1) {
            builder.dateAdded(new Date(cursor.getLong(dateAddedColumnIndex)));
        }
        return builder.build();
    }
}
