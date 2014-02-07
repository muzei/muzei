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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;

/**
 * A serializable object representing a single artwork produced by a {@link MuzeiArtSource}. An
 * artwork is simple an {@linkplain Artwork.Builder#imageUri(Uri) image} along with
 * some metadata.
 *
 * <p> To create an instance, use the {@link Artwork.Builder} class.
 */
public class Artwork {
    private static final String KEY_IMAGE_URI = "imageUri";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BYLINE = "byline";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_VIEW_INTENT = "viewIntent";
    private static final String KEY_DETAILS_URI = "detailsUri";

    private Uri mImageUri;
    private String mTitle;
    private String mByline;
    private String mToken;
    private Intent mViewIntent;

    private Artwork() {
    }

    /**
     * Returns the artwork's image URI, or null if it doesn't have one.
     *
     * @see Artwork.Builder#imageUri(Uri)
     */
    public Uri getImageUri() {
        return mImageUri;
    }

    /**
     * Returns the artwork's user-visible title, or null if it doesn't have one.
     *
     * @see Artwork.Builder#title(String)
     */
    public String getTitle() {
        return mTitle;
    }

    /**
     * Returns the artwork's user-visible byline (e.g. author and date), or null if it doesn't have
     * one.
     *
     * @see Artwork.Builder#byline(String)
     */
    public String getByline() {
        return mByline;
    }

    /**
     * Returns the artwork's opaque application-specific identifier, or null if it doesn't have
     * one.
     *
     * @see Artwork.Builder#token(String)
     */
    public String getToken() {
        return mToken;
    }

    /**
     * Returns the activity {@link Intent} that will be started when the user clicks
     * for more details about the artwork, or null if the artwork doesn't have one.
     *
     * @see Artwork.Builder#viewIntent(Intent)
     */
    public Intent getViewIntent() {
        return mViewIntent;
    }

    /**
     * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a>-style, <a
     * href="http://en.wikipedia.org/wiki/Fluent_interface">fluent interface</a> for creating {@link
     * Artwork} objects. Example usage is below
     *
     * <pre class="prettyprint">
     * Artwork artwork = new Artwork.Builder()
     *         .imageUri(Uri.parse("http://example.com/image.jpg"))
     *         .title("Example image")
     *         .byline("Unknown person, c. 1980")
     *         .viewIntent(new Intent(Intent.ACTION_VIEW,
     *                 Uri.parse("http://example.com/imagedetails.html")))
     *         .build();
     * </pre>
     *
     * The only required field is {@linkplain #imageUri(Uri) the image URI}, but you
     * should really provide all the metadata, especially title, byline, and view intent.
     */
    public static class Builder {
        private Artwork mArtwork;

        public Builder() {
            mArtwork = new Artwork();
        }

        /**
         * Sets the artwork's image URI, which must resolve to a JPEG or PNG image, ideally
         * under 5MB. Supported URI schemes are:
         *
         * <ul>
         * <li><code>content://...</code>. Content URIs must be public (i.e. not require
         * permissions). To build a file-based content provider, see the
         * <a href="https://developer.android.com/reference/android/support/v4/content/FileProvider.html">FileProvider</a>
         * class in the Android support library.</a></li>
         * <li><code>http://...</code> or <code>https://...</code>. These URLs must be
         * publicly accessible (i.e. not require authentication of any kind).</li>
         * </ul>
         *
         * While Muzei will download and cache the artwork, these URIs should be as long-lived as
         * possible, since in the event Muzei's cache is wiped out, it will attempt to fetch the
         * image again. Also, given that the device may not be connected to the network at the time
         * an artwork is {@linkplain MuzeiArtSource#publishArtwork(Artwork) published}, the time
         * the URI may be fetched significantly after the artwork is published.
         */
        public Builder imageUri(Uri imageUri) {
            mArtwork.mImageUri = imageUri;
            return this;
        }

        /**
         * Sets the artwork's user-visible title.
         */
        public Builder title(String title) {
            mArtwork.mTitle = title;
            return this;
        }

        /**
         * Sets the artwork's user-visible byline (e.g. author and date).
         */
        public Builder byline(String byline) {
            mArtwork.mByline = byline;
            return this;
        }

        /**
         * Sets the artwork's opaque application-specific identifier.
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
         * <p> Because artwork objects can be persisted across device reboots,
         * {@linkplain android.app.PendingIntent pending intents}, which would alleviate the
         * exported requirement, are not currently supported.
         */
        public Builder viewIntent(Intent viewIntent) {
            mArtwork.mViewIntent = viewIntent;
            return this;
        }

        /**
         * Creates and returns the final Artwork object. Once this method is called, it is not valid
         * to further use this {@link Artwork.Builder} object.
         */
        public Artwork build() {
            return mArtwork;
        }
    }

    /**
     * Serializes this artwork object to a {@link Bundle} representation.
     */
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_IMAGE_URI, (mImageUri != null) ? mImageUri.toString() : null);
        bundle.putString(KEY_TITLE, mTitle);
        bundle.putString(KEY_BYLINE, mByline);
        bundle.putString(KEY_TOKEN, mToken);
        bundle.putString(KEY_VIEW_INTENT, (mViewIntent != null)
                ? mViewIntent.toUri(Intent.URI_INTENT_SCHEME) : null);
        return bundle;
    }

    /**
     * Deserializes an artwork object from a {@link Bundle}.
     */
    public static Artwork fromBundle(Bundle bundle) {
        Builder builder = new Builder()
                .title(bundle.getString(KEY_TITLE))
                .byline(bundle.getString(KEY_BYLINE))
                .token(bundle.getString(KEY_TOKEN));

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
     */
    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(KEY_IMAGE_URI, (mImageUri != null) ? mImageUri.toString() : null);
        jsonObject.put(KEY_TITLE, mTitle);
        jsonObject.put(KEY_BYLINE, mByline);
        jsonObject.put(KEY_TOKEN, mToken);
        jsonObject.put(KEY_VIEW_INTENT, (mViewIntent != null)
                ? mViewIntent.toUri(Intent.URI_INTENT_SCHEME) : null);
        return jsonObject;
    }

    /**
     * Deserializes an artwork object from a {@link JSONObject}.
     */
    public static Artwork fromJson(JSONObject jsonObject) throws JSONException {
        Builder builder = new Builder()
                .title(jsonObject.optString(KEY_TITLE))
                .byline(jsonObject.optString(KEY_BYLINE))
                .token(jsonObject.optString(KEY_TOKEN));

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
}
