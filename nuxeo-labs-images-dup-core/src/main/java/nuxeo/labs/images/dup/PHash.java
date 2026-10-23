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

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Utilities for computing 64-bit DCT perceptual hashes.
 *
 * @since 2025.1.0
 */
public final class PHash {

    /**
     * Side length of the reduced luminance matrix.
     *
     * @since 2025.1.0
     */
    public static final int REDUCED_SIZE = 32;

    /** Low frequencies kept on each axis: 8 x 8 = 64 bits. */
    static final int LOW_FREQUENCIES = 8;

    /**
     * Number of orientations returned by {@link #orientationHashes(double[][])}.
     *
     * @since 2025.1.0
     */
    public static final int ORIENTATIONS = 8;

    /** Below this size (smaller side, in pixels), images (icons...) match too many others. */
    static final int MIN_IMAGE_SIZE = 32;

    /** Below this standard deviation (gray levels), the image is considered uniform (blank...). */
    static final double MIN_STANDARD_DEVIATION = 2.0;

    static final double[][] COSINES = new double[LOW_FREQUENCIES][REDUCED_SIZE];

    static {
        for (var k = 0; k < LOW_FREQUENCIES; k++) {
            for (var n = 0; n < REDUCED_SIZE; n++) {
                COSINES[k][n] = Math.cos(Math.PI * k * (2 * n + 1) / (2.0 * REDUCED_SIZE));
            }
        }
    }

    private PHash() {
        // utility class
    }

    /**
     * Returns the luminance of the image reduced to {@link #REDUCED_SIZE} x {@link #REDUCED_SIZE}, as [row][column].
     *
     * @since 2025.1.0
     */
    public static double[][] reduce(BufferedImage image) {
        return areaAverage(luminance(image), image.getWidth(), image.getHeight(), REDUCED_SIZE);
    }

    /**
     * Computes the 64-bit perceptual hash from a reduced luminance matrix.
     *
     * @since 2025.1.0
     */
    public static long hash(double[][] reduced) {
        // DCT-II on rows then columns, low frequencies only (scale factors are irrelevant for a median comparison)
        var vertical = new double[LOW_FREQUENCIES][REDUCED_SIZE];
        for (var k = 0; k < LOW_FREQUENCIES; k++) {
            for (var x = 0; x < REDUCED_SIZE; x++) {
                var sum = 0.0;
                for (var y = 0; y < REDUCED_SIZE; y++) {
                    sum += COSINES[k][y] * reduced[y][x];
                }
                vertical[k][x] = sum;
            }
        }
        var coefficients = new double[LOW_FREQUENCIES * LOW_FREQUENCIES];
        for (var k = 0; k < LOW_FREQUENCIES; k++) {
            for (var l = 0; l < LOW_FREQUENCIES; l++) {
                var sum = 0.0;
                for (var x = 0; x < REDUCED_SIZE; x++) {
                    sum += COSINES[l][x] * vertical[k][x];
                }
                coefficients[k * LOW_FREQUENCIES + l] = sum;
            }
        }
        var sorted = coefficients.clone();
        Arrays.sort(sorted);
        var middle = sorted.length / 2;
        var median = (sorted[middle - 1] + sorted[middle]) / 2;
        var hash = 0L;
        for (var coefficient : coefficients) {
            hash <<= 1;
            if (coefficient > median) {
                hash |= 1;
            }
        }
        return hash;
    }

    /**
     * Returns the hashes of the 8 orientations of the image: identity (index 0), rotations by 90, 180 and 270 degrees
     * clockwise, horizontal and vertical mirrors, transposition and anti-transposition.
     * <p>
     * Computed on the reduced matrix, which is exact because the area average commutes with these transformations.
     *
     * @since 2025.1.0
     */
    public static long[] orientationHashes(double[][] reduced) {
        var last = REDUCED_SIZE - 1;
        var hashes = new long[ORIENTATIONS];
        var oriented = new double[REDUCED_SIZE][REDUCED_SIZE];
        for (var orientation = 0; orientation < ORIENTATIONS; orientation++) {
            for (var y = 0; y < REDUCED_SIZE; y++) {
                for (var x = 0; x < REDUCED_SIZE; x++) {
                    oriented[y][x] = switch (orientation) {
                        case 0 -> reduced[y][x];
                        case 1 -> reduced[last - x][y];
                        case 2 -> reduced[last - y][last - x];
                        case 3 -> reduced[x][last - y];
                        case 4 -> reduced[y][last - x];
                        case 5 -> reduced[last - y][x];
                        case 6 -> reduced[x][y];
                        default -> reduced[last - x][last - y];
                    };
                }
            }
            hashes[orientation] = hash(oriented);
        }
        return hashes;
    }

    /**
     * Returns {@code true} if the image is too small or too uniform for its hash to be meaningful.
     *
     * @since 2025.1.0
     */
    public static boolean isDegenerate(BufferedImage image, double[][] reduced) {
        if (Math.min(image.getWidth(), image.getHeight()) < MIN_IMAGE_SIZE) {
            return true;
        }
        var sum = 0.0;
        var sumOfSquares = 0.0;
        for (var row : reduced) {
            for (var value : row) {
                sum += value;
                sumOfSquares += value * value;
            }
        }
        var count = (double) REDUCED_SIZE * REDUCED_SIZE;
        var mean = sum / count;
        var variance = Math.max(0, sumOfSquares / count - mean * mean);
        return Math.sqrt(variance) < MIN_STANDARD_DEVIATION;
    }

    /**
     * Computes the Hamming distance between two hashes.
     *
     * @since 2025.1.0
     */
    public static int distance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    /**
     * Encodes a hash as a 16-digit lowercase hexadecimal string.
     *
     * @since 2025.1.0
     */
    public static String toHex(long hash) {
        return "%016x".formatted(hash);
    }

    /**
     * Parses an unsigned 64-bit hexadecimal hash.
     *
     * @since 2025.1.0
     */
    public static long fromHex(String hex) {
        return Long.parseUnsignedLong(hex, 16);
    }

    static double[] luminance(BufferedImage image) {
        var width = image.getWidth();
        var height = image.getHeight();
        var luminance = new double[width * height];
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            // getRGB would apply a linear to sRGB conversion
            var samples = image.getRaster().getSamples(0, 0, width, height, 0, (int[]) null);
            for (var i = 0; i < samples.length; i++) {
                luminance[i] = samples[i];
            }
            return luminance;
        }
        var pixels = image.getRGB(0, 0, width, height, null, 0, width);
        for (var i = 0; i < pixels.length; i++) {
            var pixel = pixels[i];
            var alpha = ((pixel >>> 24) & 0xFF) / 255.0;
            var gray = 0.299 * ((pixel >> 16) & 0xFF) + 0.587 * ((pixel >> 8) & 0xFF) + 0.114 * (pixel & 0xFF);
            // transparent areas on white, like Nuxeo picture views
            luminance[i] = gray * alpha + 255 * (1 - alpha);
        }
        return luminance;
    }

    static double[][] areaAverage(double[] values, int width, int height, int size) {
        var result = new double[size][size];
        var cellWidth = (double) width / size;
        var cellHeight = (double) height / size;
        for (var row = 0; row < size; row++) {
            var top = row * cellHeight;
            var bottom = top + cellHeight;
            for (var column = 0; column < size; column++) {
                var left = column * cellWidth;
                var right = left + cellWidth;
                var sum = 0.0;
                var area = 0.0;
                for (var y = (int) top; y < bottom && y < height; y++) {
                    var weightY = Math.min(y + 1, bottom) - Math.max(y, top);
                    for (var x = (int) left; x < right && x < width; x++) {
                        var weight = weightY * (Math.min(x + 1, right) - Math.max(x, left));
                        sum += values[y * width + x] * weight;
                        area += weight;
                    }
                }
                result[row][column] = sum / area;
            }
        }
        return result;
    }
}
