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

package com.google.android.apps.muzei.util

import android.annotation.SuppressLint
import android.content.ContentProviderClient
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Backports [AutoCloseable] support for [ContentProviderClient] to API 19
 */
class ContentProviderClientCompat private constructor(
        private val mContentProviderClient: ContentProviderClient
) : AutoCloseable {

    companion object {
        fun getClient(context: Context, uri: Uri): ContentProviderClientCompat? =
                context.contentResolver.acquireUnstableContentProviderClient(uri)?.run {
                    ContentProviderClientCompat(this)
                }
    }

    @Throws(RemoteException::class)
    suspend fun call(
            method: String,
            arg: String? = null,
            extras: Bundle? = null
    ): Bundle? = withContext(Dispatchers.Default) {
        try {
            mContentProviderClient.call(method, arg, extras)
        } catch (e: Exception) {
            if (e is RemoteException) {
                throw e
            } else {
                throw RemoteException(e.message)
            }
        }
    }

    @SuppressLint("Recycle")
    @Throws(RemoteException::class)
    suspend fun query(
            url: Uri,
            projection: Array<String>? = null,
            selection: String? = null,
            selectionArgs: Array<String>? = null,
            sortOrder: String? = null
    ): Cursor? = withContext(Dispatchers.Default) {
        try {
            mContentProviderClient.query(
                    url, projection, selection, selectionArgs, sortOrder)
        } catch (e: Exception) {
            if (e is RemoteException) {
                throw e
            } else {
                throw RemoteException(e.message)
            }
        }
    }

    @Throws(FileNotFoundException::class, RemoteException::class)
    suspend fun openInputStream(
            url: Uri
    ): InputStream? = withContext(Dispatchers.Default) {
        try {
            mContentProviderClient.openFile(url, "r")?.run {
                ParcelFileDescriptor.AutoCloseInputStream(this)
            }
        } catch (e: Exception) {
            if (e is FileNotFoundException || e is RemoteException) {
                throw e
            } else {
                throw RemoteException(e.message)
            }
        }
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mContentProviderClient.close()
        } else {
            @Suppress("DEPRECATION")
            mContentProviderClient.release()
        }
    }
}
