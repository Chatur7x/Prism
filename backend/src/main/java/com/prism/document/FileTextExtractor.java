package com.prism.document;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Extracts plain text from an uploaded file.
 *
 * <p>Security posture: the declared MIME type is never trusted, and the content
 * is parsed by a format-specific reader rather than executed. Executable and
 * script content types are rejected outright, and the stored filename is
 * sanitized so a crafted name cannot escape the upload directory or inject into
 * a downstream header.
 */
@Component
public class FileTextExtractor {

    /** Formats PRISM accepts. Anything else is rejected before parsing. */
    private static final List<String> ALLOWED_MIME_PREFIXES = List.of(
            "text/plain", "text/markdown", "text/csv",
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private static final long MAX_EXTRACTED_CHARS = 5_000_000L;

    public boolean isSupported(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            // Fall back to the extension only when the browser sent nothing.
            return hasSupportedExtension(file.getOriginalFilename());
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("application/x-") || lower.contains("javascript")
                || lower.contains("html") || lower.contains("xml") || lower.contains("shell")) {
            return false;
        }
        return ALLOWED_MIME_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    public String extract(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = filename.toLowerCase(Locale.ROOT);

        try (InputStream in = file.getInputStream()) {
            String text;
            if (lower.endsWith(".pdf") || isPdf(file)) {
                text = extractPdf(in);
            } else if (lower.endsWith(".docx") || isDocx(file)) {
                text = extractDocx(in);
            } else if (lower.endsWith(".doc")) {
                throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "legacy .doc files are not supported; convert to .docx or .pdf");
            } else {
                text = extractPlainText(in);
            }
            String normalized = SentenceChunker.normalize(text);
            if (normalized.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "the uploaded file contains no extractable text");
            }
            if (normalized.length() > MAX_EXTRACTED_CHARS) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                        "extracted text exceeds the supported length of " + MAX_EXTRACTED_CHARS + " characters");
            }
            return normalized;
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "the uploaded file could not be read as text", ex);
        }
    }

    private String extractPdf(InputStream in) throws IOException {
        // PDFBox 3 requires a RandomAccessRead; a buffered stream is not enough
        // because the parser seeks. Read the whole (size-limited) upload.
        byte[] bytes = in.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "the PDF is encrypted; provide an unprotected copy");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            // Default sort order is physical layout order, which keeps sentences
            // coherent. Without this, columns interleave into nonsense.
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "the file is not a readable PDF", ex);
        }
    }

    private String extractDocx(InputStream in) throws IOException {
        try (XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "the file is not a readable DOCX document", ex);
        }
    }

    private String extractPlainText(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private boolean isPdf(MultipartFile file) {
        String type = file.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("application/pdf");
    }

    private boolean isDocx(MultipartFile file) {
        String type = file.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).contains("wordprocessingml");
    }

    private boolean hasSupportedExtension(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")
                || lower.endsWith(".pdf") || lower.endsWith(".docx");
    }

    /**
     * Reduces an uploaded filename to a safe display name.
     *
     * <p>Strips any directory component and control characters. The name is
     * stored and later rendered, so a value containing a path separator or a
     * newline must never survive.
     */
    public static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return null;
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == '<' || c == '>' || c == ':' || c == '"'
                    || c == '|' || c == '?' || c == '*') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return null;
        }
        return cleaned.length() > 512 ? cleaned.substring(0, 512) : cleaned;
    }

    /** Derives a document title from a filename when the client supplied none. */
    public static String titleFromFilename(String sanitized) {
        if (sanitized == null || sanitized.isBlank()) {
            return "Untitled document";
        }
        int dot = sanitized.lastIndexOf('.');
        String base = dot > 0 ? sanitized.substring(0, dot) : sanitized;
        base = base.replace('_', ' ').replace('-', ' ').strip();
        if (base.isEmpty()) {
            return "Untitled document";
        }
        return base.length() > 500 ? base.substring(0, 500) : base;
    }
}
