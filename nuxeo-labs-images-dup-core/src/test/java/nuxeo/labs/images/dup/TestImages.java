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

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Synthetic images and transformations for tests.
 */
public final class TestImages {

    private TestImages() {
        // utility class
    }

    /** Gradient background with random shapes, deterministic for a given seed. */
    public static BufferedImage sample(long seed, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        var random = new Random(seed);
        g.setPaint(new GradientPaint(0, 0, new Color(random.nextInt()), width, height, new Color(random.nextInt())));
        g.fillRect(0, 0, width, height);
        for (var i = 0; i < 12; i++) {
            g.setColor(new Color(random.nextInt()));
            var w = width / 8 + random.nextInt(width / 3);
            var h = height / 8 + random.nextInt(height / 3);
            var x = random.nextInt(width) - w / 2;
            var y = random.nextInt(height) - h / 2;
            if (random.nextBoolean()) {
                g.fillOval(x, y, w, h);
            } else {
                g.fillRect(x, y, w, h);
            }
        }
        g.dispose();
        return image;
    }

    public static BufferedImage uniform(Color color, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    public static BufferedImage resize(BufferedImage source, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return image;
    }

    public static BufferedImage rotate90(BufferedImage source) {
        var width = source.getWidth();
        var height = source.getHeight();
        var image = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(height - 1 - y, x, source.getRGB(x, y));
            }
        }
        return image;
    }

    public static BufferedImage mirror(BufferedImage source) {
        var width = source.getWidth();
        var height = source.getHeight();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(width - 1 - x, y, source.getRGB(x, y));
            }
        }
        return image;
    }

    public static byte[] jpeg(BufferedImage image, float quality) {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    public static BufferedImage recompress(BufferedImage image, float quality) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg(image, quality)))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No image reader found for generated JPEG");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static long hash(BufferedImage image) {
        return PHash.hash(PHash.reduce(image));
    }
}
