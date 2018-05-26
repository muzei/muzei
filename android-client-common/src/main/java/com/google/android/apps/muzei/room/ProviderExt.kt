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

package com.google.android.apps.muzei.room

import android.content.ComponentName
import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_DESCRIPTION
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_DESCRIPTION
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.withContext
import kotlin.reflect.KClass

private const val TAG = "Provider"

suspend fun KClass<out MuzeiArtProvider>.select(context: Context) {
    ComponentName(context, this.java).select(context)
}

suspend fun ComponentName.select(context: Context) = withContext(CommonPool) {
    val database = MuzeiDatabase.getInstance(context)
    database.beginTransaction()
    database.providerDao().deleteAll()
    database.providerDao().insert(Provider(this))
    database.setTransactionSuccessful()
    database.endTransaction()
}

suspend fun Provider.getDescription(context: Context): String {
    val contentUri = MuzeiArtProvider.getContentUri(context, componentName)
    return ContentProviderClientCompat.getClient(context, contentUri)?.use { client ->
        return try {
            val result = client.call(METHOD_GET_DESCRIPTION)
            result?.getString(KEY_DESCRIPTION, "") ?: ""
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider ${this} crashed while retrieving description", e)
            ""
        }
    } ?: ""
}
