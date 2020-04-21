
# Package com.google.android.apps.muzei.api

Contains the [MuzeiContract] APIs to get the current artwork from Muzei.

The [MuzeiContract] provides access to information about the current wallpaper via
[MuzeiContract.Artwork] and about the current source via [MuzeiContract.Sources].

Each of these contracts provide only read access to Muzei's [android.content.ContentProvider] and
therefore provide you with a content URI and set of columns for working directly with the
underlying tables via a [android.content.ContentResolver].

## Listening for changes

It is strongly recommended to listen for changes rather than repeatedly query Muzei's API. If you
only need to listen for changes while your process is alive, you can use
[android.content.Context.registerReceiver] with either of the `CONTENT_URI` Uris.

If you instead need to continually monitor Muzei for changes even while your app is in the
background or not in memory, you must use an alternate approach. On API 24 and higher devices,
you should use `WorkManager` and `addContentUriTrigger()` to trigger your work when Muzei updates.

To support devices running API 23 or earlier (where listening for content URIs in the background
is not possible), Muzei provides an `ACTION_` constant for each table that allows you register a
[android.content.BroadcastReceiver] that will be triggered every time Muzei changes.

# Package com.google.android.apps.muzei.api.provider

Contains the APIs needed to build a custom source using a [MuzeiArtProvider].

While Muzei comes with a number of built in sources, the [MuzeiArtProvider] is the key for
building your own wallpaper source, providing wallpaper from any source you want. Each
[MuzeiArtProvider] serves as the entry point for Muzei to communicate with your app and retrieve
wallpapers you make available.

While your [MuzeiArtProvider] is based on an [android.content.ContentProvider] and that level of
API **is** available for you through the columns by the [ProviderContract], it is strongly
recommended to add artwork via the APIs provided by the [ProviderClient] which can be retrieved
via the [ProviderContract.getProviderClient] methods.
