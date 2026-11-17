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
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;
import org.nuxeo.runtime.transaction.TransactionHelper;

import nuxeo.labs.images.dup.ImageHashService;

/**
 * Searches for the duplicates of pictures that are already hashed, and fires
 * {@link ImageHashService#DUPLICATED_IMAGE_EVENT} for each picture having some. Computes no hash.
 * <p>
 * This is the second pass of the detection. The one done when a picture is hashed only sees the pictures already in
 * the search index, so pictures hashed within the indexing delay of each other, typically the same file uploaded twice
 * in one import, do not see each other. Run once they are indexed, this action reports them, and it is the way to
 * detect duplicates after the {@code computeImageHash} action, which fires no event.
 * <p>
 * The duplicates listed are those readable by the user of the command. Pairs already reported are reported again.
 *
 * @since 2025.1.0
 */
public class DetectImageDuplicatesAction implements StreamProcessorTopology {

    private static final Logger log = LogManager.getLogger(DetectImageDuplicatesAction.class);

    /** @since 2025.1.0 */
    public static final String ACTION_NAME = "detectImageDuplicates";

    /** @since 2025.1.0 */
    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    /**
     * Key of the command result holding the number of pictures for which duplicates were found, a {@code Number}.
     *
     * @since 2025.1.0
     */
    public static final String RESULT_PICTURES_WITH_DUPLICATES = "picturesWithDuplicates";

    /**
     * @since 2025.1.0
     */
    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(DetectImageDuplicatesComputation::new,
                               List.of(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    /**
     * Bulk computation that detects the duplicates of each selected picture.
     *
     * @since 2025.1.0
     */
    public static class DetectImageDuplicatesComputation extends AbstractBulkComputation {

        /** @since 2025.1.0 */
        public DetectImageDuplicatesComputation() {
            super(ACTION_FULL_NAME);
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            var service = Framework.getService(ImageHashService.class);
            var picturesWithDuplicates = 0L;
            for (var doc : loadDocuments(session, ids)) {
                if (doc.isProxy() || doc.isVersion()) {
                    continue;
                }
                try {
                    if (service.detectDuplicates(session, doc, session.getPrincipal())) {
                        picturesWithDuplicates++;
                    }
                } catch (RuntimeException e) {
                    if (TransactionHelper.isTransactionMarkedRollback()) {
                        // nothing of this batch can be committed anymore, do not hide it
                        throw e;
                    }
                    // one failing picture must not leave the rest of its batch, then of its bucket, unchecked
                    log.warn("Cannot detect the duplicates of document: {}", doc.getId(), e);
                    delta.inError("Cannot detect the duplicates of document: %s, %s".formatted(doc.getId(),
                            e.getMessage()));
                }
            }
            delta.mergeResult(Map.of(RESULT_PICTURES_WITH_DUPLICATES, picturesWithDuplicates));
        }
    }
}
