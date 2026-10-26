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
import static nuxeo.labs.images.dup.ImageHashService.DUPLICATES_EVENT_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.IMAGE_HASH_FACET;
import static nuxeo.labs.images.dup.ImageHashService.PHASH_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.SIGNATURE_PROPERTY;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.DISTANCE_CONTEXT_DATA;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.DOC_ID_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.LIMIT_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.ORIENTATIONS_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.RADIUS_PARAM;
import static nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider.THRESHOLD_PARAM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.platform.picture.PictureViewsGenerationWork.PICTURE_VIEWS_GENERATION_DONE_EVENT;
import static org.nuxeo.ecm.platform.query.api.PageProviderSpec.CORE_SESSION_PROPERTY;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.impl.UserPrincipal;
import org.nuxeo.ecm.core.api.impl.blob.ByteArrayBlob;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.event.Event;
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
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import nuxeo.labs.images.dup.bulk.ComputeImageHashAction;
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
        assertTrue(json, json.contains("\"imageSimilarity\":{\"distance\":" + distances.getFirst() + "}"));
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
        assertTrue(service.findSimilar(blank2, session.getPrincipal(), null, null, null).isEmpty());
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
        var blob = new ByteArrayBlob(TestImages.jpeg(image, 0.9f), "image/jpeg");
        blob.setFilename(name + ".jpg");
        var view = new HashMap<String, Serializable>();
        view.put("title", "Small");
        view.put("width", image.getWidth());
        view.put("height", image.getHeight());
        view.put("content", (Serializable) blob);
        var doc = session.createDocumentModel(parentPath, name, "Picture");
        doc.setPropertyValue("picture:views", new ArrayList<>(List.of(view)));
        return session.createDocument(doc);
    }

    protected void viewsGenerated(DocumentModel... docs) {
        for (var doc : docs) {
            var ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
            eventService.fireEvent(ctx.newEvent(PICTURE_VIEWS_GENERATION_DONE_EVENT));
        }
        txFeature.nextTransaction();
    }

    protected static Set<String> ids(List<SimilarImage> similar) {
        return similar.stream().map(SimilarImage::docId).collect(Collectors.toSet());
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
