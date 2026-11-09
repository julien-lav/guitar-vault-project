package com.guitarvault;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import java.util.Set;

final class ImageUploads {
    private ImageUploads() { }
    static String imageType(byte[] data) throws IOException {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("jpeg", "png").contains(format) || reader.getWidth(0) > 10000
                        || reader.getHeight(0) > 10000 || (long) reader.getWidth(0) * reader.getHeight(0) > 25000000) {
                    throw new IOException("Unsupported image");
                }
                if (reader.read(0) == null) throw new IOException("Invalid image");
                return "image/" + format;
            } finally { reader.dispose(); }
        }
    }
}
