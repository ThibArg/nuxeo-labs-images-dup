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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class TestHashBands {

    @Test
    public void testBands() {
        assertEquals(List.of("p:0:0123", "p:1:4567", "p:2:89ab", "p:3:cdef"),
                HashBands.bands("p", 0x0123456789ABCDEFL));
    }

    @Test
    public void testQueryKeysCount() {
        assertEquals(4, HashBands.queryKeys("p", 42, 0).size());
        assertEquals(4 * 17, HashBands.queryKeys("p", 42, 1).size());
        assertEquals(4 * (1 + 16 + 120), HashBands.queryKeys("p", 42, 2).size());
        assertThrows(IllegalArgumentException.class, () -> HashBands.queryKeys("p", 42, 3));
    }

    @Test
    public void testAllHashesWithinGuaranteedDistanceAreFound() {
        var random = new Random(1);
        for (var radius = 0; radius <= HashBands.MAX_RADIUS; radius++) {
            var guaranteed = HashBands.BAND_COUNT * radius + HashBands.BAND_COUNT - 1;
            for (var i = 0; i < 2000; i++) {
                var hash = random.nextLong();
                var other = flipRandomBits(hash, guaranteed, random);
                assertEquals(guaranteed, PHash.distance(hash, other));
                var keys = HashBands.queryKeys("p", hash, radius);
                assertFalse(Collections.disjoint(keys, HashBands.bands("p", other)));
            }
        }
    }

    @Test
    public void testPrefixIsolatesSignatures() {
        var keys = HashBands.queryKeys("p1", 42, 2);
        assertTrue(Collections.disjoint(keys, HashBands.bands("p2", 42)));
    }

    protected static long flipRandomBits(long hash, int count, Random random) {
        var result = hash;
        var flipped = 0;
        while (flipped < count) {
            var bit = 1L << random.nextInt(Long.SIZE);
            if ((result & bit) == (hash & bit)) {
                result ^= bit;
                flipped++;
            }
        }
        return result;
    }
}
