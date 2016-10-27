package com.google.android.apps.muzei.datalayer;

import com.google.android.apps.muzei.api.MuzeiArtSource;

public class DataLayerArtSource extends MuzeiArtSource {
    private static final String TAG = "DataLayerArtSource";

    public DataLayerArtSource() {
        super(TAG);
    }

    @Override
    protected void onUpdate(int reason) {
    }
}
