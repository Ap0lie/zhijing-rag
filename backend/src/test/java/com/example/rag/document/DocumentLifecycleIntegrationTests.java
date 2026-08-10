package com.example.rag.document;

import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.example.rag.pipeline.PipelineWorkerHealthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentLifecycleIntegrationTests {

    private static final byte[] PDF_ONE = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_TWO = "%PDF-1.7\n2 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String PDF_ONE_SHA256 =
            "45a0c9cb285de42be92202ecf987556bbed9edd6095dae9e4152cb1f477977ce";
    private static final String EVALUATION_SUITE = "graph-global-golden-v1";
    private static final String SOURCE_REVISION =
            "71ac0d0bd1f951d2d6b70311f7d2ae404e1ffa82";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRevisionRepository revisions;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentAclEntryRepository aclEntries;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private ObjectStorageService storage;

    @MockitoBean
    private PipelineWorkerHealthService workerHealth;

    @Autowired
    private StorageCleanupService cleanup;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private StorageProperties storageProperties;

    @BeforeAll
    void resetBeforeSuite() {
        resetDedicatedTestState();
    }

    @BeforeEach
    void resetBeforeTest() {
        resetDedicatedTestState();
        when(workerHealth.isParserAvailable(any())).thenReturn(true);
    }

    @AfterEach
    void resetAfterTest() {
        resetDedicatedTestState();
    }

    @AfterAll
    void resetAfterSuite() {
        resetDedicatedTestState();
        assertThat(storage.list()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Long.class)).isZero();
    }

    private void resetDedicatedTestState() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (!"rag_test".equals(database) || !"rag-documents-test".equals(storageProperties.bucket())) {
            throw new IllegalStateException("Lifecycle tests require the dedicated rag_test database and test bucket");
        }
        List.copyOf(storage.list()).forEach(object -> storage.delete(object.key()));
        jdbc.execute("""
                TRUNCATE TABLE search_projection_states, pipeline_jobs, document_acl_entries,
                    document_revisions, documents
                CASCADE
                """);
    }

    @Test
    void uploadIsIdempotentAndRevisionsRemainImmutableAndDownloadable() throws Exception {
        UserEntity admin = createUser("upload-admin", UserRole.ADMIN);
        UserEntity reader = createUser("upload-reader", UserRole.USER);
        UserEntity outsider = createUser("upload-outsider", UserRole.USER);
        String createKey = "upload-create-" + UUID.randomUUID();

        MvcResult created = uploadNew(admin, reader, createKey, PDF_ONE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.title").value("Platform guide"))
                .andExpect(jsonPath("$.document.latestRevisionStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.currentRevisionId").isEmpty())
                .andReturn();
        JsonNode createdBody = json(created);
        UUID documentId = UUID.fromString(createdBody.at("/document/id").asText());
        UUID firstRevisionId = UUID.fromString(createdBody.at("/revisions/0/id").asText());

        uploadNew(admin, reader, createKey, PDF_ONE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.id").value(documentId.toString()))
                .andExpect(jsonPath("$.revisions.length()").value(1));
        assertThat(revisions.findAllByDocumentIdOrderByRevisionNumberDesc(documentId)).hasSize(1);
        publish(documentId, firstRevisionId);

        mockMvc.perform(get("/api/v1/documents/{id}", documentId).with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(1));
        mockMvc.perform(get("/api/v1/documents/{id}", documentId).with(user(principal(outsider))))
                .andExpect(status().isNotFound());

        assertDownload(documentId, firstRevisionId, reader, PDF_ONE);
        assertInlineView(documentId, firstRevisionId, reader, PDF_ONE);

        mockMvc.perform(multipart("/api/v1/admin/documents/{id}/revisions", documentId)
                        .file(pdf("guide-v2.pdf", PDF_TWO))
                        .header("Idempotency-Key", "upload-revision-" + UUID.randomUUID())
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisions[0].revisionNumber").value(2))
                .andExpect(jsonPath("$.revisions[1].revisionNumber").value(1));

        var stored = revisions.findAllByDocumentIdOrderByRevisionNumberDesc(documentId);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(1).getContentHash()).isNotEqualTo(stored.get(0).getContentHash());
        assertThat(storage.exists(stored.get(0).getSourceObjectKey())).isTrue();
        assertThat(storage.exists(stored.get(1).getSourceObjectKey())).isTrue();
        assertDownload(documentId, firstRevisionId, admin, PDF_ONE);

        assertDownload(documentId, firstRevisionId, reader, PDF_ONE);

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(pdf("forbidden.pdf", PDF_ONE))
                        .param("title", "Forbidden")
                        .param("visibility", "ALL_USERS")
                        .header("Idempotency-Key", "forbidden-" + UUID.randomUUID())
                        .with(user(principal(reader)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void changingRevisionFormatRequiresAndPersistsExplicitApproval()
            throws Exception {
        UserEntity admin = createUser("format-change-admin", UserRole.ADMIN);
        UserEntity reader = createUser("format-change-reader", UserRole.USER);
        MvcResult created = uploadNew(
                admin,
                reader,
                "format-change-create-" + UUID.randomUUID(),
                PDF_ONE
        ).andExpect(status().isCreated()).andReturn();
        UUID documentId = UUID.fromString(
                json(created).at("/document/id").asText()
        );
        UUID revisionId = UUID.fromString(
                json(created).at("/revisions/0/id").asText()
        );
        publish(documentId, revisionId);

        MockMultipartFile text = new MockMultipartFile(
                "file",
                "guide.txt",
                "text/plain",
                "A stable plain text revision.\n".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart(
                        "/api/v1/admin/documents/{id}/revisions",
                        documentId
                )
                        .file(text)
                        .header(
                                "Idempotency-Key",
                                "format-change-missing-" + UUID.randomUUID()
                        )
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "DOCUMENT_FORMAT_CHANGE_CONFIRMATION_REQUIRED"
                ));

        String reason = "将文档来源从 PDF 更换为可维护的 TXT 原文件";
        MvcResult changed = mockMvc.perform(multipart(
                        "/api/v1/admin/documents/{id}/revisions",
                        documentId
                )
                        .file(text)
                        .param(
                                "formatChangeConfirmation",
                                "CHANGE_DOCUMENT_FORMAT"
                        )
                        .param("formatChangeReason", reason)
                        .header(
                                "Idempotency-Key",
                                "format-change-approved-" + UUID.randomUUID()
                        )
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisions[0].documentFormat")
                        .value("TXT"))
                .andExpect(jsonPath("$.revisions[0].formatChangeFrom")
                        .value("PDF"))
                .andExpect(jsonPath("$.revisions[0].formatChangeReason")
                        .value(reason))
                .andReturn();

        UUID changedRevisionId = UUID.fromString(
                json(changed).at("/revisions/0/id").asText()
        );
        assertThat(jdbc.queryForMap(
                """
                SELECT format_change_from, format_change_reason,
                       format_change_requested_by
                FROM document_revisions
                WHERE id = ?
                """,
                changedRevisionId
        )).containsEntry("format_change_from", "PDF")
                .containsEntry("format_change_reason", reason)
                .containsEntry(
                        "format_change_requested_by",
                        admin.getId()
                );
    }

    @Test
    void concurrentRetryUsesOneWriterForAnUnexpiredStagedRevision() throws Exception {
        UserEntity admin = createUser("concurrent-admin", UserRole.ADMIN);
        UserEntity reader = createUser("concurrent-reader", UserRole.USER);
        String key = "concurrent-create-" + UUID.randomUUID();
        var uploadStarted = new CountDownLatch(1);
        var releaseUpload = new CountDownLatch(1);
        var uploadAttempts = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();

        doAnswer(invocation -> {
            uploadAttempts.incrementAndGet();
            uploadStarted.countDown();
            if (!releaseUpload.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Initial upload was not released");
            }
            return invocation.callRealMethod();
        }).when(storage).upload(
                any(String.class),
                any(Path.class),
                eq("application/pdf")
        );

        try {
            var firstFuture = executor.submit(() -> uploadNew(admin, reader, key, PDF_ONE).andReturn());
            assertThat(uploadStarted.await(10, TimeUnit.SECONDS)).isTrue();

            uploadNew(admin, reader, key, PDF_ONE)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("UPLOAD_IN_PROGRESS"));
            assertThat(uploadAttempts).hasValue(1);

            releaseUpload.countDown();
            MvcResult first = firstFuture.get(30, TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).isEqualTo(201);

            DocumentRevisionEntity revision = revisions.findByIdempotencyKey(key).orElseThrow();
            assertThat(revision.getStatus()).isEqualTo(RevisionStatus.UPLOADED);
            assertThat(storage.exists(revision.getSourceObjectKey())).isTrue();
            assertThat(revisions.findAllByDocumentIdOrderByRevisionNumberDesc(
                    revision.getDocument().getId()
            )).hasSize(1);
        } finally {
            releaseUpload.countDown();
            executor.shutdownNow();
            org.mockito.Mockito.reset(storage);
        }
    }

    @Test
    void retryFinalizesAStagedRevisionWhenItsObjectWasAlreadyWritten() throws Exception {
        UserEntity admin = createUser("written-object-admin", UserRole.ADMIN);
        UserEntity reader = createUser("written-object-reader", UserRole.USER);
        String key = "written-object-" + UUID.randomUUID();
        var objectWritten = new CountDownLatch(1);
        var releaseUpload = new CountDownLatch(1);
        var uploadAttempts = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();

        doAnswer(invocation -> {
            uploadAttempts.incrementAndGet();
            invocation.callRealMethod();
            objectWritten.countDown();
            if (!releaseUpload.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Initial upload was not released");
            }
            return null;
        }).when(storage).upload(
                any(String.class),
                any(Path.class),
                eq("application/pdf")
        );

        try {
            var firstFuture = executor.submit(() -> uploadNew(admin, reader, key, PDF_ONE).andReturn());
            assertThat(objectWritten.await(10, TimeUnit.SECONDS)).isTrue();

            MvcResult recovered = uploadNew(admin, reader, key, PDF_ONE)
                    .andExpect(status().isCreated())
                    .andReturn();
            assertThat(json(recovered).at("/document/latestRevisionStatus").asText())
                    .isEqualTo("UPLOADED");
            assertThat(uploadAttempts).hasValue(1);

            releaseUpload.countDown();
            assertThat(firstFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);

            DocumentRevisionEntity revision = revisions.findByIdempotencyKey(key).orElseThrow();
            assertThat(revision.getStatus()).isEqualTo(RevisionStatus.UPLOADED);
            assertThat(storage.exists(revision.getSourceObjectKey())).isTrue();
        } finally {
            releaseUpload.countDown();
            executor.shutdownNow();
            org.mockito.Mockito.reset(storage);
        }
    }

    @Test
    void reusingAnIdempotencyKeyWithDifferentContentIsRejected() throws Exception {
        UserEntity admin = createUser("fingerprint-admin", UserRole.ADMIN);
        UserEntity reader = createUser("fingerprint-reader", UserRole.USER);
        String key = "fingerprint-create-" + UUID.randomUUID();

        MvcResult created = uploadNew(admin, reader, key, PDF_ONE)
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(json(created).at("/document/id").asText());
        String originalHash = revisions.findByIdempotencyKey(key).orElseThrow().getContentHash();

        uploadNew(admin, reader, key, PDF_TWO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));

        assertThat(revisions.findAllByDocumentIdOrderByRevisionNumberDesc(documentId)).hasSize(1);
        assertThat(revisions.findByIdempotencyKey(key).orElseThrow().getContentHash()).isEqualTo(originalHash);
        assertThat(storage.list()).hasSize(1);
    }

    @Test
    void evaluationProvenanceIsReturnedForLatestAndEffectiveRevisions() throws Exception {
        UserEntity admin = createUser("evaluation-admin", UserRole.ADMIN);
        String createKey = "evaluation-create-" + UUID.randomUUID();

        MvcResult created = uploadEvaluation(
                admin,
                createKey,
                "multihop-rag:case-1:evidence-1",
                "a".repeat(64),
                PDF_ONE
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.title")
                        .value("[EVAL][PUBLIC] Source one"))
                .andExpect(jsonPath(
                        "$.document.latestEvaluationProvenance.evaluationSuiteVersion"
                ).value(EVALUATION_SUITE))
                .andExpect(jsonPath(
                        "$.document.latestEvaluationProvenance.sourceLicense"
                ).value("ODC-By-1.0"))
                .andExpect(jsonPath(
                        "$.document.effectiveEvaluationProvenance"
                ).isEmpty())
                .andExpect(jsonPath(
                        "$.revisions[0].evaluationProvenance.evaluationEvidenceKey"
                ).value("multihop-rag:case-1:evidence-1"))
                .andReturn();
        UUID documentId = UUID.fromString(json(created).at("/document/id").asText());
        UUID firstRevisionId = UUID.fromString(json(created).at("/revisions/0/id").asText());
        publish(documentId, firstRevisionId);

        mockMvc.perform(get("/api/v1/documents")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.items[0].effectiveEvaluationProvenance.evaluationEvidenceKey"
                ).value("multihop-rag:case-1:evidence-1"))
                .andExpect(jsonPath(
                        "$.items[0].latestEvaluationProvenance.sourceDataset"
                ).value("multihop-rag"));

        MvcResult second = mockMvc.perform(multipart(
                                "/api/v1/admin/documents/{id}/revisions",
                                documentId
                        )
                        .file(pdf("source-two.pdf", PDF_TWO))
                        .header(
                                "Idempotency-Key",
                                "evaluation-revision-" + UUID.randomUUID()
                        )
                        .param("evaluationSuiteVersion", EVALUATION_SUITE)
                        .param(
                                "evaluationEvidenceKey",
                                "multihop-rag:case-1:evidence-2"
                        )
                        .param("sourceDataset", "multihop-rag")
                        .param("sourceTitle", "Source two")
                        .param(
                                "sourceUrl",
                                "https://example.com/source-two"
                        )
                        .param("sourceLicense", "ODC-By-1.0")
                        .param("sourceRevision", SOURCE_REVISION)
                        .param("sourceContentHash", "b".repeat(64))
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(
                        "$.document.effectiveEvaluationProvenance.evaluationEvidenceKey"
                ).value("multihop-rag:case-1:evidence-1"))
                .andExpect(jsonPath(
                        "$.document.latestEvaluationProvenance.evaluationEvidenceKey"
                ).value("multihop-rag:case-1:evidence-2"))
                .andExpect(jsonPath(
                        "$.revisions[0].evaluationProvenance.sourceTitle"
                ).value("Source two"))
                .andReturn();
        UUID secondRevisionId = UUID.fromString(
                json(second).at("/revisions/0/id").asText()
        );
        publish(documentId, secondRevisionId);

        mockMvc.perform(get("/api/v1/documents/{id}", documentId)
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.document.effectiveEvaluationProvenance.evaluationEvidenceKey"
                ).value("multihop-rag:case-1:evidence-2"))
                .andExpect(jsonPath(
                        "$.document.latestEvaluationProvenance.sourceContentHash"
                ).value("b".repeat(64)));

        var provenance = revisions.findById(secondRevisionId)
                .orElseThrow()
                .getEvaluationProvenance();
        assertThat(provenance.getEvaluationSuiteVersion())
                .isEqualTo(EVALUATION_SUITE);
        assertThat(provenance.getSourceUrl())
                .isEqualTo("https://example.com/source-two");
    }

    @Test
    void evaluationProvenanceRequiresACompletePublicScope() throws Exception {
        UserEntity admin = createUser("evaluation-validation-admin", UserRole.ADMIN);
        long documentCount = documents.count();

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(pdf("partial.pdf", PDF_ONE))
                        .param("title", "[EVAL][PUBLIC] Partial")
                        .param("visibility", "ALL_USERS")
                        .param("evaluationSuiteVersion", EVALUATION_SUITE)
                        .header(
                                "Idempotency-Key",
                                "evaluation-partial-" + UUID.randomUUID()
                        )
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("EVALUATION_PROVENANCE_INCOMPLETE"));

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(pdf("restricted.pdf", PDF_ONE))
                        .param("title", "[EVAL][PUBLIC] Restricted")
                        .param("visibility", "RESTRICTED")
                        .param("evaluationSuiteVersion", EVALUATION_SUITE)
                        .param(
                                "evaluationEvidenceKey",
                                "multihop-rag:restricted"
                        )
                        .param("sourceDataset", "multihop-rag")
                        .param("sourceTitle", "Restricted source")
                        .param(
                                "sourceUrl",
                                "https://example.com/restricted"
                        )
                        .param("sourceLicense", "ODC-By-1.0")
                        .param("sourceRevision", SOURCE_REVISION)
                        .param("sourceContentHash", "c".repeat(64))
                        .header(
                                "Idempotency-Key",
                                "evaluation-restricted-" + UUID.randomUUID()
                        )
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("EVALUATION_PROVENANCE_SCOPE_INVALID"));

        assertThat(documents.count()).isEqualTo(documentCount);
    }

    @Test
    void evaluationProvenanceParticipatesInIdempotencyAndIsUnique()
            throws Exception {
        UserEntity admin = createUser("evaluation-idempotency-admin", UserRole.ADMIN);
        String key = "evaluation-idempotency-" + UUID.randomUUID();
        String evidenceKey = "multihop-rag:unique-evidence";

        uploadEvaluation(admin, key, evidenceKey, "d".repeat(64), PDF_ONE)
                .andExpect(status().isCreated());

        uploadEvaluation(admin, key, evidenceKey, "e".repeat(64), PDF_ONE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));

        uploadEvaluation(
                admin,
                "evaluation-duplicate-" + UUID.randomUUID(),
                evidenceKey,
                "d".repeat(64),
                PDF_ONE
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(revisions.count()).isEqualTo(1);
        assertThat(documents.count()).isEqualTo(1);
        assertThat(storage.list()).hasSize(1);
    }

    @Test
    void reparsePreservesEvaluationProvenanceWithinTheSameDocument()
            throws Exception {
        UserEntity admin = createUser("evaluation-reparse-admin", UserRole.ADMIN);
        String evidenceKey = "multihop-rag:reparse-evidence";
        MvcResult created = uploadEvaluation(
                admin,
                "evaluation-reparse-" + UUID.randomUUID(),
                evidenceKey,
                "f".repeat(64),
                PDF_ONE
        )
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(json(created).at("/document/id").asText());
        UUID sourceRevisionId = UUID.fromString(
                json(created).at("/revisions/0/id").asText()
        );
        publish(documentId, sourceRevisionId);

        MvcResult reparsed = mockMvc.perform(post(
                        "/api/v1/admin/documents/{id}/reparse",
                        documentId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevisionId": "%s",
                                  "targetParser": "PDFBOX",
                                  "idempotencyKey": "evaluation-reparse-request-%s",
                                  "reason": "使用当前解析器重新生成评测文档结构资产",
                                  "confirmation": "REPARSE"
                                }
                                """.formatted(sourceRevisionId, UUID.randomUUID()))
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andReturn();
        UUID reparsedRevisionId = UUID.fromString(
                json(reparsed).at("/revisionId").asText()
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM document_revisions
                WHERE document_id = ?
                  AND evaluation_suite_version = ?
                  AND evaluation_evidence_key = ?
                """,
                Long.class,
                documentId,
                EVALUATION_SUITE,
                evidenceKey
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT source_revision_id
                FROM document_revisions
                WHERE id = ?
                """,
                UUID.class,
                reparsedRevisionId
        )).isEqualTo(sourceRevisionId);
    }

    @Test
    void legacyContentHashFingerprintIsNotAcceptedAsTheCurrentRequestFingerprint() throws Exception {
        UserEntity admin = createUser("legacy-fingerprint-admin", UserRole.ADMIN);
        UserEntity reader = createUser("legacy-fingerprint-reader", UserRole.USER);
        String key = "legacy-fingerprint-" + UUID.randomUUID();
        DocumentEntity document = documents.saveAndFlush(
                new DocumentEntity(admin, "Platform guide", DocumentVisibility.RESTRICTED)
        );
        DocumentRevisionEntity revision = revisions.saveAndFlush(new DocumentRevisionEntity(
                document,
                1,
                PDF_ONE_SHA256,
                "objects/legacy/" + UUID.randomUUID() + ".pdf",
                RevisionStatus.UPLOADED,
                "guide.pdf",
                PDF_ONE.length,
                "application/pdf",
                key,
                PDF_ONE_SHA256,
                null
        ));

        uploadNew(admin, reader, key, PDF_ONE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));

        assertThat(revisions.findById(revision.getId()).orElseThrow().getRequestFingerprint())
                .isEqualTo(PDF_ONE_SHA256);
        assertThat(storage.list()).isEmpty();
    }

    @Test
    void userCannotListOrOpenAStagedDocument() throws Exception {
        UserEntity admin = createUser("staged-admin", UserRole.ADMIN);
        UserEntity owner = createUser("staged-owner", UserRole.USER);
        DocumentEntity document = documents.saveAndFlush(
                new DocumentEntity(owner, "Staged document", DocumentVisibility.RESTRICTED)
        );
        revisions.saveAndFlush(new DocumentRevisionEntity(
                document,
                1,
                PDF_ONE_SHA256,
                "objects/staged/" + UUID.randomUUID() + ".pdf",
                RevisionStatus.STAGED,
                "staged.pdf",
                PDF_ONE.length,
                "application/pdf",
                "staged-visible-key-" + UUID.randomUUID(),
                "b".repeat(64),
                Instant.now().plusSeconds(60)
        ));

        mockMvc.perform(get("/api/v1/documents").with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/documents/{id}", document.getId()).with(user(principal(owner))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents").with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].latestRevisionStatus").value("STAGED"));
        mockMvc.perform(get("/api/v1/documents/{id}", document.getId()).with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.latestRevisionStatus").value("STAGED"));
    }

    @Test
    void staleAclUpdateIsRejectedWithoutOverwritingTheCommittedGrant() throws Exception {
        UserEntity admin = createUser("stale-admin", UserRole.ADMIN);
        UserEntity reader = createUser("stale-reader", UserRole.USER);
        MvcResult created = uploadNew(admin, reader, "stale-create-" + UUID.randomUUID(), PDF_ONE)
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(json(created).at("/document/id").asText());
        publish(documentId, UUID.fromString(json(created).at("/revisions/0/id").asText()));

        mockMvc.perform(patch("/api/v1/admin/documents/{id}/acl", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Committed title","visibility":"RESTRICTED",
                                 "grantedUserIds":["%s"],"expectedAclVersion":1}
                                """.formatted(reader.getId()))
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.aclVersion").value(2));

        mockMvc.perform(patch("/api/v1/admin/documents/{id}/acl", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Stale overwrite","visibility":"RESTRICTED",
                                 "grantedUserIds":[],"expectedAclVersion":1}
                                """)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACL_VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/documents/{id}", documentId).with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.title").value("Committed title"))
                .andExpect(jsonPath("$.document.aclVersion").value(2));
    }

    @Test
    void adminCannotBeStoredAsARestrictedDocumentGrantee() throws Exception {
        UserEntity owner = createUser("grantee-owner", UserRole.ADMIN);
        UserEntity otherAdmin = createUser("grantee-admin", UserRole.ADMIN);
        UserEntity reader = createUser("grantee-reader", UserRole.USER);
        long documentsBefore = documents.count();

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(pdf("invalid-grantee.pdf", PDF_ONE))
                        .param("title", "Invalid grantee")
                        .param("visibility", "RESTRICTED")
                        .param("grantedUserIds", otherAdmin.getId().toString())
                        .header("Idempotency-Key", "invalid-grantee-create-" + UUID.randomUUID())
                        .with(user(principal(owner)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACL_USER_INVALID"));
        assertThat(documents.count()).isEqualTo(documentsBefore);

        MvcResult created = uploadNew(
                owner,
                reader,
                "valid-grantee-create-" + UUID.randomUUID(),
                PDF_ONE
        ).andExpect(status().isCreated()).andReturn();
        UUID documentId = UUID.fromString(json(created).at("/document/id").asText());

        mockMvc.perform(patch("/api/v1/admin/documents/{id}/acl", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Invalid replacement","visibility":"RESTRICTED",
                                 "grantedUserIds":["%s"],"expectedAclVersion":1}
                                """.formatted(otherAdmin.getId()))
                        .with(user(principal(owner)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACL_USER_INVALID"));

        assertThat(aclEntries.existsByDocumentIdAndUserId(documentId, reader.getId())).isTrue();
        assertThat(aclEntries.existsByDocumentIdAndUserId(documentId, otherAdmin.getId())).isFalse();
        assertThat(documents.findById(documentId).orElseThrow().getAclVersion()).isEqualTo(1);
    }

    @Test
    void invalidPdfContentIsRejectedBeforeAnyRevisionIsCreated() throws Exception {
        UserEntity admin = createUser("validation-admin", UserRole.ADMIN);
        long before = revisions.count();

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(pdf("fake.pdf", "not-a-pdf".getBytes(StandardCharsets.US_ASCII)))
                        .param("title", "Fake")
                        .param("visibility", "ALL_USERS")
                        .header("Idempotency-Key", "invalid-content-" + UUID.randomUUID())
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_SIGNATURE_INVALID"));

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(new MockMultipartFile("file", "wrong.pdf", "text/plain", PDF_ONE))
                        .param("title", "Wrong media")
                        .param("visibility", "ALL_USERS")
                        .header("Idempotency-Key", "invalid-media-" + UUID.randomUUID())
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_MEDIA_TYPE_INVALID"));

        assertThat(revisions.count()).isEqualTo(before);
    }

    @Test
    void aclRevocationAndTombstoneDenyImmediatelyWhileCleanupRemovesObjects() throws Exception {
        UserEntity admin = createUser("lifecycle-admin", UserRole.ADMIN);
        UserEntity reader = createUser("lifecycle-reader", UserRole.USER);
        MvcResult created = uploadNew(admin, reader, "lifecycle-create-" + UUID.randomUUID(), PDF_ONE)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(created);
        UUID documentId = UUID.fromString(body.at("/document/id").asText());
        UUID revisionId = UUID.fromString(body.at("/revisions/0/id").asText());
        String objectKey = revisions.findById(revisionId).orElseThrow().getSourceObjectKey();
        publish(documentId, revisionId);
        assertDownload(documentId, revisionId, reader, PDF_ONE);

        mockMvc.perform(patch("/api/v1/admin/documents/{id}/acl", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Platform guide","visibility":"RESTRICTED",
                                 "grantedUserIds":[],"expectedAclVersion":1}
                                """)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.aclVersion").value(2));
        mockMvc.perform(get("/api/v1/documents/{id}", documentId).with(user(principal(reader))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents").with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/documents/{documentId}/revisions/{revisionId}/download",
                        documentId, revisionId).with(user(principal(reader))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/admin/documents/{id}", documentId)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/documents/{id}", documentId).with(user(principal(admin))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents").with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/documents/{documentId}/revisions/{revisionId}/download",
                        documentId, revisionId).with(user(principal(admin))))
                .andExpect(status().isNotFound());
        assertThat(storage.exists(objectKey)).isTrue();

        cleanup.runCleanup();
        cleanup.runCleanup();
        assertThat(storage.exists(objectKey)).isFalse();
        assertThat(revisions.findById(revisionId).orElseThrow().getStatus()).isEqualTo(RevisionStatus.DELETED);
    }

    @Test
    void orphanCleanupRecoversAnInterruptedUpload() throws Exception {
        Path temporary = Files.createTempFile("orphan-upload-", ".pdf");
        String objectKey = "objects/orphan/" + UUID.randomUUID() + ".pdf";
        try {
            Files.write(temporary, PDF_ONE);
            storage.upload(objectKey, temporary, "application/pdf");
            assertThat(storage.exists(objectKey)).isTrue();

            cleanup.runCleanup();

            assertThat(storage.exists(objectKey)).isFalse();
        } finally {
            Files.deleteIfExists(temporary);
            storage.delete(objectKey);
        }
    }

    @Test
    void expiredStagedUploadIsTombstonedAndCleanedIdempotently() throws Exception {
        UserEntity admin = createUser("expired-stage-admin", UserRole.ADMIN);
        DocumentEntity document = documents.saveAndFlush(
                new DocumentEntity(admin, "Expired upload", DocumentVisibility.RESTRICTED)
        );
        Path temporary = Files.createTempFile("expired-staged-upload-", ".pdf");
        String objectKey = "objects/expired/" + UUID.randomUUID() + ".pdf";
        try {
            Files.write(temporary, PDF_ONE);
            storage.upload(objectKey, temporary, "application/pdf");
            DocumentRevisionEntity revision = revisions.saveAndFlush(new DocumentRevisionEntity(
                    document,
                    1,
                    "a".repeat(64),
                    objectKey,
                    RevisionStatus.STAGED,
                    "expired.pdf",
                    PDF_ONE.length,
                    "application/pdf",
                    "expired-stage-key-" + UUID.randomUUID(),
                    "a".repeat(64),
                    Instant.now().minusSeconds(1)
            ));

            cleanup.runCleanup();
            cleanup.runCleanup();

            assertThat(storage.exists(objectKey)).isFalse();
            assertThat(revisions.findById(revision.getId()).orElseThrow().getStatus())
                    .isNotEqualTo(RevisionStatus.STAGED);
            assertThat(documents.findById(document.getId()).orElseThrow().getDeletedAt()).isNotNull();
        } finally {
            Files.deleteIfExists(temporary);
            storage.delete(objectKey);
        }
    }

    private org.springframework.test.web.servlet.ResultActions uploadNew(
            UserEntity admin,
            UserEntity reader,
            String idempotencyKey,
            byte[] content
    ) throws Exception {
        return mockMvc.perform(multipart("/api/v1/admin/documents")
                .file(pdf("guide.pdf", content))
                .param("title", "Platform guide")
                .param("visibility", "RESTRICTED")
                .param("grantedUserIds", reader.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .with(user(principal(admin)))
                .with(csrf()));
    }

    private org.springframework.test.web.servlet.ResultActions uploadEvaluation(
            UserEntity admin,
            String idempotencyKey,
            String evidenceKey,
            String sourceHash,
            byte[] content
    ) throws Exception {
        return mockMvc.perform(multipart("/api/v1/admin/documents")
                .file(pdf("evaluation.pdf", content))
                .param("title", "[EVAL][PUBLIC] Source one")
                .param("visibility", "ALL_USERS")
                .param("evaluationSuiteVersion", EVALUATION_SUITE)
                .param("evaluationEvidenceKey", evidenceKey)
                .param("sourceDataset", "multihop-rag")
                .param("sourceTitle", "Source one")
                .param("sourceUrl", "https://example.com/source-one")
                .param("sourceLicense", "ODC-By-1.0")
                .param("sourceRevision", SOURCE_REVISION)
                .param("sourceContentHash", sourceHash)
                .header("Idempotency-Key", idempotencyKey)
                .with(user(principal(admin)))
                .with(csrf()));
    }

    private void publish(UUID documentId, UUID revisionId) {
        DocumentRevisionEntity revision = revisions.findById(revisionId).orElseThrow();
        revision.markProcessing();
        revision.markReady("test-parser");
        revisions.saveAndFlush(revision);
        jdbc.update(
                """
                UPDATE documents
                SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                revisionId,
                documentId
        );
    }

    private void assertDownload(UUID documentId, UUID revisionId, UserEntity actor, byte[] expected) throws Exception {
        MvcResult started = mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/download",
                        documentId,
                        revisionId
                ).with(user(principal(actor))))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(expected));
    }

    private void assertInlineView(UUID documentId, UUID revisionId, UserEntity actor, byte[] expected) throws Exception {
        MvcResult started = mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/download",
                        documentId,
                        revisionId
                ).param("inline", "true").with(user(principal(actor))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(expected))
                .andReturn();
        assertThat(completed.getResponse().getHeader("Content-Disposition")).startsWith("inline;");
    }

    private MockMultipartFile pdf(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "application/pdf", content);
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
