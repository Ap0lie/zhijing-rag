package com.example.rag.memory;

import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.chat.ChatPersistenceRepository;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MemoryIntegrationTests {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ChatPersistenceRepository chats;

    @Autowired
    private MemoryPackService memoryPacks;

    @Autowired
    private MemorySuggestionService memorySuggestions;

    @Autowired
    private MemoryService memories;

    @BeforeEach
    void reset() {
        String database = jdbc.queryForObject(
                "SELECT current_database()", String.class
        );
        if (!"rag_test".equals(database)) {
            throw new IllegalStateException(
                    "Memory tests require the dedicated rag_test database"
            );
        }
        jdbc.execute("""
                TRUNCATE TABLE memory_items, user_memory_settings, chat_sessions,
                    search_projection_states, source_spans, chunks, content_blocks,
                    parsed_documents, pipeline_jobs, document_acl_entries,
                    document_revisions, documents
                CASCADE
                """);
    }

    @Test
    void settingsDefaultOffAndProfileRemainOwnerScoped() throws Exception {
        UserEntity owner = createUser("memory-owner", UserRole.USER);
        UserEntity other = createUser("memory-other", UserRole.USER);
        UserEntity admin = createUser("memory-admin", UserRole.ADMIN);

        mockMvc.perform(get("/api/v1/memories/settings")
                        .with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.suggestionEnabled").value(false))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(put("/api/v1/memories/settings")
                        .with(user(principal(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "suggestionEnabled": false,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put("/api/v1/memories/settings")
                        .with(user(principal(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false,
                                  "suggestionEnabled": false,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("MEMORY_SETTINGS_CONFLICT"));

        MvcResult created = createPreference(
                owner,
                "memory-owner-language",
                false,
                "回答语言",
                "默认使用简体中文"
        ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        UUID memoryId = UUID.fromString(json(created).at("/id").asText());

        createPreference(
                owner,
                "memory-owner-language",
                false,
                "ignored",
                "不同正文"
        ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(memoryId.toString()))
                .andExpect(jsonPath("$.content").value("默认使用简体中文"));

        mockMvc.perform(get("/api/v1/memories/profile")
                        .with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryEnabled").value(true))
                .andExpect(jsonPath("$.preferences", hasSize(1)))
                .andExpect(jsonPath("$.preferences[0].key")
                        .value("回答语言"));
        mockMvc.perform(get("/api/v1/memories/{id}", memoryId)
                        .with(user(principal(other))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/memories")
                        .with(user(principal(other))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/admin/memories/summary")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsByType.USER_PREFERENCE")
                        .value(1));
        mockMvc.perform(get("/api/v1/admin/memories/summary")
                        .with(user(principal(other))))
                .andExpect(status().isForbidden());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE memory_items SET memory_key = '覆盖' WHERE id = ?",
                memoryId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void candidateReplaceRevokeAndForgetAreVersionedAndIdempotent()
            throws Exception {
        UserEntity owner = createUser("memory-lifecycle", UserRole.USER);
        MvcResult candidate = createPreference(
                owner,
                "memory-candidate-one",
                true,
                "回答风格",
                "先给结论"
        ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANDIDATE"))
                .andReturn();
        UUID candidateId = UUID.fromString(
                json(candidate).at("/id").asText()
        );

        action(owner, candidateId, "confirm", "用户确认")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        action(owner, candidateId, "confirm", "重复确认")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        createPreference(
                owner,
                "memory-active-conflict",
                false,
                " 回答风格 ",
                "直接给结论"
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("MEMORY_ACTIVE_CONFLICT"));

        MvcResult replaced = mockMvc.perform(post(
                                "/api/v1/memories/{id}/replace",
                                candidateId
                        )
                        .with(user(principal(owner)))
                        .with(csrf())
                        .header(
                                "Idempotency-Key",
                                "memory-replace-lifecycle"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryKey": "回答风格",
                                  "content": "结论优先，再给证据",
                                  "expiresAt": null,
                                  "sources": [],
                                  "reason": "修正偏好"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.supersedesMemoryId")
                        .value(candidateId.toString()))
                .andReturn();
        UUID replacementId = UUID.fromString(
                json(replaced).at("/id").asText()
        );

        mockMvc.perform(get("/api/v1/memories/{id}", candidateId)
                        .with(user(principal(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.content").value("先给结论"));
        action(owner, replacementId, "revoke", "暂时停用")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.content")
                        .value("结论优先，再给证据"));

        forget(owner, replacementId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORGOTTEN"))
                .andExpect(jsonPath("$.content").value(nullValue()))
                .andExpect(jsonPath("$.sourceCount").value(0));
        forget(owner, replacementId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORGOTTEN"));

        Long forgottenEvents = jdbc.queryForObject(
                """
                SELECT count(*) FROM memory_events
                WHERE memory_item_id = ? AND event_type = 'FORGOTTEN'
                """,
                Long.class,
                replacementId
        );
        assertThat(forgottenEvents).isEqualTo(1);
    }

    @Test
    void credentialsAndStaleDocumentSourcesFailClosed() throws Exception {
        UserEntity admin = createUser(
                "memory-document-admin", UserRole.ADMIN
        );
        UserEntity reader = createUser(
                "memory-document-reader", UserRole.USER
        );
        SourceFixture source = insertSourceFixture(
                admin.getId(), reader.getId()
        );

        createPreference(
                reader,
                "memory-secret-reject",
                false,
                "接口信息",
                "password: intentionally-invalid-memory-secret"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("MEMORY_CREDENTIAL_REJECTED"));

        String body = """
                {
                  "memoryType": "DOCUMENT_FACT",
                  "memoryKey": "发布规则",
                  "content": "发布前必须完成人工复核。",
                  "candidate": false,
                  "expiresAt": null,
                  "sources": [{
                    "sourceType": "DOCUMENT_SPAN",
                    "chatSessionId": null,
                    "chatMessageId": null,
                    "documentId": "%s",
                    "revisionId": "%s",
                    "childChunkId": "%s",
                    "sourceSpanId": "%s"
                  }]
                }
                """.formatted(
                source.documentId(),
                source.revisionId(),
                source.childChunkId(),
                source.sourceSpanId()
        );
        MvcResult created = mockMvc.perform(post("/api/v1/memories")
                        .with(user(principal(reader)))
                        .with(csrf())
                        .header(
                                "Idempotency-Key",
                                "memory-document-source"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceCount").value(1))
                .andReturn();
        UUID memoryId = UUID.fromString(json(created).at("/id").asText());

        jdbc.update(
                """
                DELETE FROM document_acl_entries
                WHERE document_id = ? AND user_id = ?
                """,
                source.documentId(),
                reader.getId()
        );
        mockMvc.perform(get("/api/v1/memories/{id}", memoryId)
                        .with(user(principal(reader))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/memories")
                        .with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        forget(reader, memoryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORGOTTEN"))
                .andExpect(jsonPath("$.content").value(nullValue()))
                .andExpect(jsonPath("$.sourceCount").value(0));
    }

    @Test
    void memoryPackIsBoundedPersistedAndOwnerScoped() throws Exception {
        UserEntity owner = createUser("memory-pack-owner", UserRole.USER);
        UserEntity other = createUser("memory-pack-other", UserRole.USER);
        PlatformUserPrincipal ownerPrincipal = principal(owner);

        mockMvc.perform(put("/api/v1/memories/settings")
                        .with(user(ownerPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "suggestionEnabled": false,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk());
        MvcResult created = createPreference(
                owner,
                "memory-pack-language",
                false,
                "回答语言",
                "默认使用简体中文"
        ).andExpect(status().isCreated()).andReturn();
        UUID memoryId = UUID.fromString(json(created).at("/id").asText());

        var pack = memoryPacks.recall(
                ownerPrincipal, "请用中文回答", true, 2_000
        );
        assertThat(pack.injected()).singleElement()
                .extracting(MemoryPackService.Selection::memoryId)
                .isEqualTo(memoryId);
        assertThat(pack.tokenCount()).isLessThanOrEqualTo(
                200
        );
        assertThat(pack.tokenBudget()).isEqualTo(200);
        assertThat(pack.tokenCounterVersion()).isEqualTo(
                MemoryPackService.TOKEN_COUNTER_VERSION
        );
        var unrelated = memoryPacks.recall(
                ownerPrincipal,
                "部署集群证书轮换",
                true,
                2_000
        );
        assertThat(unrelated.injected()).isEmpty();
        assertThat(unrelated.selections()).singleElement()
                .satisfies(selection -> {
                    assertThat(selection.status()).isEqualTo("TRIMMED");
                    assertThat(selection.trimReason())
                            .isEqualTo("MEMORY_RELEVANCE_LOW");
                });

        var session = chats.createSession(owner.getId(), "Memory pack");
        var started = chats.startRun(
                owner.getId(),
                session.id(),
                new StartRunCommand(
                        "请用中文回答",
                        "zh",
                        "phase14b-test",
                        UUID.randomUUID().toString()
                ),
                memorySuggestions.runtimeSnapshot()
        ).orElseThrow();
        memoryPacks.saveRunUsages(
                owner.getId(),
                started.run().id(),
                pack,
                Set.of(memoryId),
                Set.of()
        );

        mockMvc.perform(get(
                                "/api/v1/chat/runs/{runId}/memories",
                                started.run().id()
                        )
                        .with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].usageStatus").value("USED"))
                .andExpect(jsonPath("$[0].tokenLimit").value(200))
                .andExpect(jsonPath("$[0].tokenCounterVersion").value(
                        MemoryPackService.TOKEN_COUNTER_VERSION
                ))
                .andExpect(jsonPath("$[0].tokenCountExact").value(false))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].content")
                        .value("默认使用简体中文"));
        mockMvc.perform(get(
                                "/api/v1/chat/runs/{runId}/memories",
                                started.run().id()
                        )
                        .with(user(principal(other))))
                .andExpect(status().isNotFound());

        action(owner, memoryId, "revoke", "不再使用")
                .andExpect(status().isOk());
        mockMvc.perform(get(
                                "/api/v1/chat/runs/{runId}/memories",
                                started.run().id()
                        )
                        .with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].content").value(nullValue()));
    }

    @Test
    void suggestionJobIsIdempotentAndCreatesConfirmableCandidate() throws Exception {
        UserEntity owner = createUser("memory-suggestion-owner", UserRole.USER);
        UserEntity other = createUser("memory-suggestion-other", UserRole.USER);
        PlatformUserPrincipal ownerPrincipal = principal(owner);
        mockMvc.perform(put("/api/v1/memories/settings")
                        .with(user(ownerPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "suggestionEnabled": true,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk());

        var session = chats.createSession(owner.getId(), "Suggestion");
        var started = chats.startRun(
                owner.getId(),
                session.id(),
                new StartRunCommand(
                        "以后回答请先给结论",
                        "zh",
                        "phase14c-test",
                        UUID.randomUUID().toString()
                ),
                memorySuggestions.runtimeSnapshot()
        ).orElseThrow();
        jdbc.update(
                """
                UPDATE chat_runs
                SET status = 'COMPLETED',
                    memory_suggestion_requested_at = CURRENT_TIMESTAMP,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                started.run().id()
        );
        jdbc.update(
                """
                UPDATE chat_messages
                SET status = 'COMPLETED', content = '好的。'
                WHERE id = ?
                """,
                started.responseMessage().id()
        );

        memorySuggestions.reconcile();
        memorySuggestions.enqueue(owner.getId(), started.run().id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM memory_suggestion_jobs",
                Integer.class
        )).isEqualTo(1);

        var firstClaim = memorySuggestions.claim();
        assertThat(firstClaim).isNotNull();
        assertThat(firstClaim.leaseToken()).isNotNull();
        assertThat(memorySuggestions.heartbeat(firstClaim)).isTrue();
        jdbc.update(
                """
                UPDATE memory_suggestion_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                firstClaim.id()
        );
        var claim = memorySuggestions.claim();
        assertThat(claim).isNotNull();
        assertThat(claim.id()).isEqualTo(firstClaim.id());
        assertThat(claim.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
        assertThat(claim.attemptCount())
                .isEqualTo(firstClaim.attemptCount() + 1);
        assertThat(memorySuggestions.heartbeat(firstClaim)).isFalse();
        assertThat(memorySuggestions.heartbeat(claim)).isTrue();
        var proposed = List.of(new MemorySuggestionProvider.Suggestion(
                "USER_PREFERENCE",
                "回答顺序",
                "回答时先给结论"
        ));
        memorySuggestions.complete(claim, proposed);

        List<MemoryContracts.MemoryItemView> repeated =
                memories.createSuggestions(
                        owner.getId(),
                        session.id(),
                        started.requestMessage().id(),
                        claim.id(),
                        claim.extractorVersion(),
                        claim.promptVersion(),
                        claim.inputHash(),
                        proposed
                );
        assertThat(repeated).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("CANDIDATE");
                    assertThat(item.origin()).isEqualTo("SUGGESTION");
                    assertThat(item.sourceCount()).isEqualTo(1);
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM memory_items",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT status || ':' || suggestion_count
                FROM memory_suggestion_jobs
                """,
                String.class
        )).isEqualTo("SUCCEEDED:1");
        assertThat(memorySuggestions.claim()).isNull();
        assertThat(memorySuggestions.states(
                owner.getId(),
                List.of(started.requestMessage().id())
        ).get(started.requestMessage().id()).suggestionCount()).isEqualTo(1);
        assertThat(memorySuggestions.states(
                other.getId(),
                List.of(started.requestMessage().id())
        )).isEmpty();
        mockMvc.perform(get(
                                "/api/v1/chat/sessions/{sessionId}/memory-suggestions",
                                session.id()
                        )
                        .with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].messageId").value(
                        started.requestMessage().id().toString()
                ))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].suggestionCount").value(1));
        mockMvc.perform(get(
                                "/api/v1/chat/sessions/{sessionId}/memory-suggestions",
                                session.id()
                        )
                        .with(user(principal(other))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                                "/api/v1/memories/{id}/confirm",
                                repeated.getFirst().id()
                        )
                        .with(user(ownerPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"用户确认\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void deletedChatSourceBecomesTombstoneAndCandidateCannotBeConfirmed()
            throws Exception {
        UserEntity owner = createUser("memory-source-owner", UserRole.USER);
        PlatformUserPrincipal principal = principal(owner);
        var session = chats.createSession(owner.getId(), "Source");
        var started = chats.startRun(
                owner.getId(),
                session.id(),
                new StartRunCommand(
                        "请记住我偏好简短回答",
                        "zh",
                        "phase14-source-test",
                        UUID.randomUUID().toString()
                )
        ).orElseThrow();
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "memoryType", "USER_PREFERENCE",
                "memoryKey", "回答长度",
                "content", "偏好简短回答",
                "candidate", true,
                "sources", java.util.List.of(java.util.Map.of(
                        "sourceType", "CHAT_MESSAGE",
                        "chatSessionId", session.id(),
                        "chatMessageId", started.requestMessage().id()
                ))
        ));
        MvcResult created = mockMvc.perform(post("/api/v1/memories")
                        .with(user(principal))
                        .with(csrf())
                        .header("Idempotency-Key", "deleted-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        UUID memoryId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsString()
        ).path("id").asText());
        jdbc.update(
                """
                UPDATE chat_runs
                SET status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                started.run().id()
        );
        jdbc.update(
                """
                UPDATE chat_messages
                SET status = 'COMPLETED', content = '好的。'
                WHERE id = ?
                """,
                started.responseMessage().id()
        );

        assertThat(chats.deleteSession(owner.getId(), session.id())).isTrue();

        mockMvc.perform(get("/api/v1/memories/{id}/sources", memoryId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceDeletedAt").isNotEmpty());
        mockMvc.perform(post("/api/v1/memories/{id}/confirm", memoryId)
                        .with(user(principal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"来源已删除后确认\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "MEMORY_SOURCE_DELETED"
                ));
    }

    private org.springframework.test.web.servlet.ResultActions createPreference(
            UserEntity user,
            String idempotencyKey,
            boolean candidate,
            String key,
            String content
    ) throws Exception {
        String request = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "memoryType", "USER_PREFERENCE",
                        "memoryKey", key,
                        "content", content,
                        "candidate", candidate,
                        "sources", java.util.List.of()
                )
        );
        return mockMvc.perform(post("/api/v1/memories")
                .with(user(principal(user)))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request));
    }

    private org.springframework.test.web.servlet.ResultActions action(
            UserEntity user,
            UUID memoryId,
            String action,
            String reason
    ) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/memories/{id}/{action}",
                        memoryId,
                        action
                )
                .with(user(principal(user)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("reason", reason)
                )));
    }

    private org.springframework.test.web.servlet.ResultActions forget(
            UserEntity user,
            UUID memoryId
    ) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/memories/{id}/forget",
                        memoryId
                )
                .with(user(principal(user)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "confirmation": "FORGET_MEMORY",
                          "reason": "用户请求忘记"
                        }
                        """));
    }

    private SourceFixture insertSourceFixture(
            UUID documentOwner,
            UUID reader
    ) {
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID parentChunkId = UUID.randomUUID();
        UUID childChunkId = UUID.randomUUID();
        UUID sourceSpanId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO documents (
                    id, owner_user_id, title, visibility
                ) VALUES (?, ?, 'Memory evidence', 'RESTRICTED')
                """,
                documentId,
                documentOwner
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash,
                    source_object_key, status, original_filename,
                    file_size_bytes, media_type
                ) VALUES (
                    ?, ?, 1, ?, ?, 'READY', 'memory.pdf',
                    100, 'application/pdf'
                )
                """,
                revisionId,
                documentId,
                HASH_A,
                "memory/" + revisionId + ".pdf"
        );
        insertChunk(
                parentChunkId,
                documentId,
                revisionId,
                null,
                "PARENT",
                "Parent evidence",
                false,
                HASH_A
        );
        insertChunk(
                childChunkId,
                documentId,
                revisionId,
                parentChunkId,
                "CHILD",
                "发布制度要求引用证据并完成人工复核。",
                true,
                HASH_B
        );
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (
                    ?, ?, ?, 1, 'PAGE', 'page:1',
                    '发布制度要求引用证据并完成人工复核。', ?,
                    'utf16-v1',
                    '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb
                )
                """,
                sourceUnitId,
                documentId,
                revisionId,
                HASH_B
        );
        jdbc.update(
                """
                INSERT INTO source_spans (
                    id, chunk_id, document_id, revision_id, span_order,
                    locator_kind, start_source_unit_id, end_source_unit_id,
                    start_offset, end_offset, source_text_hash,
                    chunk_start_offset, chunk_end_offset, locator_address,
                    normalization_version
                ) VALUES (
                    ?, ?, ?, ?, 0, 'PAGE', ?, ?, 0, 18, ?, 0, 18,
                    '{"kind":"PAGE","startPage":1,"endPage":1}'::jsonb,
                    'utf16-v1'
                )
                """,
                sourceSpanId,
                childChunkId,
                documentId,
                revisionId,
                sourceUnitId,
                sourceUnitId,
                HASH_B
        );
        jdbc.update(
                """
                UPDATE documents SET current_revision_id = ?
                WHERE id = ?
                """,
                revisionId,
                documentId
        );
        jdbc.update(
                """
                INSERT INTO document_acl_entries (id, document_id, user_id)
                VALUES (?, ?, ?)
                """,
                UUID.randomUUID(),
                documentId,
                reader
        );
        return new SourceFixture(
                documentId, revisionId, childChunkId, sourceSpanId
        );
    }

    private void insertChunk(
            UUID id,
            UUID documentId,
            UUID revisionId,
            UUID parentId,
            String type,
            String text,
            boolean searchable,
            String hash
    ) {
        jdbc.update(
                """
                INSERT INTO chunks (
                    id, document_id, revision_id, parent_chunk_id,
                    chunk_type, chunk_order, text, heading_path,
                    start_block_order, end_block_order, character_count,
                    token_count, token_counter_version,
                    chunking_profile_version, parser_version,
                    chunker_version, content_hash, searchable
                ) VALUES (
                    ?, ?, ?, ?, ?, 0, ?, '[]', 0, 0, char_length(?), 1,
                    'unicode-codepoint-v1', 'phase4-v1', 'test-parser',
                    'test-chunker', ?, ?
                )
                """,
                id,
                documentId,
                revisionId,
                parentId,
                type,
                text,
                text,
                hash,
                searchable
        );
    }

    private UserEntity createUser(String username, UserRole role) {
        return users.saveAndFlush(new UserEntity(
                username + "-" + UUID.randomUUID(),
                "test-password-hash",
                role
        ));
    }

    private static PlatformUserPrincipal principal(UserEntity user) {
        return PlatformUserPrincipal.from(user);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
    }

    private record SourceFixture(
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId
    ) {
    }
}
