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

package com.google.android.apps.muzei;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.ArrayList;

/**
 * WearableListenerService responsible to receiving Data Layer changes with updated source info
 */
public class SourceChangedListenerService extends WearableListenerService {
    private static final String TAG = "SourceChangedService";

    @Override
    public void onDataChanged(final DataEventBuffer dataEvents) {
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataEvent.getDataItem());
            DataMap dataMap = dataMapItem.getDataMap();
            final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
            operations.add(ContentProviderOperation
                    .newDelete(MuzeiContract.Sources.CONTENT_URI)
                    .build());
            if (!dataMap.isEmpty()) {
                ContentValues values = new ContentValues();
                values.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                        dataMap.getString(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME));
                values.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                        dataMap.getBoolean(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED));
                values.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                        dataMap.getString(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION));
                values.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                        dataMap.getBoolean(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE));
                values.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                        dataMap.getBoolean(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND));
                values.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                        dataMap.getString(MuzeiContract.Sources.COLUMN_NAME_COMMANDS));
                operations.add(ContentProviderOperation.newInsert(MuzeiContract.Sources.CONTENT_URI)
                        .withValues(values).build());
            }
            try {
                getContentResolver().applyBatch(MuzeiContract.AUTHORITY, operations);
            } catch (RemoteException | OperationApplicationException e) {
                Log.e(TAG, "Error writing sources to ContentProvider", e);
            }
        }
    }
}
