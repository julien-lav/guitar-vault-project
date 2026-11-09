package com.guitarvault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;

final class DocumentUploadValidator {
    private DocumentUploadValidator() { }
    record Upload(String filename, String contentType, byte[] data) { }
    static Upload validate(MultipartFile file) {
        try {
            if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024) throw invalid();
            String raw = file.getOriginalFilename();
            if (raw == null) throw invalid();
            String name = raw.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).strip();
            if (name.isBlank() || name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            String lower = name.toLowerCase(Locale.ROOT);
            byte[] data = file.getBytes();
            if (data.length == 0 || data.length > 5 * 1024 * 1024) throw invalid();
            String type;
            if (lower.endsWith(".pdf")) {
                // Basic format checks only; PDFs are always downloaded as attachments, never rendered inline.
                String header = new String(data, 0, Math.min(9, data.length), StandardCharsets.ISO_8859_1);
                String tail = new String(data, Math.max(0, data.length - 1024), Math.min(1024, data.length), StandardCharsets.ISO_8859_1);
                if (!header.matches("%PDF-(1\\.[0-7]|2\\.0)[\\r\\n].*") || !tail.stripTrailing().endsWith("%%EOF")) throw invalid();
                type = "application/pdf";
            } else {
                type = ImageUploads.imageType(data);
                if (!(type.equals("image/png") && lower.endsWith(".png"))
                        && !(type.equals("image/jpeg") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg")))) throw invalid();
            }
            return new Upload(name, type, data);
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("document.fileError"); }
}
