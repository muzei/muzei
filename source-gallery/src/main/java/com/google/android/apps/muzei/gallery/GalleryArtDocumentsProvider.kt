/*
 * Copyright 2020 Google Inc.
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

package com.google.android.apps.muzei.gallery

import com.google.android.apps.muzei.api.provider.MuzeiArtDocumentsProvider

/**
 * When building multiple independent modules (as is the case for the `source-gallery`
 * module that this class is a part of), using a subclass of [MuzeiArtDocumentsProvider]
 * ensures that this provider does not conflict with any other [MuzeiArtDocumentsProvider]
 * instance that may be provided by other modules in the final merged manifest.
 */
class GalleryArtDocumentsProvider : MuzeiArtDocumentsProvider()
