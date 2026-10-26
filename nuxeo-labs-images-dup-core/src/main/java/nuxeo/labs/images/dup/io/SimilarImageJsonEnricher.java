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
package nuxeo.labs.images.dup.io;

import static org.nuxeo.ecm.core.io.registry.reflect.Instantiations.SINGLETON;
import static org.nuxeo.ecm.core.io.registry.reflect.Priorities.REFERENCE;

import java.io.IOException;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.io.marshallers.json.enrichers.AbstractJsonEnricher;
import org.nuxeo.ecm.core.io.registry.reflect.Setup;

import com.fasterxml.jackson.core.JsonGenerator;

import nuxeo.labs.images.dup.pageprovider.SimilarImagesPageProvider;

/**
 * Adds the Hamming distance computed by {@link SimilarImagesPageProvider} to the documents it returns, {@code null}
 * for any other document.
 * <p>
 * Enabled with {@code enrichers-document: imageSimilarity}. Format is:
 *
 * <pre>
 * "contextParameters": {
 *   "imageSimilarity": { "distance": 3 }
 * }
 * </pre>
 *
 * @since 2025.1.0
 */
@Setup(mode = SINGLETON, priority = REFERENCE)
public class SimilarImageJsonEnricher extends AbstractJsonEnricher<DocumentModel> {

    /** @since 2025.1.0 */
    public static final String NAME = "imageSimilarity";

    /**
     * @since 2025.1.0
     */
    public SimilarImageJsonEnricher() {
        super(NAME);
    }

    /**
     * @since 2025.1.0
     */
    @Override
    public void write(JsonGenerator jg, DocumentModel doc) throws IOException {
        if (doc.getContextData(SimilarImagesPageProvider.DISTANCE_CONTEXT_DATA) instanceof Integer distance) {
            jg.writeObjectFieldStart(NAME);
            jg.writeNumberField("distance", distance);
            jg.writeEndObject();
        } else {
            jg.writeNullField(NAME);
        }
    }
}
