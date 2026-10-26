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
package nuxeo.labs.images.dup.pageprovider;

import static java.util.Objects.requireNonNullElse;
import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;
import static org.nuxeo.ecm.platform.query.api.PageProviderSpec.CORE_SESSION_PROPERTY;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.annotation.Nullable;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.query.api.AbstractPageProvider;
import org.nuxeo.runtime.api.Framework;

import nuxeo.labs.images.dup.HashBands;
import nuxeo.labs.images.dup.ImageHashService;

/**
 * Lists the documents readable by the current user whose image is a near duplicate of a reference document's image,
 * closest first, see {@link ImageHashService#findSimilar}.
 * <p>
 * Named parameters: {@value #DOC_ID_PARAM} (the page is empty without it), {@value #THRESHOLD_PARAM},
 * {@value #RADIUS_PARAM}, {@value #ORIENTATIONS_PARAM} and {@value #LIMIT_PARAM}. Blank values use the configured
 * defaults. The distance of each returned document is put in its context data under {@link #DISTANCE_CONTEXT_DATA}.
 *
 * @since 2025.1.0
 */
public class SimilarImagesPageProvider extends AbstractPageProvider<DocumentModel> {

    private static final long serialVersionUID = 1L;

    /** @since 2025.1.0 */
    public static final String NAME = "imagehash_similar_images";

    /** @since 2025.1.0 */
    public static final String DOC_ID_PARAM = "docId";

    /** @since 2025.1.0 */
    public static final String THRESHOLD_PARAM = "threshold";

    /** @since 2025.1.0 */
    public static final String RADIUS_PARAM = "radius";

    /** @since 2025.1.0 */
    public static final String ORIENTATIONS_PARAM = "orientations";

    /**
     * Max number of returned documents. Not named {@code maxResults}, a name the REST search endpoint reserves.
     *
     * @since 2025.1.0
     */
    public static final String LIMIT_PARAM = "limit";

    /**
     * Context data holding the Hamming distance of each returned document, an {@link Integer}.
     *
     * @since 2025.1.0
     */
    public static final String DISTANCE_CONTEXT_DATA = "imageHashDistance";

    protected static final int SC_BAD_REQUEST = 400;

    protected List<DocumentModel> currentPageDocuments;

    /**
     * @since 2025.1.0
     */
    @Override
    public List<DocumentModel> getCurrentPage() {
        if (currentPageDocuments == null) {
            currentPageDocuments = fetchCurrentPage();
        }
        return currentPageDocuments;
    }

    @Override
    protected void pageChanged() {
        currentPageDocuments = null;
        super.pageChanged();
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public void setCurrentPageOffset(long offset) {
        // the service sets the page index, which fetches, then the offset, which takes precedence
        if (offset != getCurrentPageOffset()) {
            currentPageDocuments = null;
        }
        super.setCurrentPageOffset(offset);
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public void refresh() {
        currentPageDocuments = null;
        super.refresh();
    }

    protected List<DocumentModel> fetchCurrentPage() {
        var params = getNamedParameters();
        var docId = params.get(DOC_ID_PARAM);
        if (docId == null || docId.isBlank()) {
            setResultsCount(0);
            return List.of();
        }
        var threshold = parseInt(params, THRESHOLD_PARAM, 0, Long.SIZE);
        var radius = parseInt(params, RADIUS_PARAM, 0, HashBands.MAX_RADIUS);
        var orientations = parseBoolean(params, ORIENTATIONS_PARAM);
        int limit = requireNonNullElse(parseInt(params, LIMIT_PARAM, 1, Integer.MAX_VALUE),
                ImageHashService.DEFAULT_MAX_RESULTS);

        var session = getCoreSession();
        var doc = session.getDocument(new IdRef(docId.strip()));
        var similar = Framework.getService(ImageHashService.class)
                               .findSimilar(doc, session.getPrincipal(), threshold, radius, orientations);
        similar = similar.subList(0, Math.min(similar.size(), limit));
        setResultsCount(similar.size());

        var offset = getCurrentPageOffset();
        if (offset >= similar.size()) {
            return List.of();
        }
        var pageSize = getMinMaxPageSize();
        var end = pageSize > 0 ? Math.min(similar.size(), offset + pageSize) : similar.size();
        var docs = new ArrayList<DocumentModel>();
        for (var image : similar.subList((int) offset, (int) end)) {
            var ref = new IdRef(image.docId());
            // the search index can lag behind deletions and permission changes
            if (session.exists(ref) && session.hasPermission(ref, SecurityConstants.READ)) {
                var similarDoc = session.getDocument(ref);
                similarDoc.putContextData(DISTANCE_CONTEXT_DATA, image.distance());
                docs.add(similarDoc);
            }
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, String> getNamedParameters() {
        var searchDocument = getSearchDocumentModel();
        var params = searchDocument == null ? null : searchDocument.getContextData(NAMED_PARAMETERS);
        return params instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    protected CoreSession getCoreSession() {
        if (getProperties().get(CORE_SESSION_PROPERTY) instanceof CoreSession session) {
            return session;
        }
        throw new NuxeoException("No CoreSession in the properties of page provider: " + getName());
    }

    @Nullable
    protected Integer parseInt(Map<String, String> params, String name, int min, int max) {
        var value = params.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        Integer parsed;
        try {
            parsed = Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            parsed = null;
        }
        if (parsed == null || parsed < min || parsed > max) {
            throw new NuxeoException("Parameter: %s must be an integer between %d and %d, got: %s".formatted(name,
                    min, max, value), SC_BAD_REQUEST);
        }
        return parsed;
    }

    @Nullable
    protected Boolean parseBoolean(Map<String, String> params, String name) {
        var value = params.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new NuxeoException("Parameter: %s must be true or false, got: %s".formatted(name, value),
                    SC_BAD_REQUEST);
        };
    }
}
