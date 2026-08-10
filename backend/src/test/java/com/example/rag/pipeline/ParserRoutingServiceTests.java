package com.example.rag.pipeline;

import com.example.rag.document.DocumentRuntimePolicyService;
import com.example.rag.persistence.PipelineStage;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.pipeline.PipelineJobLeaseService.ParserDecision;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.PdfDocumentParser;
import com.example.rag.pipeline.parser.PdfPreflightInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParserRoutingServiceTests {

    private static final byte[] PDF = "%PDF-route-test".getBytes();
    private static final ChunkingProfile PROFILE = ChunkingProfile.phase4Default();

    private final PdfDocumentParser pdfBox = mock(PdfDocumentParser.class);
    private final PdfPreflightInspector preflight = mock(PdfPreflightInspector.class);
    private final MineruProvider mineru = mock(MineruProvider.class);
    private final PipelineJobLeaseService leases = mock(PipelineJobLeaseService.class);
    private final DocumentRuntimePolicyService runtimePolicies = mock(DocumentRuntimePolicyService.class);

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsMineruRequiredBeforeCallingADisabledProvider() throws Exception {
        ParserRoutingService routing = service(properties(false, false, "none"));
        when(preflight.inspect(any(Path.class), eq(200))).thenReturn(complexInspection());
        when(leases.recordParserDecision(any(), anyInt(), any())).thenReturn(true);

        assertCode(routing, "MINERU_REQUIRED");
        verify(mineru, never()).parse(any(Path.class), anyString(), any(), anyInt());
        assertDecision("MINERU_REQUIRED");
    }

    @Test
    void returnsGpuConflictWhenLocalMineruIsNotTheActiveProfile() throws Exception {
        ParserRoutingService routing = service(properties(true, true, "hybrid"));
        when(preflight.inspect(any(Path.class), eq(200))).thenReturn(complexInspection());
        when(leases.recordParserDecision(any(), anyInt(), any())).thenReturn(true);

        assertCode(routing, "GPU_PROFILE_CONFLICT");
        verify(mineru, never()).parse(any(Path.class), anyString(), any(), anyInt());
        assertDecision("GPU_PROFILE_CONFLICT");
    }

    @Test
    void registryExposesOnlyTheTwoPdfProvidersInPhase15A() {
        ParserRegistry registry = new ParserRegistry(java.util.List.of(
                new PdfBoxParserProvider(pdfBox),
                new MineruParserProvider(mineru, pdfBox)
        ));

        assertThat(registry.providersFor(DocumentFormat.PDF))
                .containsExactly(ParserProviderKind.MINERU, ParserProviderKind.PDFBOX);
        assertThat(registry.providersFor(DocumentFormat.TXT)).isEmpty();
    }

    private void assertCode(ParserRoutingService routing, String code) {
        assertThatThrownBy(() -> routing.parse(input(), task(), PROFILE))
                .isInstanceOfSatisfying(
                        ParserProcessingException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code)
                );
    }

    private void assertDecision(String code) {
        ArgumentCaptor<ParserDecision> decision =
                ArgumentCaptor.forClass(ParserDecision.class);
        verify(leases).recordParserDecision(any(), anyInt(), decision.capture());
        assertThat(decision.getValue().code()).isEqualTo(code);
        assertThat(decision.getValue().selectedProvider())
                .isEqualTo(ParserProviderKind.MINERU);
    }

    private ParserRoutingService service(MineruProperties properties) {
        return new ParserRoutingService(
                new ParserRegistry(java.util.List.of(
                        new PdfBoxParserProvider(pdfBox),
                        new MineruParserProvider(mineru, pdfBox)
                )),
                preflight,
                properties,
                leases,
                runtimePolicies
        );
    }

    private ParserInput input() throws Exception {
        Path path = temporaryDirectory.resolve("route-test.pdf");
        Files.write(path, PDF);
        return new ParserInput(
                path,
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentFormat.PDF,
                "application/pdf",
                PDF.length,
                "a".repeat(64)
        );
    }

    private static MineruProperties properties(
            boolean enabled,
            boolean local,
            String profile
    ) {
        return new MineruProperties(
                enabled,
                URI.create("http://mineru:8000"),
                "3.4.4",
                "model-revision",
                "a".repeat(64),
                Duration.ofMinutes(14),
                200,
                local,
                profile
        );
    }

    private static PdfPreflightInspector.Result complexInspection() {
        return new PdfPreflightInspector.Result(
                2,
                true,
                true,
                true,
                true,
                true,
                true
        );
    }

    private static ClaimedJob task() {
        return new ClaimedJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test.pdf",
                PipelineStage.PARSE,
                1,
                3,
                ParserEngine.AUTO
        );
    }
}
