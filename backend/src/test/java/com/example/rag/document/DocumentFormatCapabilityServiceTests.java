package com.example.rag.document;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.MineruProperties;
import com.example.rag.pipeline.ParserProviderKind;
import com.example.rag.pipeline.ParserRegistry;
import com.example.rag.pipeline.PipelineWorkerHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentFormatCapabilityServiceTests {

    @Test
    void exposesEnabledSafeFormatsAndRuntimeParserAvailability() {
        StorageProperties storage = mock(StorageProperties.class);
        ParserRegistry registry = mock(ParserRegistry.class);
        MineruProperties mineru = mock(MineruProperties.class);
        PipelineWorkerHealthService workerHealth = mock(
                PipelineWorkerHealthService.class
        );
        DocumentRuntimePolicyService policies = enabledPolicies();
        when(storage.maxFileSize()).thenReturn(DataSize.ofMegabytes(50));
        when(registry.providersFor(DocumentFormat.PDF))
                .thenReturn(List.of(ParserProviderKind.MINERU, ParserProviderKind.PDFBOX));
        when(registry.providersFor(DocumentFormat.TXT))
                .thenReturn(List.of(ParserProviderKind.TEXT));
        when(registry.providersFor(DocumentFormat.MARKDOWN))
                .thenReturn(List.of(ParserProviderKind.MARKDOWN));
        when(registry.providersFor(DocumentFormat.HTML))
                .thenReturn(List.of(ParserProviderKind.HTML));
        when(registry.providersFor(DocumentFormat.DOCX))
                .thenReturn(List.of(ParserProviderKind.DOCX_POI));
        when(registry.providersFor(DocumentFormat.PPTX))
                .thenReturn(List.of(ParserProviderKind.PPTX_POI));
        when(registry.providersFor(DocumentFormat.XLSX))
                .thenReturn(List.of(ParserProviderKind.XLSX_POI));
        when(registry.providersFor(DocumentFormat.CSV))
                .thenReturn(List.of(ParserProviderKind.CSV_STREAM));
        when(mineru.enabled()).thenReturn(false);
        when(workerHealth.isParserAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        DocumentFormatsResponse response = new DocumentFormatCapabilityService(
                storage,
                registry,
                mineru,
                workerHealth,
                policies
        ).capabilities();

        assertThat(response.schemaVersion()).isEqualTo("document-formats-v6");
        assertThat(response.formats())
                .extracting(DocumentFormatsResponse.DocumentFormatCapability::format)
                .containsExactly(
                        DocumentFormat.PDF,
                        DocumentFormat.TXT,
                        DocumentFormat.MARKDOWN,
                        DocumentFormat.HTML,
                        DocumentFormat.DOCX,
                        DocumentFormat.PPTX,
                        DocumentFormat.XLSX,
                        DocumentFormat.CSV
                );
        var pdf = response.formats().getFirst();
        assertThat(pdf.enabled()).isTrue();
        assertThat(pdf.runtimeStatus()).isEqualTo("AVAILABLE");
        assertThat(pdf.policyStatus()).isEqualTo("ENABLED");
        assertThat(pdf.extensions()).containsExactly(".pdf");
        assertThat(pdf.mediaTypes()).containsExactly("application/pdf");
        assertThat(pdf.maxFileSizeBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(pdf.locatorKinds()).containsExactly("PAGE");
        assertThat(pdf.parserProviders())
                .extracting(
                        DocumentFormatsResponse.ParserProviderCapability::provider,
                        DocumentFormatsResponse.ParserProviderCapability::available,
                        DocumentFormatsResponse.ParserProviderCapability::reasonCode
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PDFBOX", true, null),
                        org.assertj.core.groups.Tuple.tuple(
                                "MINERU",
                                false,
                                "MINERU_REQUIRED"
                        )
                );
        assertThat(response.formats().subList(1, 4))
                .allSatisfy(format -> {
                    assertThat(format.enabled()).isTrue();
                    assertThat(format.maxFileSizeBytes())
                            .isEqualTo(10L * 1024 * 1024);
                    assertThat(format.parserOverrideAllowed()).isFalse();
                    assertThat(format.parserProviders())
                            .singleElement()
                            .satisfies(provider -> assertThat(provider.available())
                                    .isTrue());
                });
        assertThat(response.formats().subList(4, 6))
                .allSatisfy(format -> {
                    assertThat(format.enabled()).isTrue();
                    assertThat(format.maxFileSizeBytes())
                            .isEqualTo(50L * 1024 * 1024);
                    assertThat(format.parserOverrideAllowed()).isFalse();
                });
        assertThat(response.formats().subList(6, 8))
                .allSatisfy(format -> {
                    assertThat(format.enabled()).isTrue();
                    assertThat(format.locatorKinds())
                            .containsExactly("CELL_RANGE");
                    assertThat(format.parserOverrideAllowed()).isFalse();
                    assertThat(format.parserProviders())
                            .singleElement()
                            .satisfies(provider -> assertThat(provider.available())
                                    .isTrue());
                });
        assertThat(response.formats().get(6).maxFileSizeBytes())
                .isEqualTo(50L * 1024 * 1024);
        assertThat(response.formats().get(7).maxFileSizeBytes())
                .isEqualTo(10L * 1024 * 1024);
        DocumentFormatCapabilityService service = new DocumentFormatCapabilityService(
                storage,
                registry,
                mineru,
                workerHealth,
                policies
        );
        assertThat(service.findEnabledCapability(
                "GUIDE.PDF",
                "application/pdf; charset=binary",
                "application/pdf"
        )).get().extracting(DocumentFormatsResponse.DocumentFormatCapability::format)
                .isEqualTo(DocumentFormat.PDF);
        assertThat(service.findEnabledCapability(
                "guide.pdf",
                "text/plain",
                null
        )).isEmpty();
        assertThat(service.findEnabledCapability(
                "guide.txt",
                "application/pdf",
                "application/pdf"
        )).isEmpty();
        assertThat(service.findEnabledCapability(
                "guide.md",
                "text/plain; charset=utf-8",
                "text/plain"
        )).get().extracting(DocumentFormatsResponse.DocumentFormatCapability::format)
                .isEqualTo(DocumentFormat.MARKDOWN);
        assertThat(service.findEnabledCapability(
                "guide.html",
                "text/html",
                "text/plain"
        )).isEmpty();
        assertThat(service.findEnabledCapability(
                "guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )).get().extracting(DocumentFormatsResponse.DocumentFormatCapability::format)
                .isEqualTo(DocumentFormat.DOCX);
        assertThat(service.findEnabledCapability(
                "slides.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )).isEmpty();
        assertThat(service.findEnabledCapability(
                "sales.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )).get().extracting(DocumentFormatsResponse.DocumentFormatCapability::format)
                .isEqualTo(DocumentFormat.XLSX);
        assertThat(service.findEnabledCapability(
                "sales.csv",
                "text/csv; charset=utf-8",
                "text/csv"
        )).get().extracting(DocumentFormatsResponse.DocumentFormatCapability::format)
                .isEqualTo(DocumentFormat.CSV);
    }

    @Test
    void reportsTheGpuProfileConflictAndDisablesPdfWithoutAnotherProvider() {
        StorageProperties storage = mock(StorageProperties.class);
        ParserRegistry registry = mock(ParserRegistry.class);
        MineruProperties mineru = mock(MineruProperties.class);
        PipelineWorkerHealthService workerHealth = mock(
                PipelineWorkerHealthService.class
        );
        DocumentRuntimePolicyService policies = enabledPolicies();
        when(storage.maxFileSize()).thenReturn(DataSize.ofBytes(1));
        when(registry.providersFor(DocumentFormat.PDF))
                .thenReturn(List.of(ParserProviderKind.MINERU));
        when(mineru.enabled()).thenReturn(true);
        when(mineru.gpuAvailable()).thenReturn(false);
        when(workerHealth.isParserAvailable(ParserProviderKind.MINERU))
                .thenReturn(true);

        var pdf = new DocumentFormatCapabilityService(
                storage,
                registry,
                mineru,
                workerHealth,
                policies
        )
                .capabilities()
                .formats()
                .getFirst();

        assertThat(pdf.enabled()).isFalse();
        assertThat(pdf.parserProviders()).singleElement().satisfies(provider -> {
            assertThat(provider.available()).isFalse();
            assertThat(provider.reasonCode()).isEqualTo("GPU_PROFILE_CONFLICT");
        });
    }

    @Test
    void disablesAFormatWhenNoParserWorkerHeartbeatIsFresh() {
        StorageProperties storage = mock(StorageProperties.class);
        ParserRegistry registry = mock(ParserRegistry.class);
        MineruProperties mineru = mock(MineruProperties.class);
        PipelineWorkerHealthService workerHealth = mock(
                PipelineWorkerHealthService.class
        );
        DocumentRuntimePolicyService policies = enabledPolicies();
        when(storage.maxFileSize()).thenReturn(DataSize.ofMegabytes(10));
        when(registry.providersFor(DocumentFormat.TXT))
                .thenReturn(List.of(ParserProviderKind.TEXT));

        var text = new DocumentFormatCapabilityService(
                storage,
                registry,
                mineru,
                workerHealth,
                policies
        ).capabilities().formats().stream()
                .filter(format -> format.format() == DocumentFormat.TXT)
                .findFirst()
                .orElseThrow();

        assertThat(text.enabled()).isFalse();
        assertThat(text.parserProviders()).singleElement().satisfies(provider -> {
            assertThat(provider.available()).isFalse();
            assertThat(provider.reasonCode())
                    .isEqualTo("PARSER_WORKER_UNAVAILABLE");
        });
    }

    private static DocumentRuntimePolicyService enabledPolicies() {
        DocumentRuntimePolicyService service = mock(DocumentRuntimePolicyService.class);
        DocumentRuntimePolicyService.Snapshot snapshot = mock(
                DocumentRuntimePolicyService.Snapshot.class
        );
        when(service.snapshot()).thenReturn(snapshot);
        for (DocumentFormat format : DocumentFormat.values()) {
            when(snapshot.format(format)).thenReturn(new DocumentRuntimePolicyService.Policy(
                    "FORMAT:" + format.name(),
                    DocumentRuntimePolicyService.PolicyScope.FORMAT,
                    format,
                    null,
                    DocumentRuntimePolicyService.PolicyStatus.ENABLED,
                    1,
                    null,
                    java.time.Instant.EPOCH
            ));
        }
        java.util.Map<DocumentFormat, List<ParserProviderKind>> providers = java.util.Map.of(
                DocumentFormat.PDF, List.of(ParserProviderKind.PDFBOX, ParserProviderKind.MINERU),
                DocumentFormat.TXT, List.of(ParserProviderKind.TEXT),
                DocumentFormat.MARKDOWN, List.of(ParserProviderKind.MARKDOWN),
                DocumentFormat.HTML, List.of(ParserProviderKind.HTML),
                DocumentFormat.DOCX, List.of(ParserProviderKind.DOCX_POI),
                DocumentFormat.PPTX, List.of(ParserProviderKind.PPTX_POI),
                DocumentFormat.XLSX, List.of(ParserProviderKind.XLSX_POI),
                DocumentFormat.CSV, List.of(ParserProviderKind.CSV_STREAM)
        );
        providers.forEach((format, kinds) -> kinds.forEach(provider ->
                when(snapshot.parser(format, provider)).thenReturn(
                        new DocumentRuntimePolicyService.Policy(
                                "PARSER:" + format.name() + ":" + provider.name(),
                                DocumentRuntimePolicyService.PolicyScope.PARSER,
                                format,
                                provider,
                                DocumentRuntimePolicyService.PolicyStatus.ENABLED,
                                1,
                                null,
                                java.time.Instant.EPOCH
                        )
                )));
        return service;
    }
}
