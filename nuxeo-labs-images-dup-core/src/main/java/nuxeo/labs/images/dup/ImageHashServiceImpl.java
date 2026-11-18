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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import jakarta.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.nuxeo.ecm.platform.picture.api.adapters.MultiviewPicture;
import org.nuxeo.runtime.api.Framework;
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
    protected static final String ALGORITHM = "phash64v2";

    /** Images are subsampled when read, down to about this size on their smaller side. */
    protected static final int READ_SIZE = 256;

    /** Images are subsampled further when read, if needed, so that no more pixels than this are decoded. */
    protected static final int MAX_READ_PIXELS = 1 << 21;

    /**
     * Name of the search client querying the repository itself, declared by the {@code nuxeo-core-search} bundle. It
     * is the only client that cannot serve the band query; every other one is a search engine.
     */
    protected static final String REPOSITORY_CLIENT = "repository";

    protected static final String NO_SEARCH_ENGINE_MESSAGE = "The default search index of repository: %s is served by"
            + " the '" + REPOSITORY_CLIENT + "' client, which cannot run the image band query. This plugin requires a"
            + " search engine: install nuxeo-search-client-opensearch1, nuxeo-search-client-opensearch2 or"
            + " nuxeo-search-client-elasticsearch9";

    protected static final int SC_BAD_REQUEST = 400;

    protected static final int SC_FORBIDDEN = 403;

    /** Context data read by the notification listener; Nuxeo has no constant for it. */
    protected static final String DISABLE_NOTIFICATION_SERVICE = "disableNotificationService";

    protected ImageHashConfigurationDescriptor config;

    protected String signature;

    /** Short id of the signature, prefixing the band keys. */
    protected String bandPrefix;

    /** Logged only once, the listener would otherwise report it for every picture. */
    protected volatile boolean missingSearchEngineLogged;

    /**
     * @param orientations the hashes of the 8 orientations of the image, the first one being {@code hash};
     *            {@code null} when not needed, or for a degenerate image read from the document
     */
    protected record Computed(long hash, @Nullable long[] orientations, boolean degenerate) {
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public void start(ComponentContext context) {
        config = requireNonNullElseGet(getDescriptor(CONFIGURATION_XP, UNIQUE_DESCRIPTOR_ID),
                ImageHashConfigurationDescriptor::new);
        // not the view: the hash comes from the first available one, and does not depend on it
        signature = ALGORITHM;
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
        if (computed == null || !store(session, doc, computed) || computed.degenerate()
                || !config.isDetectionEnabled()) {
            return;
        }
        // the hash is stored even without a working search engine, only the detection is skipped
        var index = findSearchIndex(doc.getRepositoryName());
        if (index == null) {
            if (!missingSearchEngineLogged) {
                missingSearchEngineLogged = true;
                log.error(NO_SEARCH_ENGINE_MESSAGE.formatted(doc.getRepositoryName())
                        + ". Hashes are still stored, duplicate detection is disabled");
            }
            return;
        }
        SimilarImages duplicates;
        try {
            duplicates = search(doc, principal, computed, index, config.isDetectionOrientations(),
                    config.getDetectionRadius(), config.getThreshold());
        } catch (RuntimeException e) {
            // the search runs in the transaction that has just saved the hash: failing here would roll it back
            log.error("The hash of document: {} is stored, but its duplicate detection failed", doc.getId(), e);
            return;
        }
        if (!duplicates.images().isEmpty()) {
            fireDuplicatedImage(session, doc, principal, duplicates);
        }
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public boolean detectDuplicates(CoreSession session, DocumentModel doc, NuxeoPrincipal principal) {
        if (!signature.equals(getStoredSignature(doc))) {
            return false;
        }
        var computed = storedOrComputed(doc, config.isDetectionOrientations());
        if (computed == null || computed.degenerate()) {
            return false;
        }
        var duplicates = search(doc, principal, computed, requireSearchIndex(doc.getRepositoryName()),
                config.isDetectionOrientations(), config.getDetectionRadius(), config.getThreshold());
        if (duplicates.images().isEmpty()) {
            return false;
        }
        fireDuplicatedImage(session, doc, principal, duplicates);
        return true;
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public SimilarImages findSimilar(DocumentModel doc, NuxeoPrincipal principal, @Nullable Integer threshold,
            @Nullable Integer radius, @Nullable Boolean orientations) {
        checkRange("threshold", threshold, Long.SIZE);
        checkRange("radius", radius, HashBands.MAX_RADIUS);
        if (!principal.isAdministrator()) {
            // each step of radius and the orientations multiply the keys queried, hence the load on the search
            // cluster: the configured values are what every user may ask for
            if (radius != null && radius > config.getSearchRadius()) {
                throw new NuxeoException("Only administrators can search with a radius above: %d, got: %d".formatted(
                        config.getSearchRadius(), radius), SC_FORBIDDEN);
            }
            if (Boolean.TRUE.equals(orientations) && !config.isSearchOrientations()) {
                throw new NuxeoException("Only administrators can search with orientations", SC_FORBIDDEN);
            }
        }
        boolean withOrientations = requireNonNullElse(orientations, config.isSearchOrientations());
        var computed = storedOrComputed(doc, withOrientations);
        if (computed == null || computed.degenerate()) {
            return SimilarImages.NONE;
        }
        return search(doc, principal, computed, requireSearchIndex(doc.getRepositoryName()), withOrientations,
                requireNonNullElse(radius, config.getSearchRadius()),
                requireNonNullElse(threshold, config.getThreshold()));
    }

    /**
     * @throws NuxeoException with a 400 status if the value is set and not between 0 and {@code max}
     */
    protected static void checkRange(String name, @Nullable Integer value, int max) {
        if (value != null && (value < 0 || value > max)) {
            throw new NuxeoException("Parameter: %s must be between 0 and %d, got: %d".formatted(name, max, value),
                    SC_BAD_REQUEST);
        }
    }

    /**
     * Returns the default search index of the repository, or {@code null} when it is served by the repository client,
     * which cannot run the band query.
     * <p>
     * The configured default is resolved rather than looking up an engine index by name, because client names are
     * stack specific ({@code opensearch} for OpenSearch 1 and 2, {@code elasticsearch} for Elasticsearch 9) and
     * because a blue/green reindex registers several engine indexes at once, only one of which is the one to query.
     */
    @Nullable
    protected SearchIndex findSearchIndex(String repository) {
        var searchService = Framework.getService(SearchService.class);
        var indexName = searchService.getDefaultIndexName(repository);
        if (indexName == null) {
            return null;
        }
        var index = searchService.getSearchIndex(indexName);
        return REPOSITORY_CLIENT.equals(index.client()) ? null : index;
    }

    /**
     * Same as {@link #findSearchIndex(String)}, but fails instead of returning {@code null}.
     *
     * @throws NuxeoException if no search engine serves the repository
     */
    protected SearchIndex requireSearchIndex(String repository) {
        var index = findSearchIndex(repository);
        if (index == null) {
            throw new NuxeoException(NO_SEARCH_ENGINE_MESSAGE.formatted(repository));
        }
        return index;
    }

    @Nullable
    protected String getStoredSignature(DocumentModel doc) {
        return doc.hasFacet(IMAGE_HASH_FACET) ? (String) doc.getPropertyValue(SIGNATURE_PROPERTY) : null;
    }

    protected List<String> getStoredOrientations(DocumentModel doc) {
        return doc.getPropertyValue(ORIENTATIONS_PROPERTY) instanceof String[] stored ? List.of(stored) : List.of();
    }

    /**
     * Returns the hashes to search from: the stored ones when they have the current signature, which spares fetching
     * and decoding a blob, and which are the ones the index knows. Otherwise they are computed from the image.
     */
    @Nullable
    protected Computed storedOrComputed(DocumentModel doc, boolean orientations) {
        if (signature.equals(getStoredSignature(doc)) && doc.getPropertyValue(PHASH_PROPERTY) instanceof String hex) {
            var hash = PHash.fromHex(hex);
            var hasBands = doc.getPropertyValue(BANDS_PROPERTY) instanceof Object[] bands && bands.length > 0;
            if (!hasBands || !orientations) {
                return new Computed(hash, null, !hasBands);
            }
            var stored = getStoredOrientations(doc);
            if (stored.size() == PHash.ORIENTATIONS) {
                return new Computed(hash, stored.stream().mapToLong(PHash::fromHex).toArray(), false);
            }
        }
        return compute(doc);
    }

    @Nullable
    protected Computed compute(DocumentModel doc) {
        var image = readImage(doc);
        if (image == null) {
            log.debug("No readable picture view for document: {}", doc::getId);
            return null;
        }
        var reduced = PHash.reduce(image);
        var orientations = PHash.orientationHashes(reduced);
        return new Computed(orientations[0], orientations, PHash.isDegenerate(image, reduced));
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
            } catch (IOException | RuntimeException e) {
                // runtime exceptions too: image readers throw some on corrupted or oversized files, and so can a
                // blob provider. An unreadable view must neither prevent trying the next one nor fail the caller.
                log.warn("Cannot read view: {} of document: {}", title, doc.getId(), e);
            }
        }
        // a picture with none of the configured views is never hashed, which nothing else would tell
        log.warn("None of the configured picture views: {} can be read for document: {}, it is not hashed",
                config.getViews(), doc.getId());
        return null;
    }

    @Nullable
    protected BufferedImage readSubsampled(InputStream stream) throws IOException {
        // disk-backed cache in java.io.tmpdir, which nuxeoctl points at the Nuxeo temporary directory
        try (var input = ImageIO.createImageInputStream(stream)) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var step = subsamplingStep(reader.getWidth(0), reader.getHeight(0));
                var param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Returns the subsampling step bringing the smaller side of the image down to about {@link #READ_SIZE}, raised if
     * needed so that at most {@link #MAX_READ_PIXELS} are decoded: reducing the smaller side of a very elongated
     * image is not enough, its luminance matrix would take hundreds of megabytes.
     */
    protected static int subsamplingStep(int width, int height) {
        var step = Math.max(1, Math.min(width, height) / READ_SIZE);
        while ((long) Math.ceilDiv(width, step) * Math.ceilDiv(height, step) > MAX_READ_PIXELS) {
            step++;
        }
        return step;
    }

    /**
     * Stores the hash unless it is already stored with the current signature.
     *
     * @return {@code true} if the document was saved
     */
    protected boolean store(CoreSession session, DocumentModel doc, Computed computed) {
        var hex = PHash.toHex(computed.hash());
        // like the band keys, of no use for a degenerate image, which is never searched
        var orientations = new ArrayList<String>();
        if (!computed.degenerate()) {
            Arrays.stream(computed.orientations()).mapToObj(PHash::toHex).forEach(orientations::add);
        }
        if (signature.equals(getStoredSignature(doc)) && hex.equals(doc.getPropertyValue(PHASH_PROPERTY))
                && orientations.equals(getStoredOrientations(doc))) {
            return false;
        }
        doc.addFacet(IMAGE_HASH_FACET);
        doc.setPropertyValue(PHASH_PROPERTY, hex);
        doc.setPropertyValue(ORIENTATIONS_PROPERTY, orientations);
        doc.setPropertyValue(BANDS_PROPERTY, computed.degenerate() ? new ArrayList<String>()
                : new ArrayList<>(HashBands.bands(bandPrefix, computed.hash())));
        doc.setPropertyValue(SIGNATURE_PROPERTY, signature);
        // technical metadata: no new version, modification date, audit entry, notification or views generation
        doc.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.NONE);
        doc.putContextData(VersioningService.DISABLE_AUTOMATIC_VERSIONING, Boolean.TRUE);
        doc.putContextData(VersioningService.DISABLE_AUTO_CHECKOUT, Boolean.TRUE);
        doc.putContextData(DublinCoreListener.DISABLE_DUBLINCORE_LISTENER, Boolean.TRUE);
        doc.putContextData(CoreSession.DISABLE_AUDIT_LOGGER, Boolean.TRUE);
        doc.putContextData(DISABLE_NOTIFICATION_SERVICE, Boolean.TRUE);
        doc.putContextData(DISABLE_PICTURE_VIEWS_GENERATION_LISTENER, Boolean.TRUE);
        session.saveDocument(doc);
        return true;
    }

    protected SimilarImages search(DocumentModel doc, NuxeoPrincipal principal, Computed computed,
            SearchIndex index, boolean orientations, int radius, int threshold) {
        var hashes = orientations ? computed.orientations() : new long[] { computed.hash() };
        var maxCandidates = config.getMaxCandidates();
        // candidate document id to hash; in the usual case a single query fetches them all
        var candidates = new LinkedHashMap<String, String>();
        var truncated = !fetchCandidates(doc, principal, index, queryKeys(hashes, radius), maxCandidates, candidates);
        if (truncated && radius > 0) {
            // That query has no order: the candidates it returned are an arbitrary subset. Ask again from the exact
            // bands outwards, each radius with what is left of the budget, so that the candidates dropped are those
            // sharing no exact band with the hash, the least likely duplicates.
            candidates.clear();
            truncated = false;
            var queried = new HashSet<String>();
            for (var r = 0; r <= radius && !truncated; r++) {
                var keys = queryKeys(hashes, r);
                keys.removeAll(queried);
                var remaining = maxCandidates - candidates.size();
                truncated = remaining <= 0 || !fetchCandidates(doc, principal, index, keys, remaining, candidates);
                queried.addAll(keys);
            }
        }
        if (truncated) {
            log.warn("Document: {} reached the max number of candidates: {}, some duplicates may be missed",
                    doc.getId(), maxCandidates);
        }

        var similar = new ArrayList<SimilarImage>();
        candidates.forEach((docId, hex) -> {
            var candidate = PHash.fromHex(hex);
            var distance = Arrays.stream(hashes).mapToInt(hash -> PHash.distance(hash, candidate)).min().orElseThrow();
            if (distance <= threshold) {
                similar.add(new SimilarImage(docId, distance));
            }
        });
        similar.sort(Comparator.comparingInt(SimilarImage::distance).thenComparing(SimilarImage::docId));
        return new SimilarImages(List.copyOf(similar), truncated);
    }

    protected Set<String> queryKeys(long[] hashes, int radius) {
        var keys = new LinkedHashSet<String>();
        for (var hash : hashes) {
            keys.addAll(HashBands.queryKeys(bandPrefix, hash, radius));
        }
        return keys;
    }

    /**
     * Adds to the candidates the documents holding one of the band keys, at most {@code limit} of them.
     *
     * @return {@code false} if the limit was reached, in which case some candidates may not have been fetched
     */
    protected boolean fetchCandidates(DocumentModel doc, NuxeoPrincipal principal, SearchIndex index, Set<String> keys,
            int limit, Map<String, String> candidates) {
        var nxql = """
                SELECT %s, %s FROM Document WHERE %s IN (%s) AND %s <> %s
                AND ecm:isProxy = 0 AND ecm:isVersion = 0 AND ecm:isTrashed = 0
                """.formatted(ECM_UUID, PHASH_PROPERTY, BANDS_PROPERTY,
                keys.stream().map(NXQL::escapeString).collect(joining(",")), ECM_UUID, NXQL.escapeString(doc.getId()));
        var query = SearchQuery.builder(nxql, principal).searchIndex(index).limit(limit).build();
        var response = Framework.getService(SearchService.class).search(query);
        for (var hit : response.getHitsAsMap()) {
            if (hit.get(PHASH_PROPERTY) instanceof String hex && hit.get(ECM_UUID) instanceof String docId) {
                candidates.putIfAbsent(docId, hex);
            }
        }
        return response.getHitsCount() < limit;
    }

    protected void fireDuplicatedImage(CoreSession session, DocumentModel doc, NuxeoPrincipal principal,
            SimilarImages duplicates) {
        var distances = new LinkedHashMap<String, Integer>();
        duplicates.images()
                  .stream()
                  .limit(MAX_EVENT_DUPLICATES)
                  .forEach(duplicate -> distances.put(duplicate.docId(), duplicate.distance()));
        var ctx = new DocumentEventContext(session, principal, doc);
        ctx.setProperty(DUPLICATES_EVENT_PROPERTY, distances);
        ctx.setProperty(DUPLICATES_COUNT_EVENT_PROPERTY, duplicates.images().size());
        ctx.setProperty(DUPLICATES_TRUNCATED_EVENT_PROPERTY, duplicates.truncated());
        Framework.getService(EventService.class).fireEvent(ctx.newEvent(DUPLICATED_IMAGE_EVENT));
    }
}
