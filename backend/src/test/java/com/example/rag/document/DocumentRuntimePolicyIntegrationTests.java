package com.example.rag.document;

import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.pipeline.ParserProviderKind;
import com.example.rag.pipeline.PipelineJobLeaseService;
import com.example.rag.pipeline.PipelineWorkerHealthService;
import com.example.rag.security.PlatformUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentRuntimePolicyIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectStorageService storage;

    @Autowired
    private PipelineJobLeaseService leases;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PipelineWorkerHealthService workerHealth;

    @BeforeEach
    void prepare() {
        when(workerHealth.isParserAvailable(any())).thenReturn(true);
        jdbc.update("""
                UPDATE document_runtime_policies
                SET status = 'ENABLED', reason = NULL, changed_by = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """);
        jdbc.execute("""
                TRUNCATE TABLE search_projection_states, pipeline_jobs,
                    document_acl_entries, document_revisions, documents
                CASCADE
                """);
        List.copyOf(storage.list()).forEach(object -> storage.delete(object.key()));
    }

    @AfterEach
    void restore() {
        jdbc.update("""
                UPDATE document_runtime_policies
                SET status = 'ENABLED', reason = NULL, changed_by = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """);
        List.copyOf(storage.list()).forEach(object -> storage.delete(object.key()));
        jdbc.execute("""
                TRUNCATE TABLE search_projection_states, pipeline_jobs,
                    document_acl_entries, document_revisions, documents
                CASCADE
                """);
    }

    @Test
    void administratorCanDisableAndRestoreAFormatWithAppendOnlyAudit() throws Exception {
        UserEntity admin = createUser("format-policy-admin", UserRole.ADMIN);
        PlatformUserPrincipal principal = principal(admin);

        mockMvc.perform(patch("/api/v1/admin/document-formats/TXT")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"DISABLE",
                                  "confirmation":"DISABLE_DOCUMENT_FORMAT",
                                  "reason":"暂停 TXT 新写入以检查解析异常"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[1].runtimeStatus").value("DISABLED"))
                .andExpect(jsonPath("$.formats[1].policyStatus").value("DISABLED"));

        MockMultipartFile disabledFile = new MockMultipartFile(
                "file",
                "disabled.txt",
                "text/plain",
                "disabled content".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(disabledFile)
                        .param("title", "Disabled TXT")
                        .param("visibility", "ALL_USERS")
                        .header("Idempotency-Key", "disabled-txt-" + UUID.randomUUID())
                        .with(user(principal))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_FORMAT_DISABLED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Long.class)).isZero();
        mockMvc.perform(get("/api/v1/admin/document-formats/TXT/events")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("DISABLE"))
                .andExpect(jsonPath("$[0].actorUsername").value(admin.getUsername()));

        mockMvc.perform(patch("/api/v1/admin/document-formats/TXT")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"RESTORE",
                                  "confirmation":"RESTORE_DOCUMENT_FORMAT",
                                  "reason":"TXT 解析器心跳正常并恢复新写入"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[1].runtimeStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.formats[1].policyStatus").value("ENABLED"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_runtime_policy_events WHERE policy_key = 'FORMAT:TXT'",
                Long.class
        )).isGreaterThanOrEqualTo(2);
    }

    @Test
    void disabledParserCannotBeSelectedAndNormalUsersCannotManagePolicies() throws Exception {
        UserEntity admin = createUser("parser-policy-admin", UserRole.ADMIN);
        UserEntity reader = createUser("parser-policy-reader", UserRole.USER);

        mockMvc.perform(patch("/api/v1/admin/document-formats/PDF")
                        .with(user(principal(reader)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parserProvider":"MINERU",
                                  "action":"DISABLE",
                                  "confirmation":"DISABLE_PARSER",
                                  "reason":"普通用户不应能够修改解析器策略"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/document-formats/PDF")
                        .with(user(principal(admin)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parserProvider":"PDFBOX",
                                  "action":"DISABLE",
                                  "confirmation":"DISABLE_PARSER",
                                  "reason":"短期停用 PDFBox 解析入口用于隔离验证"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[0].parserProviders[0].runtimeStatus")
                        .value("DISABLED"));

        assertThatThrownPolicyIsDisabled(DocumentFormat.PDF, ParserProviderKind.PDFBOX);
    }

    @Test
    void disablingAFormatStopsNewJobClaimsButKeepsTheAuthorizedOriginalDownloadable()
            throws Exception {
        UserEntity admin = createUser("format-claim-admin", UserRole.ADMIN);
        PlatformUserPrincipal principal = principal(admin);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "queued.txt",
                "text/plain",
                "queued content remains downloadable".getBytes(StandardCharsets.UTF_8)
        );
        MvcResult created = mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(file)
                        .param("title", "Queued TXT")
                        .param("visibility", "ALL_USERS")
                        .header("Idempotency-Key", "queued-txt-" + UUID.randomUUID())
                        .with(user(principal))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();
        var body = objectMapper.readTree(created.getResponse().getContentAsByteArray());
        UUID documentId = UUID.fromString(body.at("/document/id").asText());
        UUID revisionId = UUID.fromString(body.at("/revisions/0/id").asText());

        mockMvc.perform(patch("/api/v1/admin/document-formats/TXT")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"DISABLE",
                                  "confirmation":"DISABLE_DOCUMENT_FORMAT",
                                  "reason":"暂停 TXT 新任务领取但保留既有原件访问"
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(leases.claimNext()).isEmpty();
        MvcResult download = mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/revisions/{revisionId}/download",
                        documentId,
                        revisionId
                ).with(user(principal)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(download))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/document-formats/TXT")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"RESTORE",
                                  "confirmation":"RESTORE_DOCUMENT_FORMAT",
                                  "reason":"TXT 解析心跳正常并恢复新任务领取"
                                }
                                """))
                .andExpect(status().isOk());
        assertThat(leases.claimNext()).isPresent();
    }

    @Test
    void disablingTheResolvedParserRevokesAnAlreadyRunningLease()
            throws Exception {
        UserEntity admin = createUser("parser-lease-admin", UserRole.ADMIN);
        PlatformUserPrincipal principal = principal(admin);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resolved.txt",
                "text/plain",
                "resolved parser policy must fence the worker"
                        .getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(file)
                        .param("title", "Resolved TXT")
                        .param("visibility", "ALL_USERS")
                        .header("Idempotency-Key", "resolved-txt-" + UUID.randomUUID())
                        .with(user(principal))
                        .with(csrf()))
                .andExpect(status().isCreated());

        var claim = leases.claimNext().orElseThrow();
        assertThat(leases.recordParserDecision(
                claim.id(),
                claim.attempt(),
                new PipelineJobLeaseService.ParserDecision(
                        ParserProviderKind.TEXT,
                        "TEXT_SELECTED",
                        "text-parser-v1",
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        null
                )
        )).isTrue();

        mockMvc.perform(patch("/api/v1/admin/document-formats/TXT")
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parserProvider":"TEXT",
                                  "action":"DISABLE",
                                  "confirmation":"DISABLE_PARSER",
                                  "reason":"解析中停用 TEXT 必须使旧 Worker 失去提交权"
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(leases.heartbeat(claim.id(), claim.attempt())).isFalse();
        assertThat(leases.lockOwned(claim.id(), claim.attempt())).isFalse();
    }

    private void assertThatThrownPolicyIsDisabled(
            com.example.rag.persistence.DocumentFormat format,
            ParserProviderKind provider
    ) {
        String status = jdbc.queryForObject(
                "SELECT status FROM document_runtime_policies WHERE policy_key = ?",
                String.class,
                "PARSER:" + format.name() + ":" + provider.name()
        );
        assertThat(status).isEqualTo("DISABLED");
    }

    private UserEntity createUser(String username, UserRole role) {
        return users.saveAndFlush(new UserEntity(
                username + "-" + UUID.randomUUID(),
                passwordEncoder.encode("test-password"),
                role
        ));
    }

    private static PlatformUserPrincipal principal(UserEntity user) {
        return PlatformUserPrincipal.from(user);
    }
}
