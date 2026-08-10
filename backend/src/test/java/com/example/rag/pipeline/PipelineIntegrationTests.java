package com.example.rag.pipeline;

import com.example.rag.document.ObjectStorageService;
import com.example.rag.document.StorageProperties;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.PipelineJobRepository;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PipelineIntegrationTests {

    private static final String HASH = "a".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentRevisionRepository revisions;
    @Autowired private PipelineJobRepository jobs;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectStorageService storage;
    @Autowired private StorageProperties storageProperties;
    @Autowired private PipelineJobLeaseService leases;
    @Autowired private PipelineArtifactService artifacts;
    @Autowired private PipelineProperties properties;

    @MockitoSpyBean
    private PipelineService pipeline;

    @MockitoBean
    private PipelineWorkerHealthService workerHealth;

    @BeforeEach
    void resetBeforeTest() {
        resetDedicatedTestState();
        when(workerHealth.isParserAvailable(any())).thenReturn(true);
    }

    @AfterEach
    void resetAfterTest() {
        reset(pipeline);
        resetDedicatedTestState();
    }

    @Test
    void uploadFinalizationCreatesExactlyOnePendingParseJobInTheSameTransaction() throws Exception {
        UserEntity admin = createUser("upload-admin", UserRole.ADMIN);
        UserEntity reader = createUser("upload-reader", UserRole.USER);
        byte[] pdf = validPdf();
        String key = "pipeline-upload-" + UUID.randomUUID();

        UploadIds uploaded = upload(admin, reader, key, pdf);
        assertThat(revisions.findById(uploaded.revisionId()).orElseThrow().getStatus())
                .isEqualTo(RevisionStatus.UPLOADED);
        assertThat(parseJobCount(uploaded.revisionId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pipeline_jobs WHERE revision_id = ?",
                String.class,
                uploaded.revisionId()
        )).isEqualTo("PENDING");

        upload(admin, reader, key, pdf);
        assertThat(parseJobCount(uploaded.revisionId())).isOne();

        String failedKey = "pipeline-rollback-" + UUID.randomUUID();
        doThrow(new IllegalStateException("enqueue failed"))
                .when(pipeline).enqueue(any(UUID.class));
        assertThatThrownBy(() -> performUpload(admin, reader, failedKey, pdf).andReturn())
                .hasRootCauseMessage("enqueue failed");

        DocumentRevisionEntity rolledBack = revisions.findByIdempotencyKey(failedKey).orElseThrow();
        assertThat(rolledBack.getStatus()).isEqualTo(RevisionStatus.STAGED);
        assertThat(parseJobCount(rolledBack.getId())).isZero();
    }

    @Test
    void leaseClaimIsExclusiveAndAnExpiredLeaseCanBeReclaimed() throws Exception {
        JobFixture fixture = createPendingJob("lease");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> claimAfterSignal(ready, start));
            var second = executor.submit(() -> claimAfterSignal(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Optional<ClaimedJob>> results = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            ClaimedJob claimed = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(claimed.id()).isEqualTo(fixture.jobId());
            assertThat(claimed.attempt()).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(leases.claimNext()).isEmpty();
        jdbc.update(
                "UPDATE pipeline_jobs SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                fixture.jobId()
        );
        ClaimedJob reclaimed = leases.claimNext().orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(fixture.jobId());
        assertThat(reclaimed.attempt()).isEqualTo(2);
        assertThat(leases.heartbeat(fixture.jobId(), 1)).isFalse();
        assertThat(leases.lockOwned(fixture.jobId(), 1)).isFalse();
        assertThat(leases.heartbeat(fixture.jobId(), reclaimed.attempt())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT attempt FROM pipeline_jobs WHERE id = ?",
                Integer.class,
                fixture.jobId()
        )).isEqualTo(2);
        jdbc.update(
                """
                UPDATE pipeline_jobs
                SET attempt = max_attempts,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                fixture.jobId()
        );
        assertThat(leases.claimNext()).isEmpty();
        assertThat(jobStatus(fixture.jobId())).isEqualTo("FAILED");
        assertThat(revisionStatus(fixture.revision().getId())).isEqualTo("FAILED");
    }

    @Test
    void successfulFinalizationIsDeterministicIdempotentAndTraceable() throws Exception {
        UserEntity admin = createUser("artifact-admin", UserRole.ADMIN);
        UserEntity reader = createUser("artifact-reader", UserRole.USER);
        UploadIds uploaded = upload(
                admin, reader, "artifact-upload-" + UUID.randomUUID(), validPdf()
        );
        ClaimedJob task = leases.claimNext().orElseThrow();

        ParsedDocument firstParse = artifacts.parse(task);
        ParsedDocument secondParse = artifacts.parse(task);
        assertThat(secondParse.contentBlocks().stream().map(ParsedDocument.ContentBlock::id))
                .containsExactlyElementsOf(firstParse.contentBlocks().stream()
                        .map(ParsedDocument.ContentBlock::id).toList());
        assertThat(secondParse.chunks().stream().map(ParsedDocument.Chunk::id))
                .containsExactlyElementsOf(firstParse.chunks().stream()
                        .map(ParsedDocument.Chunk::id).toList());

        assertThat(artifacts.replaceArtifacts(task, firstParse)).isTrue();
        ArtifactCounts firstCounts = artifactCounts(uploaded.revisionId());
        assertThat(firstCounts.blocks()).isPositive();
        assertThat(firstCounts.parents()).isPositive();
        assertThat(firstCounts.children()).isPositive();
        assertThat(firstCounts.spans()).isPositive();
        assertTraceableChildren(uploaded.revisionId());

        List<UUID> firstIds = chunkIds(uploaded.revisionId());
        reopenForDuplicateFinalization(task.id(), uploaded.revisionId());
        ClaimedJob repeatedTask = leases.claimNext().orElseThrow();
        assertThat(artifacts.replaceArtifacts(repeatedTask, secondParse)).isTrue();
        assertThat(artifactCounts(uploaded.revisionId())).isEqualTo(firstCounts);
        assertThat(chunkIds(uploaded.revisionId())).containsExactlyElementsOf(firstIds);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parsed_documents WHERE revision_id = ?",
                Long.class,
                uploaded.revisionId()
        )).isOne();
        assertThat(jobs.findById(task.id()).orElseThrow().getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(revisions.findById(uploaded.revisionId()).orElseThrow().getStatus())
                .isEqualTo(RevisionStatus.READY);
    }

    @Test
    void quarantineFailureRetryAndAdminEndpointsRespectStateAndCsrf() throws Exception {
        JobFixture fixture = createPendingJob("state");
        UserEntity admin = createUser("state-admin", UserRole.ADMIN);
        UserEntity ordinaryUser = createUser("state-user", UserRole.USER);
        ClaimedJob claimed = leases.claimNext().orElseThrow();

        assertThat(leases.quarantine(
                claimed.id(), claimed.attempt(), "SCANNED_PDF", "扫描件需要 OCR"
        )).isTrue();
        assertThat(jobStatus(fixture.jobId())).isEqualTo("QUARANTINED");
        assertThat(revisionStatus(fixture.revision().getId())).isEqualTo("QUARANTINED");

        mockMvc.perform(get("/api/v1/admin/pipeline-jobs")
                        .param("status", "QUARANTINED")
                        .with(user(principal(ordinaryUser))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/pipeline-jobs")
                        .param("status", "QUARANTINED")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(fixture.jobId().toString()))
                .andExpect(jsonPath("$.items[0].quarantineReason").value("扫描件需要 OCR"));

        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/retry", fixture.jobId())
                        .with(user(principal(admin))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/retry", fixture.jobId())
                        .with(user(principal(ordinaryUser)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/retry", fixture.jobId())
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempt").value(0))
                .andExpect(jsonPath("$.retryable").value(false));

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            ClaimedJob retry = leases.claimNext().orElseThrow();
            assertThat(retry.attempt()).isEqualTo(attempt);
            assertThat(leases.failOrRetry(
                    retry.id(), retry.attempt(), "PARSER_ERROR", "parse failed"
            )).isTrue();
        }
        assertThat(jobStatus(fixture.jobId())).isEqualTo("FAILED");
        assertThat(revisionStatus(fixture.revision().getId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM pipeline_jobs WHERE id = ?",
                String.class,
                fixture.jobId()
        )).isEqualTo("PARSER_ERROR");

        UUID legacyJobId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, stage, status, attempt, max_attempts, pipeline_version,
                    document_format, completed_at, duration_ms, error_code
                ) VALUES (?, ?, 'PARSE', 'FAILED', 1, 3, 'legacy-v1', ?, CURRENT_TIMESTAMP, 0, 'LEGACY')
                """,
                legacyJobId,
                fixture.revision().getId(),
                fixture.revision().getDocumentFormat().name()
        );
        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/retry", legacyJobId)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIPELINE_JOB_NOT_RETRYABLE"));
    }

    @Test
    void revisionWorkbenchAggregatesServerSideAndManualRecoveryIsIdempotent() throws Exception {
        JobFixture fixture = createPendingJob("revision-workbench");
        UserEntity admin = createUser("revision-workbench-admin", UserRole.ADMIN);
        UserEntity ordinaryUser = createUser("revision-workbench-user", UserRole.USER);
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            ClaimedJob claimed = leases.claimNext().orElseThrow();
            assertThat(leases.failOrRetry(
                    claimed.id(), claimed.attempt(), "PARSER_ERROR", "parse failed"
            )).isTrue();
        }

        mockMvc.perform(get("/api/v1/admin/pipeline-revisions")
                        .param("attention", "true")
                        .param("stage", "PARSE")
                        .with(user(principal(ordinaryUser))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/pipeline-revisions")
                        .param("attention", "true")
                        .param("stage", "PARSE")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].revisionId").value(fixture.revision().getId().toString()))
                .andExpect(jsonPath("$.items[0].aggregateStatus").value("FAILED"))
                .andExpect(jsonPath("$.items[0].automaticRetryExhausted").value(true))
                .andExpect(jsonPath("$.items[0].jobs[0].errorCode").value("PARSER_ERROR"))
                .andExpect(jsonPath("$.items[0].downstream.index.kind").value("INDEX"))
                .andExpect(jsonPath("$.items[0].downstream.graph.kind").value("GRAPH"))
                .andExpect(jsonPath("$.items[0].downstream.global.kind").value("GLOBAL"));

        String request = """
                {
                  "reason": "人工核对失败原因后重新进入队列",
                  "confirmation": "RECOVER_PIPELINE_JOB",
                  "idempotencyKey": "revision-workbench-recover-001"
                }
                """;
        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/recover", fixture.jobId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/recover", fixture.jobId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("PENDING"))
                .andExpect(jsonPath("$.revision.aggregateStatus").value("PENDING"))
                .andExpect(jsonPath("$.impact.confirmation").value("RECOVER_PIPELINE_JOB"))
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/recover", fixture.jobId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("PENDING"))
                .andExpect(jsonPath("$.impact.blockers.length()").value(0))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM governance_events WHERE action = 'PIPELINE_MANUAL_RECOVERY' AND object_id = ?",
                Long.class,
                fixture.jobId().toString()
        )).isOne();
    }

    @Test
    void revisionWorkbenchDoesNotTreatSupersededFailuresAsActionable() throws Exception {
        JobFixture fixture = createPendingJob("revision-history");
        UserEntity admin = createUser("revision-history-admin", UserRole.ADMIN);
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            ClaimedJob claimed = leases.claimNext().orElseThrow();
            assertThat(leases.failOrRetry(
                    claimed.id(), claimed.attempt(), "PARSER_ERROR", "parse failed"
            )).isTrue();
        }

        DocumentEntity document = fixture.revision().getDocument();
        DocumentRevisionEntity current = revisions.saveAndFlush(new DocumentRevisionEntity(
                document,
                2,
                "b".repeat(64),
                "tests/" + UUID.randomUUID() + ".pdf",
                RevisionStatus.READY,
                "test-v2.pdf",
                128,
                "application/pdf",
                null
        ));
        assertThat(document.publishRevision(current)).isTrue();
        documents.saveAndFlush(document);
        jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, stage, status, attempt, max_attempts,
                    pipeline_version, document_format, completed_at, duration_ms
                ) VALUES (?, ?, 'PARSE', 'SUCCEEDED', 1, ?, ?, ?, CURRENT_TIMESTAMP, 1)
                """,
                UUID.randomUUID(),
                current.getId(),
                properties.maxAttempts(),
                properties.pipelineVersion(),
                current.getDocumentFormat().name()
        );

        mockMvc.perform(get("/api/v1/admin/pipeline-revisions")
                        .param("status", "FAILED")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.counts.attention").value(0))
                .andExpect(jsonPath("$.counts.failed").value(0))
                .andExpect(jsonPath("$.counts.completed").value(1));

        MvcResult all = mockMvc.perform(get("/api/v1/admin/pipeline-revisions")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        JsonNode historical = null;
        for (JsonNode item : objectMapper.readTree(
                all.getResponse().getContentAsByteArray()
        ).path("items")) {
            if (fixture.revision().getId().toString()
                    .equals(item.path("revisionId").asText())) {
                historical = item;
                break;
            }
        }
        assertThat(historical).isNotNull();
        assertThat(historical.path("nextActionCode").asText()).isEqualTo("HISTORICAL");
    }

    @Test
    void deterministicQuarantineCannotUseGenericRecovery() throws Exception {
        JobFixture fixture = createPendingJob("quarantine-workbench");
        UserEntity admin = createUser("quarantine-workbench-admin", UserRole.ADMIN);
        ClaimedJob claimed = leases.claimNext().orElseThrow();
        assertThat(leases.quarantine(
                claimed.id(), claimed.attempt(), "SCANNED_PDF", "扫描件需要 OCR"
        )).isTrue();

        mockMvc.perform(post("/api/v1/admin/pipeline-jobs/{jobId}/recover", fixture.jobId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "尝试绕过隔离的人工恢复请求",
                                  "confirmation": "RECOVER_PIPELINE_JOB",
                                  "idempotencyKey": "quarantine-recover-001"
                                }
                                """)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIPELINE_RECOVERY_BLOCKED"));
        assertThat(jobStatus(fixture.jobId())).isEqualTo("QUARANTINED");
    }

    @Test
    void parserOverrideCreatesOneAuditedJobAndKeepsTheSourceJobImmutable() throws Exception {
        JobFixture fixture = createPendingJob("override");
        UserEntity admin = createUser("override-admin", UserRole.ADMIN);
        ClaimedJob claimed = leases.claimNext().orElseThrow();
        assertThat(leases.quarantine(
                claimed.id(), claimed.attempt(), "SCANNED_PDF", "扫描件需要 MinerU"
        )).isTrue();

        String body = """
                {
                  "targetParser": "MINERU",
                  "reason": "扫描件需要 OCR 与复杂版式解析",
                  "confirmation": "OVERRIDE_PARSER",
                  "idempotencyKey": "override-mineru-001"
                }
                """;
        MvcResult created = mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/parser-override",
                        fixture.jobId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.parserRequestedEngine").value("MINERU"))
                .andExpect(jsonPath("$.parserDecisionCode").value("ADMIN_OVERRIDE_REQUESTED"))
                .andExpect(jsonPath("$.parserOverrideReason").value("扫描件需要 OCR 与复杂版式解析"))
                .andReturn();
        String createdId = objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText();

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/parser-override",
                        fixture.jobId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pipeline_jobs WHERE revision_id = ? AND stage = 'PARSE'",
                Long.class,
                fixture.revision().getId()
        )).isEqualTo(2);
        assertThat(jobStatus(fixture.jobId())).isEqualTo("QUARANTINED");
        assertThat(revisionStatus(fixture.revision().getId())).isEqualTo("PROCESSING");

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/retry",
                        fixture.jobId()
                )
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("PIPELINE_PARSE_ALREADY_ACTIVE"));

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/parser-override",
                        fixture.jobId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(
                                "扫描件需要 OCR 与复杂版式解析",
                                "同一幂等键不能静默替换审计理由"
                        ))
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void cancelledRunningParseRetriesWithANewAttemptAndRejectsTheOldLease()
            throws Exception {
        JobFixture fixture = createPendingJob("cancel-retry");
        UserEntity admin = createUser("cancel-retry-admin", UserRole.ADMIN);
        ClaimedJob oldClaim = leases.claimNext().orElseThrow();
        assertThat(oldClaim.id()).isEqualTo(fixture.jobId());
        assertThat(oldClaim.attempt()).isEqualTo(1);

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/cancel",
                        fixture.jobId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "取消运行中任务并验证安全恢复",
                                  "confirmation": "CANCEL"
                                }
                                """)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.retryable").value(true));

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/retry",
                        fixture.jobId()
                )
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.maxAttempts").value(4));

        ClaimedJob newClaim = leases.claimNext().orElseThrow();
        assertThat(newClaim.id()).isEqualTo(fixture.jobId());
        assertThat(newClaim.attempt()).isEqualTo(2);
        assertThat(leases.markSucceeded(oldClaim.id(), oldClaim.attempt()))
                .isFalse();
    }

    @Test
    void reparseCreatesANewRevisionAndCancellationKeepsThePublishedSource()
            throws Exception {
        UserEntity admin = createUser("reparse-admin", UserRole.ADMIN);
        UserEntity reader = createUser("reparse-reader", UserRole.USER);
        UploadIds source = upload(
                admin,
                reader,
                "reparse-source-" + UUID.randomUUID(),
                validPdf()
        );
        ClaimedJob sourceParse = leases.claimNext().orElseThrow();
        assertThat(artifacts.replaceArtifacts(
                sourceParse, artifacts.parse(sourceParse)
        )).isTrue();
        DocumentEntity document = documents.findById(
                source.documentId()
        ).orElseThrow();
        document.publishRevision(
                revisions.findById(source.revisionId()).orElseThrow()
        );
        documents.saveAndFlush(document);
        mockMvc.perform(patch(
                        "/api/v1/admin/documents/{documentId}/acl",
                        source.documentId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Pipeline guide",
                                  "visibility": "RESTRICTED",
                                  "grantedUserIds": ["%s"],
                                  "expectedAclVersion": 1
                                }
                                """.formatted(reader.getId()))
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.aclVersion").value(2));
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM graph_rebuild_requests
                WHERE document_id = ?
                  AND target_revision_id = ?
                  AND target_acl_version = 2
                  AND reason = 'ACL_CHANGED'
                  AND state = 'REQUESTED'
                """,
                Long.class,
                source.documentId(),
                source.revisionId()
        )).isOne();

        String key = "reparse-request-" + UUID.randomUUID();
        String request = """
                {
                  "sourceRevisionId": "%s",
                  "targetParser": "PDFBOX",
                  "idempotencyKey": "%s",
                  "reason": "使用当前 PDFBox 版本重新生成稳定 SourceSpan",
                  "confirmation": "REPARSE"
                }
                """.formatted(source.revisionId(), key);
        MvcResult created = mockMvc.perform(post(
                        "/api/v1/admin/documents/{documentId}/reparse",
                        source.documentId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceRevisionId")
                        .value(source.revisionId().toString()))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.targetParser").value("PDFBOX"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();
        JsonNode response = objectMapper.readTree(
                created.getResponse().getContentAsByteArray()
        );
        UUID revisionId = UUID.fromString(response.path("revisionId").asText());
        UUID jobId = UUID.fromString(response.path("pipelineJobId").asText());

        assertThat(jdbc.queryForObject(
                "SELECT current_revision_id FROM documents WHERE id = ?",
                UUID.class,
                source.documentId()
        )).isEqualTo(source.revisionId());
        assertThat(jdbc.queryForObject(
                """
                SELECT source_revision_id
                FROM document_revisions
                WHERE id = ?
                """,
                UUID.class,
                revisionId
        )).isEqualTo(source.revisionId());
        assertThat(jdbc.queryForObject(
                "SELECT parser_requested_engine FROM pipeline_jobs WHERE id = ?",
                String.class,
                jobId
        )).isEqualTo("PDFBOX");

        mockMvc.perform(post(
                        "/api/v1/admin/documents/{documentId}/reparse",
                        source.documentId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.revisionId")
                        .value(revisionId.toString()))
                .andExpect(jsonPath("$.pipelineJobId")
                        .value(jobId.toString()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_revisions WHERE document_id = ?",
                Long.class,
                source.documentId()
        )).isEqualTo(2);

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/cancel",
                        jobId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "管理员取消本次重解析验证任务",
                                  "confirmation": "CANCEL"
                                }
                                """)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelable").value(false));
        assertThat(revisionStatus(revisionId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT current_revision_id FROM documents WHERE id = ?",
                UUID.class,
                source.documentId()
        )).isEqualTo(source.revisionId());
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pipeline_job_action_events
                WHERE job_id = ? AND action = 'CANCEL'
                """,
                Long.class,
                jobId
        )).isOne();
    }

    @Test
    void quarantineReleaseRequiresExplicitConfirmationAndIsAudited()
            throws Exception {
        JobFixture fixture = createPendingJob("release");
        UserEntity admin = createUser("release-admin", UserRole.ADMIN);
        ClaimedJob claimed = leases.claimNext().orElseThrow();
        assertThat(leases.quarantine(
                claimed.id(),
                claimed.attempt(),
                "SCANNED_PDF",
                "扫描件需要人工确认解析器"
        )).isTrue();

        mockMvc.perform(post(
                        "/api/v1/admin/pipeline-jobs/{jobId}/quarantine-release",
                        fixture.jobId()
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "管理员确认允许重新进入解析队列",
                                  "confirmation": "RELEASE_QUARANTINE"
                                }
                                """)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(revisionStatus(fixture.revision().getId()))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pipeline_job_action_events
                WHERE job_id = ? AND action = 'RELEASE_QUARANTINE'
                """,
                Long.class,
                fixture.jobId()
        )).isOne();
    }

    @Test
    void documentTimelineAndArtifactsEnforceAclAndRevisionBinding() throws Exception {
        UserEntity admin = createUser("acl-admin", UserRole.ADMIN);
        UserEntity reader = createUser("acl-reader", UserRole.USER);
        UserEntity outsider = createUser("acl-outsider", UserRole.USER);
        byte[] pdf = validPdf();
        UploadIds first = upload(admin, reader, "acl-first-" + UUID.randomUUID(), pdf);
        ClaimedJob task = leases.claimNext().orElseThrow();
        assertThat(artifacts.replaceArtifacts(task, artifacts.parse(task))).isTrue();
        DocumentEntity publishedDocument = documents.findById(first.documentId()).orElseThrow();
        publishedDocument.publishRevision(revisions.findById(first.revisionId()).orElseThrow());
        documents.saveAndFlush(publishedDocument);

        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/pipeline",
                        first.documentId(), first.revisionId()
                ).with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/artifacts",
                        first.documentId(), first.revisionId()
                ).with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(first.revisionId().toString()))
                .andExpect(jsonPath("$.contentBlocks").isNotEmpty())
                .andExpect(jsonPath("$.chunks").isNotEmpty());
        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/structure",
                        first.documentId(), first.revisionId()
                ).with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(first.revisionId().toString()))
                .andExpect(jsonPath("$.resultPackage.schemaVersion").value("parsed-package-v3"))
                .andExpect(jsonPath("$.tables").isEmpty())
                .andExpect(jsonPath("$.images").isEmpty())
                .andExpect(jsonPath("$.sourceSpans").isNotEmpty())
                .andExpect(jsonPath("$.sourceSpans[0].chunkStartOffset").isNumber())
                .andExpect(jsonPath("$.sourceSpans[0].chunkEndOffset").isNumber());

        for (String suffix : List.of("pipeline", "artifacts", "structure")) {
            mockMvc.perform(get(
                            "/api/v1/documents/{documentId}/revisions/{revisionId}/" + suffix,
                            first.documentId(), first.revisionId()
                    ).with(user(principal(outsider))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        }

        UploadIds second = upload(admin, reader, "acl-second-" + UUID.randomUUID(), pdf);
        for (String suffix : List.of("pipeline", "artifacts", "structure")) {
            mockMvc.perform(get(
                            "/api/v1/documents/{documentId}/revisions/{revisionId}/" + suffix,
                            first.documentId(), second.revisionId()
                    ).with(user(principal(reader))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        }

        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/artifacts",
                        first.documentId(), first.revisionId()
                ).with(user(principal(admin))))
                .andExpect(status().isOk());
    }

    @Test
    void deletingADocumentTerminalizesItsPendingParseJob() throws Exception {
        JobFixture fixture = createPendingJob("delete");
        UserEntity admin = createUser("delete-admin", UserRole.ADMIN);

        mockMvc.perform(delete(
                        "/api/v1/admin/documents/{documentId}",
                        fixture.revision().getDocument().getId()
                ).with(user(principal(admin))).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jobStatus(fixture.jobId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM pipeline_jobs WHERE id = ?",
                String.class,
                fixture.jobId()
        )).isEqualTo("DOCUMENT_DELETED");
        assertThat(leases.claimNext()).isEmpty();
    }

    @Test
    void safeTextFormatsUseTheSameRevisionPipelineAndLocatorLifecycle()
            throws Exception {
        UserEntity admin = createUser("text-format-admin", UserRole.ADMIN);
        UserEntity reader = createUser("text-format-reader", UserRole.USER);
        long revisionsBeforeSpoof = revisions.count();
        performUploadFile(
                admin,
                reader,
                "text-format-spoof-" + UUID.randomUUID(),
                "spoof.html",
                "text/html",
                "This is plain text, not HTML.".getBytes(StandardCharsets.UTF_8)
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_CONTENT_TYPE_INVALID"));
        assertThat(revisions.count()).isEqualTo(revisionsBeforeSpoof);

        List<TextFixture> fixtures = List.of(
                new TextFixture(
                        "guide.txt",
                        "text/plain",
                        "第一章 检索平台\n\n中文优先并兼容 English retrieval。"
                                + " 小块负责召回，父块负责补充回答上下文。".repeat(4),
                        "TXT",
                        "TEXT",
                        "LINE_RANGE"
                ),
                new TextFixture(
                        "guide.md",
                        "text/markdown",
                        """
                        # 混合检索

                        - BM25 关键词召回
                        - Semantic Search 向量召回

                        | 阶段 | 候选 |
                        | --- | --- |
                        | Rerank | 30 |

                        每个回答引用都必须绑定当前 Revision 的来源位置。
                        """,
                        "MARKDOWN",
                        "MARKDOWN",
                        "HEADING_BLOCK"
                ),
                new TextFixture(
                        "guide.html",
                        "text/html",
                        """
                        <!doctype html><html><head>
                        <script>window.secret='do-not-index';</script>
                        </head><body>
                        <h1>安全网页知识</h1>
                        <p>HTML 解析不访问远程资源，只保留可引用的安全正文。</p>
                        <iframe src="https://attacker.invalid/frame"></iframe>
                        <p>引用使用 DOM Block 定位，并继续执行 ACL 与 Revision 复核。</p>
                        </body></html>
                        """,
                        "HTML",
                        "HTML",
                        "DOM_PATH"
                )
        );

        for (TextFixture fixture : fixtures) {
            MvcResult created = performUploadFile(
                    admin,
                    reader,
                    "text-format-" + fixture.expectedFormat().toLowerCase()
                            + "-" + UUID.randomUUID(),
                    fixture.filename(),
                    fixture.mediaType(),
                    fixture.content().getBytes(StandardCharsets.UTF_8)
            )
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.revisions[0].documentFormat")
                            .value(fixture.expectedFormat()))
                    .andReturn();
            JsonNode response = objectMapper.readTree(
                    created.getResponse().getContentAsByteArray()
            );
            UUID documentId = UUID.fromString(response.at("/document/id").asText());
            UUID revisionId = UUID.fromString(response.at("/revisions/0/id").asText());

            ClaimedJob task = leases.claimNext().orElseThrow();
            ParsedDocument parsed = artifacts.parse(task);
            assertThat(parsed.documentFormat().name())
                    .isEqualTo(fixture.expectedFormat());
            assertThat(parsed.parserProvider().name())
                    .isEqualTo(fixture.expectedProvider());
            assertThat(parsed.pageCount()).isZero();
            assertThat(parsed.sourceUnits()).isNotEmpty();
            assertThat(parsed.markdown()).doesNotContain(
                    "window.secret",
                    "attacker.invalid"
            );
            assertThat(artifacts.replaceArtifacts(task, parsed)).isTrue();

                    mockMvc.perform(get(
                            "/api/v1/documents/{documentId}/revisions/{revisionId}/structure",
                            documentId,
                            revisionId
                    ).with(user(principal(admin))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resultPackage.schemaVersion")
                            .value("parsed-package-v3"))
                    .andExpect(jsonPath("$.resultPackage.documentFormat")
                            .value(fixture.expectedFormat()))
                    .andExpect(jsonPath("$.resultPackage.parserProvider")
                            .value(fixture.expectedProvider()))
                    .andExpect(jsonPath("$.sourceSpans[0].sourceLocator.kind")
                            .value(fixture.expectedLocator()));

            MvcResult started = mockMvc.perform(get(
                            "/api/v1/documents/{documentId}/revisions/{revisionId}/download",
                            documentId,
                            revisionId
                    )
                            .param("inline", "true")
                            .with(user(principal(admin))))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MvcResult downloaded = mockMvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andReturn();
            assertThat(downloaded.getResponse().getHeader("Content-Disposition"))
                    .startsWith("attachment;");
        }
    }

    @Test
    void spreadsheetsUseStructuredTablesCellRangesAndDeterministicArtifacts()
            throws Exception {
        UserEntity admin = createUser("spreadsheet-admin", UserRole.ADMIN);
        UserEntity reader = createUser("spreadsheet-reader", UserRole.USER);
        List<SpreadsheetFixture> fixtures = List.of(
                new SpreadsheetFixture(
                        "sales.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        validXlsx(),
                        "XLSX",
                        "XLSX_POI",
                        "XLSX_EVENT_STREAM",
                        null
                ),
                new SpreadsheetFixture(
                        "sales.csv",
                        "text/csv",
                        """
                        编号;区域;收入;成本;说明
                        S-001;华东;120;70;=1+1
                        S-002;华南;95;55;已确认
                        """.getBytes(StandardCharsets.UTF_8),
                        "CSV",
                        "CSV_STREAM",
                        "CSV_STREAM_AUTO_DETECT",
                        "SEMICOLON"
                )
        );

        for (SpreadsheetFixture fixture : fixtures) {
            MvcResult created = performUploadFile(
                    admin,
                    reader,
                    "spreadsheet-" + fixture.expectedFormat().toLowerCase()
                            + "-" + UUID.randomUUID(),
                    fixture.filename(),
                    fixture.mediaType(),
                    fixture.content()
            )
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.revisions[0].documentFormat")
                            .value(fixture.expectedFormat()))
                    .andReturn();
            JsonNode response = objectMapper.readTree(
                    created.getResponse().getContentAsByteArray()
            );
            UUID documentId = UUID.fromString(response.at("/document/id").asText());
            UUID revisionId = UUID.fromString(response.at("/revisions/0/id").asText());

            ClaimedJob task = leases.claimNext().orElseThrow();
            ParsedDocument parsed = artifacts.parse(task);
            assertThat(parsed.documentFormat().name())
                    .isEqualTo(fixture.expectedFormat());
            assertThat(parsed.parserProvider().name())
                    .isEqualTo(fixture.expectedProvider());
            assertThat(parsed.pageCount()).isZero();
            assertThat(parsed.sourceUnits())
                    .allSatisfy(unit -> assertThat(unit.kind().name())
                            .isEqualTo("SHEET"));
            assertThat(parsed.tables()).isNotEmpty();
            assertThat(parsed.tables())
                    .allSatisfy(table -> assertThat(
                            table.boundingBox().sourceUnitKind().name()
                    ).isEqualTo("SHEET"));
            assertThat(artifacts.replaceArtifacts(task, parsed)).isTrue();

            assertThat(jdbc.queryForObject(
                    """
                    SELECT parser_decision_code
                    FROM pipeline_jobs
                    WHERE id = ?
                    """,
                    String.class,
                    task.id()
            )).isEqualTo(fixture.expectedDecision());
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM document_table_cells cell
                    JOIN document_tables table_asset
                      ON table_asset.id = cell.table_id
                    WHERE table_asset.revision_id = ?
                      AND cell.cell_reference IS NOT NULL
                      AND cell.display_value IS NOT NULL
                    """,
                    Long.class,
                    revisionId
            )).isPositive();
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM source_spans
                    WHERE revision_id = ? AND locator_kind = 'CELL_RANGE'
                    """,
                    Long.class,
                    revisionId
            )).isPositive();

            var structure = mockMvc.perform(get(
                            "/api/v1/documents/{documentId}/revisions/{revisionId}/structure",
                            documentId,
                            revisionId
                    ).with(user(principal(admin))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resultPackage.documentFormat")
                            .value(fixture.expectedFormat()))
                    .andExpect(jsonPath("$.resultPackage.parserProvider")
                            .value(fixture.expectedProvider()))
                    .andExpect(jsonPath("$.tables[0].sourceLocator.kind")
                            .value("CELL_RANGE"))
                    .andExpect(jsonPath("$.tables[0].cells[0].cellReference")
                            .isNotEmpty());
            if (fixture.expectedDelimiter() != null) {
                structure.andExpect(jsonPath("$.resultPackage.delimiter")
                        .value(fixture.expectedDelimiter()));
            }
            MvcResult artifactResponse = mockMvc.perform(get(
                            "/api/v1/documents/{documentId}/revisions/{revisionId}/artifacts",
                            documentId,
                            revisionId
                    ).with(user(principal(admin))))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode chunkRows = objectMapper.readTree(
                    artifactResponse.getResponse().getContentAsByteArray()
            ).path("chunks");
            assertThat(chunkRows).isNotEmpty();
            assertThat(chunkRows).allSatisfy(chunk ->
                    assertThat(chunk.path("sourceLabel").asText())
                            .contains("!")
                            .doesNotStartWith("sheet:")
            );

            long tables = count("document_tables", revisionId);
            long cells = countTableCells(revisionId);
            reopenForDuplicateFinalization(task.id(), revisionId);
            ClaimedJob repeated = leases.claimNext().orElseThrow();
            assertThat(artifacts.replaceArtifacts(repeated, parsed)).isTrue();
            assertThat(count("document_tables", revisionId)).isEqualTo(tables);
            assertThat(countTableCells(revisionId)).isEqualTo(cells);
        }
    }

    private Optional<ClaimedJob> claimAfterSignal(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("claim start signal timed out");
        }
        return leases.claimNext();
    }

    private UploadIds upload(UserEntity admin, UserEntity reader, String key, byte[] pdf) throws Exception {
        MvcResult result = performUpload(admin, reader, key, pdf)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new UploadIds(
                UUID.fromString(body.at("/document/id").asText()),
                UUID.fromString(body.at("/revisions/0/id").asText())
        );
    }

    private org.springframework.test.web.servlet.ResultActions performUpload(
            UserEntity admin,
            UserEntity reader,
            String key,
            byte[] pdf
    ) throws Exception {
        return performUploadFile(
                admin,
                reader,
                key,
                "guide.pdf",
                "application/pdf",
                pdf
        );
    }

    private org.springframework.test.web.servlet.ResultActions performUploadFile(
            UserEntity admin,
            UserEntity reader,
            String key,
            String filename,
            String mediaType,
            byte[] content
    ) throws Exception {
        return mockMvc.perform(multipart("/api/v1/admin/documents")
                .file(new MockMultipartFile(
                        "file",
                        filename,
                        mediaType,
                        content
                ))
                .param("title", "Pipeline guide")
                .param("visibility", "RESTRICTED")
                .param("grantedUserIds", reader.getId().toString())
                .header("Idempotency-Key", key)
                .with(user(principal(admin)))
                .with(csrf()));
    }

    private JobFixture createPendingJob(String prefix) {
        UserEntity owner = createUser(prefix + "-owner", UserRole.USER);
        DocumentEntity document = documents.saveAndFlush(
                new DocumentEntity(owner, prefix + " document", DocumentVisibility.RESTRICTED)
        );
        DocumentRevisionEntity revision = revisions.saveAndFlush(new DocumentRevisionEntity(
                document,
                1,
                HASH,
                "tests/" + UUID.randomUUID() + ".pdf",
                RevisionStatus.UPLOADED,
                "test.pdf",
                128,
                "application/pdf",
                null
        ));
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, stage, status, attempt, max_attempts,
                    pipeline_version, document_format
                ) VALUES (?, ?, 'PARSE', 'PENDING', 0, ?, ?, ?)
                """,
                jobId,
                revision.getId(),
                properties.maxAttempts(),
                properties.pipelineVersion(),
                revision.getDocumentFormat().name()
        );
        return new JobFixture(jobId, revision);
    }

    private void reopenForDuplicateFinalization(UUID jobId, UUID revisionId) {
        jdbc.update(
                "UPDATE document_revisions SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                revisionId
        );
        jdbc.update(
                """
                UPDATE pipeline_jobs
                SET status = 'PENDING',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    started_at = NULL,
                    completed_at = NULL,
                    duration_ms = NULL,
                    error_code = NULL,
                    error_message = NULL,
                    quarantine_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                jobId
        );
    }

    private void assertTraceableChildren(UUID revisionId) {
        Long invalidParents = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chunks child
                LEFT JOIN chunks parent
                  ON parent.id = child.parent_chunk_id
                 AND parent.document_id = child.document_id
                 AND parent.revision_id = child.revision_id
                WHERE child.revision_id = ?
                  AND child.chunk_type = 'CHILD'
                  AND (parent.id IS NULL OR parent.chunk_type <> 'PARENT')
                """,
                Long.class,
                revisionId
        );
        Long missingSpans = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chunks child
                WHERE child.revision_id = ?
                  AND child.chunk_type = 'CHILD'
                  AND NOT EXISTS (
                    SELECT 1 FROM source_spans child_span WHERE child_span.chunk_id = child.id
                  )
                """,
                Long.class,
                revisionId
        );
        Long uncoveredSpans = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chunks child
                JOIN source_spans child_span ON child_span.chunk_id = child.id
                JOIN source_units child_start
                  ON child_start.id = child_span.start_source_unit_id
                 AND child_start.revision_id = child_span.revision_id
                JOIN source_units child_end
                  ON child_end.id = child_span.end_source_unit_id
                 AND child_end.revision_id = child_span.revision_id
                WHERE child.revision_id = ?
                  AND child.chunk_type = 'CHILD'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM source_spans parent_span
                    JOIN source_units parent_start
                      ON parent_start.id = parent_span.start_source_unit_id
                     AND parent_start.revision_id = parent_span.revision_id
                    JOIN source_units parent_end
                      ON parent_end.id = parent_span.end_source_unit_id
                     AND parent_end.revision_id = parent_span.revision_id
                    WHERE parent_span.chunk_id = child.parent_chunk_id
                      AND (
                        parent_start.unit_order < child_start.unit_order
                        OR (
                          parent_start.unit_order = child_start.unit_order
                          AND parent_span.start_offset <= child_span.start_offset
                        )
                      )
                      AND (
                        parent_end.unit_order > child_end.unit_order
                        OR (
                          parent_end.unit_order = child_end.unit_order
                          AND parent_span.end_offset >= child_span.end_offset
                        )
                      )
                  )
                """,
                Long.class,
                revisionId
        );
        Long invalidRelativeSpans = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM source_spans span
                JOIN chunks chunk ON chunk.id = span.chunk_id
                WHERE span.revision_id = ?
                  AND (
                    span.chunk_start_offset < 0
                    OR span.chunk_end_offset <= span.chunk_start_offset
                    OR span.chunk_end_offset > rag_utf16_code_unit_length(chunk.text)
                  )
                """,
                Long.class,
                revisionId
        );
        assertThat(invalidParents).isZero();
        assertThat(missingSpans).isZero();
        assertThat(uncoveredSpans).isZero();
        assertThat(invalidRelativeSpans).isZero();
    }

    private ArtifactCounts artifactCounts(UUID revisionId) {
        return new ArtifactCounts(
                count("content_blocks", revisionId),
                countChunks("PARENT", revisionId),
                countChunks("CHILD", revisionId),
                count("source_spans", revisionId)
        );
    }

    private long count(String table, UUID revisionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE revision_id = ?",
                Long.class,
                revisionId
        );
    }

    private long countChunks(String type, UUID revisionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE revision_id = ? AND chunk_type = ?",
                Long.class,
                revisionId,
                type
        );
    }

    private long countTableCells(UUID revisionId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM document_table_cells cell
                JOIN document_tables table_asset
                  ON table_asset.id = cell.table_id
                WHERE table_asset.revision_id = ?
                """,
                Long.class,
                revisionId
        );
    }

    private List<UUID> chunkIds(UUID revisionId) {
        return jdbc.queryForList(
                "SELECT id FROM chunks WHERE revision_id = ? ORDER BY chunk_type, chunk_order",
                UUID.class,
                revisionId
        );
    }

    private long parseJobCount(UUID revisionId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM pipeline_jobs
                WHERE revision_id = ? AND stage = 'PARSE' AND pipeline_version = ?
                """,
                Long.class,
                revisionId,
                properties.pipelineVersion()
        );
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM pipeline_jobs WHERE id = ?",
                String.class,
                jobId
        );
    }

    private String revisionStatus(UUID revisionId) {
        return jdbc.queryForObject(
                "SELECT status FROM document_revisions WHERE id = ?",
                String.class,
                revisionId
        );
    }

    private UserEntity createUser(String prefix, UserRole role) {
        return users.saveAndFlush(new UserEntity(
                prefix + "-" + UUID.randomUUID(),
                passwordEncoder.encode("local-pass-123"),
                role
        ));
    }

    private PlatformUserPrincipal principal(UserEntity user) {
        return PlatformUserPrincipal.from(user);
    }

    private void resetDedicatedTestState() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (!"rag_test".equals(database) || !"rag-documents-test".equals(storageProperties.bucket())) {
            throw new IllegalStateException("Pipeline tests require the dedicated rag_test database and test bucket");
        }
        List.copyOf(storage.list()).forEach(object -> storage.delete(object.key()));
        jdbc.execute("""
                TRUNCATE TABLE search_projection_states, source_spans, chunks, content_blocks, parsed_documents,
                    pipeline_jobs, document_acl_entries, document_revisions, documents
                CASCADE
                """);
    }

    private static byte[] validPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("1 Enterprise Retrieval Guide");
                content.newLineAtOffset(0, -24);
                content.showText("This document explains reliable hybrid retrieval and traceable source citations.");
                content.newLineAtOffset(0, -24);
                content.showText("Every answer must remain grounded in authorized document evidence and revisions.");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] validXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("销售汇总");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("编号");
            header.createCell(1).setCellValue("收入");
            header.createCell(2).setCellValue("成本");
            header.createCell(3).setCellValue("利润");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("S-001");
            row.createCell(1).setCellValue(120);
            row.createCell(2).setCellValue(70);
            row.createCell(3).setCellFormula("B2-C2");
            workbook.getCreationHelper()
                    .createFormulaEvaluator()
                    .evaluateFormulaCell(row.getCell(3));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private record UploadIds(UUID documentId, UUID revisionId) {
    }

    private record TextFixture(
            String filename,
            String mediaType,
            String content,
            String expectedFormat,
            String expectedProvider,
            String expectedLocator
    ) {
    }

    private record SpreadsheetFixture(
            String filename,
            String mediaType,
            byte[] content,
            String expectedFormat,
            String expectedProvider,
            String expectedDecision,
            String expectedDelimiter
    ) {
    }

    private record JobFixture(UUID jobId, DocumentRevisionEntity revision) {
    }

    private record ArtifactCounts(long blocks, long parents, long children, long spans) {
    }
}
