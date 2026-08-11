package com.example.rag.chat;

import com.example.rag.chat.ChatPersistenceContracts.ChatMessage;
import com.example.rag.chat.ChatPersistenceContracts.ChatRun;
import com.example.rag.chat.ChatPersistenceContracts.ChatSession;
import com.example.rag.chat.ChatPersistenceContracts.Citation;
import com.example.rag.chat.ChatPersistenceContracts.CitationDraft;
import com.example.rag.chat.ChatPersistenceContracts.AnswerStrategy;
import com.example.rag.chat.ChatPersistenceContracts.MessageRole;
import com.example.rag.chat.ChatPersistenceContracts.MessageStatus;
import com.example.rag.chat.ChatPersistenceContracts.RunCompletion;
import com.example.rag.chat.ChatPersistenceContracts.RunHistorySnapshot;
import com.example.rag.chat.ChatPersistenceContracts.RunQueryPlanSnapshot;
import com.example.rag.chat.ChatPersistenceContracts.RunRetrievalSnapshot;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.SessionDetail;
import com.example.rag.chat.ChatPersistenceContracts.SessionStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.common.ApiException;
import com.example.rag.memory.MemorySuggestionService.ExecutionSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ChatPersistenceRepository {

    private static final RowMapper<ChatSession> SESSION_MAPPER =
            (resultSet, rowNumber) -> mapSession(resultSet);
    private static final RowMapper<ChatMessage> MESSAGE_MAPPER =
            (resultSet, rowNumber) -> mapMessage(resultSet);
    private static final RowMapper<ChatRun> RUN_MAPPER =
            (resultSet, rowNumber) -> mapRun(resultSet);
    private static final RowMapper<Citation> CITATION_MAPPER =
            (resultSet, rowNumber) -> mapCitation(resultSet);

    private final JdbcTemplate jdbc;

    public ChatPersistenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ChatSession createSession(UUID ownerUserId, String title) {
        requireOwner(ownerUserId);
        String normalizedTitle = requireText(title, "title", 200);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO chat_sessions (id, owner_user_id, title)
                VALUES (?, ?, ?)
                """,
                id,
                ownerUserId,
                normalizedTitle
        );
        return findSession(ownerUserId, id).orElseThrow();
    }

    @Transactional
    public ChatSession createEvaluationSession(
            UUID ownerUserId,
            String title,
            UUID evaluationRunId,
            UUID evaluationCaseId
    ) {
        requireOwner(ownerUserId);
        String normalizedTitle = requireText(title, "title", 200);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO chat_sessions (
                    id, owner_user_id, title, purpose,
                    evaluation_run_id, evaluation_case_id
                ) VALUES (?, ?, ?, 'EVALUATION', ?, ?)
                """,
                id, ownerUserId, normalizedTitle,
                evaluationRunId, evaluationCaseId
        );
        return findSessionByPurpose(
                ownerUserId, id, "EVALUATION"
        ).orElseThrow();
    }

    @Transactional
    public ChatSession createEvaluationDrillSession(
            UUID ownerUserId,
            String title,
            UUID evaluationDrillId
    ) {
        requireOwner(ownerUserId);
        String normalizedTitle = requireText(title, "title", 200);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO chat_sessions (
                    id, owner_user_id, title, purpose,
                    evaluation_drill_id
                ) VALUES (?, ?, ?, 'EVALUATION', ?)
                """,
                id, ownerUserId, normalizedTitle, evaluationDrillId
        );
        return findSessionByPurpose(
                ownerUserId, id, "EVALUATION"
        ).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ChatSession> listSessions(UUID ownerUserId, int limit, int offset) {
        requireOwner(ownerUserId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        return jdbc.query(
                """
                SELECT id, owner_user_id, title, status, version, created_at, updated_at
                FROM chat_sessions
                WHERE owner_user_id = ? AND purpose = 'ONLINE'
                ORDER BY updated_at DESC, id
                LIMIT ? OFFSET ?
                """,
                SESSION_MAPPER,
                ownerUserId,
                safeLimit,
                safeOffset
        );
    }

    @Transactional(readOnly = true)
    public Optional<ChatSession> findSession(UUID ownerUserId, UUID sessionId) {
        return findSessionByPurpose(ownerUserId, sessionId, "ONLINE");
    }

    private Optional<ChatSession> findSessionByPurpose(
            UUID ownerUserId,
            UUID sessionId,
            String purpose
    ) {
        requireOwner(ownerUserId);
        return first(jdbc.query(
                """
                SELECT id, owner_user_id, title, status, version, created_at, updated_at
                FROM chat_sessions
                WHERE owner_user_id = ? AND id = ? AND purpose = ?
                """,
                SESSION_MAPPER,
                ownerUserId,
                sessionId,
                purpose
        ));
    }

    @Transactional(readOnly = true)
    public Optional<SessionDetail> findSessionDetail(UUID ownerUserId, UUID sessionId) {
        return findSession(ownerUserId, sessionId).map(session -> new SessionDetail(
                session,
                listMessages(ownerUserId, sessionId),
                listRuns(ownerUserId, sessionId)
        ));
    }

    @Transactional
    public boolean updateSessionTitle(UUID ownerUserId, UUID sessionId, String title) {
        requireOwner(ownerUserId);
        String normalizedTitle = requireText(title, "title", 200);
        return jdbc.update(
                """
                UPDATE chat_sessions
                SET title = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ?
                """,
                normalizedTitle,
                ownerUserId,
                sessionId
        ) == 1;
    }

    @Transactional
    public boolean deleteSession(UUID ownerUserId, UUID sessionId) {
        requireOwner(ownerUserId);
        return jdbc.update(
                """
                DELETE FROM chat_sessions session
                WHERE session.owner_user_id = ?
                  AND session.id = ?
                  AND NOT EXISTS (
                    SELECT 1
                    FROM chat_runs run
                    WHERE run.session_id = session.id
                      AND run.status IN ('PENDING', 'RUNNING')
                  )
                """,
                ownerUserId,
                sessionId
        ) == 1;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(UUID ownerUserId, UUID sessionId) {
        requireOwner(ownerUserId);
        return jdbc.query(
                """
                SELECT id, owner_user_id, session_id, sequence_number, role, content,
                       language, status, token_count, created_at, updated_at
                FROM chat_messages
                WHERE owner_user_id = ? AND session_id = ?
                ORDER BY sequence_number
                """,
                MESSAGE_MAPPER,
                ownerUserId,
                sessionId
        );
    }

    @Transactional(readOnly = true)
    List<HistoryEntry> recentHistory(
            UUID ownerUserId,
            UUID sessionId,
            int beforeSequence,
            int limit
    ) {
        requireOwner(ownerUserId);
        int safeLimit = Math.max(1, Math.min(limit, 48));
        return jdbc.query(
                """
                SELECT *
                FROM (
                    SELECT message.id, message.owner_user_id,
                           message.session_id, message.sequence_number,
                           message.role, message.content, message.language,
                           message.status, message.token_count,
                           message.created_at, message.updated_at,
                           run.id AS run_id, run.status AS run_status
                    FROM chat_messages message
                    LEFT JOIN chat_runs run
                      ON run.owner_user_id = message.owner_user_id
                     AND run.session_id = message.session_id
                     AND run.response_message_id = message.id
                    WHERE message.owner_user_id = ?
                      AND message.session_id = ?
                      AND message.sequence_number < ?
                      AND message.status = 'COMPLETED'
                      AND message.role IN ('USER', 'ASSISTANT')
                    ORDER BY message.sequence_number DESC
                    LIMIT ?
                ) recent
                ORDER BY sequence_number
                """,
                (rs, row) -> new HistoryEntry(
                        mapMessage(rs),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("run_status") == null
                                ? null
                                : RunStatus.valueOf(
                                rs.getString("run_status"))
                ),
                ownerUserId,
                sessionId,
                beforeSequence,
                safeLimit
        );
    }

    @Transactional(readOnly = true)
    List<HistoryEntry> historyWindow(
            UUID ownerUserId,
            UUID sessionId,
            int afterSequence,
            int beforeSequence,
            int limit
    ) {
        requireOwner(ownerUserId);
        int safeLimit = Math.max(1, Math.min(limit, 48));
        return jdbc.query(
                """
                SELECT message.id, message.owner_user_id,
                       message.session_id, message.sequence_number,
                       message.role, message.content, message.language,
                       message.status, message.token_count,
                       message.created_at, message.updated_at,
                       run.id AS run_id, run.status AS run_status
                FROM chat_messages message
                LEFT JOIN chat_runs run
                  ON run.owner_user_id = message.owner_user_id
                 AND run.session_id = message.session_id
                 AND run.response_message_id = message.id
                WHERE message.owner_user_id = ?
                  AND message.session_id = ?
                  AND message.sequence_number > ?
                  AND message.sequence_number < ?
                  AND message.status = 'COMPLETED'
                  AND message.role IN ('USER', 'ASSISTANT')
                ORDER BY message.sequence_number
                LIMIT ?
                """,
                (rs, row) -> new HistoryEntry(
                        mapMessage(rs),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("run_status") == null
                                ? null
                                : RunStatus.valueOf(
                                rs.getString("run_status"))
                ),
                ownerUserId,
                sessionId,
                Math.max(0, afterSequence),
                beforeSequence,
                safeLimit
        );
    }

    @Transactional(readOnly = true)
    public List<ChatRun> listRuns(UUID ownerUserId, UUID sessionId) {
        requireOwner(ownerUserId);
        return jdbc.query(
                runSelect() + """
                 WHERE owner_user_id = ? AND session_id = ?
                 ORDER BY created_at, id
                """,
                RUN_MAPPER,
                ownerUserId,
                sessionId
        );
    }

    @Transactional
    public Optional<StartedRun> startRun(
            UUID ownerUserId,
            UUID sessionId,
            StartRunCommand command
    ) {
        return startRun(ownerUserId, sessionId, command, null);
    }

    @Transactional
    public Optional<StartedRun> startRun(
            UUID ownerUserId,
            UUID sessionId,
            StartRunCommand command,
            ExecutionSnapshot suggestionSnapshot
    ) {
        requireOwner(ownerUserId);
        String question = requireText(command.question(), "question", 4_000);
        String language = requireText(command.language(), "language", 16);
        String orchestrationVersion =
                requireText(command.orchestrationVersion(), "orchestrationVersion", 64);
        String traceId = requireText(command.traceId(), "traceId", 64);
        String graphModeRequested =
                requireText(command.graphModeRequested(), "graphModeRequested", 16);
        String answerStrategyRequested = requireText(
                command.answerStrategyRequested(),
                "answerStrategyRequested",
                16
        );
        String queryProfileVersion = command.queryIntelligenceProfileVersion();
        if (queryProfileVersion != null) {
            queryProfileVersion = requireText(
                    queryProfileVersion,
                    "queryIntelligenceProfileVersion",
                    64
            );
        }
        String compressionPolicyVersion =
                command.contextCompressionPolicyVersion();
        if (compressionPolicyVersion != null) {
            compressionPolicyVersion = requireText(
                    compressionPolicyVersion,
                    "contextCompressionPolicyVersion",
                    64
            );
        }
        AnswerStrategy strategy = AnswerStrategy.valueOf(answerStrategyRequested);
        if (strategy == AnswerStrategy.DEEP_GLOBAL
                && !"GLOBAL_GRAPH".equals(graphModeRequested)) {
            throw new IllegalArgumentException(
                    "DEEP_GLOBAL requires GLOBAL_GRAPH"
            );
        }

        List<UUID> locked = jdbc.query(
                """
                SELECT id
                FROM chat_sessions
                WHERE owner_user_id = ? AND id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                ownerUserId,
                sessionId
        );
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Boolean hasActiveRun = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM chat_runs
                    WHERE owner_user_id = ?
                      AND session_id = ?
                      AND status IN ('PENDING', 'RUNNING')
                )
                """,
                Boolean.class,
                ownerUserId,
                sessionId
        );
        if (Boolean.TRUE.equals(hasActiveRun)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CHAT_RUN_ACTIVE",
                    "请先停止当前回答"
            );
        }
        Integer currentSequence = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(sequence_number), 0)
                FROM chat_messages
                WHERE owner_user_id = ? AND session_id = ?
                """,
                Integer.class,
                ownerUserId,
                sessionId
        );
        int requestSequence = currentSequence == null ? 1 : currentSequence + 1;
        if (requestSequence == 1) {
            jdbc.update(
                    """
                    UPDATE chat_sessions
                    SET title = ?, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE owner_user_id = ? AND id = ? AND title = '新对话'
                    """,
                    automaticTitle(question),
                    ownerUserId,
                    sessionId
            );
        }
        UUID requestMessageId = UUID.randomUUID();
        UUID responseMessageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        insertMessage(
                requestMessageId,
                ownerUserId,
                sessionId,
                requestSequence,
                MessageRole.USER,
                question,
                language,
                MessageStatus.COMPLETED
        );
        insertMessage(
                responseMessageId,
                ownerUserId,
                sessionId,
                requestSequence + 1,
                MessageRole.ASSISTANT,
                "",
                language,
                MessageStatus.STREAMING
        );
        jdbc.update(
                """
                INSERT INTO chat_runs (
                    id, owner_user_id, session_id, request_message_id,
                    response_message_id, orchestration_version, standalone_query,
                    trace_id, graph_mode_requested, answer_strategy_requested,
                    query_intelligence_profile_version,
                    context_compression_policy_version,
                    memory_suggestion_snapshot_schema,
                    memory_suggestion_extractor_version,
                    memory_suggestion_prompt_version,
                    memory_suggestion_provider_key,
                    memory_suggestion_model_id,
                    memory_suggestion_model_revision,
                    memory_suggestion_endpoint_identity,
                    memory_suggestion_prompt_hash,
                    status, started_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'RUNNING',
                    CURRENT_TIMESTAMP
                )
                """,
                runId,
                ownerUserId,
                sessionId,
                requestMessageId,
                responseMessageId,
                orchestrationVersion,
                question,
                traceId,
                graphModeRequested,
                answerStrategyRequested,
                queryProfileVersion,
                compressionPolicyVersion,
                suggestionSnapshot == null
                        ? 0
                        : suggestionSnapshot.schemaVersion(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.extractorVersion(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.promptVersion(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.providerKey(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.modelId(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.modelRevision(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.endpointIdentity(),
                suggestionSnapshot == null
                        ? null
                        : suggestionSnapshot.promptHash()
        );
        touchSession(ownerUserId, sessionId);
        return Optional.of(new StartedRun(
                findMessage(ownerUserId, requestMessageId).orElseThrow(),
                findMessage(ownerUserId, responseMessageId).orElseThrow(),
                findRun(ownerUserId, runId).orElseThrow()
        ));
    }

    private static String automaticTitle(String question) {
        String normalized = question.replaceAll("\\s+", " ").strip();
        int maxCodePoints = 28;
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end).stripTrailing() + "…";
    }

    @Transactional
    public void recordHistorySnapshot(
            UUID ownerUserId,
            UUID runId,
            RunHistorySnapshot snapshot
    ) {
        requireOwner(ownerUserId);
        String hash = requireText(
                snapshot.snapshotHash(), "historySnapshotHash", 64
        );
        if (!hash.matches("[0-9a-f]{64}")
                || snapshot.tokenCount() < 0
                || snapshot.tokenCount() > 2048) {
            throw new IllegalArgumentException(
                    "invalid history snapshot"
            );
        }
        int changed = jdbc.update(
                """
                UPDATE chat_runs
                SET history_message_ids = CAST(? AS JSONB),
                    history_snapshot_hash = ?,
                    history_counter_version = ?,
                    history_token_count = ?,
                    history_trim_reasons = CAST(? AS JSONB),
                    history_summary_id = ?,
                    history_summary_token_count = ?,
                    history_summary_source_count = ?,
                    context_compression_status = ?,
                    context_compression_reason_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ?
                  AND id = ?
                  AND status = 'RUNNING'
                  AND (query_intelligence_profile_version IS NOT NULL
                       OR context_compression_policy_version IS NOT NULL)
                """,
                jsonOrDefault(snapshot.messageIdsJson(), "[]"),
                hash,
                requireText(
                        snapshot.counterVersion(),
                        "historyCounterVersion",
                        64
                ),
                snapshot.tokenCount(),
                jsonOrDefault(snapshot.trimReasonsJson(), "[]"),
                snapshot.summaryId(),
                snapshot.summaryTokenCount(),
                snapshot.summarySourceCount(),
                snapshot.compressionStatus(),
                snapshot.compressionReasonCode(),
                ownerUserId,
                runId
        );
        if (changed != 1) {
            throw new IllegalStateException(
                    "running chat run has no frozen history policy"
            );
        }
    }

    @Transactional
    public void recordQueryPlanSnapshot(
            UUID ownerUserId,
            UUID runId,
            RunQueryPlanSnapshot snapshot
    ) {
        requireOwner(ownerUserId);
        String standalone = requireText(
                snapshot.standaloneQuery(), "standaloneQuery", 500
        );
        int changed = jdbc.update(
                """
                UPDATE chat_runs
                SET standalone_query = ?,
                    sub_queries = CAST(? AS JSONB),
                    budget_usage = CAST(? AS JSONB),
                    fallback_path = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ?
                  AND id = ?
                  AND status = 'RUNNING'
                """,
                standalone,
                jsonOrDefault(snapshot.querySlotsJson(), "[]"),
                jsonOrDefault(snapshot.budgetUsageJson(), "{}"),
                snapshot.fallbackPath(),
                ownerUserId,
                runId
        );
        if (changed != 1) {
            throw new IllegalStateException(
                    "chat run is no longer active"
            );
        }
    }

    @Transactional(readOnly = true)
    public Optional<ChatRun> findRun(UUID ownerUserId, UUID runId) {
        requireOwner(ownerUserId);
        return first(jdbc.query(
                runSelect() + " WHERE owner_user_id = ? AND id = ?",
                RUN_MAPPER,
                ownerUserId,
                runId
        ));
    }

    @Transactional
    public void recordRetrievalSnapshot(
            UUID ownerUserId,
            UUID runId,
            RunRetrievalSnapshot snapshot
    ) {
        requireOwner(ownerUserId);
        jdbc.update(
                """
                UPDATE chat_runs
                SET fallback_path = ?,
                    retrieval_profile_version = ?,
                    index_generation = ?,
                    graph_profile_version = ?,
                    graph_generation = ?,
                    graph_mode_requested = ?,
                    graph_mode_used = ?,
                    graph_degraded = ?,
                    graph_degradation_code = ?,
                    global_config_version = ?,
                    global_generation = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ? AND status = 'RUNNING'
                """,
                snapshot.fallbackPath(),
                snapshot.retrievalProfileVersion(),
                snapshot.indexGeneration(),
                snapshot.graphProfileVersion(),
                snapshot.graphGeneration(),
                snapshot.graphModeRequested(),
                snapshot.graphModeUsed(),
                snapshot.graphDegraded(),
                snapshot.graphDegradationCode(),
                snapshot.globalConfigVersion(),
                snapshot.globalGeneration(),
                ownerUserId,
                runId
        );
    }

    @Transactional
    public void recordAnswerProgress(
            UUID ownerUserId,
            UUID runId,
            AnswerStrategy strategyUsed,
            int mapCallCount,
            int reduceCallCount
    ) {
        requireOwner(ownerUserId);
        if (mapCallCount < 0 || mapCallCount > 8
                || reduceCallCount < 0 || reduceCallCount > 1) {
            throw new IllegalArgumentException("answer call count exceeds budget");
        }
        int changed = jdbc.update(
                """
                UPDATE chat_runs
                SET answer_strategy_used = ?,
                    map_call_count = ?,
                    reduce_call_count = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ? AND status = 'RUNNING'
                """,
                strategyUsed == null ? null : strategyUsed.name(),
                mapCallCount,
                reduceCallCount,
                ownerUserId,
                runId
        );
        if (changed != 1) {
            throw new IllegalStateException("chat run is no longer active");
        }
    }

    @Transactional
    public List<Citation> saveCitationWhitelist(
            UUID ownerUserId,
            UUID runId,
            List<CitationDraft> drafts
    ) {
        requireOwner(ownerUserId);
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        RunPointer pointer = first(jdbc.query(
                """
                SELECT session_id, response_message_id
                FROM chat_runs
                WHERE owner_user_id = ? AND id = ? AND status = 'RUNNING'
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new RunPointer(
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getObject("response_message_id", UUID.class)
                ),
                ownerUserId,
                runId
        )).orElseThrow(() -> new NoSuchElementException("running chat run not found"));

        Set<UUID> citationIds = new HashSet<>();
        Set<UUID> sourceSpanIds = new HashSet<>();
        for (int index = 0; index < drafts.size(); index++) {
            CitationDraft draft = drafts.get(index);
            if (draft == null
                    || draft.id() == null
                    || draft.documentId() == null
                    || draft.revisionId() == null
                    || draft.childChunkId() == null
                    || draft.sourceSpanId() == null) {
                throw new IllegalArgumentException("citation draft identifiers are required");
            }
            if (!citationIds.add(draft.id())) {
                throw new IllegalArgumentException("duplicate citation id");
            }
            if (!sourceSpanIds.add(draft.sourceSpanId())) {
                throw new IllegalArgumentException("duplicate sourceSpanId");
            }
            jdbc.update(
                    """
                    INSERT INTO citations (
                        id, owner_user_id, session_id, run_id, message_id,
                        document_id, revision_id, child_chunk_id, source_span_id,
                        citation_order
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    draft.id(),
                    ownerUserId,
                    pointer.sessionId(),
                    runId,
                    pointer.responseMessageId(),
                    draft.documentId(),
                    draft.revisionId(),
                    draft.childChunkId(),
                    draft.sourceSpanId(),
                    index
            );
        }
        return listCitations(ownerUserId, runId);
    }

    @Transactional(readOnly = true)
    public List<Citation> listCitations(UUID ownerUserId, UUID runId) {
        requireOwner(ownerUserId);
        return jdbc.query(
                citationSelect() + """
                 WHERE citation.owner_user_id = ? AND citation.run_id = ?
                 ORDER BY citation.citation_order
                """,
                CITATION_MAPPER,
                ownerUserId,
                runId
        );
    }

    @Transactional(readOnly = true)
    public Optional<Citation> findCitation(UUID ownerUserId, UUID citationId) {
        requireOwner(ownerUserId);
        return first(jdbc.query(
                citationSelect() + """
                 JOIN chat_runs run
                   ON run.id = citation.run_id
                  AND run.owner_user_id = citation.owner_user_id
                 WHERE citation.owner_user_id = ?
                   AND citation.id = ?
                   AND run.status IN ('COMPLETED', 'REFUSED')
                """,
                CITATION_MAPPER,
                ownerUserId,
                citationId
        ));
    }

    @Transactional
    public boolean finishRun(UUID ownerUserId, UUID runId, RunCompletion completion) {
        requireOwner(ownerUserId);
        if (completion.status() != RunStatus.COMPLETED
                && completion.status() != RunStatus.REFUSED) {
            throw new IllegalArgumentException("finishRun requires COMPLETED or REFUSED");
        }
        String responseContent =
                requireText(completion.responseContent(), "responseContent", 100_000);
        String language = requireText(completion.language(), "language", 16);
        if (completion.tokenCount() != null && completion.tokenCount() < 0) {
            throw new IllegalArgumentException("tokenCount must not be negative");
        }
        if (completion.mapCallCount() < 0 || completion.mapCallCount() > 8
                || completion.reduceCallCount() < 0
                || completion.reduceCallCount() > 1) {
            throw new IllegalArgumentException("answer call count exceeds budget");
        }
        AnswerStrategy requestedStrategy = AnswerStrategy.valueOf(
                requireText(
                        completion.answerStrategyRequested(),
                        "answerStrategyRequested",
                        16
                )
        );
        AnswerStrategy usedStrategy = AnswerStrategy.valueOf(
                requireText(
                        completion.answerStrategyUsed(),
                        "answerStrategyUsed",
                        16
                )
        );
        if (usedStrategy == AnswerStrategy.DEEP_GLOBAL
                && (requestedStrategy != AnswerStrategy.DEEP_GLOBAL
                || completion.mapCallCount() == 0
                || completion.reduceCallCount() != 1)) {
            throw new IllegalArgumentException(
                    "invalid DEEP_GLOBAL completion"
            );
        }
        List<RunPointer> pointers = jdbc.query(
                """
                UPDATE chat_runs
                SET status = ?,
                    budget_usage = CAST(? AS jsonb),
                    fallback_path = ?,
                    retrieval_profile_version = ?,
                    index_generation = ?,
                    graph_profile_version = ?,
                    graph_generation = ?,
                    graph_mode_requested = ?,
                    graph_mode_used = ?,
                    graph_degraded = ?,
                    graph_degradation_code = ?,
                    global_config_version = ?,
                    global_generation = ?,
                    answer_strategy_requested = ?,
                    answer_strategy_used = ?,
                    map_call_count = ?,
                    reduce_call_count = ?,
                    final_evidence_ids = CAST(? AS jsonb),
                    final_source_spans = CAST(? AS jsonb),
                    trim_reasons = CAST(? AS jsonb),
                    memory_suggestion_requested_at = CASE
                        WHEN EXISTS (
                            SELECT 1
                            FROM user_memory_settings settings
                            WHERE settings.user_id = chat_runs.owner_user_id
                              AND settings.suggestion_enabled
                        )
                        AND chat_runs.memory_suggestion_snapshot_schema = 1
                        THEN CURRENT_TIMESTAMP
                        ELSE NULL
                    END,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ? AND status = 'RUNNING'
                RETURNING session_id, response_message_id
                """,
                (resultSet, rowNumber) -> new RunPointer(
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getObject("response_message_id", UUID.class)
                ),
                completion.status().name(),
                jsonOrDefault(completion.budgetUsageJson(), "{}"),
                completion.fallbackPath(),
                completion.retrievalProfileVersion(),
                completion.indexGeneration(),
                completion.graphProfileVersion(),
                completion.graphGeneration(),
                completion.graphModeRequested(),
                completion.graphModeUsed(),
                completion.graphDegraded(),
                completion.graphDegradationCode(),
                completion.globalConfigVersion(),
                completion.globalGeneration(),
                completion.answerStrategyRequested(),
                completion.answerStrategyUsed(),
                completion.mapCallCount(),
                completion.reduceCallCount(),
                jsonOrDefault(completion.finalEvidenceIdsJson(), "[]"),
                jsonOrDefault(completion.finalSourceSpansJson(), "[]"),
                jsonOrDefault(completion.trimReasonsJson(), "[]"),
                ownerUserId,
                runId
        );
        if (pointers.isEmpty()) {
            return false;
        }
        RunPointer pointer = pointers.getFirst();
        jdbc.update(
                """
                UPDATE chat_messages
                SET content = ?, language = ?, status = 'COMPLETED',
                    token_count = ?, updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND session_id = ? AND id = ?
                """,
                responseContent,
                language,
                completion.tokenCount(),
                ownerUserId,
                pointer.sessionId(),
                pointer.responseMessageId()
        );
        touchSession(ownerUserId, pointer.sessionId());
        return true;
    }

    @Transactional
    public boolean failRun(
            UUID ownerUserId,
            UUID runId,
            RunStatus status,
            String errorCode,
            String errorDetail
    ) {
        requireOwner(ownerUserId);
        if (status != RunStatus.FAILED && status != RunStatus.CANCELLED) {
            throw new IllegalArgumentException("failRun requires FAILED or CANCELLED");
        }
        String normalizedCode = requireText(
                errorCode == null && status == RunStatus.CANCELLED ? "CANCELLED" : errorCode,
                "errorCode",
                64
        );
        String normalizedDetail =
                errorDetail == null ? null : requireText(errorDetail, "errorDetail", 500);
        List<RunPointer> pointers = jdbc.query(
                """
                UPDATE chat_runs
                SET status = ?, error_code = ?, error_detail = ?,
                    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ? AND status = 'RUNNING'
                RETURNING session_id, response_message_id
                """,
                (resultSet, rowNumber) -> new RunPointer(
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getObject("response_message_id", UUID.class)
                ),
                status.name(),
                normalizedCode,
                normalizedDetail,
                ownerUserId,
                runId
        );
        if (pointers.isEmpty()) {
            return false;
        }
        RunPointer pointer = pointers.getFirst();
        jdbc.update(
                "DELETE FROM citations WHERE owner_user_id = ? AND run_id = ?",
                ownerUserId,
                runId
        );
        jdbc.update(
                """
                UPDATE chat_messages
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND session_id = ? AND id = ?
                """,
                status == RunStatus.CANCELLED
                        ? MessageStatus.CANCELLED.name()
                        : MessageStatus.FAILED.name(),
                ownerUserId,
                pointer.sessionId(),
                pointer.responseMessageId()
        );
        touchSession(ownerUserId, pointer.sessionId());
        return true;
    }

    @Transactional
    public boolean abandonFinishedRun(
            UUID ownerUserId,
            UUID runId,
            RunStatus status,
            String errorCode,
            String errorDetail
    ) {
        requireOwner(ownerUserId);
        if (status != RunStatus.FAILED && status != RunStatus.CANCELLED) {
            throw new IllegalArgumentException(
                    "abandonFinishedRun requires FAILED or CANCELLED"
            );
        }
        String normalizedCode = requireText(errorCode, "errorCode", 64);
        String normalizedDetail =
                errorDetail == null ? null : requireText(errorDetail, "errorDetail", 500);
        List<RunPointer> pointers = jdbc.query(
                """
                UPDATE chat_runs
                SET status = ?, error_code = ?, error_detail = ?,
                    final_evidence_ids = '[]'::jsonb,
                    final_source_spans = '[]'::jsonb,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ?
                  AND status IN ('COMPLETED', 'REFUSED')
                RETURNING session_id, response_message_id
                """,
                (resultSet, rowNumber) -> new RunPointer(
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getObject("response_message_id", UUID.class)
                ),
                status.name(),
                normalizedCode,
                normalizedDetail,
                ownerUserId,
                runId
        );
        if (pointers.isEmpty()) {
            return false;
        }
        RunPointer pointer = pointers.getFirst();
        jdbc.update(
                "DELETE FROM citations WHERE owner_user_id = ? AND run_id = ?",
                ownerUserId,
                runId
        );
        jdbc.update(
                """
                UPDATE chat_messages
                SET content = '', status = ?, token_count = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND session_id = ? AND id = ?
                """,
                status == RunStatus.CANCELLED
                        ? MessageStatus.CANCELLED.name()
                        : MessageStatus.FAILED.name(),
                ownerUserId,
                pointer.sessionId(),
                pointer.responseMessageId()
        );
        touchSession(ownerUserId, pointer.sessionId());
        return true;
    }

    @Transactional
    public int recoverInterruptedRuns() {
        jdbc.update(
                """
                DELETE FROM citations citation
                USING chat_runs run
                WHERE citation.owner_user_id = run.owner_user_id
                  AND citation.run_id = run.id
                  AND run.status = 'RUNNING'
                """
        );
        jdbc.update(
                """
                UPDATE chat_messages message
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                FROM chat_runs run
                WHERE run.response_message_id = message.id
                  AND run.status = 'RUNNING'
                  AND message.status IN ('PENDING', 'STREAMING')
                """
        );
        return jdbc.update(
                """
                UPDATE chat_runs
                SET status = 'FAILED',
                    error_code = 'RUN_INTERRUPTED',
                    error_detail = 'Run was interrupted by backend restart',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                """
        );
    }

    private Optional<ChatMessage> findMessage(UUID ownerUserId, UUID messageId) {
        return first(jdbc.query(
                """
                SELECT id, owner_user_id, session_id, sequence_number, role, content,
                       language, status, token_count, created_at, updated_at
                FROM chat_messages
                WHERE owner_user_id = ? AND id = ?
                """,
                MESSAGE_MAPPER,
                ownerUserId,
                messageId
        ));
    }

    private void insertMessage(
            UUID id,
            UUID ownerUserId,
            UUID sessionId,
            int sequenceNumber,
            MessageRole role,
            String content,
            String language,
            MessageStatus status
    ) {
        jdbc.update(
                """
                INSERT INTO chat_messages (
                    id, owner_user_id, session_id, sequence_number,
                    role, content, language, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                ownerUserId,
                sessionId,
                sequenceNumber,
                role.name(),
                content,
                language,
                status.name()
        );
    }

    private void touchSession(UUID ownerUserId, UUID sessionId) {
        jdbc.update(
                """
                UPDATE chat_sessions
                SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE owner_user_id = ? AND id = ?
                """,
                ownerUserId,
                sessionId
        );
    }

    private static String runSelect() {
        return """
                SELECT id, owner_user_id, session_id, request_message_id,
                       response_message_id, orchestration_version, standalone_query,
                       sub_queries::text AS sub_queries_json,
                       budget_usage::text AS budget_usage_json,
                       fallback_path, retrieval_profile_version, index_generation,
                       graph_profile_version, graph_generation,
                       graph_mode_requested, graph_mode_used, graph_degraded,
                       graph_degradation_code, global_config_version,
                       global_generation, answer_strategy_requested,
                       answer_strategy_used, map_call_count, reduce_call_count,
                       final_evidence_ids::text AS final_evidence_ids_json,
                       final_source_spans::text AS final_source_spans_json,
                       trim_reasons::text AS trim_reasons_json,
                       query_intelligence_profile_version,
                       history_message_ids::text AS history_message_ids_json,
                       history_snapshot_hash, history_counter_version,
                       history_token_count,
                       history_trim_reasons::text AS history_trim_reasons_json,
                       context_compression_policy_version,
                       history_summary_id, history_summary_token_count,
                       history_summary_source_count,
                       context_compression_status,
                       context_compression_reason_code,
                       trace_id, status, error_code, error_detail, created_at,
                       started_at, completed_at, updated_at
                FROM chat_runs
                """;
    }

    private static String citationSelect() {
        return """
                SELECT citation.id, citation.owner_user_id, citation.session_id,
                       citation.run_id, citation.message_id, citation.document_id,
                       citation.revision_id, citation.child_chunk_id,
                       citation.source_span_id, citation.citation_order,
                       location.start_page, location.end_page,
                       location.start_offset, location.end_offset,
                       location.source_text_hash, citation.created_at
                FROM citations citation
                JOIN source_spans span
                  ON span.id = citation.source_span_id
                 AND span.chunk_id = citation.child_chunk_id
                 AND span.document_id = citation.document_id
                 AND span.revision_id = citation.revision_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                """;
    }

    private static ChatSession mapSession(ResultSet resultSet) throws SQLException {
        return new ChatSession(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getString("title"),
                SessionStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static ChatMessage mapMessage(ResultSet resultSet) throws SQLException {
        return new ChatMessage(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getInt("sequence_number"),
                MessageRole.valueOf(resultSet.getString("role")),
                resultSet.getString("content"),
                resultSet.getString("language"),
                MessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("token_count", Integer.class),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static ChatRun mapRun(ResultSet resultSet) throws SQLException {
        return new ChatRun(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getObject("request_message_id", UUID.class),
                resultSet.getObject("response_message_id", UUID.class),
                resultSet.getString("orchestration_version"),
                resultSet.getString("standalone_query"),
                resultSet.getString("sub_queries_json"),
                resultSet.getString("budget_usage_json"),
                resultSet.getString("fallback_path"),
                resultSet.getString("retrieval_profile_version"),
                resultSet.getObject("index_generation", Long.class),
                resultSet.getString("graph_profile_version"),
                resultSet.getObject("graph_generation", Long.class),
                resultSet.getString("graph_mode_requested"),
                resultSet.getString("graph_mode_used"),
                resultSet.getBoolean("graph_degraded"),
                resultSet.getString("graph_degradation_code"),
                resultSet.getString("global_config_version"),
                resultSet.getObject("global_generation", Long.class),
                resultSet.getString("answer_strategy_requested"),
                resultSet.getString("answer_strategy_used"),
                resultSet.getInt("map_call_count"),
                resultSet.getInt("reduce_call_count"),
                resultSet.getString("final_evidence_ids_json"),
                resultSet.getString("final_source_spans_json"),
                resultSet.getString("trim_reasons_json"),
                resultSet.getString(
                        "query_intelligence_profile_version"
                ),
                resultSet.getString("history_message_ids_json"),
                resultSet.getString("history_snapshot_hash"),
                resultSet.getString("history_counter_version"),
                resultSet.getInt("history_token_count"),
                resultSet.getString("history_trim_reasons_json"),
                resultSet.getString("context_compression_policy_version"),
                resultSet.getObject("history_summary_id", UUID.class),
                resultSet.getInt("history_summary_token_count"),
                resultSet.getInt("history_summary_source_count"),
                resultSet.getString("context_compression_status"),
                resultSet.getString("context_compression_reason_code"),
                resultSet.getString("trace_id"),
                RunStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("error_code"),
                resultSet.getString("error_detail"),
                instant(resultSet, "created_at"),
                nullableInstant(resultSet, "started_at"),
                nullableInstant(resultSet, "completed_at"),
                instant(resultSet, "updated_at")
        );
    }

    private static Citation mapCitation(ResultSet resultSet) throws SQLException {
        return new Citation(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getObject("message_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getObject("child_chunk_id", UUID.class),
                resultSet.getObject("source_span_id", UUID.class),
                resultSet.getInt("citation_order"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getInt("start_offset"),
                resultSet.getInt("end_offset"),
                resultSet.getString("source_text_hash"),
                instant(resultSet, "created_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T> Optional<T> first(List<T> values) {
        return values.stream().findFirst();
    }

    private static void requireOwner(UUID ownerUserId) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("ownerUserId is required");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static String jsonOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    record HistoryEntry(
            ChatMessage message,
            UUID runId,
            RunStatus runStatus
    ) {
    }

    private record RunPointer(UUID sessionId, UUID responseMessageId) {
    }
}
