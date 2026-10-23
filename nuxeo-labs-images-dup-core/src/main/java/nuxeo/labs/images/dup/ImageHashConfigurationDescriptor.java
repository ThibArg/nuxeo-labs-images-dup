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

import static java.util.Objects.requireNonNullElse;

import java.util.ArrayList;
import java.util.List;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XNodeList;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.runtime.model.Descriptor;

/**
 * Configuration of the {@link ImageHashService}. Contributions are merged, unset values use the defaults.
 *
 * @since 2025.1.0
 */
@XObject("configuration")
public class ImageHashConfigurationDescriptor implements Descriptor {

    /**
     * Default picture views, in order of preference.
     *
     * @since 2025.1.0
     */
    public static final List<String> DEFAULT_VIEWS = List.of("Small", "Medium", "FullHD", "OriginalJpeg");

    /**
     * Default maximum Hamming distance for duplicate detection.
     *
     * @since 2025.1.0
     */
    public static final int DEFAULT_THRESHOLD = 8;

    /**
     * Default band-search radius for duplicate detection.
     *
     * @since 2025.1.0
     */
    public static final int DEFAULT_DETECTION_RADIUS = 1;

    /**
     * Whether duplicate detection compares rotated and mirrored orientations by default.
     *
     * @since 2025.1.0
     */
    public static final boolean DEFAULT_DETECTION_ORIENTATIONS = false;

    /**
     * Default band-search radius for similar-image searches.
     *
     * @since 2025.1.0
     */
    public static final int DEFAULT_SEARCH_RADIUS = 1;

    /**
     * Whether similar-image searches compare rotated and mirrored orientations by default.
     *
     * @since 2025.1.0
     */
    public static final boolean DEFAULT_SEARCH_ORIENTATIONS = true;

    /**
     * Maximum number of candidates examined by a search.
     *
     * @since 2025.1.0
     */
    public static final int DEFAULT_MAX_CANDIDATES = 10_000;

    @XNodeList(value = "views/view", type = ArrayList.class, componentType = String.class, nullByDefault = true)
    protected List<String> views;

    @XNode("threshold")
    protected Integer threshold;

    @XNode("detectionRadius")
    protected Integer detectionRadius;

    @XNode("detectionOrientations")
    protected Boolean detectionOrientations;

    @XNode("searchRadius")
    protected Integer searchRadius;

    @XNode("searchOrientations")
    protected Boolean searchOrientations;

    @XNode("maxCandidates")
    protected Integer maxCandidates;

    /**
     * @since 2025.1.0
     */
    @Override
    public String getId() {
        return UNIQUE_DESCRIPTOR_ID;
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public Descriptor merge(Descriptor o) {
        var other = (ImageHashConfigurationDescriptor) o;
        var merged = new ImageHashConfigurationDescriptor();
        merged.views = other.views != null && !other.views.isEmpty() ? other.views : views;
        merged.threshold = firstNonNull(other.threshold, threshold);
        merged.detectionRadius = firstNonNull(other.detectionRadius, detectionRadius);
        merged.detectionOrientations = firstNonNull(other.detectionOrientations, detectionOrientations);
        merged.searchRadius = firstNonNull(other.searchRadius, searchRadius);
        merged.searchOrientations = firstNonNull(other.searchOrientations, searchOrientations);
        merged.maxCandidates = firstNonNull(other.maxCandidates, maxCandidates);
        return merged;
    }

    protected static <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Picture views to hash, by order of preference.
     *
     * @since 2025.1.0
     */
    public List<String> getViews() {
        return views == null || views.isEmpty() ? DEFAULT_VIEWS : views;
    }

    /**
     * Max Hamming distance, out of 64 bits, for 2 images to be considered duplicates.
     *
     * @since 2025.1.0
     */
    public int getThreshold() {
        return Math.clamp(requireNonNullElse(threshold, DEFAULT_THRESHOLD), 0, Long.SIZE);
    }

    /**
     * @since 2025.1.0
     */
    public int getDetectionRadius() {
        return Math.clamp(requireNonNullElse(detectionRadius, DEFAULT_DETECTION_RADIUS), 0, HashBands.MAX_RADIUS);
    }

    /**
     * @since 2025.1.0
     */
    public boolean isDetectionOrientations() {
        return requireNonNullElse(detectionOrientations, DEFAULT_DETECTION_ORIENTATIONS);
    }

    /**
     * @since 2025.1.0
     */
    public int getSearchRadius() {
        return Math.clamp(requireNonNullElse(searchRadius, DEFAULT_SEARCH_RADIUS), 0, HashBands.MAX_RADIUS);
    }

    /**
     * @since 2025.1.0
     */
    public boolean isSearchOrientations() {
        return requireNonNullElse(searchOrientations, DEFAULT_SEARCH_ORIENTATIONS);
    }

    /**
     * @since 2025.1.0
     */
    public int getMaxCandidates() {
        return Math.max(1, requireNonNullElse(maxCandidates, DEFAULT_MAX_CANDIDATES));
    }
}
