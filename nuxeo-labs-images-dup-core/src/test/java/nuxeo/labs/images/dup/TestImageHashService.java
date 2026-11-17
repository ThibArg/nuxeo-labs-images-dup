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

import static nuxeo.labs.images.dup.ImageHashService.BANDS_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.DUPLICATES_COUNT_EVENT_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.DUPLICATES_EVENT_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.DUPLICATES_TRUNCATED_EVENT_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.IMAGE_HASH_FACET;
import static nuxeo.labs.images.dup.ImageHashService.MAX_EVENT_DUPLICATES;
import static nuxeo.labs.images.dup.ImageHashService.PHASH_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.SIGNATURE_PROPERTY;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.DISTANCE_CONTEXT_DATA;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.DOC_ID_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.LIMIT_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.ORIENTATIONS_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.RADIUS_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.THRESHOLD_PARAM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.platform.picture.recompute.RecomputeViewsAction.RecomputeViewsComputation.PICTURE_VIEWS_GENERATION_DONE_EVENT;
import static org.nuxeo.ecm.platform.query.api.PageProviderSpec.CORE_SESSION_PROPERTY;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import jakarta.inject.Inject;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.impl.UserPrincipal;
import org.nuxeo.ecm.core.api.impl.blob.ByteArrayBlob;
import org.nuxeo.ecm.core.api.model.ReadOnlyPropertyException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.io.registry.MarshallerHelper;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext.CtxBuilder;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.search.SearchQuery;
import org.nuxeo.ecm.core.search.SearchService;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchCoreSearchFeature;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.ecm.platform.query.api.PageProviderSpec;
import org.nuxeo.runtime.api.login.NuxeoLoginContext;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import nuxeo.labs.images.dup.bulk.ComputeImageHashAction;
import nuxeo.labs.images.dup.bulk.DetectImageDuplicatesAction;
import nuxeo.labs.images.dup.io.SimilarImageJsonEnricher;
import nuxeo.labs.images.dup.operations.FindSimilarImagesOp;
import nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider;

@RunWith(FeaturesRunner.class)
// the duplicate search requires the OpenSearch client, started here as an embedded node
@Features({ AutomationFeature.class, OpenSearchCoreSearchFeature.class })
@RepositoryConfig(cleanup = Granularity.METHOD)
// Picture doctype, without the views generation (no ImageMagick needed)
@Deploy("org.nuxeo.ecm.platform.tag:OSGI-INF/faceted-tag-service-core-types.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core:OSGI-INF/picture-schemas-contrib.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core:OSGI-INF/picture-adapter-contrib.xml")
// target of the bundle's Web UI contribution
@Deploy("org.nuxeo.web.resources.core")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-opensearch-mapping-contrib.xml")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-capture-listener-contrib.xml")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-failing-save-listener-contrib.xml")
public class TestImageHashService {

    protected static final NuxeoPrincipal JOHN = new UserPrincipal("john", List.of(), false, false);

    @Inject
    protected CoreSession session;

    @Inject
    protected ImageHashService service;

    @Inject
    protected EventService eventService;

    @Inject
    protected AutomationService automationService;

    @Inject
    protected BulkService bulkService;

    @Inject
    protected SearchService searchService;

    @Inject
    protected PageProviderService pageProviderService;

    @Inject
    protected TransactionalFeature txFeature;

    protected final BufferedImage original = TestImages.sample(42, 560, 420);

    @Before
    public void before() {
        DuplicatedImageCaptureListener.clear();
    }

    @Test
    public void testHashIsStoredQuietlyAfterViewsGeneration() {
        var doc = createPicture("/", "original", original);
        var modified = doc.getPropertyValue("dc:modified");
        viewsGenerated(doc);

        doc = session.getDocument(doc.getRef());
        assertTrue(doc.hasFacet(IMAGE_HASH_FACET));
        assertEquals(16, ((String) doc.getPropertyValue(PHASH_PROPERTY)).length());
        assertEquals(HashBands.BAND_COUNT, ((Object[]) doc.getPropertyValue(BANDS_PROPERTY)).length);
        assertEquals(service.getSignature(), doc.getPropertyValue(SIGNATURE_PROPERTY));
        assertEquals(modified, doc.getPropertyValue("dc:modified"));
        assertTrue(session.getVersions(doc.getRef()).isEmpty());
        assertTrue(DuplicatedImageCaptureListener.getEvents().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDuplicatedImageEvent() {
        var first = createPicture("/", "first", original);
        viewsGenerated(first);
        assertTrue(DuplicatedImageCaptureListener.getEvents().isEmpty());

        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        var other = createPicture("/", "other", TestImages.sample(7, 560, 420));
        viewsGenerated(resized, other);

        var events = DuplicatedImageCaptureListener.getEvents();
        assertEquals(1, events.size());
        var ctx = (DocumentEventContext) events.getFirst().getContext();
        assertEquals(resized.getId(), ctx.getSourceDocument().getId());
        var duplicates = (Map<String, Integer>) ctx.getProperty(DUPLICATES_EVENT_PROPERTY);
        assertEquals(Set.of(first.getId()), duplicates.keySet());
        assertEquals(Boolean.FALSE, ctx.getProperty(DUPLICATES_TRUNCATED_EVENT_PROPERTY));

        // views generated again with the same image: hash unchanged, no new event
        DuplicatedImageCaptureListener.clear();
        viewsGenerated(resized);
        assertTrue(DuplicatedImageCaptureListener.getEvents().isEmpty());
    }

    @Test
    public void testFindSimilarHonorsPermissions() {
        var privateFolder = createFolder("private", false);
        var sharedFolder = createFolder("shared", true);
        var hidden = createPicture(privateFolder.getPathAsString(), "hidden", original);
        var resized = createPicture(sharedFolder.getPathAsString(), "resized",
                TestImages.resize(original, 280, 210));
        var rotated = createPicture(sharedFolder.getPathAsString(), "rotated",
                TestImages.rotate90(original));
        var other = createPicture(sharedFolder.getPathAsString(), "other", TestImages.sample(7, 560, 420));
        viewsGenerated(hidden, resized, rotated, other);

        assertEquals(Set.of(hidden.getId(), rotated.getId()),
                ids(service.findSimilar(resized, session.getPrincipal(), null, null, null)));
        assertEquals(Set.of(rotated.getId()), ids(service.findSimilar(resized, JOHN, null, null, null)));
        assertEquals(Set.of(hidden.getId()),
                ids(service.findSimilar(resized, session.getPrincipal(), null, null, Boolean.FALSE)));
    }

    @Test
    public void testFindSimilarOperation() throws OperationException {
        var first = createPicture("/", "first", original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        var recompressed = createPicture("/", "recompressed", TestImages.recompress(original, 0.3f));
        var other = createPicture("/", "other", TestImages.sample(7, 560, 420));
        viewsGenerated(first, resized, recompressed, other);

        var ctx = new OperationContext(session);
        ctx.setInput(first);
        var result = (DocumentModelList) automationService.run(ctx, FindSimilarImagesOp.ID);
        assertEquals(Set.of(resized.getId(), recompressed.getId()),
                result.stream().map(DocumentModel::getId).collect(Collectors.toSet()));
        assertEquals(Boolean.FALSE, ctx.get(FindSimilarImagesOp.TRUNCATED_CONTEXT_VARIABLE));

        ctx = new OperationContext(session);
        ctx.setInput(first);
        result = (DocumentModelList) automationService.run(ctx, FindSimilarImagesOp.ID, Map.of("maxResults", 1));
        assertEquals(1, result.size());
    }

    @Test
    public void testSimilarImagesPageProvider() throws IOException {
        var first = createPicture("/", "first", original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        var recompressed = createPicture("/", "recompressed", TestImages.recompress(original, 0.3f));
        var other = createPicture("/", "other", TestImages.sample(7, 560, 420));
        viewsGenerated(first, resized, recompressed, other);

        var provider = similarImages(session, Map.of(DOC_ID_PARAM, first.getId()), 40, 0);
        var docs = provider.getCurrentPage();
        assertEquals(Set.of(resized.getId(), recompressed.getId()), docIds(docs));
        assertEquals(2, provider.getResultsCount());
        var distances = docs.stream().map(doc -> (Integer) doc.getContextData(DISTANCE_CONTEXT_DATA)).toList();
        assertEquals(distances.stream().sorted().toList(), distances);

        var ctx = CtxBuilder.enrichDoc(SimilarImageJsonEnricher.NAME).get();
        var json = MarshallerHelper.objectToJson(docs.getFirst(), ctx);
        assertTrue(json, json.contains(
                "\"imageSimilarity\":{\"distance\":" + distances.getFirst() + ",\"truncated\":false}"));
        assertFalse(((SimilarImagesPageProvider) provider).isTruncated());
        json = MarshallerHelper.objectToJson(session.getDocument(first.getRef()), ctx);
        assertTrue(json, json.contains("\"imageSimilarity\":null"));
    }

    @Test
    public void testSimilarImagesPageProviderPaging() {
        var first = createPicture("/", "first", original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        var recompressed = createPicture("/", "recompressed", TestImages.recompress(original, 0.3f));
        var rotated = createPicture("/", "rotated", TestImages.rotate90(original));
        viewsGenerated(first, resized, recompressed, rotated);

        var params = Map.of(DOC_ID_PARAM, first.getId());
        var firstPage = similarImages(session, params, 2, 0);
        assertEquals(2, firstPage.getCurrentPage().size());
        assertEquals(3, firstPage.getResultsCount());
        var secondPage = similarImages(session, params, 2, 1);
        assertEquals(1, secondPage.getCurrentPage().size());
        var all = new ArrayList<>(firstPage.getCurrentPage());
        all.addAll(secondPage.getCurrentPage());
        assertEquals(Set.of(resized.getId(), recompressed.getId(), rotated.getId()), docIds(all));

        params = Map.of(DOC_ID_PARAM, first.getId(), LIMIT_PARAM, "2");
        firstPage = similarImages(session, params, 2, 0);
        assertEquals(2, firstPage.getCurrentPage().size());
        assertEquals(2, firstPage.getResultsCount());
        assertTrue(similarImages(session, params, 2, 1).getCurrentPage().isEmpty());
    }

    @Test
    public void testSimilarImagesPageProviderHonorsPermissions() {
        var privateFolder = createFolder("private", false);
        var sharedFolder = createFolder("shared", true);
        var hidden = createPicture(privateFolder.getPathAsString(), "hidden", original);
        var resized = createPicture(sharedFolder.getPathAsString(), "resized",
                TestImages.resize(original, 280, 210));
        var rotated = createPicture(sharedFolder.getPathAsString(), "rotated", TestImages.rotate90(original));
        viewsGenerated(hidden, resized, rotated);

        var params = Map.of(DOC_ID_PARAM, resized.getId());
        assertEquals(Set.of(hidden.getId(), rotated.getId()),
                docIds(similarImages(session, params, 40, 0).getCurrentPage()));
        var johnSession = CoreInstance.getCoreSession(session.getRepositoryName(), JOHN);
        assertEquals(Set.of(rotated.getId()), docIds(similarImages(johnSession, params, 40, 0).getCurrentPage()));
    }

    @Test
    public void testSimilarImagesPageProviderParameters() {
        var first = createPicture("/", "first", original);
        var rotated = createPicture("/", "rotated", TestImages.rotate90(original));
        viewsGenerated(first, rotated);

        assertTrue(similarImages(session, Map.of(), 40, 0).getCurrentPage().isEmpty());
        assertEquals(Set.of(rotated.getId()),
                docIds(similarImages(session, Map.of(DOC_ID_PARAM, first.getId()), 40, 0).getCurrentPage()));
        assertTrue(similarImages(session, Map.of(DOC_ID_PARAM, first.getId(), ORIENTATIONS_PARAM, "false"), 40, 0)
                .getCurrentPage()
                .isEmpty());

        assertBadRequest(Map.of(DOC_ID_PARAM, first.getId(), RADIUS_PARAM, "3"));
        assertBadRequest(Map.of(DOC_ID_PARAM, first.getId(), THRESHOLD_PARAM, "abc"));
        assertBadRequest(Map.of(DOC_ID_PARAM, first.getId(), ORIENTATIONS_PARAM, "maybe"));
        assertBadRequest(Map.of(DOC_ID_PARAM, first.getId(), LIMIT_PARAM, "0"));
    }

    @Test
    public void testBlankImagesAreNotDuplicates() {
        var blank1 = createPicture("/", "blank1", TestImages.uniform(Color.WHITE, 560, 420));
        viewsGenerated(blank1);
        var blank2 = createPicture("/", "blank2", TestImages.uniform(Color.WHITE, 560, 420));
        viewsGenerated(blank2);

        blank2 = session.getDocument(blank2.getRef());
        assertTrue(blank2.hasFacet(IMAGE_HASH_FACET));
        assertTrue(ArrayUtils.isEmpty((Object[]) blank2.getPropertyValue(BANDS_PROPERTY)));
        assertTrue(DuplicatedImageCaptureListener.getEvents().isEmpty());
        assertEquals(SimilarImages.NONE, service.findSimilar(blank2, session.getPrincipal(), null, null, null));
    }

    @Test
    public void testBulkAction() throws InterruptedException {
        var first = createPicture("/", "first", original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        txFeature.nextTransaction();

        var commandId = bulkService.submit(new BulkCommand.Builder(ComputeImageHashAction.ACTION_NAME,
                "SELECT * FROM Picture", session.getPrincipal().getName()).repository(session.getRepositoryName())
                                                                           .build());
        assertTrue(bulkService.await(commandId, Duration.ofSeconds(60)));
        assertEquals(BulkStatus.State.COMPLETED, bulkService.getStatus(commandId).getState());
        txFeature.nextTransaction();

        assertTrue(session.getDocument(first.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertTrue(session.getDocument(resized.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertTrue(DuplicatedImageCaptureListener.getEvents().isEmpty());
        assertEquals(Set.of(resized.getId()), ids(service.findSimilar(first, session.getPrincipal(), null, null, null)));
    }

    @Test
    public void testBandKeysAreNotIndexedInFulltext() {
        var doc = createPicture("/", "original", original);
        viewsGenerated(doc);

        doc = session.getDocument(doc.getRef());
        var bands = (Object[]) doc.getPropertyValue(BANDS_PROPERTY);
        assertEquals(HashBands.BAND_COUNT, bands.length);

        // the band keys are opaque search keys, they must not leak into fulltext search
        for (var band : bands) {
            var nxql = "SELECT * FROM Document WHERE ecm:fulltext = " + NXQL.escapeString((String) band);
            var query = SearchQuery.builder(nxql, session.getPrincipal())
                                   .index(searchService.getDefaultIndexName(session.getRepositoryName()))
                                   .limit(10)
                                   .build();
            assertEquals("band key found in fulltext: " + band, 0, searchService.search(query).getHitsCount());
        }
    }

    @Test
    public void testHashAndSignatureAreNotIndexedInFulltext() {
        var doc = createPicture("/", "original", original);
        viewsGenerated(doc);

        doc = session.getDocument(doc.getRef());
        var phash = (String) doc.getPropertyValue(PHASH_PROPERTY);
        var signature = (String) doc.getPropertyValue(SIGNATURE_PROPERTY);
        assertEquals(service.getSignature(), signature);

        // the same signature on every hashed picture: in fulltext, they would all match it
        for (var term : List.of(signature, phash)) {
            var nxql = "SELECT * FROM Document WHERE ecm:fulltext = " + NXQL.escapeString(term);
            var query = SearchQuery.builder(nxql, session.getPrincipal())
                                   .index(searchService.getDefaultIndexName(session.getRepositoryName()))
                                   .limit(10)
                                   .build();
            assertEquals("hash metadata found in fulltext: " + term, 0, searchService.search(query).getHitsCount());
        }
    }

    /**
     * Duplicates hashed at the same time are not detected on the spot: neither is in the search index yet when the
     * other one is searched. The detection bulk action, run once they are indexed, reports them.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testDetectDuplicatesBulkAction() throws InterruptedException {
        var first = createPicture("/", "first", original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        var other = createPicture("/", "other", TestImages.sample(7, 560, 420));
        // one transaction, one event bundle
        viewsGenerated(first, resized, other);
        assertTrue("if this fails the immediate detection now covers this case: rework this test",
                DuplicatedImageCaptureListener.getEvents().isEmpty());
        // never hashed: the action only detects, it must skip it
        createPicture("/", "unhashed", original);
        txFeature.nextTransaction();

        var commandId = bulkService.submit(new BulkCommand.Builder(DetectImageDuplicatesAction.ACTION_NAME,
                "SELECT * FROM Picture", session.getPrincipal().getName()).repository(session.getRepositoryName())
                                                                           .build());
        assertTrue(bulkService.await(commandId, Duration.ofSeconds(60)));
        var status = bulkService.getStatus(commandId);
        assertEquals(BulkStatus.State.COMPLETED, status.getState());
        assertFalse(status.getErrorMessage(), status.hasError());
        var picturesWithDuplicates = (Number) status.getResult()
                                                    .get(DetectImageDuplicatesAction.RESULT_PICTURES_WITH_DUPLICATES);
        assertEquals(2, picturesWithDuplicates.intValue());

        var duplicatesBySource = new HashMap<String, Set<String>>();
        for (var event : DuplicatedImageCaptureListener.getEvents()) {
            var ctx = (DocumentEventContext) event.getContext();
            duplicatesBySource.put(ctx.getSourceDocument().getId(),
                    ((Map<String, Integer>) ctx.getProperty(DUPLICATES_EVENT_PROPERTY)).keySet());
        }
        // a pair is reported once from each side
        assertEquals(Map.of(first.getId(), Set.of(resized.getId()), resized.getId(), Set.of(first.getId())),
                duplicatesBySource);
        assertEquals(2, DuplicatedImageCaptureListener.getEvents().size());
    }

    /**
     * The search lists the documents the user can browse, which is less than reading them. Such a result must be
     * skipped, by the operation as by the page provider, and must not count in {@code maxResults}.
     */
    @Test
    public void testResultsTheUserCannotReadAreSkipped() throws OperationException {
        var sharedFolder = createFolder("shared", true);
        var browsableFolder = session.createDocument(session.createDocumentModel("/", "browsable", "Folder"));
        var acp = browsableFolder.getACP();
        acp.getOrCreateACL().add(new ACE(JOHN.getName(), SecurityConstants.BROWSE, true));
        session.setACP(browsableFolder.getRef(), acp, true);
        var reference = createPicture(sharedFolder.getPathAsString(), "reference", original);
        var browsable = createPicture(browsableFolder.getPathAsString(), "browsable", original);
        var readable = createPicture(sharedFolder.getPathAsString(), "readable",
                TestImages.resize(original, 280, 210));
        viewsGenerated(reference, browsable, readable);

        var johnSession = CoreInstance.getCoreSession(session.getRepositoryName(), JOHN);
        assertTrue(johnSession.exists(browsable.getRef()));
        assertFalse(johnSession.hasPermission(browsable.getRef(), SecurityConstants.READ));

        var ctx = new OperationContext(johnSession);
        ctx.setInput(johnSession.getDocument(reference.getRef()));
        var docs = (DocumentModelList) automationService.run(ctx, FindSimilarImagesOp.ID, Map.of("maxResults", 1));
        assertEquals(Set.of(readable.getId()), docIds(docs));
        assertTrue(docs.getFirst().getContextData(DISTANCE_CONTEXT_DATA) instanceof Integer);

        var provider = similarImages(johnSession, Map.of(DOC_ID_PARAM, reference.getId()), 40, 0);
        assertEquals(Set.of(readable.getId()), docIds(provider.getCurrentPage()));
    }

    /** A very common image must not put thousands of ids in an event: the list is capped, the total is given. */
    @Test
    @SuppressWarnings("unchecked")
    public void testEventDuplicatesAreCapped() {
        var reference = createPicture("/", "reference", original);
        viewsGenerated(reference);
        var hash = PHash.fromHex((String) session.getDocument(reference.getRef()).getPropertyValue(PHASH_PROPERTY));
        for (var i = 0; i <= MAX_EVENT_DUPLICATES; i++) {
            createHashed("same" + i, hash);
        }
        txFeature.nextTransaction();
        viewsGenerated(createPicture("/", "copy", original));

        var events = DuplicatedImageCaptureListener.getEvents();
        assertEquals(1, events.size());
        var ctx = events.getFirst().getContext();
        assertEquals(MAX_EVENT_DUPLICATES, ((Map<String, Integer>) ctx.getProperty(DUPLICATES_EVENT_PROPERTY)).size());
        assertEquals(MAX_EVENT_DUPLICATES + 2, ctx.getProperty(DUPLICATES_COUNT_EVENT_PROPERTY));
    }

    /**
     * The signature is the version of the algorithm and nothing else. The hash comes from the first available view,
     * not always the first configured one, and a perceptual hash does not depend on the view it is computed from.
     */
    @Test
    public void testSignatureDoesNotNameAView() {
        assertEquals(ImageHashServiceImpl.ALGORITHM, service.getSignature());

        var fromSmall = createPicture("/", "fromSmall", original);
        var fromMedium = createPicture("/", "fromMedium",
                jpegView("Medium", "fromMedium", TestImages.resize(original, 1000, 750)));
        viewsGenerated(fromSmall, fromMedium);

        assertEquals(service.getSignature(),
                session.getDocument(fromMedium.getRef()).getPropertyValue(SIGNATURE_PROPERTY));
        assertEquals(Set.of(fromMedium.getId()),
                ids(service.findSimilar(fromSmall, session.getPrincipal(), null, null, null)));
    }

    /** Out-of-range parameters are a client error, whatever the entry point. */
    @Test
    public void testFindSimilarValidatesItsParameters() {
        var first = createPicture("/", "first", original);
        viewsGenerated(first);

        var principal = session.getPrincipal();
        assertEquals(400, assertThrows(NuxeoException.class,
                () -> service.findSimilar(first, principal, 65, null, null)).getStatusCode());
        assertEquals(400, assertThrows(NuxeoException.class,
                () -> service.findSimilar(first, principal, -1, null, null)).getStatusCode());
        assertEquals(400, assertThrows(NuxeoException.class,
                () -> service.findSimilar(first, principal, null, 3, null)).getStatusCode());

        assertOperationBadRequest(first, Map.of("radius", 5));
        assertOperationBadRequest(first, Map.of("threshold", 500));
        assertOperationBadRequest(first, Map.of("maxResults", -1));
    }

    protected void assertOperationBadRequest(DocumentModel doc, Map<String, Object> params) {
        var ctx = new OperationContext(session);
        ctx.setInput(doc);
        var e = assertThrows(NuxeoException.class, () -> automationService.run(ctx, FindSimilarImagesOp.ID, params));
        assertEquals(params.toString(), 400, e.getStatusCode());
    }

    /** The cache directory of ImageIO is a JVM-wide setting, shared with every other user of ImageIO. */
    @Test
    public void testServiceLeavesTheImageIOCacheDirectoryAlone() {
        assertNull(ImageIO.getCacheDirectory());
    }

    /**
     * A search that does not need the orientations starts from the stored hash: it must not fetch and decode the view
     * again. The view is made unreadable after the hashing to tell the two apart.
     */
    @Test
    public void testFindSimilarUsesTheStoredHash() {
        var first = createPicture("/", "first", original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        viewsGenerated(first, resized);

        first = session.getDocument(first.getRef());
        first.setPropertyValue("picture:views", new ArrayList<>(List.of(unreadableView("Small"))));
        first = session.saveDocument(first);
        txFeature.nextTransaction();

        assertEquals(Set.of(resized.getId()),
                ids(service.findSimilar(first, session.getPrincipal(), null, null, Boolean.FALSE)));
        // the hashes of the orientations are not stored: they still need the image
        assertEquals(SimilarImages.NONE, service.findSimilar(first, session.getPrincipal(), null, null, Boolean.TRUE));
    }

    /** With the automatic detection switched off, pictures are still hashed, and the bulk action still detects. */
    @Test
    @Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-detection-disabled-contrib.xml")
    public void testDetectionCanBeDisabled() throws InterruptedException {
        var first = createPicture("/", "first", original);
        viewsGenerated(first);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        viewsGenerated(resized);

        assertTrue(session.getDocument(resized.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertTrue("no event when the detection is disabled", DuplicatedImageCaptureListener.getEvents().isEmpty());
        // the explicit search and the second pass are not concerned
        assertEquals(Set.of(first.getId()), ids(service.findSimilar(resized, session.getPrincipal(), null, null, null)));
        var commandId = bulkService.submit(new BulkCommand.Builder(DetectImageDuplicatesAction.ACTION_NAME,
                "SELECT * FROM Picture", session.getPrincipal().getName()).repository(session.getRepositoryName())
                                                                           .build());
        assertTrue(bulkService.await(commandId, Duration.ofSeconds(60)));
        assertEquals(2, DuplicatedImageCaptureListener.getEvents().size());
    }

    /** The image reader throws a runtime exception on the first view, not an {@link IOException}. */
    @Test
    public void testUnreadableViewFallsBackToNextView() {
        // checked on the service's own reading code: ImageIO.read() would wrap it into an IIOException
        var reader = (ImageHashServiceImpl) service;
        assertThrows(IllegalArgumentException.class,
                () -> reader.readSubsampled(new ByteArrayInputStream(TestImages.unreadableGif())));

        var reference = createPicture("/", "reference", original);
        var doc = createPicture("/", "fallback", unreadableView("Small"), jpegView("Medium", "fallback", original));
        viewsGenerated(reference, doc);

        doc = session.getDocument(doc.getRef());
        assertTrue("the picture must be hashed from its Medium view", doc.hasFacet(IMAGE_HASH_FACET));
        assertEquals(session.getDocument(reference.getRef()).getPropertyValue(PHASH_PROPERTY),
                doc.getPropertyValue(PHASH_PROPERTY));
    }

    /** One picture that cannot be read and one that cannot be saved, in the same bundle as two valid ones. */
    @Test
    public void testFailingPicturesDoNotFailTheirEventBundle() {
        var unreadable = createPicture("/", "unreadable", unreadableView("Small"));
        var first = createPicture("/", "first", original);
        var unsavable = createPicture("/", FailingSaveListener.DOC_NAME, original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        viewsGenerated(unreadable, first, unsavable, resized);

        assertTrue(session.getDocument(first.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertTrue(session.getDocument(resized.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertFalse(session.getDocument(unreadable.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertFalse(session.getDocument(unsavable.getRef()).hasFacet(IMAGE_HASH_FACET));
    }

    /** Same as above, in the same batch of the bulk action. */
    @Test
    public void testFailingPicturesDoNotFailTheirBulkBatch() throws InterruptedException {
        var unreadable = createPicture("/", "unreadable", unreadableView("Small"));
        var first = createPicture("/", "first", original);
        var unsavable = createPicture("/", FailingSaveListener.DOC_NAME, original);
        var resized = createPicture("/", "resized", TestImages.resize(original, 280, 210));
        txFeature.nextTransaction();

        var commandId = bulkService.submit(new BulkCommand.Builder(ComputeImageHashAction.ACTION_NAME,
                "SELECT * FROM Picture", session.getPrincipal().getName()).repository(session.getRepositoryName())
                                                                           .build());
        assertTrue(bulkService.await(commandId, Duration.ofSeconds(60)));
        var status = bulkService.getStatus(commandId);
        assertEquals(BulkStatus.State.COMPLETED, status.getState());
        txFeature.nextTransaction();

        assertTrue(session.getDocument(first.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertTrue(session.getDocument(resized.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertFalse(session.getDocument(unreadable.getRef()).hasFacet(IMAGE_HASH_FACET));
        assertFalse(session.getDocument(unsavable.getRef()).hasFacet(IMAGE_HASH_FACET));
        // the failed save is reported, the unreadable picture is only skipped
        assertEquals(1, status.getErrorCount());
        assertTrue(status.getErrorMessage(), status.getErrorMessage().contains(unsavable.getId()));
    }

    /** The duplicate search runs after the hash is saved, in the same transaction: its failure must not undo it. */
    @Test
    @Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-failing-search-contrib.xml")
    public void testFailingSearchKeepsTheHash() {
        var doc = createPicture("/", "original", original);
        // the search does fail with this configuration
        assertThrows(RuntimeException.class, () -> service.findSimilar(doc, session.getPrincipal(), null, null, null));

        viewsGenerated(doc);

        var hashed = session.getDocument(doc.getRef());
        assertTrue("the hash must be stored although the detection failed", hashed.hasFacet(IMAGE_HASH_FACET));
        assertEquals(service.getSignature(), hashed.getPropertyValue(SIGNATURE_PROPERTY));
        assertTrue(DuplicatedImageCaptureListener.getEvents().isEmpty());
    }

    /** Same, for a direct caller of the service: the detection failure is not its failure. */
    @Test
    @Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-failing-search-contrib.xml")
    public void testFailingSearchDoesNotFailTheCaller() {
        var doc = createPicture("/", "original", original);
        assertThrows(RuntimeException.class, () -> service.findSimilar(doc, session.getPrincipal(), null, null, null));

        service.updateHashAndDetectDuplicates(session, doc, session.getPrincipal());
        txFeature.nextTransaction();

        assertTrue(session.getDocument(doc.getRef()).hasFacet(IMAGE_HASH_FACET));
    }

    /**
     * More candidates than {@code maxCandidates}: the cap must drop the least likely candidates, not arbitrary ones,
     * and every entry point must say that the result may be incomplete.
     * <p>
     * 16 decoys share no band with the reference, each has its first band 1 bit away and the three others inverted:
     * candidates at radius 1, at a distance of 49. They are indexed before the identical copy, the only real
     * duplicate, which an unsorted query cut at 5 hits therefore leaves out.
     */
    @Test
    @Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-few-candidates-contrib.xml")
    @SuppressWarnings("unchecked")
    public void testCandidateCapKeepsTheClosestCandidatesAndIsReported() throws OperationException, IOException {
        var reference = createPicture("/", "reference", original);
        viewsGenerated(reference);
        var hash = PHash.fromHex((String) session.getDocument(reference.getRef()).getPropertyValue(PHASH_PROPERTY));
        for (var bit = 0; bit < 16; bit++) {
            createHashed("decoy" + bit, ~hash ^ 0xFFFFL << 48 ^ 1L << (48 + bit));
        }
        txFeature.nextTransaction();
        var copy = createPicture("/", "copy", original);
        viewsGenerated(copy);

        // the automatic detection of the copy: the reference is found among the decoys, and the event says more
        var events = DuplicatedImageCaptureListener.getEvents();
        assertEquals(1, events.size());
        var eventContext = events.getFirst().getContext();
        assertEquals(Set.of(reference.getId()),
                ((Map<String, Integer>) eventContext.getProperty(DUPLICATES_EVENT_PROPERTY)).keySet());
        assertEquals(Boolean.TRUE, eventContext.getProperty(DUPLICATES_TRUNCATED_EVENT_PROPERTY));

        var similar = service.findSimilar(reference, session.getPrincipal(), null, 1, Boolean.FALSE);
        assertEquals(Set.of(copy.getId()), ids(similar));
        assertTrue(similar.truncated());
        // radius 0 only queries the exact bands: one candidate, nothing is cut
        similar = service.findSimilar(reference, session.getPrincipal(), null, 0, Boolean.FALSE);
        assertEquals(Set.of(copy.getId()), ids(similar));
        assertFalse(similar.truncated());

        var ctx = new OperationContext(session);
        ctx.setInput(session.getDocument(reference.getRef()));
        var docs = (DocumentModelList) automationService.run(ctx, FindSimilarImagesOp.ID,
                Map.of("radius", 1, "orientations", false));
        assertEquals(Set.of(copy.getId()), docIds(docs));
        assertEquals(Boolean.TRUE, ctx.get(FindSimilarImagesOp.TRUNCATED_CONTEXT_VARIABLE));

        var provider = similarImages(session,
                Map.of(DOC_ID_PARAM, reference.getId(), RADIUS_PARAM, "1", ORIENTATIONS_PARAM, "false"), 40, 0);
        assertEquals(Set.of(copy.getId()), docIds(provider.getCurrentPage()));
        assertTrue(((SimilarImagesPageProvider) provider).isTruncated());
        var json = MarshallerHelper.objectToJson(provider.getCurrentPage().getFirst(),
                CtxBuilder.enrichDoc(SimilarImageJsonEnricher.NAME).get());
        assertTrue(json, json.contains("\"imageSimilarity\":{\"distance\":0,\"truncated\":true}"));
    }

    /** A document carrying the given hash, as the service stores it, without any picture. */
    protected DocumentModel createHashed(String name, long hash) {
        var doc = session.createDocumentModel("/", name, "File");
        doc.addFacet(IMAGE_HASH_FACET);
        doc.setPropertyValue(PHASH_PROPERTY, PHash.toHex(hash));
        doc.setPropertyValue(BANDS_PROPERTY,
                new ArrayList<>(HashBands.bands(((ImageHashServiceImpl) service).bandPrefix, hash)));
        doc.setPropertyValue(SIGNATURE_PROPERTY, service.getSignature());
        return session.createDocument(doc);
    }

    /**
     * A user who can write a picture must not be able to hide it from detection, or to forge its hash.
     * <p>
     * The secured check reads the login of the current thread, not the principal of the session, so opening a session
     * for john is not enough: he is pushed on the login stack, as a REST call does.
     */
    @Test
    public void testHashPropertiesAreSecured() {
        var folder = createFolder("writable", true);
        var acp = folder.getACP();
        acp.getOrCreateACL().add(new ACE(JOHN.getName(), SecurityConstants.READ_WRITE, true));
        session.setACP(folder.getRef(), acp, true);
        var first = createPicture(folder.getPathAsString(), "first", original);
        var resized = createPicture(folder.getPathAsString(), "resized", TestImages.resize(original, 280, 210));
        viewsGenerated(first, resized);
        assertEquals(Set.of(resized.getId()), ids(service.findSimilar(first, JOHN, null, null, null)));

        try (var ignored = loginAs(JOHN)) {
            var johnSession = CoreInstance.getCoreSession(session.getRepositoryName(), JOHN);
            var doc = johnSession.getDocument(first.getRef());
            // john does have Write on the picture
            doc.setPropertyValue("dc:description", "updated by john");
            assertThrows(ReadOnlyPropertyException.class,
                    () -> doc.setPropertyValue(BANDS_PROPERTY, new ArrayList<String>()));
            assertThrows(ReadOnlyPropertyException.class,
                    () -> doc.setPropertyValue(PHASH_PROPERTY, "0000000000000000"));
            assertThrows(ReadOnlyPropertyException.class, () -> doc.setPropertyValue(SIGNATURE_PROPERTY, "forged"));
            johnSession.saveDocument(doc);
        }
        txFeature.nextTransaction();

        var saved = session.getDocument(first.getRef());
        assertEquals("updated by john", saved.getPropertyValue("dc:description"));
        assertEquals(HashBands.BAND_COUNT, ((Object[]) saved.getPropertyValue(BANDS_PROPERTY)).length);
        assertEquals(service.getSignature(), saved.getPropertyValue(SIGNATURE_PROPERTY));
        assertEquals(Set.of(resized.getId()), ids(service.findSimilar(first, JOHN, null, null, null)));
    }

    /** Pushes the principal on the login stack of the current thread; closing the context pops it. */
    protected static NuxeoLoginContext loginAs(NuxeoPrincipal principal) {
        var loginContext = NuxeoLoginContext.create(principal);
        loginContext.login();
        return loginContext;
    }

    protected DocumentModel createFolder(String name, boolean readableByJohn) {
        var folder = session.createDocument(session.createDocumentModel("/", name, "Folder"));
        var acp = folder.getACP();
        var acl = acp.getOrCreateACL();
        if (readableByJohn) {
            acl.add(new ACE(JOHN.getName(), SecurityConstants.READ, true));
        } else {
            acl.add(new ACE(session.getPrincipal().getName(), SecurityConstants.EVERYTHING, true));
            acl.add(ACE.BLOCK);
        }
        session.setACP(folder.getRef(), acp, true);
        return folder;
    }

    protected DocumentModel createPicture(String parentPath, String name, BufferedImage image) {
        return createPicture(parentPath, name, jpegView("Small", name, image));
    }

    @SafeVarargs
    protected final DocumentModel createPicture(String parentPath, String name, Map<String, Serializable>... views) {
        var doc = session.createDocumentModel(parentPath, name, "Picture");
        doc.setPropertyValue("picture:views", new ArrayList<>(List.of(views)));
        return session.createDocument(doc);
    }

    protected static Map<String, Serializable> jpegView(String title, String name, BufferedImage image) {
        var blob = new ByteArrayBlob(TestImages.jpeg(image, 0.9f), "image/jpeg");
        blob.setFilename(name + ".jpg");
        return view(title, blob, image.getWidth(), image.getHeight());
    }

    /** A view the image reader fails on with a runtime exception, see {@link TestImages#unreadableGif()}. */
    protected static Map<String, Serializable> unreadableView(String title) {
        var blob = new ByteArrayBlob(TestImages.unreadableGif(), "image/gif");
        blob.setFilename("unreadable.gif");
        return view(title, blob, 64, 48);
    }

    protected static Map<String, Serializable> view(String title, ByteArrayBlob blob, int width, int height) {
        var view = new HashMap<String, Serializable>();
        view.put("title", title);
        view.put("width", width);
        view.put("height", height);
        view.put("content", blob);
        return view;
    }

    protected void viewsGenerated(DocumentModel... docs) {
        for (var doc : docs) {
            var ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
            eventService.fireEvent(ctx.newEvent(PICTURE_VIEWS_GENERATION_DONE_EVENT));
        }
        txFeature.nextTransaction();
    }

    protected static Set<String> ids(SimilarImages similar) {
        return similar.images().stream().map(SimilarImage::docId).collect(Collectors.toSet());
    }

    /** Builds the provider the way the REST search endpoint does, named parameters included. */
    @SuppressWarnings("unchecked")
    protected PageProvider<DocumentModel> similarImages(CoreSession coreSession, Map<String, String> params,
            long pageSize, long page) {
        var searchDocument = PageProviderHelper.getSearchDocumentModel(coreSession, SimilarImagesPageProvider.NAME,
                params);
        return (PageProvider<DocumentModel>) pageProviderService.getPageProvider(
                PageProviderSpec.builder(SimilarImagesPageProvider.NAME)
                                .searchDocument(searchDocument)
                                .pageSize(pageSize)
                                .currentPage(page)
                                .property(CORE_SESSION_PROPERTY, (Serializable) coreSession)
                                .build());
    }

    protected void assertBadRequest(Map<String, String> params) {
        var e = assertThrows(NuxeoException.class, () -> similarImages(session, params, 40, 0).getCurrentPage());
        assertEquals(400, e.getStatusCode());
    }

    protected static Set<String> docIds(List<DocumentModel> docs) {
        return docs.stream().map(DocumentModel::getId).collect(Collectors.toSet());
    }
}
