package com.example.rag.chat;

import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.search.SearchContracts;
import com.example.rag.chat.ChatPersistenceContracts.AnswerStrategy;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.QuerySlot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatApiContracts {

    private ChatApiContracts() {
    }

    public record CreateSessionRequest(@Size(max = 200) String title) {
    }

    public record RenameSessionRequest(
            @NotBlank @Size(max = 200) String title
    ) {
    }

    public record StartRunRequest(
            @NotBlank @Size(max = SearchContracts.MAX_QUERY_LENGTH) String question,
            GraphMode graphModeRequested,
            AnswerStrategy answerStrategyRequested
    ) {
        public StartRunRequest(String question, GraphMode graphModeRequested) {
            this(question, graphModeRequested, null);
        }
    }

    public record SessionListResponse(List<SessionSummary> items) {
    }

    public record SessionSummary(
            UUID id,
            String title,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record SessionDetailResponse(
            UUID id,
            String title,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<MessageView> messages,
            List<RunView> runs
    ) {
    }

    public record MessageView(
            UUID id,
            String role,
            String status,
            String content,
            String language,
            UUID runId,
            boolean hidden,
            Instant createdAt,
            List<CitationSummary> citations,
            String memorySuggestionStatus,
            int memorySuggestionCount,
            String memorySuggestionErrorCode
    ) {
    }

    public record MemorySuggestionStatusItem(
            UUID messageId,
            String status,
            int suggestionCount,
            String errorCode
    ) {
    }

    public record MemorySuggestionStatusResponse(
            List<MemorySuggestionStatusItem> items,
            boolean pending
    ) {
    }

    public record ContextStatusResponse(
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

    public record RunView(
            UUID id,
            String status,
            String errorCode,
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
            String queryProfileVersion,
            List<UUID> historyMessageIds,
            String historyCounterVersion,
            int historyTokenCount,
            List<String> historyTrimReasons,
            String contextCompressionPolicyVersion,
            UUID historySummaryId,
            int historySummaryTokenCount,
            int historySummarySourceCount,
            String contextCompressionStatus,
            String contextCompressionReasonCode,
            int memoryUsedCount,
            int memoryTokenCount,
            String memoryDegradationCode,
            String standaloneQuery,
            List<QuerySlot> querySlots,
            int plannerCallCount,
            int retrievalCallCount,
            int rerankCallCount,
            boolean coverageSufficient,
            boolean queryDegraded,
            String queryDegradationCode,
            Integer retrievedCandidateCount,
            Integer authorizedCandidateCount,
            Integer rerankedCandidateCount,
            Integer evidenceCandidateCount,
            Integer validatedEvidenceCount,
            String routeSelectedMode,
            int routerCallCount,
            String routeReasonCode,
            boolean routeDegraded,
            String routeDegradationCode,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record CitationSummary(
            UUID id,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            UUID chunkId,
            Integer startPage,
            Integer endPage,
            String label,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public CitationSummary(
                UUID id,
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                UUID chunkId,
                int startPage,
                int endPage,
                String label
        ) {
            this(
                    id,
                    documentId,
                    documentTitle,
                    revisionId,
                    revisionNumber,
                    chunkId,
                    startPage,
                    endPage,
                    label,
                    "PDF",
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
            );
        }
    }

    public record CitationDetail(
            UUID id,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            UUID chunkId,
            Integer startPage,
            Integer endPage,
            String label,
            String childText,
            List<String> headingPath,
            SourceSpanDetail sourceSpan,
            String parentText,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public CitationDetail(
                UUID id,
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                UUID chunkId,
                int startPage,
                int endPage,
                String label,
                String childText,
                List<String> headingPath,
                SourceSpanDetail sourceSpan,
                String parentText
        ) {
            this(
                    id,
                    documentId,
                    documentTitle,
                    revisionId,
                    revisionNumber,
                    chunkId,
                    startPage,
                    endPage,
                    label,
                    childText,
                    headingPath,
                    sourceSpan,
                    parentText,
                    "PDF",
                    sourceSpan == null
                            ? SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    )
                            : sourceSpan.sourceLocator(),
                    sourceSpan == null
                            ? SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
                            : sourceSpan.sourceLabel()
            );
        }
    }

    public record SourceSpanDetail(
            UUID id,
            int order,
            Integer startPage,
            Integer endPage,
            int startOffset,
            int endOffset,
            String sourceTextHash,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public SourceSpanDetail(
                UUID id,
                int order,
                int startPage,
                int endPage,
                int startOffset,
                int endOffset,
                String sourceTextHash
        ) {
            this(
                    id,
                    order,
                    startPage,
                    endPage,
                    startOffset,
                    endOffset,
                    sourceTextHash,
                    "PDF",
                    SourceLocatorResponse.pdf(
                            null,
                            null,
                            startPage,
                            endPage,
                            startOffset,
                            endOffset,
                            null,
                            sourceTextHash,
                            "pdf-page-compat-v1"
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
            );
        }
    }

    public record AnswerDeltaEvent(UUID runId, UUID messageId, String text) {
    }

    public record CitationEvent(UUID runId, CitationSummary citation) {
    }

    public record MemoryUsedSummary(
            UUID memoryId,
            String memoryType,
            String usageStatus
    ) {
    }

    public record MemoryUsedEvent(
            UUID runId,
            List<MemoryUsedSummary> memories
    ) {
    }

    public record ContextUsedEvent(
            UUID runId,
            ContextStatusResponse context
    ) {
    }

    public record CompletedEvent(
            UUID runId,
            String status,
            UUID messageId,
            String refusalCode,
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
            String queryProfileVersion,
            int historyMessageCount,
            int historyTokenCount,
            List<String> historyTrimReasons,
            String standaloneQuery,
            List<QuerySlot> querySlots,
            int plannerCallCount,
            int retrievalCallCount,
            int rerankCallCount,
            boolean coverageSufficient,
            boolean queryDegraded,
            String queryDegradationCode,
            int retrievedCandidateCount,
            int authorizedCandidateCount,
            int rerankedCandidateCount,
            int evidenceCandidateCount,
            int validatedEvidenceCount,
            String routeSelectedMode,
            int routerCallCount,
            String routeReasonCode,
            boolean routeDegraded,
            String routeDegradationCode
    ) {
    }

    public record FailedEvent(
            UUID runId,
            String status,
            String code,
            String message
    ) {
    }
}
