package com.example.rag.document;

import com.example.rag.common.ApiException;
import com.example.rag.persistence.DocumentFormat;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class DocumentFileValidator {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes();

    private final DocumentFormatCapabilityService formats;
    private final OoxmlPackageInspector ooxml;
    private final Tika tika = new Tika();

    public DocumentFileValidator(
            DocumentFormatCapabilityService formats,
            OoxmlPackageInspector ooxml
    ) {
        this.formats = formats;
        this.ooxml = ooxml;
    }

    public ValidatedDocument validate(MultipartFile file) {
        String filename = safeFilename(file.getOriginalFilename());
        DocumentFormatsResponse registry = formats.capabilities();
        var extensionCapability = formats
                .findCapability(registry, filename, null, null)
                .orElseThrow(() -> invalid("FILE_EXTENSION_INVALID", "文件扩展名不受支持"));
        formats.requireOperational(extensionCapability);
        String declaredMediaType = file.getContentType();
        boolean uninformativeDeclaration = declaredMediaType == null
                || declaredMediaType.isBlank()
                || "application/octet-stream".equalsIgnoreCase(
                declaredMediaType
        );
        if (uninformativeDeclaration
                && extensionCapability.format() == DocumentFormat.PDF) {
            throw invalid("FILE_MEDIA_TYPE_INVALID", "文件声明类型与扩展名不匹配");
        }
        var declaredCapability = uninformativeDeclaration
                ? extensionCapability
                : formats.findCapability(
                        registry,
                        filename,
                        declaredMediaType,
                        null
                )
                .orElseThrow(() -> invalid(
                        "FILE_MEDIA_TYPE_INVALID",
                        "文件声明类型与扩展名不匹配"
                ));
        long maxBytes = declaredCapability.maxFileSizeBytes();
        if (file.isEmpty() || file.getSize() > maxBytes) {
            throw invalid(
                    "FILE_SIZE_INVALID",
                    declaredCapability.displayName() + " 必须非空且不超过 "
                            + maxBytes / 1024 / 1024 + " MiB"
            );
        }

        Path temporary = null;
        try {
            String extension = declaredCapability.extensions().getFirst();
            temporary = Files.createTempFile("rag-upload-", extension);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyAndHash(
                    file,
                    temporary,
                    digest,
                    maxBytes,
                    declaredCapability.displayName()
            );
            validateSignature(declaredCapability.format(), temporary);
            String detectedMediaType;
            try (InputStream content = Files.newInputStream(temporary)) {
                detectedMediaType = tika.detect(content);
            }
            if (declaredCapability.format() == DocumentFormat.DOCX
                    || declaredCapability.format() == DocumentFormat.PPTX
                    || declaredCapability.format() == DocumentFormat.XLSX) {
                // The package inspector has already verified the exact OOXML
                // main part and content type. Tika may still report the
                // generic ZIP/OOXML media type for a valid package.
                detectedMediaType = declaredCapability.mediaTypes().getFirst();
            }
            var detectedCapability = formats.findCapability(
                    registry,
                    filename,
                    null,
                    detectedMediaType
                    )
                    .orElseThrow(() -> invalid(
                            "FILE_CONTENT_TYPE_INVALID",
                            "文件真实类型与扩展名不匹配"
                    ));
            formats.requireOperational(detectedCapability);
            return new ValidatedDocument(
                    temporary,
                    filename,
                    size,
                    HexFormat.of().formatHex(digest.digest()),
                    detectedCapability.format(),
                    detectedMediaType,
                    extension
            );
        } catch (ApiException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "无法读取上传文件", exception);
        }
    }

    private static long copyAndHash(
            MultipartFile file,
            Path target,
            MessageDigest digest,
            long maxBytes,
            String displayName
    ) throws IOException {
        long size = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw invalid(
                            "FILE_SIZE_INVALID",
                            displayName + " 必须非空且不超过 "
                                    + maxBytes / 1024 / 1024 + " MiB"
                    );
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return size;
    }

    private void validateSignature(DocumentFormat format, Path path)
            throws IOException {
        if (format == DocumentFormat.DOCX
                || format == DocumentFormat.PPTX
                || format == DocumentFormat.XLSX) {
            try {
                ooxml.inspect(path, format);
            } catch (OoxmlPackageInspector.OoxmlValidationException exception) {
                throw invalid(exception.code(), exception.getMessage());
            }
            return;
        }
        if (format != DocumentFormat.PDF) {
            return;
        }
        byte[] header = new byte[PDF_SIGNATURE.length];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(header) != header.length
                    || !MessageDigest.isEqual(header, PDF_SIGNATURE)) {
                throw invalid("FILE_SIGNATURE_INVALID", "文件内容不是有效的 PDF");
            }
        }
    }

    private static String safeFilename(String value) {
        String filename = value == null ? "document" : value.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replace('\r', '_').replace('\n', '_').trim();
        if (filename.isEmpty()) {
            filename = "document";
        }
        return filename.length() <= 255 ? filename : filename.substring(filename.length() - 255);
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Temporary files are also removed by the container lifecycle.
            }
        }
    }

    public record ValidatedDocument(
            Path path,
            String filename,
            long size,
            String sha256,
            DocumentFormat format,
            String mediaType,
            String extension
    ) implements AutoCloseable {
        @Override
        public void close() {
            deleteQuietly(path);
        }
    }
}
