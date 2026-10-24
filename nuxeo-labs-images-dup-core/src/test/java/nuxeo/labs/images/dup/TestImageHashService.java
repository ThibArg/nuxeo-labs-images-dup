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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.platform.picture.PictureViewsGenerationWork.PICTURE_VIEWS_GENERATION_DONE_EVENT;

import java.awt.Color;
import java.awt.image.BufferedImage;
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
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
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
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchCoreSearchFeature;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import nuxeo.labs.images.dup.bulk.ComputeImageHashAction;
import nuxeo.labs.images.dup.operations.FindSimilarImagesOp;

@RunWith(FeaturesRunner.class)
// the duplicate search requires the OpenSearch client, started here as an embedded node
@Features({ AutomationFeature.class, OpenSearchCoreSearchFeature.class })
@RepositoryConfig(cleanup = Granularity.METHOD)
// Picture doctype, without the views generation (no ImageMagick needed)
@Deploy("org.nuxeo.ecm.platform.tag:OSGI-INF/faceted-tag-service-core-types.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core:OSGI-INF/picture-schemas-contrib.xml")
@Deploy("org.nuxeo.ecm.platform.picture.core:OSGI-INF/picture-adapter-contrib.xml")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core")
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
}
