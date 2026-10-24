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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.stream.Collectors.joining;
import static org.nuxeo.ecm.core.query.sql.NXQL.ECM_UUID;
import static org.nuxeo.ecm.platform.picture.api.ImagingDocumentConstants.PICTURE_FACET;
import static org.nuxeo.ecm.platform.picture.listener.PictureViewsGenerationListener.DISABLE_PICTURE_VIEWS_GENERATION_LISTENER;
import static org.nuxeo.runtime.model.Descriptor.UNIQUE_DESCRIPTOR_ID;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import jakarta.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.common.Environment;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.search.SearchIndex;
import org.nuxeo.ecm.core.search.SearchQuery;
import org.nuxeo.ecm.core.search.SearchService;
import org.nuxeo.ecm.platform.dublincore.listener.DublinCoreListener;
import org.nuxeo.ecm.platform.picture.api.PictureView;
import org.nuxeo.ecm.platform.picture.api.adapters.MultiviewPicture;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.RuntimeServiceException;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * Default implementation of the perceptual image hash service.
 *
 * @since 2025.1.0
 */
public class ImageHashServiceImpl extends DefaultComponent implements ImageHashService {

    private static final Logger log = LogManager.getLogger(ImageHashServiceImpl.class);

    /**
     * Configuration extension point.
     *
     * @since 2025.1.0
     */
    public static final String CONFIGURATION_XP = "configuration";

    /** To change whenever the hash computation changes, so that existing hashes are not compared anymore. */
    protected static final String ALGORITHM = "phash64v1";

    /** Images are subsampled when read, down to about this size on their smaller side. */
    protected static final int READ_SIZE = 256;

    /**
     * Prefix of the search client names able to run the band query. Covers the blue/green deployment client
     * {@code opensearch-green}.
     */
    protected static final String OPENSEARCH_CLIENT_PREFIX = "opensearch";

    protected static final String NO_OPENSEARCH_MESSAGE = "No OpenSearch search index found for repository: %s."
            + " This plugin requires the nuxeo-search-client-opensearch1 package, with"
            + " nuxeo.search.client.default.name=opensearch";

    protected ImageHashConfigurationDescriptor config;

    protected String signature;

    /** Short id of the signature, prefixing the band keys. */
    protected String bandPrefix;

    /** Logged only once, the listener would otherwise report it for every picture. */
    protected volatile boolean missingOpenSearchLogged;

    protected record Computed(long hash, double[][] reduced, boolean degenerate) {
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public void start(ComponentContext context) {
        // ImageIO uses this directory for disk-backed caches when decoding picture blobs.
        var tempDir = Environment.getDefault().getTemp();
        if ((!tempDir.isDirectory() && !tempDir.mkdirs()) || !tempDir.isDirectory() || !tempDir.canWrite()) {
            throw new RuntimeServiceException("Cannot use Nuxeo temporary directory for ImageIO cache: " + tempDir);
        }
        ImageIO.setCacheDirectory(tempDir);
        config = requireNonNullElseGet(getDescriptor(CONFIGURATION_XP, UNIQUE_DESCRIPTOR_ID),
                ImageHashConfigurationDescriptor::new);
        signature = ALGORITHM + "/" + config.getViews().getFirst();
        var crc = new CRC32();
        crc.update(signature.getBytes(UTF_8));
        bandPrefix = "%08x".formatted(crc.getValue());
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public String getSignature() {
        return signature;
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public boolean updateHash(CoreSession session, DocumentModel doc, boolean force) {
        if (!force && signature.equals(getStoredSignature(doc))) {
            return false;
        }
        var computed = compute(doc);
        return computed != null && store(session, doc, computed);
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public void updateHashAndDetectDuplicates(CoreSession session, DocumentModel doc, NuxeoPrincipal principal) {
        var computed = compute(doc);
        if (computed == null || !store(session, doc, computed) || computed.degenerate()) {
            return;
        }
        // the hash is stored even without OpenSearch, only the detection is skipped
        var index = findSearchIndex(doc.getRepositoryName());
        if (index == null) {
            if (!missingOpenSearchLogged) {
                missingOpenSearchLogged = true;
                log.error(NO_OPENSEARCH_MESSAGE.formatted(doc.getRepositoryName())
                        + ". Hashes are still stored, duplicate detection is disabled");
            }
            return;
        }
        var duplicates = search(doc, principal, computed, index, config.isDetectionOrientations(),
                config.getDetectionRadius(), config.getThreshold());
        if (!duplicates.isEmpty()) {
            fireDuplicatedImage(session, doc, principal, duplicates);
        }
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public List<SimilarImage> findSimilar(DocumentModel doc, NuxeoPrincipal principal, @Nullable Integer threshold,
            @Nullable Integer radius, @Nullable Boolean orientations) {
        var computed = compute(doc);
        if (computed == null || computed.degenerate()) {
            return List.of();
        }
        return search(doc, principal, computed, requireSearchIndex(doc.getRepositoryName()),
                requireNonNullElse(orientations, config.isSearchOrientations()),
                requireNonNullElse(radius, config.getSearchRadius()),
                requireNonNullElse(threshold, config.getThreshold()));
    }

    /**
     * Returns the first search index of the repository served by an OpenSearch client, or {@code null}.
     * <p>
     * The index name is deployment specific, so the client name is what identifies it.
     */
    @Nullable
    protected SearchIndex findSearchIndex(String repository) {
        var searchService = Framework.getService(SearchService.class);
        return searchService.getIndexNames(repository)
                            .stream()
                            .map(searchService::getSearchIndex)
                            .filter(index -> index.client().startsWith(OPENSEARCH_CLIENT_PREFIX))
                            .findFirst()
                            .orElse(null);
    }

    /**
     * Same as {@link #findSearchIndex(String)}, but fails instead of returning {@code null}.
     *
     * @throws NuxeoException if the OpenSearch search client is not deployed
     */
    protected SearchIndex requireSearchIndex(String repository) {
        var index = findSearchIndex(repository);
        if (index == null) {
            throw new NuxeoException(NO_OPENSEARCH_MESSAGE.formatted(repository));
        }
        return index;
    }

    @Nullable
    protected String getStoredSignature(DocumentModel doc) {
        return doc.hasFacet(IMAGE_HASH_FACET) ? (String) doc.getPropertyValue(SIGNATURE_PROPERTY) : null;
    }

    @Nullable
    protected Computed compute(DocumentModel doc) {
        var image = readImage(doc);
        if (image == null) {
            log.debug("No readable picture view for document: {}", doc::getId);
            return null;
        }
        var reduced = PHash.reduce(image);
        return new Computed(PHash.hash(reduced), reduced, PHash.isDegenerate(image, reduced));
    }

    @Nullable
    protected BufferedImage readImage(DocumentModel doc) {
        if (!doc.hasFacet(PICTURE_FACET)) {
            return null;
        }
        var picture = doc.getAdapter(MultiviewPicture.class);
        for (var title : config.getViews()) {
            var view = picture.getView(title);
            var blob = view == null ? null : view.getBlob();
            if (blob == null) {
                continue;
            }
            try (InputStream stream = blob.getStream()) {
                var image = readSubsampled(stream);
                if (image != null) {
                    return image;
                }
                log.warn("No image reader for view: {} of document: {}", title, doc.getId());
            } catch (IOException e) {
                log.warn("Cannot read view: {} of document: {}", title, doc.getId(), e);
            }
        }
        return null;
    }

    @Nullable
    protected BufferedImage readSubsampled(InputStream stream) throws IOException {
        try (var input = ImageIO.createImageInputStream(stream)) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var step = Math.max(1, Math.min(reader.getWidth(0), reader.getHeight(0)) / READ_SIZE);
                var param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Stores the hash unless it is already stored with the current signature.
     *
     * @return {@code true} if the document was saved
     */
    protected boolean store(CoreSession session, DocumentModel doc, Computed computed) {
        var hex = PHash.toHex(computed.hash());
        if (signature.equals(getStoredSignature(doc)) && hex.equals(doc.getPropertyValue(PHASH_PROPERTY))) {
            return false;
        }
        doc.addFacet(IMAGE_HASH_FACET);
        doc.setPropertyValue(PHASH_PROPERTY, hex);
        doc.setPropertyValue(BANDS_PROPERTY, computed.degenerate() ? new ArrayList<String>()
                : new ArrayList<>(HashBands.bands(bandPrefix, computed.hash())));
        doc.setPropertyValue(SIGNATURE_PROPERTY, signature);
        // technical metadata: no new version, modification date, audit entry, notification or views generation
        doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.NONE);
        doc.putContextData(VersioningService.DISABLE_AUTOMATIC_VERSIONING, Boolean.TRUE);
        doc.putContextData(VersioningService.DISABLE_AUTO_CHECKOUT, Boolean.TRUE);
        doc.putContextData(DublinCoreListener.DISABLE_DUBLINCORE_LISTENER, Boolean.TRUE);
        doc.putContextData(CoreSession.DISABLE_AUDIT_LOGGER, Boolean.TRUE);
        doc.putContextData("disableNotificationService", Boolean.TRUE);
        doc.putContextData(DISABLE_PICTURE_VIEWS_GENERATION_LISTENER, Boolean.TRUE);
        session.saveDocument(doc);
        return true;
    }

    protected List<SimilarImage> search(DocumentModel doc, NuxeoPrincipal principal, Computed computed,
            SearchIndex index, boolean orientations, int radius, int threshold) {
        var hashes = orientations ? PHash.orientationHashes(computed.reduced()) : new long[] { computed.hash() };
        var keys = new LinkedHashSet<String>();
        for (var hash : hashes) {
            keys.addAll(HashBands.queryKeys(bandPrefix, hash, radius));
        }
        var nxql = """
                SELECT %s, %s FROM Document WHERE %s IN (%s) AND %s <> %s
                AND ecm:isProxy = 0 AND ecm:isVersion = 0 AND ecm:isTrashed = 0
                """.formatted(ECM_UUID, PHASH_PROPERTY, BANDS_PROPERTY,
                keys.stream().map(NXQL::escapeString).collect(joining(",")), ECM_UUID, NXQL.escapeString(doc.getId()));

        var maxCandidates = config.getMaxCandidates();
        var query = SearchQuery.builder(nxql, principal).searchIndex(index).limit(maxCandidates).build();
        var response = Framework.getService(SearchService.class).search(query);
        if (response.getHitsCount() >= maxCandidates) {
            log.warn("Document: {} reached the max number of candidates: {}, some duplicates may be missed",
                    doc.getId(), maxCandidates);
        }

        var similar = new LinkedHashMap<String, SimilarImage>();
        for (var hit : response.getHitsAsMap()) {
            if (hit.get(PHASH_PROPERTY) instanceof String hex && hit.get(ECM_UUID) instanceof String docId) {
                var candidate = PHash.fromHex(hex);
                var distance = Arrays.stream(hashes)
                                     .mapToInt(hash -> PHash.distance(hash, candidate))
                                     .min()
                                     .orElseThrow();
                if (distance <= threshold) {
                    similar.putIfAbsent(docId, new SimilarImage(docId, distance));
                }
            }
        }
        return similar.values()
                      .stream()
                      .sorted(Comparator.comparingInt(SimilarImage::distance).thenComparing(SimilarImage::docId))
                      .toList();
    }

    protected void fireDuplicatedImage(CoreSession session, DocumentModel doc, NuxeoPrincipal principal,
            List<SimilarImage> duplicates) {
        var distances = new LinkedHashMap<String, Integer>();
        duplicates.forEach(duplicate -> distances.put(duplicate.docId(), duplicate.distance()));
        var ctx = new DocumentEventContext(session, principal, doc);
        ctx.setProperty(DUPLICATES_EVENT_PROPERTY, distances);
        Framework.getService(EventService.class).fireEvent(ctx.newEvent(DUPLICATED_IMAGE_EVENT));
    }
}
