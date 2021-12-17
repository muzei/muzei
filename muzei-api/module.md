
# Module muzei-api

Muzei offers two API surfaces:

1. An API that allows you to build your own wallpaper source via the classes in the
 [com.google.android.apps.muzei.api.provider] package.

2. A content provider contract that allows you to read current artwork data. This is the focus of
 the [com.google.android.apps.muzei.api] package.

### Building your own wallpaper source (API 3.2+)

Muzei itself is responsible for displaying wallpaper images, or `Artwork`, but even its built
in wallpaper sources are all built with the same
[com.google.android.apps.muzei.api.provider.MuzeiArtProvider] API, which makes it possible
for any app to provide wallpapers to Muzei via just a few steps:

1. Add the following Maven coordinates as a dependency:
   **`com.google.android.apps.muzei:muzei-api:3.2.0`** or higher.
2. Create a new provider that extends
   [com.google.android.apps.muzei.api.provider.MuzeiArtProvider].
3. Add the corresponding `<provider>` tag to your `AndroidManifest.xml` file and add the required
    `<intent-filter>` and `<meta-data>` elements.

Once you have both Muzei and your custom source installed, you should be able to choose your source
from the 'Sources' screen in Muzei.

A deeper discussion of the API, along with code snippets, is available in the
[com.google.android.apps.muzei.api.provider.MuzeiArtProvider] class reference.

#### Sample code

A complete example is available in the
[example-unsplash](https://github.com/muzei/muzei/tree/main/example-unsplash) directory.

### Accessing current wallpaper information (API 2.0+)

You can access the current wallpaper on Android phones and tablets, or on Wear OS, while Muzei is
the active wallpaper.

1. Add the following Maven coordinates as a dependency:
   **`com.google.android.apps.muzei:muzei-api:2.+`**.
2. Use either
   [com.google.android.apps.muzei.api.MuzeiContract.Artwork.getCurrentArtworkBitmap]
   to get the current artwork as a bitmap, or access more information about the artwork by querying
   [com.google.android.apps.muzei.api.MuzeiContract.Artwork.CONTENT_URI]
   with a `ContentResolver`.

A deeper discussion of the API, along with code snippets, is available in the
[com.google.android.apps.muzei.api] package reference docs.

#### Sample code

Complete examples are available in the
[example-watchface](https://github.com/muzei/muzei/tree/main/example-watchface) directory.
