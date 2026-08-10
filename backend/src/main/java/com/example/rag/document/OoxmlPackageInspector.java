package com.example.rag.document;

import com.example.rag.persistence.DocumentFormat;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Bounded, network-free OOXML package validation shared by upload validation
 * and the native Office parser.
 */
@Component
public final class OoxmlPackageInspector {

    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    public static final long MAX_IMAGE_BYTES = 48L * 1024 * 1024;
    public static final int MAX_IMAGES = 500;
    public static final double MIN_INFLATE_RATIO = 0.01d;
    private static final long RATIO_CHECK_MINIMUM = 1024L * 1024;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};

    public Inspection inspect(Path path, DocumentFormat expected)
            throws IOException, OoxmlValidationException {
        if (expected != DocumentFormat.DOCX
                && expected != DocumentFormat.PPTX
                && expected != DocumentFormat.XLSX) {
            throw new IllegalArgumentException(
                    "OOXML inspection requires DOCX, PPTX or XLSX"
            );
        }
        requireZipSignature(path);
        ContentTypesInspection contentTypes = null;
        boolean mainPartFound = false;
        int entries = 0;
        int images = 0;
        int commentParts = 0;
        long expanded = 0;
        long imageBytes = 0;
        Set<String> names = new HashSet<>();

        try (ZipFile archive = new ZipFile(path.toFile())) {
            var iterator = archive.entries().asIterator();
            while (iterator.hasNext()) {
                ZipEntry entry = iterator.next();
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw invalid("OOXML_ENTRY_LIMIT", "Office package contains too many ZIP entries");
                }
                String name = normalizedName(entry.getName());
                if (!names.add(name)) {
                    throw invalid("OOXML_DUPLICATE_ENTRY", "Office package contains duplicate entries");
                }
                rejectUnsupportedPart(name);
                boolean metadata = name.equals("[content_types].xml")
                        || name.endsWith(".rels");
                byte[] captured;
                try (InputStream input = archive.getInputStream(entry)) {
                    captured = readBounded(input, metadata ? MAX_METADATA_BYTES : 0);
                }
                long actualSize = captured == null
                        ? actualSize(archive, entry)
                        : captured.length;
                if (actualSize > MAX_ENTRY_BYTES) {
                    throw invalid("OOXML_ENTRY_TOO_LARGE", "Office package entry exceeds the size limit");
                }
                expanded += actualSize;
                if (expanded > MAX_EXPANDED_BYTES) {
                    throw invalid("OOXML_EXPANDED_SIZE_LIMIT", "Office package expands beyond the safe limit");
                }
                checkCompressionRatio(entry, actualSize);

                if (isImage(name)) {
                    images++;
                    imageBytes += actualSize;
                    if (images > MAX_IMAGES || imageBytes > MAX_IMAGE_BYTES) {
                        throw invalid("OOXML_IMAGE_LIMIT", "Office package contains too many image assets");
                    }
                }
                if (isCommentPart(name)) {
                    commentParts++;
                }
                if (name.equals("[content_types].xml")) {
                    contentTypes = inspectContentTypes(captured, expected);
                } else if (name.endsWith(".rels")) {
                    rejectExternalRelationships(captured);
                }
                mainPartFound |= name.equals(mainPart(expected));
            }
        }

        if (contentTypes == null || !mainPartFound
                || !contentTypes.expectedMainPart()) {
            throw invalid(
                    "OOXML_FORMAT_MISMATCH",
                    "Office package does not match its declared document format"
            );
        }
        if (contentTypes.macroEnabled()) {
            throw invalid("OOXML_MACRO_REJECTED", "Macro-enabled Office documents are not supported");
        }
        return new Inspection(
                entries,
                expanded,
                images,
                imageBytes,
                commentParts
        );
    }

    private static long actualSize(ZipFile archive, ZipEntry entry)
            throws IOException, OoxmlValidationException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = archive.getInputStream(entry)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    throw invalid("OOXML_ENTRY_TOO_LARGE", "Office package entry exceeds the size limit");
                }
            }
        }
        return total;
    }

    /**
     * Captures only metadata entries. For other entries a null return avoids a
     * second in-memory copy while the caller performs a bounded size pass.
     */
    private static byte[] readBounded(InputStream input, int captureLimit)
            throws IOException, OoxmlValidationException {
        if (captureLimit == 0) {
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > captureLimit) {
                throw invalid("OOXML_METADATA_LIMIT", "Office package metadata exceeds the safe limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void requireZipSignature(Path path)
            throws IOException, OoxmlValidationException {
        byte[] header = new byte[ZIP_SIGNATURE.length];
        try (InputStream input = java.nio.file.Files.newInputStream(path)) {
            if (input.read(header) != header.length
                    || !java.security.MessageDigest.isEqual(header, ZIP_SIGNATURE)) {
                throw invalid("OOXML_SIGNATURE_INVALID", "Office document is not a valid OOXML ZIP package");
            }
        }
    }

    private static String normalizedName(String raw)
            throws OoxmlValidationException {
        String name = raw.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (name.startsWith("/") || name.contains("../") || name.contains("/..")) {
            throw invalid("OOXML_PATH_INVALID", "Office package contains an unsafe entry path");
        }
        return name;
    }

    private static void rejectUnsupportedPart(String name)
            throws OoxmlValidationException {
        if (name.endsWith("vbaproject.bin")
                || name.contains("/embeddings/")
                || name.contains("/activex/")
                || name.contains("/oleobject")
                || name.equals("encryptedpackage")
                || name.contains("/externallinks/")) {
            throw invalid(
                    "OOXML_ACTIVE_CONTENT_REJECTED",
                    "Macros, OLE objects, embedded packages and active content are not supported"
            );
        }
    }

    private static ContentTypesInspection inspectContentTypes(
            byte[] contentTypes,
            DocumentFormat format
    ) throws OoxmlValidationException {
        String expectedPart = "/" + mainPart(format);
        String expectedType = switch (format) {
            case DOCX ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            case PPTX ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
            case XLSX ->
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            default -> throw new IllegalArgumentException(
                    "Unsupported OOXML format " + format
            );
        };
        boolean expectedMainPart = false;
        boolean macroEnabled = false;
        XMLStreamReader reader = xmlReader(contentTypes);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) {
                    throw invalid("OOXML_XML_UNSAFE", "Office metadata contains a DTD");
                }
                if (event != XMLStreamConstants.START_ELEMENT
                        || !"Override".equals(reader.getLocalName())) {
                    continue;
                }
                String partName = attribute(reader, "PartName");
                String contentType = attribute(reader, "ContentType");
                if (contentType != null) {
                    macroEnabled |= contentType.toLowerCase(Locale.ROOT)
                            .contains("macroenabled");
                }
                expectedMainPart |= expectedPart.equals(partName)
                        && expectedType.equals(contentType);
            }
            return new ContentTypesInspection(expectedMainPart, macroEnabled);
        } catch (XMLStreamException exception) {
            throw invalidXml(exception);
        } finally {
            close(reader);
        }
    }

    private static String mainPart(DocumentFormat format) {
        return switch (format) {
            case DOCX -> "word/document.xml";
            case PPTX -> "ppt/presentation.xml";
            case XLSX -> "xl/workbook.xml";
            default -> throw new IllegalArgumentException(
                    "Unsupported OOXML format " + format
            );
        };
    }

    private static void rejectExternalRelationships(byte[] value)
            throws OoxmlValidationException {
        XMLStreamReader reader = xmlReader(value);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) {
                    throw invalid("OOXML_XML_UNSAFE", "Office metadata contains a DTD");
                }
                if (event == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(reader.getLocalName())
                        && "external".equalsIgnoreCase(
                                attribute(reader, "TargetMode")
                        )) {
                    throw invalid(
                            "OOXML_EXTERNAL_RELATIONSHIP",
                            "Office package contains an external relationship"
                    );
                }
            }
        } catch (XMLStreamException exception) {
            throw invalidXml(exception);
        } finally {
            close(reader);
        }
    }

    private static XMLStreamReader xmlReader(byte[] value)
            throws OoxmlValidationException {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
                throw new XMLStreamException("External XML resources are disabled");
            });
            return factory.createXMLStreamReader(new ByteArrayInputStream(value));
        } catch (IllegalArgumentException | XMLStreamException exception) {
            throw invalidXml(exception);
        }
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (localName.equals(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static void close(XMLStreamReader reader) {
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Parsing has already completed or failed with the authoritative error.
        }
    }

    private static OoxmlValidationException invalidXml(Exception cause) {
        OoxmlValidationException exception = invalid(
                "OOXML_XML_INVALID",
                "Office package metadata is not valid safe XML"
        );
        exception.initCause(cause);
        return exception;
    }

    private static boolean isImage(String name) {
        return name.startsWith("word/media/")
                || name.startsWith("ppt/media/")
                || name.startsWith("xl/media/");
    }

    private static boolean isCommentPart(String name) {
        return name.equals("word/comments.xml")
                || name.startsWith("word/commentsextended")
                || name.startsWith("ppt/comments/")
                || name.equals("xl/comments.xml")
                || name.matches("xl/comments[0-9]+\\.xml");
    }

    private static void checkCompressionRatio(ZipEntry entry, long expanded)
            throws OoxmlValidationException {
        long compressed = entry.getCompressedSize();
        if (expanded >= RATIO_CHECK_MINIMUM
                && compressed > 0
                && (double) compressed / (double) expanded < MIN_INFLATE_RATIO) {
            throw invalid("OOXML_ZIP_BOMB", "Office package contains a suspicious compression ratio");
        }
    }

    private static OoxmlValidationException invalid(String code, String message) {
        return new OoxmlValidationException(code, message);
    }

    public record Inspection(
            int entryCount,
            long expandedBytes,
            int imageCount,
            long imageBytes,
            int commentPartCount
    ) {
    }

    private record ContentTypesInspection(
            boolean expectedMainPart,
            boolean macroEnabled
    ) {
    }

    public static final class OoxmlValidationException extends Exception {
        private final String code;

        OoxmlValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
