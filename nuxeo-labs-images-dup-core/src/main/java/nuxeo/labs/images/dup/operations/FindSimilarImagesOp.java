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
package nuxeo.labs.images.dup.operations;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;

import nuxeo.labs.images.dup.ImageHashService;

/**
 * Automation operation that returns documents with images similar to the input image.
 *
 * @since 2025.1.0
 */
@Operation(id = FindSimilarImagesOp.ID, category = Constants.CAT_DOCUMENT, label = "Find Similar Images",
        description = "Returns the documents readable by the current user whose image is a near duplicate of the input"
        + " document's image, closest first. Unset parameters use the configured values. threshold: max Hamming"
        + " distance (0-64). radius: 0-2, higher finds more duplicates but is slower. orientations: also find"
        + " rotated and mirrored images. maxResults: defaults to 50.")
public class FindSimilarImagesOp {

    /** @since 2025.1.0 */
    public static final String ID = "ImageHash.FindSimilar";

    @Context
    protected CoreSession session;

    @Context
    protected ImageHashService service;

    @Param(name = "threshold", required = false)
    protected Integer threshold;

    @Param(name = "radius", required = false)
    protected Integer radius;

    @Param(name = "orientations", required = false)
    protected Boolean orientations;

    @Param(name = "maxResults", required = false)
    protected int maxResults = 50;

    /**
     * @since 2025.1.0
     */
    @OperationMethod
    public DocumentModelList run(DocumentModel doc) {
        var result = new DocumentModelListImpl();
        service.findSimilar(doc, session.getPrincipal(), threshold, radius, orientations)
               .stream()
               .limit(maxResults)
               .map(similar -> new IdRef(similar.docId()))
               .filter(session::exists)
               .forEach(ref -> result.add(session.getDocument(ref)));
        return result;
    }
}
