/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.labs.images.dup;

import java.io.Serializable;
import java.util.List;

/**
 * The result of a similar images search.
 *
 * @param images the similar images, closest first
 * @param truncated {@code true} if the search reached the configured maximum number of candidates, or of matches
 *            when the search engine computes the distance: some similar images may be missing. The documents sharing
 *            an exact band with the searched hash are fetched first, so the closest images are the last ones to be
 *            missed
 * @since 2025.1.0
 */
public record SimilarImages(List<SimilarImage> images, boolean truncated) implements Serializable {

    /**
     * An empty, complete result.
     *
     * @since 2025.1.0
     */
    public static final SimilarImages NONE = new SimilarImages(List.of(), false);
}
