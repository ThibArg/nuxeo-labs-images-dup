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
import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.query.sql.NXQL;

/**
 * The NXQL hint operator that lets the search engine compute the Hamming distance, shared by the hint query builders
 * of the three search stacks.
 * <p>
 * The plugin writes {@code /*+ES: OPERATOR(imagehash_distance) *&#47; imgh:bands IN ('threshold:8', 'hash:<hex>', ...,
 * '<band key>', ...)}. The builder of each stack turns it into the same search engine query: a {@code terms} filter on
 * the band keys, the pre-filter that keeps the search cheap, and a Painless {@code script} filter keeping only the
 * documents whose {@code imgh:phash} is within the threshold of one of the hashes. The engine then returns the
 * matches, not every candidate sharing a band.
 * <p>
 * The query is given to each search client as a {@code wrapper} query, base64 encoded JSON, so its content is written
 * once, here, and is identical on every stack; only the few lines wrapping it depend on the client.
 * <p>
 * Every value is validated before being written into the JSON: the hint can be used in any NXQL query, by any user.
 *
 * @since 2025.2.0
 */
public final class HashDistanceHint {

    /**
     * Name of the hint operator, as registered on the {@code hint} extension point of the search client.
     *
     * @since 2025.2.0
     */
    public static final String OPERATOR = "imagehash_distance";

    /**
     * Prefix of the value holding the maximum Hamming distance.
     *
     * @since 2025.2.0
     */
    public static final String THRESHOLD_PREFIX = "threshold:";

    /**
     * Prefix of the values holding the hashes to compare with, in hexadecimal.
     *
     * @since 2025.2.0
     */
    public static final String HASH_PREFIX = "hash:";

    /**
     * Configuration property, set by the Marketplace template when the hint is registered for the installed search
     * client: the searches then use it. Unset or {@code false}, they use the band query alone, capped at
     * {@code maxCandidates} candidates.
     *
     * @since 2025.2.0
     */
    public static final String ENABLED_PROPERTY = "nuxeo.labs.images.dup.search.distanceHint";

    /** The script reads the hash as a keyword doc value: 16 lowercase hexadecimal digits, as stored by the plugin. */
    protected static final String SCRIPT = "def d = doc[params.f]; if (d.size() == 0) { return false; } "
            + "String s = d.value; long h = 0; for (int i = 0; i < s.length(); i++) { int c = s.charAt(i); "
            + "h = (h << 4) | (c <= 57 ? c - 48 : c - 87); } "
            + "for (def q : params.q) { if (Long.bitCount(h ^ (long) q) <= params.t) { return true; } } "
            + "return false;";

    protected static final Pattern BAND_KEY = Pattern.compile("[0-9a-f]{8}:[0-9]:[0-9a-f]{4}");

    protected static final Pattern HEX_HASH = Pattern.compile("[0-9a-f]{16}");

    /** The most keys a search of the plugin can query: radius 2 with the 8 orientations. */
    protected static final int MAX_KEYS = PHash.ORIENTATIONS * 548;

    private HashDistanceHint() {
        // utility class
    }

    /**
     * The decoded values of the hint.
     *
     * @since 2025.2.0
     */
    public record Values(int threshold, List<Long> hashes, List<String> keys) {
    }

    /**
     * Returns the NXQL clause of the hint.
     *
     * @since 2025.2.0
     */
    public static String clause(String field, int threshold, long[] hashes, Collection<String> keys) {
        var values = new ArrayList<String>();
        values.add(THRESHOLD_PREFIX + threshold);
        for (var hash : hashes) {
            values.add(HASH_PREFIX + PHash.toHex(hash));
        }
        values.addAll(keys);
        return "/*+ES: OPERATOR(%s) */ %s IN (%s)".formatted(OPERATOR, field,
                values.stream().map(NXQL::escapeString).collect(joining(",")));
    }

    /**
     * Decodes and validates the value the search client passes to the hint builder.
     *
     * @throws NuxeoException with a 400 status if the values are not those of the hint
     * @since 2025.2.0
     */
    public static Values decode(String field, Object value) {
        if (!ImageHashService.BANDS_PROPERTY.equals(field)) {
            throw badRequest("applies to %s only, got: %s".formatted(ImageHashService.BANDS_PROPERTY, field));
        }
        var items = value instanceof Object[] array ? array : new Object[] { value };
        Integer threshold = null;
        var hashes = new LinkedHashSet<Long>();
        var keys = new LinkedHashSet<String>();
        for (var item : items) {
            var string = String.valueOf(item);
            if (string.startsWith(THRESHOLD_PREFIX)) {
                threshold = parseThreshold(string.substring(THRESHOLD_PREFIX.length()));
            } else if (string.startsWith(HASH_PREFIX)) {
                var hex = string.substring(HASH_PREFIX.length());
                if (!HEX_HASH.matcher(hex).matches()) {
                    throw badRequest("invalid hash: " + hex);
                }
                hashes.add(PHash.fromHex(hex));
            } else if (BAND_KEY.matcher(string).matches()) {
                keys.add(string);
            } else {
                throw badRequest("invalid value: " + string);
            }
        }
        if (threshold == null || hashes.isEmpty() || keys.isEmpty()) {
            throw badRequest("expects a threshold, at least one hash and at least one band key");
        }
        if (hashes.size() > PHash.ORIENTATIONS || keys.size() > MAX_KEYS) {
            throw badRequest("too many hashes or band keys");
        }
        return new Values(threshold, List.copyOf(hashes), List.copyOf(keys));
    }

    /**
     * Returns the search engine query of the hint, as JSON.
     *
     * @param field the field of the NXQL clause, as given to the hint builder
     * @param value the values of the NXQL clause, as given to the hint builder
     * @throws NuxeoException with a 400 status if the values are not those of the hint
     * @since 2025.2.0
     */
    public static String toJson(String field, Object value) {
        var values = decode(field, value);
        // every string written here is a constant, or was validated by decode
        var keys = values.keys().stream().map(key -> '"' + key + '"').collect(joining(","));
        var hashes = values.hashes().stream().map(String::valueOf).collect(joining(","));
        return """
                {"bool":{"filter":[{"terms":{"%s":[%s]}},{"script":{"script":{"lang":"painless","source":"%s",\
                "params":{"f":"%s","q":[%s],"t":%d}}}}]}}""".formatted(field, keys, SCRIPT,
                ImageHashService.PHASH_PROPERTY, hashes, values.threshold());
    }

    /**
     * Returns the search engine query of the hint, as the base64 encoded JSON of a {@code wrapper} query.
     *
     * @throws NuxeoException with a 400 status if the values are not those of the hint
     * @since 2025.2.0
     */
    public static String toBase64(String field, Object value) {
        return Base64.getEncoder().encodeToString(toJson(field, value).getBytes(UTF_8));
    }

    protected static int parseThreshold(String string) {
        try {
            var threshold = Integer.parseInt(string);
            if (threshold >= 0 && threshold <= Long.SIZE) {
                return threshold;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw badRequest("invalid threshold: " + string);
    }

    protected static NuxeoException badRequest(String message) {
        return new NuxeoException("NXQL hint: " + OPERATOR + " " + message, 400);
    }
}
