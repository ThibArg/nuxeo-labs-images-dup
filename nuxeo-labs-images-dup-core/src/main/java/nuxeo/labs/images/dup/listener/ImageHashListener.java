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
package nuxeo.labs.images.dup.listener;

import static java.util.Objects.requireNonNullElse;
import static org.nuxeo.ecm.platform.picture.PictureViewsGenerationWork.PICTURE_VIEWS_GENERATION_DONE_EVENT;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.PostCommitEventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.event.impl.ShallowDocumentModel;
import org.nuxeo.runtime.api.Framework;

import nuxeo.labs.images.dup.ImageHashService;

/**
 * Asynchronously hashes pictures once their views are generated, and detects duplicates readable by the user who
 * triggered the views generation.
 *
 * @since 2025.1.0
 */
public class ImageHashListener implements PostCommitEventListener {

    private static final Logger log = LogManager.getLogger(ImageHashListener.class);

    /**
     * @since 2025.1.0
     */
    @Override
    public void handleEvent(EventBundle events) {
        var service = Framework.getService(ImageHashService.class);
        for (var event : events) {
            if (!PICTURE_VIEWS_GENERATION_DONE_EVENT.equals(event.getName())
                    || !(event.getContext() instanceof DocumentEventContext ctx)) {
                continue;
            }
            var source = ctx.getSourceDocument();
            if (source == null || source instanceof ShallowDocumentModel || source.isProxy() || source.isVersion()) {
                continue;
            }
            // the hash is technical metadata, saved even if the user cannot write the document
            var session = CoreInstance.getCoreSessionSystem(source.getRepositoryName());
            try {
                var doc = session.getDocument(source.getRef());
                service.updateHashAndDetectDuplicates(session, doc,
                        requireNonNullElse(ctx.getPrincipal(), session.getPrincipal()));
            } catch (DocumentNotFoundException e) {
                log.debug("Document: {} was removed before being hashed", source::getId);
            }
        }
    }
}
