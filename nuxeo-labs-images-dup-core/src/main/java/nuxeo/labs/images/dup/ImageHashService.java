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

import java.util.List;

import jakarta.annotation.Nullable;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;

/**
 * Computes the perceptual hash of documents having the Picture facet, and finds near duplicates with NXQL, so results
 * only contain documents the principal can read.
 *
 * @since 2025.1.0
 */
public interface ImageHashService {

    /**
     * Facet added to documents with computed image hashes.
     *
     * @since 2025.1.0
     */
    String IMAGE_HASH_FACET = "ImageHash";

    /**
     * Property containing the perceptual hash.
     *
     * @since 2025.1.0
     */
    String PHASH_PROPERTY = "imgh:phash";

    /**
     * Property containing the multi-index band keys.
     *
     * @since 2025.1.0
     */
    String BANDS_PROPERTY = "imgh:bands";

    /**
     * Property containing the hash algorithm and view signature.
     *
     * @since 2025.1.0
     */
    String SIGNATURE_PROPERTY = "imgh:signature";

    /**
     * Fired on a document when near duplicates are found after its picture views generation.
     *
     * @since 2025.1.0
     */
    String DUPLICATED_IMAGE_EVENT = "duplicatedImage";

    /**
     * Event property, a {@code LinkedHashMap<String, Integer>}: duplicate document id to distance, closest first.
     *
     * @since 2025.1.0
     */
    String DUPLICATES_EVENT_PROPERTY = "duplicates";

    /**
     * Default maximum number of similar documents returned to a caller.
     *
     * @since 2025.1.0
     */
    int DEFAULT_MAX_RESULTS = 50;

    /**
     * Returns the signature of the hashes computed with the current configuration. Hashes stored with another
     * signature are ignored by searches.
     *
     * @since 2025.1.0
     */
    String getSignature();

    /**
     * Computes the hash of the document and saves it if it changed. Does not search for duplicates.
     *
     * @param force if {@code false}, documents already hashed with the current signature are skipped
     * @return {@code true} if the document was saved
     * @since 2025.1.0
     */
    boolean updateHash(CoreSession session, DocumentModel doc, boolean force);

    /**
     * Computes the hash of the document and, if it changed, saves it and fires {@link #DUPLICATED_IMAGE_EVENT} when
     * near duplicates readable by the principal are found.
     * <p>
     * Duplicate detection requires a search engine client (OpenSearch or Elasticsearch). When the repository client
     * is the one configured, the hash is still stored and the detection is skipped.
     *
     * @since 2025.1.0
     */
    void updateHashAndDetectDuplicates(CoreSession session, DocumentModel doc, NuxeoPrincipal principal);

    /**
     * Returns the documents readable by the principal whose image is a near duplicate of the document's one, closest
     * first. {@code null} parameters use the configured search values.
     *
     * @param threshold max Hamming distance, out of 64 bits
     * @param radius 0 to 2, see {@link HashBands}
     * @param orientations also find rotated and mirrored images
     * @throws org.nuxeo.ecm.core.api.NuxeoException if no search engine serves the repository, see
     *             {@link #updateHashAndDetectDuplicates}
     * @since 2025.1.0
     */
    List<SimilarImage> findSimilar(DocumentModel doc, NuxeoPrincipal principal, @Nullable Integer threshold,
            @Nullable Integer radius, @Nullable Boolean orientations);
}
