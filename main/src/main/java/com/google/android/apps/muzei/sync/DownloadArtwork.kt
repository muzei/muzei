package com.google.android.apps.muzei.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import net.nurik.roman.muzei.BuildConfig
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL

private const val TAG = "DownloadArtwork"

internal suspend fun downloadArtwork(context: Context)
        = doDownload(context).also { success ->
    if (success) {
        ArtworkLoadingLiveData.postValue(ArtworkLoadingSuccess)
    } else {
        ArtworkLoadingLiveData.postValue(ArtworkLoadingFailure)
    }
}

private suspend fun doDownload(context: Context): Boolean {
    val artwork = MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtwork()
    val resolver = context.contentResolver
    if (artwork == null) {
        Log.w(TAG, "Could not read current artwork")
        return false
    }
    val artworkUri = ContentUris.withAppendedId(MuzeiContract.Artwork.CONTENT_URI,
            artwork.id)
    if (artwork.imageUri == null) {
        // There's nothing else we can do here so declare success
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Artwork $artworkUri does not have an image URI, skipping")
        }
        return true
    }
    if (BuildConfig.DEBUG) {
        Log.d(TAG, "Attempting to download ${artwork.imageUri} to $artworkUri")
    }
    try {
        resolver.openOutputStream(artworkUri)?.use { out ->
            openUri(context, artwork.imageUri).use { input ->
                // Only publish progress (i.e., say we've started loading the artwork)
                // if we actually need to download the artwork
                ArtworkLoadingLiveData.postValue(ArtworkLoadingInProgress)
                val buffer = ByteArray(1024)
                var bytes = input.read(buffer)
                while (bytes >= 0) {
                    out.write(buffer, 0, bytes)
                    bytes = input.read(buffer)
                }
                out.flush()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Artwork $artworkUri was successfully written")
                }
            }
        } ?: run {
            // We've already downloaded the file
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Artwork $artworkUri has already been downloaded")
            }
            return true
        }
    } catch (e: IOException) {
        Log.e(TAG, "Error downloading artwork", e)
        return false
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Error downloading artwork", e)
        return false
    }

    return true
}

@Throws(IOException::class)
private fun openUri(context: Context, uri: Uri?): InputStream {
    if (uri == null) {
        throw IllegalArgumentException("Uri cannot be empty")
    }
    val scheme = uri.scheme ?: throw IOException("Uri had no scheme")
    var input: InputStream? = null
    if ("content" == scheme || "android.resource" == scheme) {
        try {
            input = context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw FileNotFoundException("No access to $uri: $e")
        } catch (e: NullPointerException) {
            throw FileNotFoundException("Error accessing to $uri: $e")
        }
    } else if ("file" == scheme) {
        val segments = uri.pathSegments
        input = if (segments != null && segments.size > 1
                && "android_asset" == segments[0]) {
            val assetManager = context.assets
            val assetPath = StringBuilder()
            for (i in 1 until segments.size) {
                if (i > 1) {
                    assetPath.append("/")
                }
                assetPath.append(segments[i])
            }
            assetManager.open(assetPath.toString())
        } else {
            FileInputStream(File(uri.path))
        }
    } else if ("http" == scheme || "https" == scheme) {
        val client = OkHttpClientFactory.getNewOkHttpsSafeClient()
        val request: Request = Request.Builder().url(URL(uri.toString())).build()
        val response = client.newCall(request).execute()
        val responseCode = response.code()
        if (responseCode !in 200..299) {
            throw IOException("HTTP error response $responseCode reading $uri")
        }
        input = response.body()?.byteStream()
    }
    if (input == null) {
        throw FileNotFoundException("Null input stream for URI: $uri")
    }
    return input
}