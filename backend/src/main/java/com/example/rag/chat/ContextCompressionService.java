package com.example.rag.chat;

import com.example.rag.chat.AnswerSourceService.RunSources;
import com.example.rag.chat.ChatModelProvider.ContextSummaryResult;
import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.ChatPersistenceContracts.ChatMessage;
import com.example.rag.chat.ChatPersistenceContracts.MessageRole;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceRepository.HistoryEntry;
import com.example.rag.common.ApiException;
import com.example.rag.common.SensitiveContentDetector;
import com.example.rag.persistence.UserRepository;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ContextCompressionService {

    static final String POLICY_VERSION = "context-compression-v1";
    static final String COUNTER_VERSION = "conservative-utf8-request-v2";
    private static final int HISTORY_LIMIT = 12;
    private static final int HISTORY_TOKEN_BUDGET = 2_048;
    private static final int RECENT_MESSAGE_LIMIT = 4;
    private static final int SUMMARY_MAX_TOKENS = 512;
    private static final int SUMMARY_INPUT_MAX_TOKENS = 4_096;
    private static final int MAX_CANDIDATES = 48;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChatPersistenceRepository repository;
    private final AnswerSourceService answerSources;
    private final UserRepository users;
    private final ChatModelProvider model;
    private final ChatProperties properties;
    private final ObjectMapper objectMapper;

    ContextCompressionService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ChatPersistenceRepository repository,
            AnswerSourceService answerSources,
            UserRepository users,
            ChatModelProvider model,
            ChatProperties properties,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.repository = repository;
        this.answerSources = answerSources;
        this.users = users;
        this.model = model;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    ContextStatus prepare(
            PlatformUserPrincipal user,
            UUID sessionId
    ) {
        requireSession(user, sessionId);
        if (!properties.getContextCompression().isEnabled()) {
            return disabled();
        }
        PreparedJob prepared = prepareJob(user, sessionId, Integer.MAX_VALUE);
        if (prepared.reasonCode() != null) {
            return statusFromPrepared(prepared);
        }
        if (prepared.deltaSources().isEmpty()) {
            return status(user, sessionId);
        }
        transactions.executeWithoutResult(ignored -> enqueue(prepared));
        return status(user, sessionId);
    }

    ContextStatus status(
            PlatformUserPrincipal user,
            UUID sessionId
    ) {
        requireSession(user, sessionId);
        if (!properties.getContextCompression().isEnabled()) {
            return disabled();
        }
        SummaryArtifact summary = latestValidSummary(
                user, sessionId, Integer.MAX_VALUE
        );
        JobState job = latestJob(user.id(), sessionId);
        boolean caughtUp = summary != null && summaryCaughtUp(
                user, sessionId, Integer.MAX_VALUE, summary
        );
        String state;
        String reason;
        if (caughtUp) {
            state = "USED";
            reason = null;
        } else if (job != null && ("PENDING".equals(job.status())
                || "RUNNING".equals(job.status()))) {
            state = "PENDING";
            reason = summary == null ? job.errorCode()
                    : "CONTEXT_SUMMARY_CATCHING_UP";
        } else if (summary != null) {
            state = "FALLBACK";
            reason = "CONTEXT_SUMMARY_CATCHING_UP";
        } else if (job != null) {
            state = switch (job.status()) {
                case "FAILED" -> "FAILED";
                default -> "NOT_NEEDED";
            };
            reason = job.errorCode();
        } else {
            state = "NOT_NEEDED";
            reason = null;
        }
        int tailCount = tailCount(user, sessionId, Integer.MAX_VALUE, summary);
        int summaryTokens = summary == null ? 0 : summary.tokenCount();
        int covered = summary == null ? 0 : summary.sourceCount();
        int tailTokens = tailTokens(
                user, sessionId, Integer.MAX_VALUE, summary
        );
        int rawTokens = summary == null
                ? rawHistoryTokens(user, sessionId, Integer.MAX_VALUE)
                : summary.sourceTokenCount() + tailTokens;
        int finalTokens = summaryTokens + tailTokens;
        int saved = Math.max(0, rawTokens - finalTokens);
        return new ContextStatus(
                state,
                POLICY_VERSION,
                covered,
                tailCount,
                summaryTokens,
                finalTokens,
                saved,
                rawTokens == 0 ? 0.0 : saved / (double) rawTokens,
                summary == null ? job == null ? null : job.updatedAt()
                        : summary.createdAt(),
                reason
        );
    }

    SummaryArtifact latestValidSummary(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence
    ) {
        if (!properties.getContextCompression().isEnabled()
                || !conversationContextAllowed()) {
            return null;
        }
        SummaryArtifact summary = first(jdbc.query(
                """
                SELECT id, owner_user_id, session_id, policy_version,
                       version_number, parent_summary_id, chain_depth,
                       covered_through_sequence, summary_json::text,
                       summary_token_count, source_message_count,
                       source_token_count, lineage_hash, input_hash,
                       content_hash, created_at
                FROM chat_context_summaries
                WHERE owner_user_id = ? AND session_id = ?
                  AND policy_version = ?
                  AND covered_through_sequence < ?
                ORDER BY covered_through_sequence DESC, version_number DESC
                LIMIT 1
                """,
                ContextCompressionService::summary,
                user.id(), sessionId, POLICY_VERSION, beforeSequence
        ));
        if (summary == null || !sourcesCurrent(user, summary)) {
            return null;
        }
        return summary;
    }

    HistoryContext historyContext(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence,
            int messageLimit,
            int tokenBudget
    ) {
        if (!conversationContextAllowed()) {
            return HistoryContext.empty(
                    "REMOTE_BLOCKED",
                    "REMOTE_CONVERSATION_CONTEXT_BLOCKED"
            );
        }
        boolean compressionEnabled =
                properties.getContextCompression().isEnabled();
        SummaryArtifact summary = compressionEnabled
                ? latestValidSummary(user, sessionId, beforeSequence)
                : null;
        List<SourceMessage> eligible = eligible(
                user, sessionId, beforeSequence
        );
        int summaryCovered = summary == null
                ? 0 : summary.coveredThroughSequence();
        boolean catchingUp = summary != null && eligible.stream()
                .filter(source -> source.sequence()
                        > summaryCovered)
                .count() > RECENT_MESSAGE_LIMIT;
        if (catchingUp) {
            summary = null;
        }
        int covered = summary == null ? 0 : summary.coveredThroughSequence();
        List<SourceMessage> raw = new ArrayList<>(eligible.stream()
                .filter(source -> source.sequence() > covered)
                .toList());
        int safeLimit = summary == null
                ? Math.max(1, Math.min(HISTORY_LIMIT, messageLimit))
                : RECENT_MESSAGE_LIMIT;
        while (raw.size() > safeLimit) {
            raw.removeFirst();
        }
        int safeBudget = Math.max(64, Math.min(
                effectiveHistoryBudget(), tokenBudget
        ));
        int summaryTokens = summary == null ? 0 : summary.tokenCount();
        int rawTokens = raw.stream().mapToInt(SourceMessage::tokens).sum();
        while (!raw.isEmpty() && summaryTokens + rawTokens > safeBudget) {
            rawTokens -= raw.removeFirst().tokens();
        }
        if (!raw.isEmpty() && raw.getFirst().role() == MessageRole.ASSISTANT) {
            rawTokens -= raw.removeFirst().tokens();
        }
        if (!properties.getLlm().isLocalEndpoint()) {
            String outbound = (summary == null ? "" : summary.summaryJson())
                    + raw.stream().map(SourceMessage::content)
                    .reduce("", (left, right) -> left + '\n' + right);
            if (SensitiveContentDetector.containsCredentials(outbound)) {
                return HistoryContext.empty(
                        "REMOTE_BLOCKED", "REMOTE_CONTEXT_SENSITIVE_BLOCKED"
                );
            }
        }
        if (summary != null && summaryTokens + rawTokens > safeBudget) {
            summary = null;
            summaryTokens = 0;
        }
        String state = catchingUp ? "PENDING" : summary == null
                ? compressionEnabled && needsCompression(eligible, safeBudget)
                    ? "PENDING" : "NOT_NEEDED"
                : "USED";
        return new HistoryContext(
                summary,
                List.copyOf(raw),
                state,
                catchingUp ? "CONTEXT_SUMMARY_CATCHING_UP"
                        : compressionEnabled ? null
                        : "CONTEXT_COMPRESSION_DISABLED",
                summaryTokens + rawTokens
        );
    }

    ClaimedJob claim() {
        if (!properties.getContextCompression().isEnabled()
                || !properties.getContextCompression().isWorkerEnabled()) {
            return null;
        }
        return transactions.execute(ignored -> first(jdbc.query(
                """
                WITH candidate AS (
                    SELECT id
                    FROM chat_context_summary_jobs
                    WHERE (
                        (status = 'PENDING'
                            AND available_at <= CURRENT_TIMESTAMP)
                        OR (status = 'RUNNING'
                            AND lease_expires_at < CURRENT_TIMESTAMP)
                    )
                    AND attempt_count < max_attempts
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE chat_context_summary_jobs job
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_token = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    error_code = NULL, error_detail = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.owner_user_id, job.session_id,
                          job.policy_version, job.parent_summary_id,
                          job.source_from_sequence,
                          job.source_through_sequence, job.input_hash,
                          job.provider_key, job.model_id,
                          job.model_revision, job.endpoint_identity,
                          job.prompt_version, job.schema_version,
                          job.counter_version, job.lease_token,
                          job.attempt_count, job.max_attempts
                """,
                ContextCompressionService::mapClaim,
                properties.getContextCompression().getWorkerId(),
                UUID.randomUUID(),
                properties.getContextCompression().getLeaseDuration().toMillis()
        )));
    }

    void process(ClaimedJob claim) {
        PlatformUserPrincipal user = users.findById(claim.ownerUserId())
                .filter(current -> current.isEnabled())
                .map(PlatformUserPrincipal::from)
                .orElseThrow(() -> new ContextCompressionException(
                        "CONTEXT_OWNER_UNAVAILABLE",
                        "会话所有者不可用"
                ));
        if (!conversationContextAllowed()) {
            throw new ContextCompressionException(
                    "REMOTE_CONVERSATION_CONTEXT_BLOCKED",
                    "远程会话上下文未获许可"
            );
        }
        PreparedJob current = inputForClaim(user, claim);
        if (current.reasonCode() != null
                || !claim.inputHash().equals(current.inputHash())) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_INPUT_STALE",
                    "摘要输入事实已变化"
            );
        }
        List<ModelHistoryMessage> messages = modelHistory(
                current.deltaSources()
        );
        ContextSummaryResult result = model.summarizeContext(
                current.parent() == null ? null : current.parent().summaryJson(),
                messages,
                summaryOutputBudget()
        );
        if (!heartbeat(claim)) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_LEASE_LOST",
                    "摘要任务租约已失效"
            );
        }
        transactions.executeWithoutResult(ignored -> complete(
                claim, current, result.canonicalJson()
        ));
        try {
            prepare(user, claim.sessionId());
        } catch (ApiException deleted) {
            if (!"CHAT_SESSION_NOT_FOUND".equals(deleted.getCode())) {
                throw deleted;
            }
        }
    }

    boolean heartbeat(ClaimedJob claim) {
        return jdbc.update(
                """
                UPDATE chat_context_summary_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_token = ? AND attempt_count = ?
                """,
                properties.getContextCompression().getLeaseDuration().toMillis(),
                claim.id(), claim.leaseToken(), claim.attemptCount()
        ) == 1;
    }

    void fail(ClaimedJob claim, RuntimeException failure) {
        String code = failure instanceof ContextCompressionException known
                ? known.code() : "CONTEXT_SUMMARY_FAILED";
        String detail = failure.getMessage() == null
                ? "Context summary failed" : failure.getMessage();
        transactions.executeWithoutResult(ignored -> {
            int changed = jdbc.update(
                    """
                    UPDATE chat_context_summary_jobs
                    SET status = CASE WHEN attempt_count >= max_attempts
                                      THEN 'FAILED' ELSE 'PENDING' END,
                        available_at = CURRENT_TIMESTAMP + INTERVAL '5 seconds',
                        lease_owner = NULL, lease_token = NULL,
                        lease_expires_at = NULL,
                        error_code = ?, error_detail = ?,
                        updated_at = CURRENT_TIMESTAMP,
                        completed_at = CASE WHEN attempt_count >= max_attempts
                                            THEN CURRENT_TIMESTAMP ELSE NULL END
                    WHERE id = ? AND status = 'RUNNING'
                      AND lease_token = ? AND attempt_count = ?
                    """,
                    code,
                    detail.substring(0, Math.min(500, detail.length())),
                    claim.id(), claim.leaseToken(), claim.attemptCount()
            );
            if (changed == 1) {
                event(
                        claim.ownerUserId(), claim.sessionId(), claim.id(),
                        null, "FAILED", code, Map.of(
                                "attemptCount", claim.attemptCount()
                        )
                );
            }
        });
    }

    private PreparedJob prepareJob(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence
    ) {
        List<SourceMessage> recentEligible = eligible(
                user, sessionId, beforeSequence
        );
        int rawTokens = recentEligible.stream()
                .mapToInt(SourceMessage::tokens).sum();
        SummaryArtifact latest = latestValidSummary(
                user, sessionId, beforeSequence
        );
        int latestCovered = latest == null
                ? 0 : latest.coveredThroughSequence();
        boolean summaryNeedsCatchUp = latest != null
                && recentEligible.stream()
                .filter(source -> source.sequence() > latestCovered)
                .count() > RECENT_MESSAGE_LIMIT;
        if (!summaryNeedsCatchUp
                && recentEligible.size() <= HISTORY_LIMIT
                && rawTokens <= effectiveHistoryBudget()) {
            return PreparedJob.reason("CONTEXT_COMPRESSION_NOT_NEEDED");
        }
        if (!properties.getLlm().isEnabled()) {
            return PreparedJob.reason("CONTEXT_MODEL_UNAVAILABLE");
        }
        if (!conversationContextAllowed()) {
            return PreparedJob.reason(
                    "REMOTE_CONVERSATION_CONTEXT_BLOCKED"
            );
        }
        if (summaryOutputBudget() < 64) {
            return PreparedJob.reason(
                    "CONTEXT_SUMMARY_OUTPUT_BUDGET_EXHAUSTED"
            );
        }
        if (recentEligible.size() <= RECENT_MESSAGE_LIMIT) {
            return PreparedJob.reason("CONTEXT_COMPRESSION_NOT_NEEDED");
        }
        int tailStartSequence = recentEligible.get(
                recentEligible.size() - RECENT_MESSAGE_LIMIT
        ).sequence();
        SummaryArtifact parent = latestValidSummary(
                user, sessionId, tailStartSequence
        );
        int coveredSequence = parent == null
                ? 0 : parent.coveredThroughSequence();
        SummaryArtifact fixedParent = parent;
        List<SourceMessage> pending = eligibleWindow(
                user, sessionId, coveredSequence, tailStartSequence
        );
        if (pending.isEmpty()) {
            return new PreparedJob(
                    user.id(), sessionId, fixedParent, List.of(), List.of(),
                    null, null
            );
        }
        List<SourceMessage> bounded = new ArrayList<>();
        for (SourceMessage source : pending) {
            List<SourceMessage> trial = new ArrayList<>(bounded);
            trial.add(source);
            if (summaryRequestCount(fixedParent, trial)
                    > summaryInputCap()) {
                break;
            }
            bounded.add(source);
        }
        if (bounded.isEmpty()) {
            return PreparedJob.reason(
                    "CONTEXT_SUMMARY_INPUT_BUDGET_EXHAUSTED"
            );
        }
        List<SourceMessage> delta = List.copyOf(bounded);
        String sensitive = delta.stream()
                .map(SourceMessage::content)
                .reduce("", (left, right) -> left + '\n' + right);
        if (!properties.getLlm().isLocalEndpoint()
                && SensitiveContentDetector.containsCredentials(sensitive)) {
            return PreparedJob.reason("REMOTE_CONTEXT_SENSITIVE_BLOCKED");
        }
        String inputHash = hash(Map.of(
                "policyVersion", POLICY_VERSION,
                "parentContentHash", fixedParent == null
                        ? "ROOT" : fixedParent.contentHash(),
                "sources", delta.stream().map(source -> Map.of(
                        "id", source.id(),
                        "sequence", source.sequence(),
                        "contentHash", source.contentHash(),
                        "sourceFactHash", source.sourceFactHash()
                )).toList()
        ));
        return new PreparedJob(
                user.id(), sessionId, fixedParent, delta, delta,
                inputHash, null
        );
    }

    private PreparedJob inputForClaim(
            PlatformUserPrincipal user,
            ClaimedJob claim
    ) {
        if (!runtimeMatches(claim)) {
            return PreparedJob.reason("CONTEXT_SUMMARY_RUNTIME_CHANGED");
        }
        SummaryArtifact parent = claim.parentSummaryId() == null
                ? null : findSummary(user, claim.parentSummaryId());
        if (claim.parentSummaryId() != null && parent == null) {
            return PreparedJob.reason("CONTEXT_SUMMARY_PARENT_STALE");
        }
        List<SourceMessage> delta = eligibleWindow(
                user,
                claim.sessionId(),
                claim.sourceFromSequence() - 1,
                claim.sourceThroughSequence() + 1
        );
        if (delta.isEmpty()
                || delta.getFirst().sequence() != claim.sourceFromSequence()
                || delta.getLast().sequence() != claim.sourceThroughSequence()) {
            return PreparedJob.reason("CONTEXT_SUMMARY_INPUT_STALE");
        }
        if (summaryRequestCount(parent, delta) > summaryInputCap()) {
            return PreparedJob.reason(
                    "CONTEXT_SUMMARY_INPUT_BUDGET_EXHAUSTED"
            );
        }
        String inputHash = hash(Map.of(
                "policyVersion", POLICY_VERSION,
                "parentContentHash", parent == null
                        ? "ROOT" : parent.contentHash(),
                "sources", delta.stream().map(source -> Map.of(
                        "id", source.id(),
                        "sequence", source.sequence(),
                        "contentHash", source.contentHash(),
                        "sourceFactHash", source.sourceFactHash()
                )).toList()
        ));
        return new PreparedJob(
                user.id(), claim.sessionId(), parent, delta, delta,
                inputHash, null
        );
    }

    private SummaryArtifact findSummary(
            PlatformUserPrincipal user,
            UUID summaryId
    ) {
        SummaryArtifact summary = first(jdbc.query(
                """
                SELECT id, owner_user_id, session_id, policy_version,
                       version_number, parent_summary_id, chain_depth,
                       covered_through_sequence, summary_json::text,
                       summary_token_count, source_message_count,
                       source_token_count, lineage_hash, input_hash,
                       content_hash, created_at
                FROM chat_context_summaries
                WHERE id = ? AND owner_user_id = ? AND policy_version = ?
                """,
                ContextCompressionService::summary,
                summaryId, user.id(), POLICY_VERSION
        ));
        return summary != null && sourcesCurrent(user, summary)
                ? summary : null;
    }

    private List<SourceMessage> eligible(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence
    ) {
        return eligibleEntries(user, repository.recentHistory(
                user.id(), sessionId, beforeSequence, MAX_CANDIDATES
        ));
    }

    private List<SourceMessage> eligibleWindow(
            PlatformUserPrincipal user,
            UUID sessionId,
            int afterSequence,
            int beforeSequence
    ) {
        return eligibleEntries(user, repository.historyWindow(
                user.id(), sessionId, afterSequence, beforeSequence,
                MAX_CANDIDATES
        ));
    }

    private List<SourceMessage> eligibleEntries(
            PlatformUserPrincipal user,
            List<HistoryEntry> recent
    ) {
        Map<UUID, RunSources> sources = answerSources.load(
                user,
                recent.stream()
                        .filter(entry -> entry.runId() != null
                                && entry.runStatus() == RunStatus.COMPLETED)
                        .map(HistoryEntry::runId)
                        .distinct()
                        .toList()
        );
        List<SourceMessage> result = new ArrayList<>();
        for (HistoryEntry entry : recent) {
            ChatMessage message = entry.message();
            if (message.role() == MessageRole.USER) {
                result.add(source(
                        message, entry.runId(), entry.runStatus(),
                        hash("USER_MESSAGE")
                ));
                continue;
            }
            if (message.role() != MessageRole.ASSISTANT
                    || entry.runId() == null
                    || entry.runStatus() != RunStatus.COMPLETED
                    && entry.runStatus() != RunStatus.REFUSED) {
                continue;
            }
            if (entry.runStatus() == RunStatus.COMPLETED) {
                RunSources status = sources.getOrDefault(
                        entry.runId(), RunSources.invalid()
                );
                if (!status.current() || status.usedMemory()) {
                    continue;
                }
                result.add(source(
                        message, entry.runId(), entry.runStatus(),
                        sourceFactHash(entry.runId(), entry.runStatus(), status)
                ));
                continue;
            }
            result.add(source(
                    message, entry.runId(), entry.runStatus(),
                    sourceFactHash(entry.runId(), entry.runStatus(), null)
            ));
        }
        return List.copyOf(result);
    }

    private SourceMessage source(
            ChatMessage message,
            UUID runId,
            RunStatus runStatus,
            String sourceFactHash
    ) {
        String contentHash = hash(message.content());
        int tokens = count(Map.of(
                "role", message.role().name().toLowerCase(),
                "content", message.content()
        ));
        return new SourceMessage(
                message.id(), runId, runStatus,
                message.sequenceNumber(), message.role(),
                message.content(), contentHash, sourceFactHash, tokens
        );
    }

    private int summaryInputCap() {
        int contextWindow = properties.getLlm().getContextWindowTokens();
        int reserve = Math.max(
                256, (int) Math.ceil(contextWindow * 0.05)
        );
        return Math.min(
                SUMMARY_INPUT_MAX_TOKENS,
                contextWindow - summaryOutputBudget() - reserve
        );
    }

    private int summaryOutputBudget() {
        return Math.min(
                SUMMARY_MAX_TOKENS,
                effectiveHistoryBudget() / 4
        );
    }

    private int summaryRequestCount(
            SummaryArtifact parent,
            List<SourceMessage> sources
    ) {
        return model.countContextSummaryRequest(
                parent == null ? null : parent.summaryJson(),
                modelHistory(sources),
                summaryOutputBudget()
        );
    }

    private boolean runtimeMatches(ClaimedJob claim) {
        return POLICY_VERSION.equals(claim.policyVersion())
                && "OPENAI_COMPATIBLE".equals(claim.providerKey())
                && properties.getLlm().getModel().equals(claim.modelId())
                && properties.getLlm().getModelRevision().equals(
                        claim.modelRevision()
                )
                && hash(properties.getLlm().getBaseUrl()).equals(
                        claim.endpointIdentity()
                )
                && "context-compression-prompt-v1".equals(
                        claim.promptVersion()
                )
                && "context-compression-schema-v1".equals(
                        claim.schemaVersion()
                )
                && COUNTER_VERSION.equals(claim.counterVersion());
    }

    private List<ModelHistoryMessage> modelHistory(
            List<SourceMessage> sources
    ) {
        return sources.stream().map(source -> new ModelHistoryMessage(
                source.role().name().toLowerCase(), source.content()
        )).toList();
    }

    private String sourceFactHash(
            UUID runId,
            RunStatus runStatus,
            RunSources sources
    ) {
        return hash(Map.of(
                "runId", runId,
                "runStatus", runStatus.name(),
                "current", sources != null && sources.current(),
                "usedMemory", sources != null && sources.usedMemory(),
                "citations", sources == null ? List.of() : sources.citations()
        ));
    }

    private void enqueue(PreparedJob prepared) {
        int from = prepared.deltaSources().getFirst().sequence();
        int through = prepared.deltaSources().getLast().sequence();
        UUID id = UUID.randomUUID();
        int changed = jdbc.update(
                """
                INSERT INTO chat_context_summary_jobs (
                    id, owner_user_id, session_id, policy_version,
                    parent_summary_id, source_from_sequence,
                    source_through_sequence, input_hash, provider_key,
                    model_id, model_revision, endpoint_identity,
                    prompt_version, schema_version, counter_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                id, prepared.ownerUserId(), prepared.sessionId(),
                POLICY_VERSION,
                prepared.parent() == null ? null : prepared.parent().id(),
                from, through, prepared.inputHash(), "OPENAI_COMPATIBLE",
                properties.getLlm().getModel(),
                properties.getLlm().getModelRevision(),
                hash(properties.getLlm().getBaseUrl()),
                "context-compression-prompt-v1",
                "context-compression-schema-v1", COUNTER_VERSION
        );
        if (changed == 1) {
            event(
                    prepared.ownerUserId(), prepared.sessionId(), id, null,
                    "QUEUED", null, Map.of(
                            "sourceFromSequence", from,
                            "sourceThroughSequence", through
                    )
            );
        }
    }

    private void complete(
            ClaimedJob claim,
            PreparedJob prepared,
            String summaryJson
    ) {
        if (!claim.inputHash().equals(prepared.inputHash())) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_INPUT_STALE",
                    "摘要输入事实已变化"
            );
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(summaryJson);
        } catch (JsonProcessingException exception) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_INVALID", "摘要 JSON 无效", exception
            );
        }
        if (parsed == null || !parsed.isObject()) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_INVALID", "摘要 JSON 无效"
            );
        }
        int tokens = count(parsed);
        if (tokens < 1 || tokens > summaryOutputBudget()) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_BUDGET_EXCEEDED", "摘要超过预算"
            );
        }
        List<SourceMessage> allSources = mergeSources(
                prepared.parent(), prepared.coveredSources()
        );
        int version = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM chat_context_summaries
                WHERE owner_user_id = ? AND session_id = ?
                  AND policy_version = ?
                """,
                Integer.class,
                claim.ownerUserId(), claim.sessionId(), POLICY_VERSION
        );
        UUID summaryId = UUID.randomUUID();
        String canonical = json(parsed);
        boolean rebase = prepared.parent() != null
                && prepared.parent().chainDepth() >= 8;
        jdbc.update(
                """
                INSERT INTO chat_context_summaries (
                    id, owner_user_id, session_id, policy_version,
                    version_number, parent_summary_id, chain_depth,
                    covered_through_sequence, summary_json,
                    summary_token_count, source_message_count,
                    source_token_count, lineage_hash, input_hash,
                    content_hash, provider_key,
                    model_id, model_revision, endpoint_identity,
                    prompt_version, schema_version, counter_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                summaryId, claim.ownerUserId(), claim.sessionId(),
                POLICY_VERSION, version,
                prepared.parent() == null || rebase
                        ? null : prepared.parent().id(),
                prepared.parent() == null || rebase
                        ? 1 : prepared.parent().chainDepth() + 1,
                allSources.getLast().sequence(), canonical, tokens,
                allSources.size(), allSources.stream()
                        .mapToInt(SourceMessage::tokens).sum(),
                lineageHash(allSources), claim.inputHash(),
                hash(canonical), "OPENAI_COMPATIBLE",
                properties.getLlm().getModel(),
                properties.getLlm().getModelRevision(),
                hash(properties.getLlm().getBaseUrl()),
                "context-compression-prompt-v1",
                "context-compression-schema-v1", COUNTER_VERSION
        );
        int order = 1;
        for (SourceMessage source : allSources) {
            jdbc.update(
                    """
                    INSERT INTO chat_context_summary_sources (
                        summary_id, owner_user_id, session_id, message_id,
                        source_order, source_sequence, source_role,
                        source_content_hash, source_fact_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    summaryId, claim.ownerUserId(), claim.sessionId(),
                    source.id(), order++, source.sequence(),
                    source.role().name(), source.contentHash(),
                    source.sourceFactHash()
            );
        }
        int changed = jdbc.update(
                """
                UPDATE chat_context_summary_jobs
                SET status = 'SUCCEEDED', result_summary_id = ?,
                    lease_owner = NULL, lease_token = NULL,
                    lease_expires_at = NULL, completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_token = ? AND attempt_count = ?
                """,
                summaryId, claim.id(), claim.leaseToken(), claim.attemptCount()
        );
        if (changed != 1) {
            throw new ContextCompressionException(
                    "CONTEXT_SUMMARY_LEASE_LOST", "摘要任务租约已失效"
            );
        }
        event(
                claim.ownerUserId(), claim.sessionId(), claim.id(),
                summaryId, "SUCCEEDED", null, Map.of(
                        "sourceMessageCount", allSources.size(),
                        "summaryTokenCount", tokens
                )
        );
    }

    private List<SourceMessage> mergeSources(
            SummaryArtifact parent,
            List<SourceMessage> current
    ) {
        LinkedHashMap<UUID, SourceMessage> merged = new LinkedHashMap<>();
        if (parent != null) {
            summarySources(parent).forEach(source -> merged.put(
                    source.id(), source
            ));
        }
        current.forEach(source -> merged.put(source.id(), source));
        return merged.values().stream()
                .sorted(java.util.Comparator.comparingInt(
                        SourceMessage::sequence
                ))
                .toList();
    }

    private List<SourceMessage> summarySources(SummaryArtifact summary) {
        return jdbc.query(
                """
                SELECT source.message_id, run.id AS run_id,
                       run.status AS run_status,
                       source.source_sequence, source.source_role,
                       message.content, source.source_content_hash,
                       source.source_fact_hash
                FROM chat_context_summary_sources source
                JOIN chat_messages message
                  ON message.id = source.message_id
                 AND message.owner_user_id = source.owner_user_id
                 AND message.session_id = source.session_id
                LEFT JOIN chat_runs run
                  ON run.owner_user_id = message.owner_user_id
                 AND run.session_id = message.session_id
                 AND run.response_message_id = message.id
                WHERE source.summary_id = ? AND source.owner_user_id = ?
                ORDER BY source.source_order
                """,
                (rs, row) -> new SourceMessage(
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("run_status") == null
                                ? null
                                : RunStatus.valueOf(rs.getString("run_status")),
                        rs.getInt("source_sequence"),
                        MessageRole.valueOf(rs.getString("source_role")),
                        rs.getString("content"),
                        rs.getString("source_content_hash"),
                        rs.getString("source_fact_hash"),
                        count(Map.of(
                                "role", rs.getString("source_role")
                                        .toLowerCase(),
                                "content", rs.getString("content")
                        ))
                ),
                summary.id(), summary.ownerUserId()
        );
    }

    private boolean sourcesCurrent(
            PlatformUserPrincipal user,
            SummaryArtifact summary
    ) {
        List<SourceMessage> sources = summarySources(summary);
        if (sources.size() != summary.sourceCount()
                || sources.stream().anyMatch(source ->
                !source.contentHash().equals(hash(source.content())))
                || sources.stream()
                .filter(source -> source.role() == MessageRole.USER)
                .anyMatch(source -> !source.sourceFactHash().equals(
                        hash("USER_MESSAGE")
                ))) {
            return false;
        }
        Map<UUID, RunSources> current = answerSources.load(
                user,
                sources.stream()
                        .filter(source -> source.role() == MessageRole.ASSISTANT
                                && source.runStatus() == RunStatus.COMPLETED)
                        .map(SourceMessage::runId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList()
        );
        return sources.stream()
                .filter(source -> source.role() == MessageRole.ASSISTANT)
                .allMatch(source -> {
                    if (source.runId() == null) {
                        return false;
                    }
                    if (source.runStatus() == RunStatus.REFUSED) {
                        return source.sourceFactHash().equals(sourceFactHash(
                                source.runId(), source.runStatus(), null
                        ));
                    }
                    if (source.runStatus() != RunStatus.COMPLETED) {
                        return false;
                    }
                    RunSources status = current.getOrDefault(
                            source.runId(), RunSources.invalid()
                    );
                    return status.current() && !status.usedMemory()
                            && source.sourceFactHash().equals(sourceFactHash(
                            source.runId(), source.runStatus(), status
                    ));
                });
    }

    private JobState latestJob(UUID owner, UUID session) {
        return first(jdbc.query(
                """
                SELECT status, error_code, updated_at
                FROM chat_context_summary_jobs
                WHERE owner_user_id = ? AND session_id = ?
                ORDER BY CASE WHEN status IN ('PENDING', 'RUNNING')
                              THEN 0 ELSE 1 END,
                         created_at DESC, id DESC
                LIMIT 1
                """,
                (rs, row) -> new JobState(
                        rs.getString("status"), rs.getString("error_code"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                owner, session
        ));
    }

    private ContextStatus statusFromPrepared(PreparedJob prepared) {
        String reason = prepared.reasonCode();
        String state = switch (reason) {
            case "CONTEXT_COMPRESSION_NOT_NEEDED" -> "NOT_NEEDED";
            case "REMOTE_CONVERSATION_CONTEXT_BLOCKED",
                 "REMOTE_CONTEXT_SENSITIVE_BLOCKED" -> "REMOTE_BLOCKED";
            default -> "FAILED";
        };
        return new ContextStatus(
                state, POLICY_VERSION, 0, 0, 0, 0, 0, 0.0,
                Instant.now(), reason
        );
    }

    private int tailCount(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence,
            SummaryArtifact summary
    ) {
        return tail(user, sessionId, beforeSequence, summary).size();
    }

    private int tailTokens(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence,
            SummaryArtifact summary
    ) {
        return tail(user, sessionId, beforeSequence, summary).stream()
                .mapToInt(SourceMessage::tokens).sum();
    }

    List<SourceMessage> tail(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence,
            SummaryArtifact summary
    ) {
        int covered = summary == null ? 0 : summary.coveredThroughSequence();
        List<SourceMessage> sources = eligible(user, sessionId, beforeSequence)
                .stream()
                .filter(source -> source.sequence() > covered)
                .toList();
        int from = Math.max(0, sources.size() - RECENT_MESSAGE_LIMIT);
        return List.copyOf(sources.subList(from, sources.size()));
    }

    private int rawHistoryTokens(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence
    ) {
        return eligible(user, sessionId, beforeSequence).stream()
                .mapToInt(SourceMessage::tokens).sum();
    }

    int effectiveHistoryBudget() {
        int contextBudget = properties.getLlm().getContextWindowTokens() / 5;
        return Math.min(HISTORY_TOKEN_BUDGET, contextBudget);
    }

    private boolean needsCompression(
            List<SourceMessage> eligible,
            int tokenBudget
    ) {
        return eligible.size() > HISTORY_LIMIT
                || eligible.stream().mapToInt(SourceMessage::tokens).sum()
                > tokenBudget;
    }

    private boolean summaryCaughtUp(
            PlatformUserPrincipal user,
            UUID sessionId,
            int beforeSequence,
            SummaryArtifact summary
    ) {
        return eligible(user, sessionId, beforeSequence).stream()
                .filter(source -> source.sequence()
                        > summary.coveredThroughSequence())
                .count() <= RECENT_MESSAGE_LIMIT;
    }

    private boolean conversationContextAllowed() {
        return properties.getLlm().isLocalEndpoint()
                || properties.getLlm().isRemoteConversationContextAllowed();
    }

    private void requireSession(
            PlatformUserPrincipal user,
            UUID sessionId
    ) {
        if (user == null || sessionId == null
                || repository.findSession(user.id(), sessionId).isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "CHAT_SESSION_NOT_FOUND",
                    "会话不存在"
            );
        }
    }

    private ContextStatus disabled() {
        return new ContextStatus(
                "NOT_NEEDED", POLICY_VERSION, 0, 0, 0, 0,
                0, 0.0, Instant.now(), "CONTEXT_COMPRESSION_DISABLED"
        );
    }

    private void event(
            UUID owner,
            UUID session,
            UUID job,
            UUID summary,
            String type,
            String reason,
            Map<String, Object> details
    ) {
        jdbc.update(
                """
                INSERT INTO chat_context_summary_events (
                    owner_user_id, session_id, job_id, summary_id,
                    event_type, reason_code, details
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """,
                owner, session, job, summary, type, reason, json(details)
        );
    }

    private String lineageHash(List<SourceMessage> sources) {
        return hash(sources.stream().map(source -> Map.of(
            "messageId", source.id(),
            "sequence", source.sequence(),
            "contentHash", source.contentHash(),
            "sourceFactHash", source.sourceFactHash()
        )).toList());
    }

    private int count(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            return Math.max(1, (bytes.length + 3) / 4);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to count context", exception);
        }
    }

    private String hash(Object value) {
        try {
            byte[] bytes = value instanceof String text
                    ? text.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to hash context", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize context", exception);
        }
    }

    private static SummaryArtifact summary(ResultSet rs, int row)
            throws SQLException {
        return new SummaryArtifact(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("policy_version"),
                rs.getInt("version_number"),
                rs.getObject("parent_summary_id", UUID.class),
                rs.getInt("chain_depth"),
                rs.getInt("covered_through_sequence"),
                rs.getString("summary_json"),
                rs.getInt("summary_token_count"),
                rs.getInt("source_message_count"),
                rs.getInt("source_token_count"),
                rs.getString("lineage_hash"),
                rs.getString("input_hash"),
                rs.getString("content_hash"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static ClaimedJob mapClaim(ResultSet rs, int row)
            throws SQLException {
        return new ClaimedJob(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("policy_version"),
                rs.getObject("parent_summary_id", UUID.class),
                rs.getInt("source_from_sequence"),
                rs.getInt("source_through_sequence"),
                rs.getString("input_hash"),
                rs.getString("provider_key"),
                rs.getString("model_id"),
                rs.getString("model_revision"),
                rs.getString("endpoint_identity"),
                rs.getString("prompt_version"),
                rs.getString("schema_version"),
                rs.getString("counter_version"),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts")
        );
    }

    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    record ContextStatus(
            String status,
            String policyVersion,
            int coveredMessageCount,
            int tailMessageCount,
            int summaryTokenCount,
            int finalHistoryTokenCount,
            int estimatedSavedTokens,
            double compressionRatio,
            Instant updatedAt,
            String reasonCode
    ) {
    }

    record SummaryArtifact(
            UUID id,
            UUID ownerUserId,
            UUID sessionId,
            String policyVersion,
            int versionNumber,
            UUID parentSummaryId,
            int chainDepth,
            int coveredThroughSequence,
            String summaryJson,
            int tokenCount,
            int sourceCount,
            int sourceTokenCount,
            String lineageHash,
            String inputHash,
            String contentHash,
            Instant createdAt
    ) {
    }

    record SourceMessage(
            UUID id,
            UUID runId,
            RunStatus runStatus,
            int sequence,
            MessageRole role,
            String content,
            String contentHash,
            String sourceFactHash,
            int tokens
    ) {
    }

    record HistoryContext(
            SummaryArtifact summary,
            List<SourceMessage> rawMessages,
            String status,
            String reasonCode,
            int tokenCount
    ) {
        static HistoryContext empty(String status, String reasonCode) {
            return new HistoryContext(
                    null, List.of(), status, reasonCode, 0
            );
        }
    }

    record ClaimedJob(
            UUID id,
            UUID ownerUserId,
            UUID sessionId,
            String policyVersion,
            UUID parentSummaryId,
            int sourceFromSequence,
            int sourceThroughSequence,
            String inputHash,
            String providerKey,
            String modelId,
            String modelRevision,
            String endpointIdentity,
            String promptVersion,
            String schemaVersion,
            String counterVersion,
            UUID leaseToken,
            int attemptCount,
            int maxAttempts
    ) {
    }

    private record PreparedJob(
            UUID ownerUserId,
            UUID sessionId,
            SummaryArtifact parent,
            List<SourceMessage> deltaSources,
            List<SourceMessage> coveredSources,
            String inputHash,
            String reasonCode
    ) {
        static PreparedJob reason(String code) {
            return new PreparedJob(
                    null, null, null, List.of(), List.of(), null, code
            );
        }
    }

    private record JobState(
            String status,
            String errorCode,
            Instant updatedAt
    ) {
    }
}

final class ContextCompressionException extends RuntimeException {

    private final String code;

    ContextCompressionException(String code, String message) {
        super(message);
        this.code = code;
    }

    ContextCompressionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
