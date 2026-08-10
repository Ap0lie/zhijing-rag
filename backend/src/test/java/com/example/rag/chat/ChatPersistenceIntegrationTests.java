package com.example.rag.chat;

import com.example.rag.chat.ChatPersistenceContracts.CitationDraft;
import com.example.rag.chat.ChatPersistenceContracts.RunCompletion;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChatPersistenceRepository.class)
class ChatPersistenceIntegrationTests {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private ChatPersistenceRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID otherUserId;

    @BeforeEach
    void setUpUsers() {
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        insertUser(ownerId, "chat-owner-" + ownerId, "USER");
        insertUser(otherUserId, "chat-other-" + otherUserId, "USER");
    }

    @Test
    void sessionRunAndRecoveryRemainOwnerScoped() {
        SourceFixture source = insertSourceFixture();
        var session = repository.createSession(ownerId, "Owner session");
        repository.createSession(otherUserId, "Other session");
        var started = repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand("What is grounded RAG?", "en", "phase7b-v1", "trace-" + UUID.randomUUID())
        ).orElseThrow();
        UUID citationId = UUID.randomUUID();
        repository.saveCitationWhitelist(
                ownerId,
                started.run().id(),
                List.of(new CitationDraft(
                        citationId,
                        source.documentId(),
                        source.revisionId(),
                        source.childChunkId(),
                        source.sourceSpanId()
                ))
        );

        assertThat(repository.listSessions(ownerId, 20, 0))
                .extracting(ChatPersistenceContracts.ChatSession::id)
                .containsExactly(session.id());
        assertThat(repository.findSessionDetail(otherUserId, session.id())).isEmpty();
        assertThat(repository.findRun(otherUserId, started.run().id())).isEmpty();
        assertThat(repository.findSessionDetail(ownerId, session.id()).orElseThrow().messages())
                .hasSize(2);

        assertThat(repository.recoverInterruptedRuns()).isEqualTo(1);
        var recovered = repository.findRun(ownerId, started.run().id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(RunStatus.FAILED);
        assertThat(recovered.errorCode()).isEqualTo("RUN_INTERRUPTED");
        assertThat(repository.listCitations(ownerId, started.run().id())).isEmpty();

        assertThat(repository.deleteSession(otherUserId, session.id())).isFalse();
        assertThat(repository.deleteSession(ownerId, session.id())).isTrue();
        assertThat(repository.findRun(ownerId, started.run().id())).isEmpty();
    }

    @Test
    void firstQuestionCreatesReadableTitleWithoutOverwritingManualTitle() {
        var automatic = repository.createSession(ownerId, "新对话");
        repository.startRun(
                ownerId,
                automatic.id(),
                new StartRunCommand(
                        "分别说明 TXT-SENTINEL-1501、MD-SENTINEL-1502 和 HTML-SENTINEL-1503 的状态",
                        "zh",
                        "phase12c-v1",
                        "trace-" + UUID.randomUUID()
                )
        ).orElseThrow();

        String automaticTitle = repository.findSession(
                        ownerId, automatic.id()
                )
                .orElseThrow()
                .title();
        assertThat(automaticTitle)
                .startsWith("分别说明 TXT-SENTINEL-1501")
                .endsWith("…");
        assertThat(automaticTitle.codePointCount(0, automaticTitle.length()))
                .isLessThanOrEqualTo(29);

        var manual = repository.createSession(ownerId, "我的发布核对");
        repository.startRun(
                ownerId,
                manual.id(),
                new StartRunCommand(
                        "这个标题不能覆盖人工命名",
                        "zh",
                        "phase12c-v1",
                        "trace-" + UUID.randomUUID()
                )
        ).orElseThrow();

        assertThat(repository.findSession(ownerId, manual.id()))
                .get()
                .extracting(ChatPersistenceContracts.ChatSession::title)
                .isEqualTo("我的发布核对");
    }

    @Test
    void secondActiveRunReturnsControlledConflict() {
        var session = repository.createSession(ownerId, "Single active run");
        repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand(
                        "First question",
                        "en",
                        "phase7b-v1",
                        "trace-" + UUID.randomUUID()
                )
        ).orElseThrow();

        assertThatThrownBy(() -> repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand(
                        "Second question",
                        "en",
                        "phase7b-v1",
                        "trace-" + UUID.randomUUID()
                )
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getCode()).isEqualTo("CHAT_RUN_ACTIVE");
        });

        assertThat(repository.listRuns(ownerId, session.id())).hasSize(1);
        assertThat(repository.listMessages(ownerId, session.id())).hasSize(2);
    }

    @Test
    void deepGlobalRequiresGlobalGraphAndPersistsRequestedStrategy() {
        var session = repository.createSession(ownerId, "Deep global");

        assertThatThrownBy(() -> repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand(
                        "Summarize all documents",
                        "en",
                        "phase10-stategraph-v1",
                        "trace-" + UUID.randomUUID(),
                        "HYBRID",
                        "DEEP_GLOBAL"
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GLOBAL_GRAPH");

        var started = repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand(
                        "Summarize all documents",
                        "en",
                        "phase10-stategraph-v1",
                        "trace-" + UUID.randomUUID(),
                        "GLOBAL_GRAPH",
                        "DEEP_GLOBAL"
                )
        ).orElseThrow();

        assertThat(started.run().graphModeRequested())
                .isEqualTo("GLOBAL_GRAPH");
        assertThat(started.run().answerStrategyRequested())
                .isEqualTo("DEEP_GLOBAL");
        assertThat(started.run().mapCallCount()).isZero();
        assertThat(started.run().reduceCallCount()).isZero();
    }

    @Test
    void citationWhitelistUsesServerIdAndExactSourceSpan() {
        SourceFixture source = insertSourceFixture();
        var session = repository.createSession(ownerId, "Citation session");
        var started = repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand("Explain the evidence", "en", "phase7b-v1", "trace-" + UUID.randomUUID())
        ).orElseThrow();
        UUID citationId = UUID.randomUUID();

        var citations = repository.saveCitationWhitelist(
                ownerId,
                started.run().id(),
                List.of(new CitationDraft(
                        citationId,
                        source.documentId(),
                        source.revisionId(),
                        source.childChunkId(),
                        source.sourceSpanId()
                ))
        );
        assertThat(citations).singleElement().extracting(ChatPersistenceContracts.Citation::id)
                .isEqualTo(citationId);

        assertThat(repository.finishRun(
                ownerId,
                started.run().id(),
                new RunCompletion(
                        RunStatus.COMPLETED,
                        "The answer is supported by the cited source.",
                        "en",
                        10,
                        "{}",
                        "BM25",
                        null,
                        null,
                        "[\"" + citationId + "\"]",
                        "[\"" + source.sourceSpanId() + "\"]",
                        "[]"
                )
        )).isTrue();

        var citation = repository.findCitation(ownerId, citationId).orElseThrow();
        assertThat(citation.sourceSpanId()).isEqualTo(source.sourceSpanId());
        assertThat(citation.startPage()).isEqualTo(1);
        assertThat(repository.findCitation(otherUserId, citationId)).isEmpty();
    }

    @Test
    void activeRunBlocksDeleteAndDeliveredTerminalCanBeAbandoned() {
        UUID evidenceId = UUID.randomUUID();
        UUID sourceSpanId = UUID.randomUUID();
        var session = repository.createSession(ownerId, "Cancellation session");
        var started = repository.startRun(
                ownerId,
                session.id(),
                new StartRunCommand(
                        "Question",
                        "en",
                        "phase7b-v1",
                        "trace-" + UUID.randomUUID()
                )
        ).orElseThrow();

        assertThat(repository.deleteSession(ownerId, session.id())).isFalse();
        assertThat(repository.finishRun(
                ownerId,
                started.run().id(),
                new RunCompletion(
                        RunStatus.REFUSED,
                        "Not enough evidence.",
                        "en",
                        null,
                        "{}",
                        "BM25",
                        null,
                        null,
                        "[\"" + evidenceId + "\"]",
                        "[\"" + sourceSpanId + "\"]",
                        "[]"
                )
        )).isTrue();
        assertThat(repository.abandonFinishedRun(
                ownerId,
                started.run().id(),
                RunStatus.CANCELLED,
                "STREAM_DISCONNECTED",
                "Client disconnected"
        )).isTrue();

        var abandoned = repository.findRun(ownerId, started.run().id()).orElseThrow();
        assertThat(abandoned.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(abandoned.finalEvidenceIdsJson()).isEqualTo("[]");
        assertThat(abandoned.finalSourceSpansJson()).isEqualTo("[]");
        var response = repository.listMessages(ownerId, session.id()).stream()
                .filter(message -> message.id().equals(started.responseMessage().id()))
                .findFirst()
                .orElseThrow();
        assertThat(response.content()).isEmpty();
        assertThat(response.status())
                .isEqualTo(ChatPersistenceContracts.MessageStatus.CANCELLED);
        assertThat(repository.deleteSession(ownerId, session.id())).isTrue();
    }

    private SourceFixture insertSourceFixture() {
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID parentChunkId = UUID.randomUUID();
        UUID childChunkId = UUID.randomUUID();
        UUID sourceSpanId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO documents (id, owner_user_id, title, visibility)
                VALUES (?, ?, 'Citation source', 'RESTRICTED')
                """,
                documentId,
                ownerId
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash, source_object_key,
                    status, original_filename, file_size_bytes, media_type
                ) VALUES (?, ?, 1, ?, 'citation/source.pdf', 'READY',
                          'source.pdf', 100, 'application/pdf')
                """,
                revisionId,
                documentId,
                HASH_A
        );
        insertChunk(parentChunkId, documentId, revisionId, null, "PARENT", "Parent", false, HASH_A);
        insertChunk(
                childChunkId,
                documentId,
                revisionId,
                parentChunkId,
                "CHILD",
                "Child evidence",
                true,
                HASH_B
        );
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (?, ?, ?, 1, 'PAGE', 'page:1', 'Child evidence',
                          ?, 'utf16-v1',
                          '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb)
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
                    start_offset, end_offset, chunk_start_offset,
                    chunk_end_offset, source_text_hash, locator_address,
                    normalization_version
                ) VALUES (
                    ?, ?, ?, ?, 0, 'PAGE', ?, ?, 0, 14, 0, 14, ?,
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
        return new SourceFixture(documentId, revisionId, childChunkId, sourceSpanId);
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
                    id, document_id, revision_id, parent_chunk_id, chunk_type,
                    chunk_order, text, heading_path, start_block_order, end_block_order,
                    character_count, token_count, token_counter_version,
                    chunking_profile_version, parser_version, chunker_version,
                    content_hash, searchable
                ) VALUES (?, ?, ?, ?, ?, 0, ?, '[]', 0, 0, char_length(?), 1,
                          'unicode-codepoint-v1', 'phase4-v1', 'test-parser',
                          'test-chunker', ?, ?)
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

    private void insertUser(UUID id, String username, String role) {
        jdbc.update(
                """
                INSERT INTO users (id, username, password_hash, role)
                VALUES (?, ?, 'test-password-hash', ?)
                """,
                id,
                username,
                role
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
