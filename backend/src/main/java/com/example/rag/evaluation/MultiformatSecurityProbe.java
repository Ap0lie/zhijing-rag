package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.document.DocumentFileValidator;
import com.example.rag.document.OoxmlPackageInspector;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.ParserInput;
import com.example.rag.pipeline.ParserProcessingException;
import com.example.rag.pipeline.ParserProviderKind;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.SpreadsheetDocumentParser;
import com.example.rag.pipeline.parser.TextDocumentParser;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
final class MultiformatSecurityProbe {

    static final String SUITE_VERSION = "phase18-security-v1";
    private static final UUID DOCUMENT_ID = UUID.nameUUIDFromBytes(
            "phase18-security-document".getBytes(StandardCharsets.UTF_8)
    );
    private static final UUID REVISION_ID = UUID.nameUUIDFromBytes(
            "phase18-security-revision".getBytes(StandardCharsets.UTF_8)
    );
    private static final List<ProbeDefinition> DEFINITIONS = List.of(
            definition("PDF", "pdf-format-spoof"),
            definition("TXT", "txt-nul-rejected"),
            definition("MARKDOWN", "markdown-html-inert"),
            definition("HTML", "html-active-content-removed"),
            definition("DOCX", "docx-external-relationship"),
            definition("PPTX", "pptx-external-relationship"),
            definition("XLSX", "xlsx-external-relationship"),
            definition("CSV", "csv-formula-inert")
    );

    private final DocumentFileValidator fileValidator;
    private final OoxmlPackageInspector ooxml;
    private final TextDocumentParser textParser;
    private final SpreadsheetDocumentParser spreadsheetParser;

    MultiformatSecurityProbe(
            DocumentFileValidator fileValidator,
            OoxmlPackageInspector ooxml,
            TextDocumentParser textParser,
            SpreadsheetDocumentParser spreadsheetParser
    ) {
        this.fileValidator = fileValidator;
        this.ooxml = ooxml;
        this.textParser = textParser;
        this.spreadsheetParser = spreadsheetParser;
    }

    List<ProbeDefinition> definitions() {
        return DEFINITIONS;
    }

    String suiteHash() {
        return sha256(DEFINITIONS.stream()
                .map(item -> item.key() + ":" + item.inputSha256())
                .collect(java.util.stream.Collectors.joining("|")));
    }

    ProbeResult execute(String key) {
        ProbeDefinition definition = DEFINITIONS.stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown security probe " + key
                ));
        try {
            return switch (definition.key()) {
                case "pdf-format-spoof" -> pdfSpoof(definition);
                case "txt-nul-rejected" -> txtNul(definition);
                case "markdown-html-inert" -> markdownInert(definition);
                case "html-active-content-removed" -> htmlSanitized(definition);
                case "docx-external-relationship" ->
                        externalRelationship(definition, DocumentFormat.DOCX);
                case "pptx-external-relationship" ->
                        externalRelationship(definition, DocumentFormat.PPTX);
                case "xlsx-external-relationship" ->
                        externalRelationship(definition, DocumentFormat.XLSX);
                case "csv-formula-inert" -> csvFormula(definition);
                default -> throw new IllegalStateException(
                        "Unhandled security probe " + definition.key()
                );
            };
        } catch (Exception failure) {
            return result(
                    definition,
                    false,
                    "SECURITY_PROBE_FAILED",
                    failure.getClass().getSimpleName()
            );
        }
    }

    private ProbeResult pdfSpoof(ProbeDefinition definition) {
        byte[] payload = payload(definition.key());
        try {
            try (var ignored = fileValidator.validate(new BytesMultipartFile(
                    "attack.pdf", "application/pdf", payload
            ))) {
                // An accepted spoof is a failed probe; closing removes its temp file.
            }
            return result(definition, false, "PDF_SPOOF_ACCEPTED", null);
        } catch (ApiException expected) {
            boolean passed = "FILE_SIGNATURE_INVALID".equals(expected.getCode());
            return result(definition, passed, expected.getCode(), null);
        }
    }

    private ProbeResult txtNul(ProbeDefinition definition) throws Exception {
        byte[] payload = payload(definition.key());
        try {
            parseText(payload, DocumentFormat.TXT, ParserProviderKind.TEXT);
            return result(definition, false, "TXT_NUL_ACCEPTED", null);
        } catch (ParserProcessingException expected) {
            boolean passed = "DOCUMENT_BINARY_CONTENT".equals(expected.code());
            return result(definition, passed, expected.code(), null);
        }
    }

    private ProbeResult markdownInert(ProbeDefinition definition)
            throws Exception {
        byte[] payload = payload(definition.key());
        var parsed = parseText(
                payload, DocumentFormat.MARKDOWN, ParserProviderKind.MARKDOWN
        );
        boolean passed = parsed.packageMetadata().manifestJson()
                .contains("raw HTML remains inert text")
                && parsed.packageMetadata().manifestJson()
                .contains("\"networkResourcesFetched\":0");
        return result(
                definition, passed,
                passed ? "MARKDOWN_HTML_INERT" : "MARKDOWN_HTML_CONTRACT_LOST",
                null
        );
    }

    private ProbeResult htmlSanitized(ProbeDefinition definition)
            throws Exception {
        byte[] payload = payload(definition.key());
        var parsed = parseText(
                payload, DocumentFormat.HTML, ParserProviderKind.HTML
        );
        boolean passed = !parsed.markdown().contains("window.secret")
                && !parsed.markdown().contains("attacker.invalid")
                && !parsed.markdown().contains("onload")
                && parsed.packageMetadata().manifestJson()
                .contains("\"networkResourcesFetched\":0");
        return result(
                definition, passed,
                passed ? "HTML_ACTIVE_CONTENT_REMOVED"
                        : "HTML_SANITIZATION_REGRESSION",
                null
        );
    }

    private ProbeResult externalRelationship(
            ProbeDefinition definition,
            DocumentFormat format
    ) throws Exception {
        Path path = Files.createTempFile(
                "phase18-security-", "." + format.name().toLowerCase()
        );
        try {
            Files.write(path, payload(definition.key()));
            try {
                ooxml.inspect(path, format);
                return result(
                        definition, false,
                        "OOXML_EXTERNAL_RELATIONSHIP_ACCEPTED", null
                );
            } catch (OoxmlPackageInspector.OoxmlValidationException expected) {
                boolean passed = "OOXML_EXTERNAL_RELATIONSHIP".equals(
                        expected.code()
                );
                return result(definition, passed, expected.code(), null);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private ProbeResult csvFormula(ProbeDefinition definition)
            throws Exception {
        byte[] payload = payload(definition.key());
        Path path = Files.createTempFile("phase18-security-", ".csv");
        try {
            Files.write(path, payload);
            var parsed = spreadsheetParser.parse(
                    input(path, payload, DocumentFormat.CSV, "text/csv"),
                    ChunkingProfile.phase4Default(),
                    ParserProviderKind.CSV_STREAM
            );
            boolean formulaInert = parsed.tables().stream()
                    .flatMap(table -> table.cells().stream())
                    .filter(cell -> "B2".equals(cell.cellReference()))
                    .anyMatch(cell -> "=1+1".equals(cell.text())
                            && cell.formulaText() == null);
            boolean passed = formulaInert
                    && parsed.packageMetadata().manifestJson()
                    .contains("\"formulaEvaluationPerformed\":false");
            return result(
                    definition, passed,
                    passed ? "CSV_FORMULA_INERT" : "CSV_FORMULA_EXECUTION_RISK",
                    null
            );
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private com.example.rag.pipeline.parser.ParsedDocument parseText(
            byte[] payload,
            DocumentFormat format,
            ParserProviderKind provider
    ) throws Exception {
        Path path = Files.createTempFile(
                "phase18-security-", "." + format.name().toLowerCase()
        );
        try {
            Files.write(path, payload);
            String mediaType = format == DocumentFormat.HTML
                    ? "text/html" : format == DocumentFormat.MARKDOWN
                    ? "text/markdown" : "text/plain";
            return textParser.parse(
                    input(path, payload, format, mediaType),
                    ChunkingProfile.phase4Default(),
                    provider
            );
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static ParserInput input(
            Path path,
            byte[] bytes,
            DocumentFormat format,
            String mediaType
    ) {
        return new ParserInput(
                path, DOCUMENT_ID, REVISION_ID, format, mediaType,
                bytes.length, sha256(bytes)
        );
    }

    private static byte[] externalPackage(DocumentFormat format) {
        String mainPart = switch (format) {
            case DOCX -> "word/document.xml";
            case PPTX -> "ppt/presentation.xml";
            case XLSX -> "xl/workbook.xml";
            default -> throw new IllegalArgumentException("Not OOXML " + format);
        };
        String contentType = switch (format) {
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            case PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            default -> throw new IllegalArgumentException("Not OOXML " + format);
        };
        int separator = mainPart.lastIndexOf('/');
        String rels = mainPart.substring(0, separator)
                + "/_rels/" + mainPart.substring(separator + 1) + ".rels";
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream output = new ZipOutputStream(bytes)) {
            entry(output, "[Content_Types].xml",
                    "<Types><Override PartName=\"/" + mainPart
                            + "\" ContentType=\"" + contentType
                            + "\"/></Types>");
            entry(output, mainPart, "<document/>");
            entry(output, rels,
                    "<Relationships><Relationship Target=\"https://attacker.invalid/payload\" "
                            + "TargetMode=\"External\"/></Relationships>");
            output.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not construct the deterministic OOXML probe",
                    exception
            );
        }
    }

    private static void entry(
            ZipOutputStream output,
            String name,
            String value
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static ProbeDefinition definition(String format, String key) {
        return new ProbeDefinition(
                format,
                key,
                sha256(payload(key))
        );
    }

    private static byte[] payload(String key) {
        return switch (key) {
            case "pdf-format-spoof" ->
                    "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8);
            case "txt-nul-rejected" ->
                    ("readable text ".repeat(20) + "\0secret")
                            .getBytes(StandardCharsets.UTF_8);
            case "markdown-html-inert" ->
                    ("# 安全 Markdown\n\n"
                            + "<script>window.phase18Attack=true</script>\n\n"
                            + "正文不会把 HTML 片段作为可执行页面返回。".repeat(8))
                            .getBytes(StandardCharsets.UTF_8);
            case "html-active-content-removed" ->
                    ("<!doctype html><html><body onload=\"steal()\">"
                            + "<h1>安全网页</h1>"
                            + "<script>window.secret='x'</script>"
                            + "<iframe src=\"https://attacker.invalid/x\"></iframe>"
                            + "<p>只保留本地、安全、可引用的正文。</p>".repeat(8)
                            + "</body></html>")
                            .getBytes(StandardCharsets.UTF_8);
            case "docx-external-relationship" ->
                    externalPackage(DocumentFormat.DOCX);
            case "pptx-external-relationship" ->
                    externalPackage(DocumentFormat.PPTX);
            case "xlsx-external-relationship" ->
                    externalPackage(DocumentFormat.XLSX);
            case "csv-formula-inert" ->
                    "id,value\n1,=1+1\n2,safe\n"
                            .getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException(
                    "Unknown security probe payload " + key
            );
        };
    }

    private static ProbeResult result(
            ProbeDefinition definition,
            boolean passed,
            String code,
            String error
    ) {
        return new ProbeResult(
                definition.key(),
                definition.documentFormat(),
                SUITE_VERSION,
                definition.inputSha256(),
                passed,
                code,
                error,
                Map.of("networkAccessAllowed", false)
        );
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record ProbeDefinition(
            String documentFormat,
            String key,
            String inputSha256
    ) {
    }

    record ProbeResult(
            String key,
            String documentFormat,
            String suiteVersion,
            String inputSha256,
            boolean passed,
            String code,
            String error,
            Map<String, Object> details
    ) {
    }

    private record BytesMultipartFile(
            String originalFilename,
            String contentType,
            byte[] bytes
    ) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), bytes);
        }
    }
}
