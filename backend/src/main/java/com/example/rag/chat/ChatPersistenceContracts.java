package com.example.rag.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatPersistenceContracts {

    private ChatPersistenceContracts() {
    }

    public enum SessionStatus {
        ACTIVE,
        ARCHIVED
    }

    public enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    public enum MessageStatus {
        PENDING,
        STREAMING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum RunStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        REFUSED,
        FAILED,
        CANCELLED
    }

    public enum AnswerStrategy {
        STANDARD,
        DEEP_GLOBAL
    }

    public record ChatSession(
            UUID id,
            UUID ownerUserId,
            String title,
            SessionStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ChatMessage(
            UUID id,
            UUID ownerUserId,
            UUID sessionId,
            int sequenceNumber,
            MessageRole role,
            String content,
            String language,
            MessageStatus status,
            Integer tokenCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ChatRun(
            UUID id,
            UUID ownerUserId,
            UUID sessionId,
            UUID requestMessageId,
            UUID responseMessageId,
            String orchestrationVersion,
            String standaloneQuery,
            String subQueriesJson,
            String budgetUsageJson,
            String fallbackPath,
            String retrievalProfileVersion,
            Long indexGeneration,
            String graphProfileVersion,
            Long graphGeneration,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            String globalConfigVersion,
            Long globalGeneration,
            String answerStrategyRequested,
            String answerStrategyUsed,
            int mapCallCount,
            int reduceCallCount,
            String finalEvidenceIdsJson,
            String finalSourceSpansJson,
            String trimReasonsJson,
            String queryIntelligenceProfileVersion,
            String historyMessageIdsJson,
            String historySnapshotHash,
            String historyCounterVersion,
            int historyTokenCount,
            String historyTrimReasonsJson,
            String contextCompressionPolicyVersion,
            UUID historySummaryId,
            int historySummaryTokenCount,
            int historySummarySourceCount,
            String contextCompressionStatus,
            String contextCompressionReasonCode,
            String traceId,
            RunStatus status,
            String errorCode,
            String errorDetail,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt
    ) {
        public ChatRun(
                UUID id,
                UUID ownerUserId,
                UUID sessionId,
                UUID requestMessageId,
                UUID responseMessageId,
                String orchestrationVersion,
                String standaloneQuery,
                String subQueriesJson,
                String budgetUsageJson,
                String fallbackPath,
                String retrievalProfileVersion,
                Long indexGeneration,
                String finalEvidenceIdsJson,
                String finalSourceSpansJson,
                String trimReasonsJson,
                String traceId,
                RunStatus status,
                String errorCode,
                String errorDetail,
                Instant createdAt,
                Instant startedAt,
                Instant completedAt,
                Instant updatedAt
        ) {
            this(
                    id, ownerUserId, sessionId, requestMessageId,
                    responseMessageId, orchestrationVersion, standaloneQuery,
                    subQueriesJson, budgetUsageJson, fallbackPath,
                    retrievalProfileVersion, indexGeneration,
                    null, null, null, null, false, null,
                    null, null, AnswerStrategy.STANDARD.name(), null, 0, 0,
                    finalEvidenceIdsJson, finalSourceSpansJson, trimReasonsJson,
                    null, "[]", null, null, 0, "[]",
                    null, null, 0, 0, null, null,
                    traceId, status, errorCode, errorDetail, createdAt,
                    startedAt, completedAt, updatedAt
            );
        }
    }

    public record Citation(
            UUID id,
            UUID ownerUserId,
            UUID sessionId,
            UUID runId,
            UUID messageId,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            int order,
            Integer startPage,
            Integer endPage,
            int startOffset,
            int endOffset,
            String sourceTextHash,
            Instant createdAt
    ) {
    }

    public record SessionDetail(
            ChatSession session,
            List<ChatMessage> messages,
            List<ChatRun> runs
    ) {
    }

    public record StartRunCommand(
            String question,
            String language,
            String orchestrationVersion,
            String traceId,
            String graphModeRequested,
            String answerStrategyRequested,
            String queryIntelligenceProfileVersion,
            String contextCompressionPolicyVersion
    ) {
        public StartRunCommand(
                String question,
                String language,
                String orchestrationVersion,
                String traceId,
                String graphModeRequested,
                String answerStrategyRequested,
                String queryIntelligenceProfileVersion
        ) {
            this(
                    question, language, orchestrationVersion, traceId,
                    graphModeRequested, answerStrategyRequested,
                    queryIntelligenceProfileVersion,
                    ContextCompressionService.POLICY_VERSION
            );
        }

        public StartRunCommand(
                String question,
                String language,
                String orchestrationVersion,
                String traceId
        ) {
            this(
                    question, language, orchestrationVersion, traceId, "HYBRID",
                    AnswerStrategy.STANDARD.name(), null,
                    ContextCompressionService.POLICY_VERSION
            );
        }

        public StartRunCommand(
                String question,
                String language,
                String orchestrationVersion,
                String traceId,
                String graphModeRequested
        ) {
            this(
                    question, language, orchestrationVersion, traceId,
                    graphModeRequested, AnswerStrategy.STANDARD.name(), null,
                    ContextCompressionService.POLICY_VERSION
            );
        }

        public StartRunCommand(
                String question,
                String language,
                String orchestrationVersion,
                String traceId,
                String graphModeRequested,
                String answerStrategyRequested
        ) {
            this(
                    question, language, orchestrationVersion, traceId,
                    graphModeRequested, answerStrategyRequested, null,
                    ContextCompressionService.POLICY_VERSION
            );
        }
    }

    public record StartedRun(
            ChatMessage requestMessage,
            ChatMessage responseMessage,
            ChatRun run
    ) {
    }

    public record CitationDraft(
            UUID id,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId
    ) {
    }

    public record RunRetrievalSnapshot(
            String fallbackPath,
            String retrievalProfileVersion,
            Long indexGeneration,
            String graphProfileVersion,
            Long graphGeneration,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            String globalConfigVersion,
            Long globalGeneration
    ) {
        public RunRetrievalSnapshot(
                String fallbackPath,
                String retrievalProfileVersion,
                Long indexGeneration,
                String graphProfileVersion,
                Long graphGeneration,
                String graphModeRequested,
                String graphModeUsed,
                boolean graphDegraded,
                String graphDegradationCode
        ) {
            this(
                    fallbackPath, retrievalProfileVersion, indexGeneration,
                    graphProfileVersion, graphGeneration, graphModeRequested,
                    graphModeUsed, graphDegraded, graphDegradationCode,
                    null, null
            );
        }
    }

    public record RunHistorySnapshot(
            String messageIdsJson,
            String snapshotHash,
            String counterVersion,
            int tokenCount,
            String trimReasonsJson,
            String compressionPolicyVersion,
            UUID summaryId,
            int summaryTokenCount,
            int summarySourceCount,
            String compressionStatus,
            String compressionReasonCode
    ) {
        public RunHistorySnapshot(
                String messageIdsJson,
                String snapshotHash,
                String counterVersion,
                int tokenCount,
                String trimReasonsJson
        ) {
            this(
                    messageIdsJson, snapshotHash, counterVersion, tokenCount,
                    trimReasonsJson, null, null, 0, 0, null, null
            );
        }
    }

    public record RunQueryPlanSnapshot(
            String standaloneQuery,
            String querySlotsJson,
            String budgetUsageJson,
            String fallbackPath
    ) {
    }

    public record RunCompletion(
            RunStatus status,
            String responseContent,
            String language,
            Integer tokenCount,
            String budgetUsageJson,
            String fallbackPath,
            String retrievalProfileVersion,
            Long indexGeneration,
            String graphProfileVersion,
            Long graphGeneration,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            String globalConfigVersion,
            Long globalGeneration,
            String answerStrategyRequested,
            String answerStrategyUsed,
            int mapCallCount,
            int reduceCallCount,
            String finalEvidenceIdsJson,
            String finalSourceSpansJson,
            String trimReasonsJson
    ) {
        public RunCompletion(
                RunStatus status,
                String responseContent,
                String language,
                Integer tokenCount,
                String budgetUsageJson,
                String fallbackPath,
                String retrievalProfileVersion,
                Long indexGeneration,
                String finalEvidenceIdsJson,
                String finalSourceSpansJson,
                String trimReasonsJson
        ) {
            this(
                    status, responseContent, language, tokenCount,
                    budgetUsageJson, fallbackPath, retrievalProfileVersion,
                    indexGeneration, null, null, "HYBRID", "HYBRID",
                    false, null, null, null,
                    AnswerStrategy.STANDARD.name(),
                    AnswerStrategy.STANDARD.name(), 0, 0, finalEvidenceIdsJson,
                    finalSourceSpansJson, trimReasonsJson
            );
        }
    }
}
