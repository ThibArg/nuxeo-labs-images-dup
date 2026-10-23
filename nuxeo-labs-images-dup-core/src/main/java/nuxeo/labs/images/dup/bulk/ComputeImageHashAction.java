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
package nuxeo.labs.images.dup.bulk;

import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.ecm.platform.picture.api.ImagingDocumentConstants.PICTURE_FACET;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

import nuxeo.labs.images.dup.ImageHashService;

/**
 * Computes the hashes of existing pictures (initial load, signature change...). Does not fire duplicate events.
 *
 * @since 2025.1.0
 */
public class ComputeImageHashAction implements StreamProcessorTopology {

    /** @since 2025.1.0 */
    public static final String ACTION_NAME = "computeImageHash";

    /** @since 2025.1.0 */
    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    /**
     * Boolean, recomputes even the hashes having the current signature.
     *
     * @since 2025.1.0
     */
    public static final String PARAM_FORCE = "force";

    /**
     * @since 2025.1.0
     */
    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(ComputeImageHashComputation::new,
                               List.of(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    /**
     * Bulk computation that hashes each selected picture.
     *
     * @since 2025.1.0
     */
    public static class ComputeImageHashComputation extends AbstractBulkComputation {

        protected boolean force;

        /** @since 2025.1.0 */
        public ComputeImageHashComputation() {
            super(ACTION_FULL_NAME);
        }

        /**
         * @since 2025.1.0
         */
        @Override
        public void startBucket(String bucketKey) {
            var value = getCurrentCommand().getParam(PARAM_FORCE);
            force = Boolean.parseBoolean(String.valueOf(value));
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            var service = Framework.getService(ImageHashService.class);
            for (var doc : loadDocuments(session, ids)) {
                if (doc.hasFacet(PICTURE_FACET) && !doc.isProxy() && !doc.isVersion()) {
                    service.updateHash(session, doc, force);
                }
            }
        }
    }
}
