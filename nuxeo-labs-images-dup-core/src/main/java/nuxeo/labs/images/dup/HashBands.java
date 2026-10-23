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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds multi-index keys for Hamming-distance image hash searches.
 *
 * @since 2025.1.0
 */
public final class HashBands {

    /**
     * Number of 16-bit bands in a 64-bit hash.
     *
     * @since 2025.1.0
     */
    public static final int BAND_COUNT = 4;

    static final int BAND_BITS = 16;

    /**
     * Maximum supported query radius.
     *
     * @since 2025.1.0
     */
    public static final int MAX_RADIUS = 2;

    private HashBands() {
        // utility class
    }

    /**
     * Returns one key per band, in the form {@code <prefix>:<bandIndex>:<hexValue>}.
     *
     * @since 2025.1.0
     */
    public static List<String> bands(String prefix, long hash) {
        var keys = new ArrayList<String>(BAND_COUNT);
        for (var index = 0; index < BAND_COUNT; index++) {
            keys.add(key(prefix, index, band(hash, index)));
        }
        return keys;
    }

    /**
     * Returns the keys to query to find all the stored hashes within a distance of {@code 4 * radius + 3}.
     *
     * @since 2025.1.0
     */
    public static Set<String> queryKeys(String prefix, long hash, int radius) {
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Radius must be between 0 and " + MAX_RADIUS + ", got: " + radius);
        }
        var keys = new LinkedHashSet<String>();
        for (var index = 0; index < BAND_COUNT; index++) {
            var value = band(hash, index);
            keys.add(key(prefix, index, value));
            for (var bit1 = 0; radius >= 1 && bit1 < BAND_BITS; bit1++) {
                keys.add(key(prefix, index, value ^ (1 << bit1)));
                for (var bit2 = bit1 + 1; radius >= 2 && bit2 < BAND_BITS; bit2++) {
                    keys.add(key(prefix, index, value ^ (1 << bit1) ^ (1 << bit2)));
                }
            }
        }
        return keys;
    }

    static int band(long hash, int index) {
        return (int) ((hash >>> (BAND_BITS * (BAND_COUNT - 1 - index))) & 0xFFFF);
    }

    static String key(String prefix, int index, int value) {
        return "%s:%d:%04x".formatted(prefix, index, value);
    }
}
