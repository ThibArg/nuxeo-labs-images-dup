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

import static nuxeo.labs.images.dup.TestImages.hash;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.Test;

public class TestPHash {

    protected static final int THRESHOLD = ImageHashConfigurationDescriptor.DEFAULT_THRESHOLD;

    protected final BufferedImage original = TestImages.sample(42, 560, 420);

    @Test
    public void testSameImageSameHash() {
        assertEquals(hash(original), hash(TestImages.sample(42, 560, 420)));
    }

    @Test
    public void testResizedImageIsDuplicate() {
        assertTrue(PHash.distance(hash(original), hash(TestImages.resize(original, 280, 210))) <= THRESHOLD);
        assertTrue(PHash.distance(hash(original), hash(TestImages.resize(original, 1000, 750))) <= THRESHOLD);
    }

    @Test
    public void testRecompressedImageIsDuplicate() {
        assertTrue(PHash.distance(hash(original), hash(TestImages.recompress(original, 0.3f))) <= THRESHOLD);
    }

    @Test
    public void testDifferentImagesAreNotDuplicates() {
        for (var seed = 1; seed <= 20; seed++) {
            var other = hash(TestImages.sample(seed, 560, 420));
            assertTrue("seed " + seed, PHash.distance(hash(original), other) > THRESHOLD);
        }
    }

    @Test
    public void testOrientations() {
        var orientations = PHash.orientationHashes(PHash.reduce(original));
        assertEquals(PHash.ORIENTATIONS, orientations.length);
        assertEquals(hash(original), orientations[0]);

        var rotated90 = TestImages.rotate90(original);
        assertTrue(PHash.distance(orientations[1], hash(rotated90)) <= 1);
        var rotated180 = TestImages.rotate90(rotated90);
        assertTrue(PHash.distance(orientations[2], hash(rotated180)) <= 1);
        assertTrue(PHash.distance(orientations[3], hash(TestImages.rotate90(rotated180))) <= 1);
        assertTrue(PHash.distance(orientations[4], hash(TestImages.mirror(original))) <= 1);

        // a rotated copy is not found without orientations
        assertTrue(PHash.distance(hash(original), hash(rotated90)) > THRESHOLD);
    }

    /**
     * Every bit must carry information. The DC coefficient, the sum of the luminance, is never below the others, so a
     * hash keeping it has a bit that is always set, and the band holding that bit selects half as well as the others.
     */
    @Test
    public void testBitDistribution() {
        var random = new Random(42);
        var count = 3000;
        var ones = new int[Long.SIZE];
        for (var i = 0; i < count; i++) {
            var hash = PHash.hash(randomMatrix(random, i % 3));
            for (var bit = 0; bit < Long.SIZE; bit++) {
                ones[bit] += (int) (hash >>> bit & 1);
            }
        }
        for (var bit = 0; bit < Long.SIZE; bit++) {
            var share = (double) ones[bit] / count;
            assertTrue("bit " + bit + " is set in " + share + " of the hashes", share > 0.25 && share < 0.75);
        }
    }

    /** Noise, a gradient or a dark image with bright spots: luminance matrices with different spectra. */
    protected static double[][] randomMatrix(Random random, int kind) {
        var matrix = new double[PHash.REDUCED_SIZE][PHash.REDUCED_SIZE];
        var dx = random.nextDouble() * 2 - 1;
        var dy = random.nextDouble() * 2 - 1;
        for (var y = 0; y < PHash.REDUCED_SIZE; y++) {
            for (var x = 0; x < PHash.REDUCED_SIZE; x++) {
                matrix[y][x] = switch (kind) {
                    case 0 -> random.nextInt(256);
                    case 1 -> Math.clamp(128 + 3 * (dx * x + dy * y) + 40 * random.nextGaussian(), 0, 255);
                    default -> random.nextInt(20) == 0 ? 200 + random.nextInt(56) : random.nextInt(30);
                };
            }
        }
        return matrix;
    }

    @Test
    public void testDegenerate() {
        var blank = TestImages.uniform(Color.WHITE, 560, 420);
        assertTrue(PHash.isDegenerate(blank, PHash.reduce(blank)));
        var icon = TestImages.sample(42, 16, 16);
        assertTrue(PHash.isDegenerate(icon, PHash.reduce(icon)));
        assertFalse(PHash.isDegenerate(original, PHash.reduce(original)));
    }

    @Test
    public void testAreaAverage() {
        var values = new double[64 * 64];
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                values[y * 64 + x] = x < 32 ? 0 : 100;
            }
        }
        var reduced = PHash.areaAverage(values, 64, 64, 32);
        assertEquals(0, reduced[5][15], 1e-9);
        assertEquals(100, reduced[5][16], 1e-9);
        // 3 pixels into 2 cells: the middle pixel is shared
        var shared = PHash.areaAverage(new double[] { 0, 60, 120 }, 3, 1, 2);
        assertEquals(20, shared[0][0], 1e-9);
        assertEquals(100, shared[0][1], 1e-9);
    }

    @Test
    public void testHex() {
        var hash = 0xF123456789ABCDEFL;
        assertEquals("f123456789abcdef", PHash.toHex(hash));
        assertEquals(hash, PHash.fromHex(PHash.toHex(hash)));
        assertEquals("000000000000000a", PHash.toHex(10));
    }
}
