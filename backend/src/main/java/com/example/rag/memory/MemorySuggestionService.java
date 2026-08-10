package com.example.rag.memory;

import com.example.rag.memory.MemoryContracts.MemoryItemView;
import com.example.rag.memory.MemorySuggestionProvider.Suggestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true"
)
public class MemorySuggestionService {

    private final JdbcTemplate jdbc;
    private final MemoryService memories;
    private final MemorySuggestionProperties properties;
    private final MemorySuggestionProvider provider;

    MemorySuggestionService(
            JdbcTemplate jdbc,
            MemoryService memories,
            MemorySuggestionProperties properties,
            MemorySuggestionProvider provider
    ) {
        this.jdbc = jdbc;
        this.memories = memories;
        this.properties = properties;
        this.provider = provider;
    }

    public ExecutionSnapshot runtimeSnapshot() {
        return provider.snapshot();
    }

    @Transactional
    public void enqueue(UUID owner, UUID runId) {
        List<SourceMessage> sources = jdbc.query(
                """
                SELECT run.session_id, run.request_message_id, message.content,
                       run.memory_suggestion_snapshot_schema,
                       run.memory_suggestion_extractor_version,
                       run.memory_suggestion_prompt_version,
                       run.memory_suggestion_provider_key,
                       run.memory_suggestion_model_id,
                       run.memory_suggestion_model_revision,
                       run.memory_suggestion_endpoint_identity,
                       run.memory_suggestion_prompt_hash
                FROM chat_runs run
                JOIN chat_messages message
                  ON message.id = run.request_message_id
                 AND message.session_id = run.session_id
                 AND message.owner_user_id = run.owner_user_id
                WHERE run.id = ?
                  AND run.owner_user_id = ?
                  AND run.status IN ('COMPLETED', 'REFUSED')
                  AND run.memory_suggestion_requested_at IS NOT NULL
                  AND message.role = 'USER'
                  AND message.status = 'COMPLETED'
                """,
                (rs, row) -> new SourceMessage(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("request_message_id", UUID.class),
                        rs.getString("content"),
                        snapshot(rs)
                ),
                runId,
                owner
        );
        if (sources.size() != 1) {
            return;
        }
        SourceMessage source = sources.getFirst();
        String inputHash = hash(source.content());
        if (source.snapshot() == null) {
            insertLegacyFailure(owner, runId, source, inputHash);
            return;
        }
        ExecutionSnapshot snapshot = source.snapshot();
        jdbc.update(
                """
                INSERT INTO memory_suggestion_jobs (
                    id, owner_user_id, run_id, session_id,
                    source_message_id, extractor_version, prompt_version,
                    input_hash, max_attempts, snapshot_schema_version,
                    provider_key, model_id, model_revision,
                    endpoint_identity, prompt_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, owner_user_id) DO NOTHING
                """,
                UUID.randomUUID(),
                owner,
                runId,
                source.sessionId(),
                source.messageId(),
                snapshot.extractorVersion(),
                snapshot.promptVersion(),
                inputHash,
                properties.maxAttempts(),
                snapshot.schemaVersion(),
                snapshot.providerKey(),
                snapshot.modelId(),
                snapshot.modelRevision(),
                snapshot.endpointIdentity(),
                snapshot.promptHash()
        );
    }

    private void insertLegacyFailure(
            UUID owner,
            UUID runId,
            SourceMessage source,
            String inputHash
    ) {
        jdbc.update(
                """
                INSERT INTO memory_suggestion_jobs (
                    id, owner_user_id, run_id, session_id,
                    source_message_id, extractor_version, prompt_version,
                    input_hash, max_attempts, snapshot_schema_version,
                    status, error_code, error_detail, completed_at
                ) VALUES (
                    ?, ?, ?, ?, ?, 'legacy', 'legacy', ?, ?, 0,
                    'FAILED', 'MEMORY_SUGGESTION_LEGACY_SNAPSHOT_MISSING',
                    '旧问答未冻结记忆建议执行配置', CURRENT_TIMESTAMP
                )
                ON CONFLICT (run_id, owner_user_id) DO NOTHING
                """,
                UUID.randomUUID(),
                owner,
                runId,
                source.sessionId(),
                source.messageId(),
                inputHash,
                properties.maxAttempts()
        );
    }

    @Transactional
    void reconcile() {
        List<RunIntent> intents = jdbc.query(
                """
                SELECT run.owner_user_id, run.id
                FROM chat_runs run
                WHERE run.memory_suggestion_requested_at IS NOT NULL
                  AND run.status IN ('COMPLETED', 'REFUSED')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM memory_suggestion_jobs job
                      WHERE job.run_id = run.id
                        AND job.owner_user_id = run.owner_user_id
                  )
                ORDER BY run.memory_suggestion_requested_at, run.id
                LIMIT 50
                """,
                (rs, row) -> new RunIntent(
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("id", UUID.class)
                )
        );
        intents.forEach(intent -> enqueue(intent.owner(), intent.runId()));
    }

    @Transactional
    ClaimedJob claim() {
        jdbc.update(
                """
                UPDATE memory_suggestion_jobs job
                SET status = 'SKIPPED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    lease_token = NULL,
                    error_code = 'MEMORY_SUGGESTION_DISABLED',
                    error_detail = '用户已关闭建议或来源不再有效',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE (
                        job.status = 'PENDING'
                        OR (
                            job.status = 'RUNNING'
                            AND job.lease_expires_at < CURRENT_TIMESTAMP
                        )
                    )
                  AND (
                      NOT EXISTS (
                          SELECT 1
                          FROM users owner
                          JOIN user_memory_settings settings
                            ON settings.user_id = owner.id
                           AND settings.suggestion_enabled
                          WHERE owner.id = job.owner_user_id
                            AND owner.enabled
                      )
                      OR NOT EXISTS (
                          SELECT 1
                          FROM chat_runs run
                          WHERE run.id = job.run_id
                            AND run.owner_user_id = job.owner_user_id
                            AND run.status IN ('COMPLETED', 'REFUSED')
                      )
                      OR NOT EXISTS (
                          SELECT 1
                          FROM chat_messages message
                          WHERE message.id = job.source_message_id
                            AND message.session_id = job.session_id
                            AND message.owner_user_id = job.owner_user_id
                            AND message.role = 'USER'
                            AND message.status = 'COMPLETED'
                      )
                  )
                """
        );
        jdbc.update(
                """
                UPDATE memory_suggestion_jobs
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    lease_token = NULL,
                    error_code = 'MEMORY_SUGGESTION_WORKER_INTERRUPTED',
                    error_detail = '记忆建议任务在租约到期前未完成',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                  AND lease_expires_at < CURRENT_TIMESTAMP
                  AND attempt_count >= max_attempts
                """
        );
        UUID leaseToken = UUID.randomUUID();
        List<ClaimedJob> claims = jdbc.query(
                """
                WITH candidate AS (
                    SELECT id
                    FROM memory_suggestion_jobs
                    WHERE attempt_count < max_attempts
                      AND snapshot_schema_version = 1
                      AND (
                          (
                              status = 'PENDING'
                              AND available_at <= CURRENT_TIMESTAMP
                          )
                          OR
                          (
                              status = 'RUNNING'
                              AND lease_expires_at < CURRENT_TIMESTAMP
                          )
                      )
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE memory_suggestion_jobs job
                SET status = 'RUNNING',
                    attempt_count = job.attempt_count + 1,
                    lease_owner = ?,
                    lease_token = ?,
                    lease_expires_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 millisecond'),
                    started_at = COALESCE(job.started_at, CURRENT_TIMESTAMP),
                    error_code = NULL,
                    error_detail = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.owner_user_id, job.run_id,
                          job.session_id, job.source_message_id,
                          job.extractor_version, job.prompt_version,
                          job.snapshot_schema_version, job.provider_key,
                          job.model_id, job.model_revision,
                          job.endpoint_identity, job.prompt_hash,
                          job.input_hash, job.attempt_count,
                          job.max_attempts, job.lease_owner,
                          job.lease_token
                """,
                (rs, row) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("source_message_id", UUID.class),
                        rs.getString("extractor_version"),
                        rs.getString("prompt_version"),
                        new ExecutionSnapshot(
                                rs.getInt("snapshot_schema_version"),
                                rs.getString("extractor_version"),
                                rs.getString("prompt_version"),
                                rs.getString("provider_key"),
                                rs.getString("model_id"),
                                rs.getString("model_revision"),
                                rs.getString("endpoint_identity"),
                                rs.getString("prompt_hash")
                        ),
                        rs.getString("input_hash"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getString("lease_owner"),
                        rs.getObject("lease_token", UUID.class)
                ),
                properties.workerId(),
                leaseToken,
                properties.leaseDuration().toMillis()
        );
        return claims.isEmpty() ? null : claims.getFirst();
    }

    @Transactional(readOnly = true)
    String input(ClaimedJob claim) {
        List<String> messages = jdbc.query(
                """
                SELECT message.content
                FROM memory_suggestion_jobs job
                JOIN chat_messages message
                  ON message.id = job.source_message_id
                 AND message.session_id = job.session_id
                 AND message.owner_user_id = job.owner_user_id
                JOIN users owner
                  ON owner.id = job.owner_user_id
                 AND owner.enabled
                JOIN user_memory_settings settings
                  ON settings.user_id = job.owner_user_id
                 AND settings.suggestion_enabled
                JOIN chat_runs run
                  ON run.id = job.run_id
                 AND run.owner_user_id = job.owner_user_id
                 AND run.status IN ('COMPLETED', 'REFUSED')
                WHERE job.id = ?
                  AND job.owner_user_id = ?
                  AND job.status = 'RUNNING'
                  AND job.lease_owner = ?
                  AND job.lease_token = ?
                  AND job.attempt_count = ?
                  AND job.lease_expires_at > CURRENT_TIMESTAMP
                  AND message.role = 'USER'
                  AND message.status = 'COMPLETED'
                """,
                (rs, row) -> rs.getString("content"),
                claim.id(),
                claim.owner(),
                claim.leaseOwner(),
                claim.leaseToken(),
                claim.attemptCount()
        );
        if (messages.size() != 1
                || !hash(messages.getFirst()).equals(claim.inputHash())) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_SOURCE_INVALID",
                    "记忆建议来源已失效"
            );
        }
        return messages.getFirst();
    }

    @Transactional
    void complete(ClaimedJob claim, List<Suggestion> suggestions) {
        String source = input(claim);
        if (!hash(source).equals(claim.inputHash())) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_SOURCE_INVALID",
                    "记忆建议来源已变化"
            );
        }
        List<MemoryItemView> created = memories.createSuggestions(
                claim.owner(),
                claim.sessionId(),
                claim.sourceMessageId(),
                claim.id(),
                claim.extractorVersion(),
                claim.promptVersion(),
                claim.inputHash(),
                suggestions
        );
        for (int index = 0; index < created.size(); index++) {
            jdbc.update(
                    """
                    INSERT INTO memory_suggestion_outputs (
                        job_id, owner_user_id, position, memory_item_id
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT (job_id, position) DO NOTHING
                    """,
                    claim.id(),
                    claim.owner(),
                    index + 1,
                    created.get(index).id()
            );
        }
        int updated = jdbc.update(
                """
                UPDATE memory_suggestion_jobs
                SET status = 'SUCCEEDED',
                    suggestion_count = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    lease_token = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND owner_user_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND attempt_count = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                created.size(),
                claim.id(),
                claim.owner(),
                claim.leaseOwner(),
                claim.leaseToken(),
                claim.attemptCount()
        );
        if (updated != 1) {
            throw new MemorySuggestionException(
                    "MEMORY_SUGGESTION_LEASE_LOST",
                    "记忆建议任务租约已失效"
            );
        }
    }

    @Transactional
    void fail(ClaimedJob claim, RuntimeException failure) {
        String code = failure instanceof MemorySuggestionException known
                ? known.code()
                : "MEMORY_SUGGESTION_FAILED";
        String detail = failure instanceof MemorySuggestionException
                ? cleanDetail(failure.getMessage())
                : "记忆建议生成失败";
        boolean terminal = claim.attemptCount() >= claim.maxAttempts()
                || "MEMORY_SUGGESTION_MODEL_DISABLED".equals(code)
                || "MEMORY_SUGGESTION_SOURCE_INVALID".equals(code)
                || "MEMORY_SUGGESTION_INPUT_REJECTED".equals(code)
                || "MEMORY_SUGGESTION_REMOTE_NOT_ALLOWED".equals(code)
                || "MEMORY_SUGGESTION_RUNTIME_MISMATCH".equals(code);
        jdbc.update(
                """
                UPDATE memory_suggestion_jobs
                SET status = ?,
                    available_at = CASE
                        WHEN ? THEN available_at
                        ELSE CURRENT_TIMESTAMP
                            + (attempt_count * INTERVAL '5 seconds')
                    END,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    lease_token = NULL,
                    error_code = ?,
                    error_detail = ?,
                    completed_at = CASE
                        WHEN ? THEN CURRENT_TIMESTAMP
                        ELSE NULL
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND owner_user_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND attempt_count = ?
                """,
                terminal ? "FAILED" : "PENDING",
                terminal,
                code,
                detail,
                terminal,
                claim.id(),
                claim.owner(),
                claim.leaseOwner(),
                claim.leaseToken(),
                claim.attemptCount()
        );
    }

    @Transactional
    boolean heartbeat(ClaimedJob claim) {
        return jdbc.update(
                """
                UPDATE memory_suggestion_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND owner_user_id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND attempt_count = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                properties.leaseDuration().toMillis(),
                claim.id(),
                claim.owner(),
                claim.leaseOwner(),
                claim.leaseToken(),
                claim.attemptCount()
        ) == 1;
    }

    @Transactional(readOnly = true)
    public Map<UUID, SuggestionState> states(
            UUID owner,
            List<UUID> messageIds
    ) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = messageIds.stream().distinct().limit(200).toList();
        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(ids.size(), "?")
        );
        List<Object> arguments = new ArrayList<>();
        arguments.add(owner);
        arguments.addAll(ids);
        Map<UUID, SuggestionState> states = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT source_message_id, status, suggestion_count, error_code
                FROM memory_suggestion_jobs
                WHERE owner_user_id = ?
                  AND source_message_id IN (%s)
                ORDER BY source_message_id, created_at DESC, id DESC
                """.formatted(placeholders),
                rs -> {
                    UUID messageId = rs.getObject(
                            "source_message_id", UUID.class
                    );
                    states.putIfAbsent(
                            messageId,
                            new SuggestionState(
                                    rs.getString("status"),
                                    rs.getInt("suggestion_count"),
                                    rs.getString("error_code")
                            )
                    );
                },
                arguments.toArray()
        );
        return Map.copyOf(states);
    }

    @Transactional(readOnly = true)
    public Map<UUID, SuggestionState> statesForSession(
            UUID owner,
            UUID sessionId
    ) {
        Map<UUID, SuggestionState> states = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT run.request_message_id AS source_message_id,
                       COALESCE(latest.status, 'PENDING') AS status,
                       COALESCE(latest.suggestion_count, 0) AS suggestion_count,
                       latest.error_code
                FROM chat_runs run
                JOIN chat_sessions session
                  ON session.id = run.session_id
                 AND session.owner_user_id = run.owner_user_id
                 AND session.purpose = 'ONLINE'
                LEFT JOIN LATERAL (
                    SELECT job.status, job.suggestion_count, job.error_code
                    FROM memory_suggestion_jobs job
                    WHERE job.owner_user_id = run.owner_user_id
                      AND job.source_message_id = run.request_message_id
                    ORDER BY job.created_at DESC, job.id DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE run.owner_user_id = ?
                  AND run.session_id = ?
                  AND (
                      run.memory_suggestion_requested_at IS NOT NULL
                      OR latest.status IS NOT NULL
                  )
                ORDER BY run.created_at, run.id
                """,
                rs -> {
                    states.put(
                            rs.getObject("source_message_id", UUID.class),
                            new SuggestionState(
                                    rs.getString("status"),
                                    rs.getInt("suggestion_count"),
                                    rs.getString("error_code")
                            )
                    );
                },
                owner,
                sessionId
        );
        return Map.copyOf(states);
    }

    public record SuggestionState(
            String status,
            int suggestionCount,
            String errorCode
    ) {
    }

    record ClaimedJob(
            UUID id,
            UUID owner,
            UUID runId,
            UUID sessionId,
            UUID sourceMessageId,
            String extractorVersion,
            String promptVersion,
            ExecutionSnapshot snapshot,
            String inputHash,
            int attemptCount,
            int maxAttempts,
            String leaseOwner,
            UUID leaseToken
    ) {
    }

    private record RunIntent(UUID owner, UUID runId) {
    }

    private record SourceMessage(
            UUID sessionId,
            UUID messageId,
            String content,
            ExecutionSnapshot snapshot
    ) {
    }

    public record ExecutionSnapshot(
            int schemaVersion,
            String extractorVersion,
            String promptVersion,
            String providerKey,
            String modelId,
            String modelRevision,
            String endpointIdentity,
            String promptHash
    ) {
        public ExecutionSnapshot {
            if (schemaVersion != 1
                    || blank(extractorVersion)
                    || blank(promptVersion)
                    || blank(providerKey)
                    || blank(modelId)
                    || blank(modelRevision)
                    || blank(endpointIdentity)
                    || promptHash == null
                    || !promptHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "invalid memory suggestion execution snapshot"
                );
            }
        }
    }

    private static ExecutionSnapshot snapshot(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        int schema = rs.getInt("memory_suggestion_snapshot_schema");
        if (schema != 1) {
            return null;
        }
        return new ExecutionSnapshot(
                schema,
                rs.getString("memory_suggestion_extractor_version"),
                rs.getString("memory_suggestion_prompt_version"),
                rs.getString("memory_suggestion_provider_key"),
                rs.getString("memory_suggestion_model_id"),
                rs.getString("memory_suggestion_model_revision"),
                rs.getString("memory_suggestion_endpoint_identity"),
                rs.getString("memory_suggestion_prompt_hash")
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(input.trim().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String cleanDetail(String value) {
        String detail = value == null ? "记忆建议生成失败" : value.trim();
        return detail.substring(0, Math.min(detail.length(), 500));
    }
}
