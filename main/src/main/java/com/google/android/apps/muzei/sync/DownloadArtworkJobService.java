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

package com.google.android.apps.muzei.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * JobService that handles reloading artwork after any initial failure
 */
public class DownloadArtworkJobService extends JobService {
    private DownloadArtworkTask mDownloadArtworkTask = null;

    @Override
    public boolean onStartJob(final JobParameters params) {
        mDownloadArtworkTask = new DownloadArtworkTask(this) {
            @Override
            protected void onPostExecute(Boolean success) {
                jobFinished(params, !success);
            }
        };
        mDownloadArtworkTask.execute();
        return true;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        if (mDownloadArtworkTask != null) {
            mDownloadArtworkTask.cancel(true);
        }
        return true;
    }
}
