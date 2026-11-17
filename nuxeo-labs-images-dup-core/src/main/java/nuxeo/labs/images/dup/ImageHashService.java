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
     * Event property, a {@code LinkedHashMap<String, Integer>}: duplicate document id to distance, closest first, at
     * most {@link #MAX_EVENT_DUPLICATES} of them.
     * <p>
     * The duplicates listed are those readable by the principal of the event, who is the user having triggered the
     * views generation, or the user of the command for the {@code detectImageDuplicates} bulk action. A listener
     * forwarding this list to someone else, the creator of the document for instance, may disclose documents that
     * person cannot read.
     *
     * @since 2025.1.0
     */
    String DUPLICATES_EVENT_PROPERTY = "duplicates";

    /**
     * Event property, an {@code Integer}: the number of duplicates found, which can exceed the number of entries of
     * {@link #DUPLICATES_EVENT_PROPERTY}.
     *
     * @since 2025.1.0
     */
    String DUPLICATES_COUNT_EVENT_PROPERTY = "duplicatesCount";

    /**
     * Maximum number of duplicates listed in an event: a very common image, a logo or a placeholder, has thousands,
     * and events are serialized for asynchronous listeners.
     *
     * @since 2025.1.0
     */
    int MAX_EVENT_DUPLICATES = 100;

    /**
     * Event property, a {@code Boolean}: {@code true} if the detection reached the maximum number of candidates, in
     * which case {@link #DUPLICATES_EVENT_PROPERTY} may be incomplete, see {@link SimilarImages#truncated()}.
     *
     * @since 2025.1.0
     */
    String DUPLICATES_TRUNCATED_EVENT_PROPERTY = "duplicatesTruncated";

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
     * <p>
     * The hash properties are secured: the calling thread must be logged in as an administrator or as the system
     * user, whatever the principal of the session.
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
     * is the one configured, the hash is still stored and the detection is skipped. The same goes when the search
     * fails: the failure is logged, not thrown, so that it cannot roll back the hash saved in the same transaction.
     * The detection is also skipped when the configuration disables it, see {@link #detectDuplicates}.
     * <p>
     * The hash properties are secured: the calling thread must be logged in as an administrator or as the system
     * user, whatever the principal of the session.
     *
     * @since 2025.1.0
     */
    void updateHashAndDetectDuplicates(CoreSession session, DocumentModel doc, NuxeoPrincipal principal);

    /**
     * Searches for the near duplicates of a document that is already hashed, with the detection settings, and fires
     * {@link #DUPLICATED_IMAGE_EVENT} when some, readable by the principal, are found. Neither computes nor stores a
     * hash: a document that is not hashed with the current signature is skipped.
     * <p>
     * This is the second pass of the detection, see the {@code detectImageDuplicates} bulk action:
     * {@link #updateHashAndDetectDuplicates} only sees the pictures already in the search index, so pictures hashed
     * within the indexing delay of each other do not see each other.
     *
     * @return {@code true} if the event was fired
     * @throws org.nuxeo.ecm.core.api.NuxeoException if no search engine serves the repository. Unlike in
     *             {@link #updateHashAndDetectDuplicates}, a failing search is thrown too: nothing else is at stake
     * @since 2025.1.0
     */
    boolean detectDuplicates(CoreSession session, DocumentModel doc, NuxeoPrincipal principal);

    /**
     * Returns the documents readable by the principal whose image is a near duplicate of the document's one, closest
     * first. {@code null} parameters use the configured search values.
     * <p>
     * The search examines at most the configured number of candidates. Beyond it the result is
     * {@link SimilarImages#truncated() truncated}: callers must not present it as complete.
     *
     * @param threshold max Hamming distance, from 0 to 64
     * @param radius 0 to 2, see {@link HashBands}
     * @param orientations also find rotated and mirrored images
     * @throws org.nuxeo.ecm.core.api.NuxeoException if no search engine serves the repository, see
     *             {@link #updateHashAndDetectDuplicates}, or, with a 400 status, if {@code threshold} or
     *             {@code radius} is out of range
     * @since 2025.1.0
     */
    SimilarImages findSimilar(DocumentModel doc, NuxeoPrincipal principal, @Nullable Integer threshold,
            @Nullable Integer radius, @Nullable Boolean orientations);
}
