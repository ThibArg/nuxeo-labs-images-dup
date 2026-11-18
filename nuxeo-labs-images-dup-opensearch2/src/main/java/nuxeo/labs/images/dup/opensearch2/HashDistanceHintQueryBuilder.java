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
package nuxeo.labs.images.dup.opensearch2;

import org.nuxeo.ecm.core.query.sql.model.EsHint;
import org.nuxeo.ecm.core.search.client.opensearch2.OpenSearchHintQueryBuilder;
import org.opensearch.client.opensearch._types.query_dsl.Query;

import nuxeo.labs.images.dup.HashDistanceHint;

/**
 * The {@link HashDistanceHint} operator for the OpenSearch 2 search client.
 * <p>
 * Only loaded when the Marketplace template registers it, that is when this client is installed.
 *
 * @since 2025.2.0
 */
public class HashDistanceHintQueryBuilder implements OpenSearchHintQueryBuilder {

    /**
     * @since 2025.2.0
     */
    @Override
    public Query.Builder make(EsHint hint, String fieldName, Object value) {
        var query = HashDistanceHint.toBase64(fieldName, value);
        var builder = new Query.Builder();
        builder.wrapper(wrapper -> wrapper.query(query));
        return builder;
    }
}
