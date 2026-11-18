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

import static java.nio.charset.StandardCharsets.UTF_8;
import static nuxeo.labs.images.dup.ImageHashService.BANDS_PROPERTY;
import static nuxeo.labs.images.dup.ImageHashService.PHASH_PROPERTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Test;
import org.nuxeo.ecm.core.api.NuxeoException;

import com.fasterxml.jackson.databind.ObjectMapper;

public class TestHashDistanceHint {

    protected static final long HASH = 0x0123456789abcdefL;

    protected static final long ROTATED = 0xfedcba9876543210L;

    /** The values of the NXQL clause, as the search client passes them to the hint builder. */
    protected static Object[] values(String clause) {
        var matcher = Pattern.compile("'([^']*)'").matcher(clause);
        var values = new ArrayList<String>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values.toArray();
    }

    @Test
    public void testClauseRoundTrip() {
        var keys = HashBands.queryKeys("5b010d06", HASH, 1);
        var clause = HashDistanceHint.clause(BANDS_PROPERTY, 8, new long[] { HASH, ROTATED }, keys);
        assertTrue(clause, clause.startsWith("/*+ES: OPERATOR(imagehash_distance) */ imgh:bands IN ('threshold:8',"
                + "'hash:0123456789abcdef','hash:fedcba9876543210','5b010d06:0:0123',"));

        var decoded = HashDistanceHint.decode(BANDS_PROPERTY, values(clause));
        assertEquals(8, decoded.threshold());
        assertEquals(List.of(HASH, ROTATED), decoded.hashes());
        assertEquals(List.copyOf(keys), decoded.keys());
    }

    @Test
    public void testJson() throws Exception {
        var clause = HashDistanceHint.clause(BANDS_PROPERTY, 5, new long[] { ROTATED },
                HashBands.queryKeys("5b010d06", ROTATED, 0));
        var json = HashDistanceHint.toJson(BANDS_PROPERTY, values(clause));
        var filters = new ObjectMapper().readTree(json).get("bool").get("filter");
        assertEquals(4, filters.get(0).get("terms").get(BANDS_PROPERTY).size());
        var script = filters.get(1).get("script").get("script");
        assertEquals("painless", script.get("lang").asText());
        assertEquals(PHASH_PROPERTY, script.get("params").get("f").asText());
        // a hash above Long.MAX_VALUE is passed as the signed long of the same bits
        assertEquals(ROTATED, script.get("params").get("q").get(0).asLong());
        assertEquals(5, script.get("params").get("t").asInt());

        assertEquals(json, new String(Base64.getDecoder().decode(HashDistanceHint.toBase64(BANDS_PROPERTY,
                values(clause))), UTF_8));
    }

    @Test
    public void testInvalidValuesAreRejected() {
        var key = HashBands.bands("5b010d06", HASH).getFirst();
        assertBadRequest(BANDS_PROPERTY, new Object[] { "threshold:8", "hash:0123456789abcdef", "x\"}}" });
        assertBadRequest(BANDS_PROPERTY, new Object[] { "threshold:8", "hash:0123456789ABCDEF", key });
        assertBadRequest(BANDS_PROPERTY, new Object[] { "threshold:65", "hash:0123456789abcdef", key });
        assertBadRequest(BANDS_PROPERTY, new Object[] { "threshold:x", "hash:0123456789abcdef", key });
        assertBadRequest(BANDS_PROPERTY, new Object[] { "hash:0123456789abcdef", key });
        assertBadRequest(BANDS_PROPERTY, new Object[] { "threshold:8", key });
        assertBadRequest(BANDS_PROPERTY, new Object[] { "threshold:8", "hash:0123456789abcdef" });
        assertBadRequest("dc:title", new Object[] { "threshold:8", "hash:0123456789abcdef", key });
        assertBadRequest("imgh:bands\"", new Object[] { "threshold:8", "hash:0123456789abcdef", key });
    }

    protected static void assertBadRequest(String field, Object[] values) {
        var e = assertThrows(NuxeoException.class, () -> HashDistanceHint.toJson(field, values));
        assertEquals(400, e.getStatusCode());
    }
}
