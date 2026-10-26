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

import static org.junit.Assert.assertTrue;

import java.util.Set;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.repository.FulltextConfiguration;
import org.nuxeo.ecm.core.repository.RepositoryService;
import org.nuxeo.ecm.core.storage.sql.IgnoreIfNotVCSRepository;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.ConditionalIgnoreRule;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * Regression test for the repository fulltext exclusion shipped by the marketplace template.
 * <p>
 * Band keys are opaque strings, and Nuxeo indexes every string property of every schema into the repository fulltext.
 * The template contributes two {@code excludeField} entries, which serve two different consumers and are both needed:
 * {@code imgh:bands} suppresses the text extraction, because {@code StringsExtractor} tests an array property at its
 * own path with no trailing {@code /*}, while {@code imgh:bands/*} suppresses the work scheduling, because
 * {@code DirtyPathsFinder} appends {@code /*} for arrays. A wrong path, or an index name that does not match the one
 * declared by the database template, fails silently, hence this test.
 * <p>
 * Asserts on the configuration rather than on a fulltext search, because the H2 test repository declares
 * {@code searchDisabled="true"}. VCS only: the deployed contribution targets the SQL repository service.
 *
 * @since 2025.1.0
 */
@RunWith(FeaturesRunner.class)
// AutomationFeature rather than CoreFeature so the bundle's operations contribution resolves
@Features(AutomationFeature.class)
@ConditionalIgnoreRule.Ignore(condition = IgnoreIfNotVCSRepository.class, cause = "the test contribution is the VCS one")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core")
@Deploy("nuxeo.labs.images.dup.nuxeo-labs-images-dup-core:OSGI-INF/test-fulltext-exclude-contrib.xml")
public class TestFulltextExclusion {

    @Inject
    protected CoreSession session;

    /**
     * @since 2025.1.0
     */
    @Test
    public void testBandsAreExcludedFromRepositoryFulltext() {
        var config = getFulltextConfiguration();
        assertTrue("imgh:bands must be excluded, otherwise the band keys are extracted into ecm:fulltextSimple",
                config.indexesByPropPathExcludedSimple.containsKey("imgh:bands"));
        assertTrue("imgh:bands/* must be excluded, otherwise every hash store schedules a FulltextExtractorWork",
                config.indexesByPropPathExcludedSimple.containsKey("imgh:bands/*"));
    }

    /**
     * The exclusion must not land on a second, unused index: the contribution has to merge into the index declared by
     * the database template, which is named {@code default} on every VCS backend.
     *
     * @since 2025.1.0
     */
    @Test
    public void testExclusionMergesIntoTheDefaultIndex() {
        var config = getFulltextConfiguration();
        assertTrue("the exclusion must be registered on the default index",
                config.propPathsExcludedByIndexSimple.getOrDefault("default", Set.of())
                      .containsAll(Set.of("imgh:bands", "imgh:bands/*")));
    }

    /**
     * Guards against an over-broad exclusion: the other properties of the schema must keep being indexed.
     *
     * @since 2025.1.0
     */
    @Test
    public void testOtherPropertiesAreStillIndexed() {
        var config = getFulltextConfiguration();
        assertTrue("imgh:phash must stay indexed", config.indexesByPropPathSimple.containsKey("imgh:phash"));
        assertTrue("dc:title must stay indexed", config.indexesByPropPathSimple.containsKey("dc:title"));
    }

    protected FulltextConfiguration getFulltextConfiguration() {
        return Framework.getService(RepositoryService.class)
                        .getRepository(session.getRepositoryName())
                        .getFulltextConfiguration();
    }
}
