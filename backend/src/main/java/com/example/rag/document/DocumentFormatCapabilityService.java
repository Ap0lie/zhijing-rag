package com.example.rag.document;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.MineruProperties;
import com.example.rag.pipeline.ParserProviderKind;
import com.example.rag.pipeline.ParserRegistry;
import com.example.rag.pipeline.PipelineWorkerHealthService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class DocumentFormatCapabilityService {

    private static final String SCHEMA_VERSION = "document-formats-v6";
    private static final long TEXT_MAX_BYTES = 10L * 1024 * 1024;
    private static final long OFFICE_MAX_BYTES = 50L * 1024 * 1024;

    private final StorageProperties storage;
    private final ParserRegistry parserRegistry;
    private final MineruProperties mineru;
    private final PipelineWorkerHealthService workerHealth;
    private final DocumentRuntimePolicyService policies;

    public DocumentFormatCapabilityService(
            StorageProperties storage,
            ParserRegistry parserRegistry,
            MineruProperties mineru,
            PipelineWorkerHealthService workerHealth,
            DocumentRuntimePolicyService policies
    ) {
        this.storage = storage;
        this.parserRegistry = parserRegistry;
        this.mineru = mineru;
        this.workerHealth = workerHealth;
        this.policies = policies;
    }

    public DocumentFormatsResponse capabilities() {
        DocumentRuntimePolicyService.Snapshot policySnapshot = policies.snapshot();
        List<DocumentFormatsResponse.ParserProviderCapability> pdfProviders = providers(
                policySnapshot,
                DocumentFormat.PDF,
                ParserProviderKind.PDFBOX,
                ParserProviderKind.MINERU
        );
        var pdf = formatCapability(
                policySnapshot,
                DocumentFormat.PDF,
                "PDF",
                List.of(".pdf"),
                List.of("application/pdf"),
                storage.maxFileSize().toBytes(),
                List.of("PAGE"),
                pdfProviders,
                true
        );
        long textMaximum = Math.min(
                storage.maxFileSize().toBytes(),
                TEXT_MAX_BYTES
        );
        var text = capability(
                policySnapshot, DocumentFormat.TXT,
                "TXT",
                List.of(".txt"),
                List.of("text/plain"),
                textMaximum,
                List.of("LINE_RANGE", "HEADING_BLOCK"),
                ParserProviderKind.TEXT
        );
        var markdown = capability(
                policySnapshot, DocumentFormat.MARKDOWN,
                "Markdown",
                List.of(".md", ".markdown"),
                List.of(
                        "text/markdown",
                        "text/x-markdown",
                        "text/plain"
                ),
                textMaximum,
                List.of("LINE_RANGE", "HEADING_BLOCK"),
                ParserProviderKind.MARKDOWN
        );
        var html = capability(
                policySnapshot, DocumentFormat.HTML,
                "HTML",
                List.of(".html", ".htm"),
                List.of("text/html", "application/xhtml+xml"),
                textMaximum,
                List.of("DOM_PATH", "HEADING_BLOCK"),
                ParserProviderKind.HTML
        );
        long officeMaximum = Math.min(
                storage.maxFileSize().toBytes(),
                OFFICE_MAX_BYTES
        );
        var docx = capability(
                policySnapshot, DocumentFormat.DOCX,
                "Word (DOCX)",
                List.of(".docx"),
                List.of(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ),
                officeMaximum,
                List.of("PARAGRAPH", "TABLE_CELL"),
                ParserProviderKind.DOCX_POI
        );
        var pptx = capability(
                policySnapshot, DocumentFormat.PPTX,
                "PowerPoint (PPTX)",
                List.of(".pptx"),
                List.of(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                ),
                officeMaximum,
                List.of("SLIDE_SHAPE", "TABLE_CELL"),
                ParserProviderKind.PPTX_POI
        );
        var xlsx = capability(
                policySnapshot, DocumentFormat.XLSX,
                "Excel (XLSX)",
                List.of(".xlsx"),
                List.of(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ),
                officeMaximum,
                List.of("CELL_RANGE"),
                ParserProviderKind.XLSX_POI
        );
        var csv = capability(
                policySnapshot, DocumentFormat.CSV,
                "CSV",
                List.of(".csv"),
                List.of("text/csv", "application/csv", "text/plain"),
                textMaximum,
                List.of("CELL_RANGE"),
                ParserProviderKind.CSV_STREAM
        );
        return new DocumentFormatsResponse(
                SCHEMA_VERSION,
                List.of(pdf, text, markdown, html, docx, pptx, xlsx, csv)
        );
    }

    public Optional<DocumentFormatsResponse.DocumentFormatCapability> findEnabledCapability(
            String filename,
            String declaredMediaType,
            String detectedMediaType
    ) {
        return findCapability(capabilities(), filename, declaredMediaType, detectedMediaType)
                .filter(DocumentFormatsResponse.DocumentFormatCapability::enabled);
    }

    public Optional<DocumentFormatsResponse.DocumentFormatCapability> findCapability(
            DocumentFormatsResponse registry,
            String filename,
            String declaredMediaType,
            String detectedMediaType
    ) {
        String normalizedFilename = filename == null
                ? ""
                : filename.toLowerCase(Locale.ROOT);
        String normalizedDeclared = normalizeMediaType(declaredMediaType);
        String normalizedDetected = normalizeMediaType(detectedMediaType);
        return registry.formats().stream()
                .filter(capability -> capability.extensions().stream()
                        .anyMatch(normalizedFilename::endsWith))
                .filter(capability -> normalizedDeclared == null
                        || capability.mediaTypes().contains(normalizedDeclared))
                .filter(capability -> normalizedDetected == null
                        || capability.mediaTypes().contains(normalizedDetected))
                .findFirst();
    }

    public Optional<DocumentFormatsResponse.DocumentFormatCapability> findCapability(
            String filename,
            String declaredMediaType,
            String detectedMediaType
    ) {
        return findCapability(
                capabilities(), filename, declaredMediaType, detectedMediaType
        );
    }

    public void requireOperational(DocumentFormat format) {
        DocumentFormatsResponse.DocumentFormatCapability capability = capabilities()
                .formats()
                .stream()
                .filter(candidate -> candidate.format() == format)
                .findFirst()
                .orElseThrow();
        requireOperational(capability);
    }

    public void requireOperational(
            DocumentFormatsResponse.DocumentFormatCapability capability
    ) {
        if ("DISABLED".equals(capability.runtimeStatus())) {
            policies.requireFormatEnabled(capability.format());
        }
        if (!capability.enabled()) {
            throw new com.example.rag.common.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "DOCUMENT_FORMAT_PROVIDER_UNAVAILABLE",
                    "该格式当前没有可用解析器"
            );
        }
    }

    public void requireProviderOperational(
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        policies.requireProviderEnabled(format, provider);
        DocumentFormatsResponse.ParserProviderCapability capability = providers(
                policies.snapshot(), format, provider
        ).stream().findFirst().orElseThrow(() -> new com.example.rag.common.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "PARSER_PROVIDER_UNSUPPORTED",
                "该解析器不支持此文档格式"
        ));
        if (!capability.available()) {
            throw new com.example.rag.common.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    capability.reasonCode() == null
                            ? "PARSER_PROVIDER_UNAVAILABLE" : capability.reasonCode(),
                    "该解析器当前不可用"
            );
        }
    }

    private DocumentFormatsResponse.ParserProviderCapability providerCapability(
            DocumentRuntimePolicyService.Snapshot policySnapshot,
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        DocumentRuntimePolicyService.Policy policy = policySnapshot.parser(format, provider);
        boolean policyEnabled = policy != null && policy.enabled();
        long policyVersion = policy == null ? 0 : policy.policyVersion();
        long runningJobs = policySnapshot.running(format, provider);
        if (!policyEnabled) {
            return new DocumentFormatsResponse.ParserProviderCapability(
                    provider.name(), false, "PARSER_PROVIDER_DISABLED",
                    "DISABLED", "DISABLED", policyVersion, runningJobs
            );
        }
        if (!workerHealth.isParserAvailable(provider)) {
            return new DocumentFormatsResponse.ParserProviderCapability(
                    provider.name(),
                    false,
                    "PARSER_WORKER_UNAVAILABLE",
                    "HEARTBEAT_STALE",
                    "ENABLED",
                    policyVersion,
                    runningJobs
            );
        }
        return switch (provider) {
            case PDFBOX -> new DocumentFormatsResponse.ParserProviderCapability(
                    provider.name(),
                    true,
                    null,
                    "AVAILABLE",
                    "ENABLED",
                    policyVersion,
                    runningJobs
            );
            case MINERU -> {
                boolean available = mineru.enabled() && mineru.gpuAvailable();
                String reasonCode = available
                        ? null
                        : mineru.enabled() ? "GPU_PROFILE_CONFLICT" : "MINERU_REQUIRED";
                yield new DocumentFormatsResponse.ParserProviderCapability(
                        provider.name(),
                        available,
                        reasonCode,
                        available ? "AVAILABLE" : "UNAVAILABLE",
                        "ENABLED",
                        policyVersion,
                        runningJobs
                );
            }
            case TEXT, MARKDOWN, HTML, DOCX_POI, PPTX_POI,
                    XLSX_POI, CSV_STREAM ->
                    new DocumentFormatsResponse.ParserProviderCapability(
                            provider.name(),
                            true,
                            null,
                            "AVAILABLE",
                            "ENABLED",
                            policyVersion,
                            runningJobs
                    );
        };
    }

    private DocumentFormatsResponse.DocumentFormatCapability capability(
            DocumentRuntimePolicyService.Snapshot policySnapshot,
            DocumentFormat format,
            String displayName,
            List<String> extensions,
            List<String> mediaTypes,
            long maximum,
            List<String> locatorKinds,
            ParserProviderKind provider
    ) {
        List<DocumentFormatsResponse.ParserProviderCapability> providers =
                providers(policySnapshot, format, provider);
        return formatCapability(
                policySnapshot, format,
                displayName,
                extensions,
                mediaTypes,
                maximum,
                locatorKinds,
                providers,
                false
        );
    }

    private DocumentFormatsResponse.DocumentFormatCapability formatCapability(
            DocumentRuntimePolicyService.Snapshot policySnapshot,
            DocumentFormat format,
            String displayName,
            List<String> extensions,
            List<String> mediaTypes,
            long maximum,
            List<String> locatorKinds,
            List<DocumentFormatsResponse.ParserProviderCapability> providers,
            boolean overrideAllowed
    ) {
        DocumentRuntimePolicyService.Policy policy = policySnapshot.format(format);
        boolean policyEnabled = policy != null && policy.enabled();
        boolean providerAvailable = hasAvailableProvider(providers);
        String runtimeStatus = !policyEnabled
                ? "DISABLED"
                : providerAvailable
                ? "AVAILABLE"
                : providers.stream().anyMatch(provider ->
                        "HEARTBEAT_STALE".equals(provider.runtimeStatus()))
                ? "HEARTBEAT_STALE" : "UNAVAILABLE";
        return new DocumentFormatsResponse.DocumentFormatCapability(
                format,
                policyEnabled && providerAvailable,
                runtimeStatus,
                policyEnabled ? "ENABLED" : "DISABLED",
                policy == null ? 0 : policy.policyVersion(),
                policySnapshot.running(format),
                displayName,
                extensions,
                mediaTypes,
                maximum,
                locatorKinds,
                providers,
                overrideAllowed
        );
    }

    private static boolean hasAvailableProvider(
            List<DocumentFormatsResponse.ParserProviderCapability> providers
    ) {
        return providers.stream().anyMatch(
                DocumentFormatsResponse.ParserProviderCapability::available
        );
    }

    private List<DocumentFormatsResponse.ParserProviderCapability> providers(
            DocumentRuntimePolicyService.Snapshot policySnapshot,
            DocumentFormat format,
            ParserProviderKind... requested
    ) {
        Set<ParserProviderKind> registered = Set.copyOf(
                parserRegistry.providersFor(format)
        );
        return List.of(requested).stream()
                .filter(registered::contains)
                .map(provider -> providerCapability(policySnapshot, format, provider))
                .toList();
    }

    private static String normalizeMediaType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int parameters = value.indexOf(';');
        String mediaType = parameters < 0 ? value : value.substring(0, parameters);
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }
}
