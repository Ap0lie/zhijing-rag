package com.example.rag.search;

import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.persistence.DocumentVisibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SearchContracts {

    private static final int MAX_RESULT_WINDOW = 10_000;
    public static final int MAX_QUERY_LENGTH = 500;

    private SearchContracts() {
    }

    public record SearchRequest(
            @NotBlank @Size(max = MAX_QUERY_LENGTH) String query,
            Integer page,
            Integer size,
            UUID documentId,
            DocumentVisibility visibility,
            GraphMode graphModeRequested
    ) {
        public SearchRequest(
                String query,
                Integer page,
                Integer size,
                UUID documentId,
                DocumentVisibility visibility
        ) {
            this(query, page, size, documentId, visibility, null);
        }

        GraphMode requestedGraphMode() {
            return graphModeRequested == null
                    ? GraphMode.HYBRID
                    : graphModeRequested;
        }

        int safePage() {
            int requested = page == null ? 0 : Math.max(0, page);
            int maximum = Math.max(0, maxPages() - 1);
            return Math.min(requested, maximum);
        }

        int safeSize() {
            return size == null ? 20 : Math.min(Math.max(size, 1), 50);
        }

        int maxPages() {
            return MAX_RESULT_WINDOW / safeSize();
        }
    }

    public record SearchPage(
            List<SearchHit> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            long tookMs,
            String profileVersion,
            long indexGeneration,
            String modeRequested,
            String modeUsed,
            boolean degraded,
            String degradationCode,
            String totalRelation,
            String graphProfileVersion,
            Long graphGeneration,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            GlobalExecution globalExecution,
            RouteExecution routeExecution,
            QueryExecution queryExecution
    ) {
        public SearchPage(
                List<SearchHit> items,
                int page,
                int size,
                long totalElements,
                int totalPages,
                long tookMs,
                String profileVersion,
                long indexGeneration,
                String modeRequested,
                String modeUsed,
                boolean degraded,
                String degradationCode,
                String totalRelation
        ) {
            this(
                    items, page, size, totalElements, totalPages, tookMs,
                    profileVersion, indexGeneration, modeRequested, modeUsed,
                    degraded, degradationCode, totalRelation,
                    null, null, GraphMode.HYBRID.name(),
                    GraphMode.HYBRID.name(), false, null, null,
                    RouteExecution.explicit(GraphMode.HYBRID),
                    QueryExecution.single("")
            );
        }

        static SearchPage empty(
                SearchRequest request,
                SearchMetadata metadata,
                QueryExecution queryExecution
        ) {
            return new SearchPage(
                    List.of(),
                    request.safePage(),
                    request.safeSize(),
                    0,
                    0,
                    0,
                    metadata.profileVersion(),
                    metadata.indexGeneration(),
                    metadata.modeRequested(),
                    metadata.modeUsed(),
                    metadata.degraded(),
                    metadata.degradationCode(),
                    "EXACT",
                    metadata.graphProfileVersion(),
                    metadata.graphGeneration(),
                    metadata.graphModeRequested(),
                    metadata.graphModeUsed(),
                    metadata.graphDegraded(),
                    metadata.graphDegradationCode(),
                    metadata.globalExecution(),
                    metadata.routeExecution(),
                    queryExecution
            );
        }
    }

    public record RouteExecution(
            String requestedMode,
            String selectedMode,
            int routerCallCount,
            String reasonCode,
            boolean degraded,
            String degradationCode
    ) {
        public static RouteExecution explicit(GraphMode mode) {
            GraphMode value = mode == null ? GraphMode.HYBRID : mode;
            return new RouteExecution(
                    value.name(), value.name(), 0,
                    "EXPLICIT_MODE", false, null
            );
        }
    }

    public record QueryExecution(
            String standaloneQuery,
            List<QuerySlot> slots,
            int plannerCallCount,
            int retrievalCallCount,
            int rerankCallCount,
            boolean coverageSufficient,
            boolean degraded,
            String degradationCode,
            int retrievedCandidateCount,
            int authorizedCandidateCount,
            int rerankedCandidateCount,
            int evidenceCandidateCount
    ) {
        public QueryExecution {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }

        public static QueryExecution single(String query) {
            String value = query == null ? "" : query.strip();
            return new QueryExecution(
                    value,
                    value.isEmpty()
                            ? List.of()
                            : List.of(new QuerySlot(
                            1, 1, value, "PENDING", 0, null
                    )),
                    0,
                    value.isEmpty() ? 0 : 1,
                    0,
                    false,
                    false,
                    null,
                    0,
                    0,
                    0,
                    0
            );
        }
    }

    public record QuerySlot(
            int round,
            int slot,
            String query,
            String status,
            int candidateCount,
            String degradationCode
    ) {
    }

    record SearchMetadata(
            String profileVersion,
            long indexGeneration,
            String modeRequested,
            String modeUsed,
            boolean degraded,
            String degradationCode,
            String graphProfileVersion,
            Long graphGeneration,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            GlobalExecution globalExecution,
            RouteExecution routeExecution
    ) {
    }

    public enum GraphMode {
        AUTO,
        HYBRID,
        LOCAL_GRAPH,
        GLOBAL_GRAPH
    }

    public record SearchHit(
            UUID chunkId,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            List<String> headingPath,
            Integer startPage,
            Integer endPage,
            String snippet,
            EvidenceContext evidence,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public SearchHit(
                UUID chunkId,
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                List<String> headingPath,
                int startPage,
                int endPage,
                String snippet,
                EvidenceContext evidence
        ) {
            this(
                    chunkId,
                    documentId,
                    documentTitle,
                    revisionId,
                    revisionNumber,
                    headingPath,
                    startPage,
                    endPage,
                    snippet,
                    evidence,
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

    public record EvidenceContext(
            int rank,
            double retrievalScore,
            Double rerankScore,
            String childText,
            int childTokenCount,
            ParentContext parent,
            List<GraphPathView> graphPaths,
            List<GlobalClaimView> globalClaims,
            @JsonIgnore List<String> querySlots
    ) {
        public EvidenceContext(
                int rank,
                double retrievalScore,
                Double rerankScore,
                String childText,
                int childTokenCount,
                ParentContext parent
        ) {
            this(
                    rank, retrievalScore, rerankScore, childText,
                    childTokenCount, parent, List.of(), List.of(), List.of()
            );
        }

        public EvidenceContext(
                int rank,
                double retrievalScore,
                Double rerankScore,
                String childText,
                int childTokenCount,
                ParentContext parent,
                List<GraphPathView> graphPaths
        ) {
            this(
                    rank, retrievalScore, rerankScore, childText,
                    childTokenCount, parent, graphPaths, List.of(), List.of()
            );
        }

        public EvidenceContext(
                int rank,
                double retrievalScore,
                Double rerankScore,
                String childText,
                int childTokenCount,
                ParentContext parent,
                List<GraphPathView> graphPaths,
                List<GlobalClaimView> globalClaims
        ) {
            this(
                    rank, retrievalScore, rerankScore, childText,
                    childTokenCount, parent, graphPaths, globalClaims,
                    List.of()
            );
        }
    }

    public record GraphPathView(
            int depth,
            UUID relationshipId,
            String relationshipType,
            UUID supportingChunkId,
            UUID sourceSpanId,
            UUID documentId,
            String documentTitle,
            Integer startPage,
            Integer endPage,
            String evidenceText,
            int contributedTokens,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public GraphPathView(
                int depth,
                UUID relationshipId,
                String relationshipType,
                UUID supportingChunkId,
                UUID sourceSpanId,
                UUID documentId,
                String documentTitle,
                int startPage,
                int endPage,
                String evidenceText,
                int contributedTokens
        ) {
            this(
                    depth,
                    relationshipId,
                    relationshipType,
                    supportingChunkId,
                    sourceSpanId,
                    documentId,
                    documentTitle,
                    startPage,
                    endPage,
                    evidenceText,
                    contributedTokens,
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

    public record GlobalClaimView(
            UUID reportId,
            String reportTitle,
            int communityKey,
            UUID claimId,
            String claimText,
            UUID supportingChunkId,
            UUID sourceSpanId,
            UUID documentId,
            String documentTitle,
            Integer startPage,
            Integer endPage,
            String evidenceText,
            int contributedTokens,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public GlobalClaimView(
                UUID reportId,
                String reportTitle,
                int communityKey,
                UUID claimId,
                String claimText,
                UUID supportingChunkId,
                UUID sourceSpanId,
                UUID documentId,
                String documentTitle,
                int startPage,
                int endPage,
                String evidenceText,
                int contributedTokens
        ) {
            this(
                    reportId,
                    reportTitle,
                    communityKey,
                    claimId,
                    claimText,
                    supportingChunkId,
                    sourceSpanId,
                    documentId,
                    documentTitle,
                    startPage,
                    endPage,
                    evidenceText,
                    contributedTokens,
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

    public record ParentContext(
            UUID chunkId,
            String text,
            List<String> headingPath,
            Integer startPage,
            Integer endPage,
            int tokenCount,
            int contributedTokens,
            boolean truncated,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public ParentContext(
                UUID chunkId,
                String text,
                List<String> headingPath,
                int startPage,
                int endPage,
                int tokenCount,
                int contributedTokens,
                boolean truncated
        ) {
            this(
                    chunkId,
                    text,
                    headingPath,
                    startPage,
                    endPage,
                    tokenCount,
                    contributedTokens,
                    truncated,
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

    public record DebugCandidate(
            int rank,
            double score,
            Integer bm25Rank,
            Integer vectorRank,
            Double rrfScore,
            Integer graphRank,
            List<GraphPathView> graphPaths,
            Integer globalRank,
            List<GlobalClaimView> globalClaims,
            Integer rerankRank,
            Double rerankScore,
            Integer evidenceRank,
            List<String> matchedFields,
            boolean accepted,
            String rejectionReason,
            SearchHit result
    ) {
        public DebugCandidate(
                int rank,
                double score,
                Integer bm25Rank,
                Integer vectorRank,
                Double rrfScore,
                Integer rerankRank,
                Double rerankScore,
                Integer evidenceRank,
                List<String> matchedFields,
                boolean accepted,
                String rejectionReason,
                SearchHit result
        ) {
            this(
                    rank, score, bm25Rank, vectorRank, rrfScore, null,
                    List.of(), null, List.of(),
                    rerankRank, rerankScore, evidenceRank, matchedFields,
                    accepted, rejectionReason, result
            );
        }
    }

    public record DebugStage(
            String name,
            String status,
            int inputCount,
            int outputCount,
            long tookMs,
            String code
    ) {
    }

    public record ContextBudget(
            int limitTokens,
            int childTokens,
            int parentTokens,
            int totalTokens,
            int parentCount,
            int graphTokens,
            int graphPathCount,
            int globalTokens,
            int globalClaimCount,
            List<String> trimReasons
    ) {
        public ContextBudget(
                int limitTokens,
                int childTokens,
                int parentTokens,
                int totalTokens,
                int parentCount,
                List<String> trimReasons
        ) {
            this(
                    limitTokens, childTokens, parentTokens, totalTokens,
                    parentCount, 0, 0, 0, 0, trimReasons
            );
        }

        public ContextBudget(
                int limitTokens,
                int childTokens,
                int parentTokens,
                int totalTokens,
                int parentCount,
                int graphTokens,
                int graphPathCount,
                List<String> trimReasons
        ) {
            this(
                    limitTokens, childTokens, parentTokens, totalTokens,
                    parentCount, graphTokens, graphPathCount,
                    0, 0, trimReasons
            );
        }

        static ContextBudget empty() {
            return new ContextBudget(
                    0, 0, 0, 0, 0, 0, 0, List.of()
            );
        }
    }

    public record SearchDebugResponse(
            String query,
            String retrievalProfile,
            String indexName,
            long indexGeneration,
            String modeRequested,
            String modeUsed,
            boolean degraded,
            String degradationCode,
            String graphProfileVersion,
            Long graphGeneration,
            String graphModeRequested,
            String graphModeUsed,
            boolean graphDegraded,
            String graphDegradationCode,
            GlobalExecution globalExecution,
            long tookMs,
            List<DebugStage> stages,
            ContextBudget contextBudget,
            GraphDiagnostics graphDiagnostics,
            List<DebugCandidate> candidates,
            SearchPage result
    ) {
    }

    public record GraphDiagnostics(
            int seedEntityCount,
            List<UUID> seedDocumentIds,
            int graphCandidateCount,
            int graphAddedCandidateCount,
            int pathCount,
            GraphEntityLinkShadowDiagnostics entityLinkShadow
    ) {
        public GraphDiagnostics {
            seedDocumentIds = seedDocumentIds == null
                    ? List.of()
                    : List.copyOf(seedDocumentIds);
            entityLinkShadow = entityLinkShadow == null
                    ? GraphEntityLinkShadowDiagnostics.notRequested()
                    : entityLinkShadow;
        }

        public static GraphDiagnostics empty() {
            return new GraphDiagnostics(
                    0, List.of(), 0, 0, 0,
                    GraphEntityLinkShadowDiagnostics.notRequested()
            );
        }
    }

    public record GraphEntityLinkShadowDiagnostics(
            boolean measured,
            int seedEntityCount,
            List<UUID> seedDocumentIds,
            int addedSeedEntityCount,
            List<String> matchModes,
            String reasonCode
    ) {
        public GraphEntityLinkShadowDiagnostics {
            seedDocumentIds = seedDocumentIds == null
                    ? List.of() : List.copyOf(seedDocumentIds);
            matchModes = matchModes == null
                    ? List.of() : List.copyOf(matchModes);
        }

        public static GraphEntityLinkShadowDiagnostics notRequested() {
            return new GraphEntityLinkShadowDiagnostics(
                    false, 0, List.of(), 0, List.of(),
                    "GRAPH_ENTITY_LINK_SHADOW_NOT_REQUESTED"
            );
        }
    }

    public record GlobalExecution(
            String configVersion,
            Long globalGeneration,
            int reportCount,
            int reportLimit,
            int modelCallLimit,
            int hardTimeoutMs,
            boolean shadow
    ) {
    }

    public record ChunkContext(
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            ChunkView child,
            ChunkView parent,
            List<SourceSpanView> sourceSpans,
            String documentFormat
    ) {
        public ChunkContext(
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                ChunkView child,
                ChunkView parent,
                List<SourceSpanView> sourceSpans
        ) {
            this(
                    documentId,
                    documentTitle,
                    revisionId,
                    revisionNumber,
                    child,
                    parent,
                    sourceSpans,
                    "PDF"
            );
        }
    }

    public record ChunkView(
            UUID id,
            String type,
            int order,
            String text,
            List<String> headingPath,
            Integer startPage,
            Integer endPage,
            int tokenCount,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public ChunkView(
                UUID id,
                String type,
                int order,
                String text,
                List<String> headingPath,
                int startPage,
                int endPage,
                int tokenCount
        ) {
            this(
                    id,
                    type,
                    order,
                    text,
                    headingPath,
                    startPage,
                    endPage,
                    tokenCount,
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

    public record SourceSpanView(
            UUID id,
            int order,
            Integer startPage,
            Integer endPage,
            int startOffset,
            int endOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public SourceSpanView(
                UUID id,
                int order,
                int startPage,
                int endPage,
                int startOffset,
                int endOffset,
                int chunkStartOffset,
                int chunkEndOffset,
                String sourceTextHash
        ) {
            this(
                    id,
                    order,
                    startPage,
                    endPage,
                    startOffset,
                    endOffset,
                    chunkStartOffset,
                    chunkEndOffset,
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

    public record IndexStatus(
            String indexName,
            long indexGeneration,
            long documentCount,
            long chunkCount,
            String status,
            Instant updatedAt,
            boolean rebuilding
    ) {
        static IndexStatus uninitialized() {
            return new IndexStatus("", 0, 0, 0, "UNINITIALIZED", null, false);
        }
    }
}
