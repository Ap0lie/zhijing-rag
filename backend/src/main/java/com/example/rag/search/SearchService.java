package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.graph.GlobalGraphRetrievalService;
import com.example.rag.graph.GlobalGraphRetrievalService.GlobalCandidate;
import com.example.rag.graph.GlobalGraphRetrievalService.GlobalClaim;
import com.example.rag.graph.GlobalGraphRetrievalService.GlobalEvidence;
import com.example.rag.graph.LocalGraphRetrievalService;
import com.example.rag.graph.LocalGraphRetrievalService.Expansion;
import com.example.rag.graph.LocalGraphRetrievalService.GraphCandidate;
import com.example.rag.graph.LocalGraphRetrievalService.GraphPath;
import com.example.rag.search.EvidenceContextService.ContextPlan;
import com.example.rag.search.EvidenceContextService.ContextSeed;
import com.example.rag.search.EvidenceContextService.Material;
import com.example.rag.search.ModelCircuitBreakers.ModelType;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalMode;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalProfileView;
import com.example.rag.search.SearchAccessService.AuthorizedRevision;
import com.example.rag.search.SearchContracts.ContextBudget;
import com.example.rag.search.SearchContracts.DebugCandidate;
import com.example.rag.search.SearchContracts.DebugStage;
import com.example.rag.search.SearchContracts.EvidenceContext;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.GraphDiagnostics;
import com.example.rag.search.SearchContracts.GraphPathView;
import com.example.rag.search.SearchContracts.GlobalClaimView;
import com.example.rag.search.SearchContracts.GlobalExecution;
import com.example.rag.search.SearchContracts.QueryExecution;
import com.example.rag.search.SearchContracts.QuerySlot;
import com.example.rag.search.SearchContracts.RouteExecution;
import com.example.rag.search.SearchContracts.SearchDebugResponse;
import com.example.rag.search.SearchContracts.SearchHit;
import com.example.rag.search.SearchContracts.SearchMetadata;
import com.example.rag.search.SearchContracts.SearchPage;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.search.SearchIndexService.ActiveIndex;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
public class SearchService {

    private static final List<String> QUERY_FIELDS = List.of(
            "title^4", "title.english^4",
            "heading^2", "heading.english^2",
            "text", "text.english"
    );
    private static final List<String> SOURCE_FIELDS = List.of(
            "chunkId", "documentId", "documentTitle", "revisionId",
            "revisionNumber", "headingPath", "startPage", "endPage",
            "documentFormat", "sourceLocator", "sourceLabel",
            "text", "aclVersion"
    );
    private static final Pattern QUERY_TERM = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Pattern BRIDGE_ENTITY = Pattern.compile(
            "(?U)\\b(?:[A-Z][\\p{L}\\p{M}'’.-]{1,}|[A-Z]{2,})"
                    + "(?:\\s+(?:[A-Z][\\p{L}\\p{M}'’.-]{1,}|[A-Z]{2,}|&)){1,4}\\b"
    );
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "did",
            "do", "does", "for", "from", "had", "has", "have", "how",
            "in", "is", "of", "on", "or", "that", "the", "to", "was",
            "were", "what", "when", "where", "which", "who", "with"
    );
    private static final Set<String> BRIDGE_STOP_VALUES = Set.of(
            "article", "document", "evidence", "introduction",
            "references", "source", "summary"
    );
    private static final double SECOND_ROUND_RRF_WEIGHT = 0.25;
    private static final long SECOND_HOP_DOWNSTREAM_RESERVE_NANOS =
            TimeUnit.MILLISECONDS.toNanos(1_500);

    private final SearchAccessService access;
    private final SearchIndexService indexes;
    private final OpenSearchGateway openSearch;
    private final RetrievalConfigurationRepository configurations;
    private final EmbeddingCacheService embeddings;
    private final RerankProvider reranker;
    private final EvidenceContextService contexts;
    private final LocalGraphRetrievalService graphs;
    private final Optional<GlobalGraphRetrievalService> globalGraphs;
    private final ModelCircuitBreakers circuits;
    private final SearchProperties properties;
    private final ExecutorService executor;

    SearchService(
            SearchAccessService access,
            SearchIndexService indexes,
            OpenSearchGateway openSearch,
            RetrievalConfigurationRepository configurations,
            EmbeddingCacheService embeddings,
            RerankProvider reranker,
            EvidenceContextService contexts,
            LocalGraphRetrievalService graphs,
            Optional<GlobalGraphRetrievalService> globalGraphs,
            ModelCircuitBreakers circuits,
            SearchProperties properties,
            @Qualifier("searchBranchExecutor") ExecutorService executor
    ) {
        this.access = access;
        this.indexes = indexes;
        this.openSearch = openSearch;
        this.configurations = configurations;
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.contexts = contexts;
        this.graphs = graphs;
        this.globalGraphs = globalGraphs;
        this.circuits = circuits;
        this.properties = properties;
        this.executor = executor;
    }

    public SearchPage search(SearchRequest request, PlatformUserPrincipal user) {
        return execute(
                request, user, null, null, null, null, null, List.of()
        ).page();
    }

    SearchDebugResponse debug(SearchRequest request, PlatformUserPrincipal user) {
        return debugResponse(
                request,
                execute(
                        request, user, null, null, null, null, null,
                        List.of()
                )
        );
    }

    public SearchPage search(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target
    ) {
        return execute(
                request, user, target, null, null, null, null, List.of()
        ).page();
    }

    public SearchPage search(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            List<UUID> supplementalChildIds
    ) {
        return execute(
                request, user, target, null, null, null, null,
                supplementalChildIds
        ).page();
    }

    public SearchPage searchPlanned(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan initialPlan,
            SecondRoundPlanner secondRoundPlanner
    ) {
        return execute(
                request, user, target, initialPlan, secondRoundPlanner,
                null, null, List.of()
        ).page();
    }

    public SearchPage searchPlanned(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan initialPlan,
            SecondRoundPlanner secondRoundPlanner,
            RoutingDecision routing
    ) {
        return execute(
                request, user, target, initialPlan,
                secondRoundPlanner, routing, null, List.of()
        ).page();
    }

    public SearchPage searchPlanned(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan initialPlan,
            SecondRoundPlanner secondRoundPlanner,
            RoutingDecision routing,
            QueryExecutionPolicy policy
    ) {
        return execute(
                request, user, target, initialPlan,
                secondRoundPlanner, routing, policy, List.of()
        ).page();
    }

    public SearchPage searchPlanned(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan initialPlan,
            SecondRoundPlanner secondRoundPlanner,
            RoutingDecision routing,
            QueryExecutionPolicy policy,
            List<UUID> supplementalChildIds
    ) {
        return execute(
                request, user, target, initialPlan,
                secondRoundPlanner, routing, policy, supplementalChildIds
        ).page();
    }

    public SearchDebugResponse evaluate(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target
    ) {
        return debugResponse(
                request,
                execute(
                        request, user, target, null, null, null, null,
                        List.of()
                )
        );
    }

    public SearchDebugResponse evaluatePlanned(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan plan,
            RoutingDecision routing
    ) {
        return debugResponse(
                request,
                execute(
                        request, user, target, plan, null, routing, null,
                        List.of()
                )
        );
    }

    public SearchDebugResponse evaluatePlanned(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan plan,
            SecondRoundPlanner secondRoundPlanner,
            RoutingDecision routing,
            QueryExecutionPolicy policy
    ) {
        return debugResponse(
                request,
                execute(
                        request, user, target, plan,
                        secondRoundPlanner, routing, policy, List.of()
                )
        );
    }

    private SearchDebugResponse debugResponse(
            SearchRequest request,
            SearchExecution execution
    ) {
        SearchMetadata metadata = execution.metadata();
        return new SearchDebugResponse(
                request.query().trim(),
                metadata.profileVersion(),
                execution.indexName(),
                metadata.indexGeneration(),
                metadata.modeRequested(),
                metadata.modeUsed(),
                metadata.degraded(),
                metadata.degradationCode(),
                metadata.graphProfileVersion(),
                metadata.graphGeneration(),
                metadata.graphModeRequested(),
                metadata.graphModeUsed(),
                metadata.graphDegraded(),
                metadata.graphDegradationCode(),
                metadata.globalExecution(),
                execution.page().tookMs(),
                execution.stages(),
                execution.contextBudget(),
                execution.graphDiagnostics(),
                execution.candidates(),
                execution.page()
        );
    }

    private SearchExecution execute(
            SearchRequest request,
            PlatformUserPrincipal user,
            EvaluationTarget target,
            QueryPlan requestedPlan,
            SecondRoundPlanner secondRoundPlanner,
            RoutingDecision requestedRouting,
            QueryExecutionPolicy requestedPolicy,
            List<UUID> requestedSupplementalChildIds
    ) {
        String rawQuery = request.query() == null ? "" : request.query();
        if (rawQuery.length() > SearchContracts.MAX_QUERY_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "SEARCH_QUERY_TOO_LONG",
                    "搜索内容不能超过 " + SearchContracts.MAX_QUERY_LENGTH + " 个字符"
            );
        }
        String originalQuery = rawQuery.trim();
        if (originalQuery.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "SEARCH_QUERY_REQUIRED",
                    "请输入搜索内容"
            );
        }
        QueryExecutionPolicy policy = requestedPolicy == null
                ? QueryExecutionPolicy.singleRound()
                : requestedPolicy;
        QueryPlan plan = normalizePlan(
                requestedPlan, originalQuery, 1,
                policy.maxSubQueries()
        );
        String query = plan.standaloneQuery();

        RetrievalProfileView profile = target == null
                ? configurations.currentProfile()
                : configurations.profile(target.retrievalProfileVersion());
        String requestedMode = profile.mode().name();
        GraphMode declaredGraphMode = request.requestedGraphMode();
        RoutingDecision routing = routing(
                declaredGraphMode, requestedRouting
        );
        GraphMode requestedGraphMode = routing.selectedMode();
        List<AuthorizedRevision> allowed = authorized(
                user, request.documentId(), request.visibility()
        );
        ActiveIndex active = target == null
                ? indexes.activeIndex().orElse(null)
                : new ActiveIndex(
                        target.indexName(),
                        target.indexGeneration(),
                        target.indexConfigVersion()
                );
        if (allowed.isEmpty() || active == null) {
            String querySkipCode = allowed.isEmpty()
                    ? "NO_AUTHORIZED_SOURCE"
                    : "ACTIVE_INDEX_UNAVAILABLE";
            boolean degraded = profile.mode() == RetrievalMode.HYBRID && active == null;
            boolean graphDegraded =
                    declaredGraphMode != GraphMode.HYBRID;
            String graphDegradationCode = allowed.isEmpty()
                    ? "GRAPH_NO_AUTHORIZED_SOURCE"
                    : "GRAPH_ACTIVE_INDEX_UNAVAILABLE";
            SearchMetadata metadata = new SearchMetadata(
                    profile.version(),
                    active == null ? 0 : active.generation(),
                    requestedMode,
                    degraded ? RetrievalMode.BM25.name() : requestedMode,
                    degraded,
                    degraded ? "ACTIVE_INDEX_UNAVAILABLE" : null,
                    null,
                    null,
                    declaredGraphMode.name(),
                    GraphMode.HYBRID.name(),
                    graphDegraded,
                    graphDegraded ? graphDegradationCode : null,
                    null,
                    routeExecution(declaredGraphMode, routing)
            );
            return new SearchExecution(
                    active == null ? "" : active.indexName(),
                    SearchPage.empty(
                            request,
                            metadata,
                            skippedQueryExecution(plan, querySkipCode)
                    ),
                    List.of(),
                    List.of(),
                    ContextBudget.empty(),
                    GraphDiagnostics.empty(),
                    metadata
            );
        }

        long started = System.nanoTime();
        long searchDeadline =
                started + properties.getRequestTimeout().toNanos();
        long deadline = Math.min(
                searchDeadline, policy.deadlineNanos()
        );
        List<DebugStage> stages = new ArrayList<>();
        Set<String> degradationCodes = new LinkedHashSet<>();
        if (plan.degradationCode() != null) {
            degradationCodes.add(plan.degradationCode());
        }
        List<String> accessKeys = allowed.stream()
                .map(AuthorizedRevision::projectionKey)
                .toList();
        boolean hybridRequested = profile.mode() == RetrievalMode.HYBRID;
        boolean phase6c = profile.rerankTopK() > 0;

        IndexConfigView activeConfig = configurations.indexConfig(
                active.indexConfigVersion()
        );
        Map<UUID, Candidate> merged = new LinkedHashMap<>();
        List<QuerySlot> querySlots = new ArrayList<>();
        RecallSummary firstRecall = recallRound(
                plan.queries(),
                1,
                active.indexName(),
                accessKeys,
                profile,
                activeConfig,
                hybridRequested,
                deadline,
                stages,
                merged
        );
        degradationCodes.addAll(firstRecall.degradationCodes());
        if (firstRecall.successfulQueries() == 0) {
            throw unavailable(
                    "SEARCH_ALL_BRANCHES_FAILED",
                    "搜索基础设施暂时不可用",
                    null
            );
        }
        querySlots.addAll(firstRecall.slots());
        long rawTotal = firstRecall.rawTotal();
        int rawHitCount = firstRecall.rawHitCount();

        long rrfStarted = System.nanoTime();
        List<Candidate> ranked = rank(
                merged, profile.rrfRankConstant()
        );
        Map<UUID, AuthorizedRevision> current = authorizedByDocument(
                user, request.documentId(), request.visibility()
        );
        Map<UUID, AuthorizedRevision> firstAuthorization = current;
        List<Candidate> verified = ranked.stream()
                .filter(candidate -> authorized(
                        candidate, firstAuthorization
                ))
                .toList();
        boolean coverageSufficient = coverageSufficient(
                verified.size(), profile.evidenceTopK()
        );
        stages.add(stage(
                "ACL_REVISION_R1",
                "SUCCESS",
                ranked.size(),
                verified.size(),
                0,
                null
        ));

        int plannerCalls = plan.plannerCallCount();
        int maxQuerySlots = policy.maxQuerySlots();
        int remainingSlots = Math.min(
                policy.maxSubQueries(),
                maxQuerySlots - querySlots.size()
        );
        long secondRoundDeadline = deadline
                - SECOND_HOP_DOWNSTREAM_RESERVE_NANOS;
        boolean secondRoundBudgetAvailable =
                System.nanoTime() < secondRoundDeadline;
        List<String> secondQueries = new ArrayList<>();
        if (remainingSlots > 0
                && secondRoundBudgetAvailable
                && !coverageSufficient
                && !plan.degraded()
                && policy.maxRetrievalRounds() > 1
                && secondRoundPlanner != null
                && plannerCalls < policy.plannerCallLimit()) {
            QueryPlan second = normalizePlan(
                    secondRoundPlanner.plan(new Coverage(
                            verified.size(), remainingSlots
                    )),
                    query,
                    2,
                    remainingSlots
            );
            plannerCalls = Math.min(
                    policy.plannerCallLimit(),
                    plannerCalls + second.plannerCallCount()
            );
            if (second.degradationCode() != null) {
                degradationCodes.add(second.degradationCode());
            }
            secondQueries.addAll(withoutExisting(
                    second.queries(), querySlots
            ));
        }
        int bridgeSlots = remainingSlots - secondQueries.size();
        List<String> bridgeQueries = List.of();
        if (bridgeSlots > 0
                && secondRoundBudgetAvailable
                && policy.maxRetrievalRounds() > 1
                && requestedGraphMode == GraphMode.LOCAL_GRAPH) {
            bridgeQueries = resultDrivenSecondHopQueries(
                    query,
                    verified,
                    querySlots,
                    Math.min(1, bridgeSlots)
            ).stream()
                    .filter(candidate -> secondQueries.stream()
                            .noneMatch(existing -> sameQuery(
                                    existing, candidate
                            )))
                    .limit(bridgeSlots)
                    .toList();
            secondQueries.addAll(bridgeQueries);
        }
        if (requestedGraphMode == GraphMode.LOCAL_GRAPH
                && policy.maxRetrievalRounds() > 1) {
            stages.add(stage(
                    "RESULT_DRIVEN_SECOND_HOP",
                    bridgeQueries.isEmpty() ? "SKIPPED" : "SUCCESS",
                    verified.size(),
                    bridgeQueries.size(),
                    0,
                    null
            ));
        }
        if (!secondQueries.isEmpty()) {
            RecallSummary secondRecall = recallRound(
                    secondQueries,
                    2,
                    active.indexName(),
                    accessKeys,
                    profile,
                    activeConfig,
                    hybridRequested,
                    secondRoundDeadline,
                    stages,
                    merged
            );
            querySlots.addAll(secondRecall.slots());
            rawTotal = Math.max(rawTotal, secondRecall.rawTotal());
            rawHitCount += secondRecall.rawHitCount();
            degradationCodes.addAll(secondRecall.degradationCodes());
            ranked = rank(merged, profile.rrfRankConstant());
            current = authorizedByDocument(
                    user, request.documentId(), request.visibility()
            );
            Map<UUID, AuthorizedRevision> secondAuthorization = current;
            verified = ranked.stream()
                    .filter(candidate -> authorized(
                            candidate, secondAuthorization
                    ))
                    .toList();
            coverageSufficient = coverageSufficient(
                    verified.size(), profile.evidenceTopK()
            );
            stages.add(stage(
                    "ACL_REVISION_R2",
                    "SUCCESS",
                    ranked.size(),
                    verified.size(),
                    0,
                    null
            ));
        }
        boolean hybridUsed = merged.values().stream()
                .anyMatch(Candidate::hasVector);
        Map<UUID, AuthorizedRevision> retrievalAuthorization = current;
        stages.add(stage(
                "RRF",
                hybridUsed ? "SUCCESS" : "SKIPPED",
                rawHitCount,
                ranked.size(),
                elapsed(rrfStarted),
                null
        ));
        stages.add(stage(
                "ACL_REVISION",
                "SUCCESS",
                ranked.size(),
                verified.size(),
                0,
                null
        ));

        GlobalGraphRetrievalService.Expansion globalExpansion = null;
        boolean globalUsed = false;
        String globalDegradationCode = null;
        if (requestedGraphMode == GraphMode.GLOBAL_GRAPH) {
            if (!phase6c) {
                globalDegradationCode =
                        "GLOBAL_REQUIRES_RERANK_PROFILE";
                stages.add(stage(
                        "GLOBAL_REPORT", "DEGRADED",
                        verified.size(), 0, 0,
                        globalDegradationCode
                ));
                stages.add(stage(
                        "GLOBAL_FUSION", "SKIPPED",
                        0, 0, 0, globalDegradationCode
                ));
            } else {
                List<UUID> globalAuthorizations = current.values().stream()
                        .map(AuthorizedRevision::documentId)
                        .toList();
                if (globalGraphs.isEmpty()) {
                    globalDegradationCode = "GLOBAL_DISABLED";
                    stages.add(stage(
                            "GLOBAL_REPORT", "DEGRADED",
                            verified.size(), 0, 0,
                            globalDegradationCode
                    ));
                    stages.add(stage(
                            "GLOBAL_FUSION", "SKIPPED",
                            0, 0, 0, globalDegradationCode
                    ));
                } else {
                if (target != null && target.fault()
                        == EvaluationFault.GLOBAL_OPENSEARCH_UNAVAILABLE) {
                    globalDegradationCode =
                            "GLOBAL_INDEX_UNAVAILABLE";
                    stages.add(stage(
                            "GLOBAL_REPORT", "DEGRADED",
                            verified.size(), 0, 0,
                            globalDegradationCode
                    ));
                    stages.add(stage(
                            "GLOBAL_FUSION", "SKIPPED",
                            0, 0, 0, globalDegradationCode
                    ));
                } else {
                Future<GlobalGraphRetrievalService.Expansion> globalFuture =
                        executor.submit(() -> globalGraphs.orElseThrow().expand(
                                query,
                                globalAuthorizations,
                                target == null
                                        ? null
                                        : target.globalGeneration()
                        ));
                try {
                    globalExpansion = await(globalFuture, deadline);
                    globalDegradationCode =
                            globalExpansion.degradationCode();
                    stages.add(stage(
                            "GLOBAL_REPORT",
                            globalExpansion.used()
                                    ? "SUCCESS" : "DEGRADED",
                            verified.size(),
                            globalExpansion.candidates().size(),
                            globalExpansion.tookMs(),
                            globalDegradationCode
                    ));
                    if (globalExpansion.used()) {
                        Map<UUID, GlobalChild> globalChildren =
                                globalChildren(globalExpansion.candidates());
                        Future<TimedSearch> childrenFuture =
                                executor.submit(() -> timedSearch(
                                        active.indexName(),
                                        graphChildrenBody(
                                                globalChildren.keySet()
                                                        .stream()
                                                        .toList(),
                                                accessKeys
                                        )
                                ));
                        TimedSearch children = await(
                                childrenFuture,
                                deadline
                        );
                        long fusionStarted = System.nanoTime();
                        GlobalFusion fusion = fuseGlobal(
                                verified,
                                globalChildren,
                                children.response(),
                                profile.rrfRankConstant()
                        );
                        verified = fusion.candidates().stream()
                                .filter(candidate ->
                                        authorized(
                                                candidate,
                                                retrievalAuthorization
                                        ))
                                .toList();
                        globalUsed =
                                fusion.globalCandidateCount() > 0;
                        if (!globalUsed) {
                            globalDegradationCode =
                                    "GLOBAL_CHILD_LOOKUP_EMPTY";
                        }
                        stages.add(stage(
                                "GLOBAL_FUSION",
                                globalUsed ? "SUCCESS" : "DEGRADED",
                                globalChildren.size(),
                                fusion.globalCandidateCount(),
                                elapsed(fusionStarted),
                                globalDegradationCode
                        ));
                    } else {
                        stages.add(stage(
                                "GLOBAL_FUSION", "SKIPPED",
                                0, 0, 0, globalDegradationCode
                        ));
                    }
                } catch (BranchTimeoutException exception) {
                    globalDegradationCode = "GLOBAL_TIMEOUT";
                    stages.add(stage(
                            "GLOBAL_REPORT", "DEGRADED",
                            verified.size(), 0, 0,
                            globalDegradationCode
                    ));
                    stages.add(stage(
                            "GLOBAL_FUSION", "SKIPPED",
                            0, 0, 0, globalDegradationCode
                    ));
                } catch (ApiException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    globalDegradationCode =
                            "GLOBAL_CHILD_LOOKUP_UNAVAILABLE";
                    stages.add(stage(
                            "GLOBAL_FUSION", "DEGRADED",
                            0, 0, 0, globalDegradationCode
                    ));
                }
                }
                }
            }
        } else {
            stages.add(stage(
                    "GLOBAL_REPORT", "SKIPPED", 0, 0, 0, null
            ));
            stages.add(stage(
                    "GLOBAL_FUSION", "SKIPPED", 0, 0, 0, null
            ));
        }

        Expansion graphExpansion = null;
        boolean graphUsed = false;
        String graphDegradationCode = null;
        boolean tryLocalGraph =
                requestedGraphMode != GraphMode.HYBRID
                        && !globalUsed;
        if (tryLocalGraph) {
            if (!phase6c) {
                graphDegradationCode =
                        "GRAPH_REQUIRES_RERANK_PROFILE";
                stages.add(stage(
                        "GRAPH_SEED", "DEGRADED",
                        verified.size(), 0, 0,
                        graphDegradationCode
                ));
                stages.add(stage(
                        "GRAPH_TRAVERSE", "SKIPPED", 0, 0, 0,
                        graphDegradationCode
                ));
                stages.add(stage(
                        "GRAPH_FUSION", "SKIPPED", 0, 0, 0,
                        graphDegradationCode
                ));
            } else if (target != null
                    && target.fault() == EvaluationFault.GRAPH_STALE) {
                graphDegradationCode = "GRAPH_PROJECTION_STALE";
                stages.add(stage(
                        "GRAPH_SEED", "DEGRADED",
                        verified.size(), 0, 0,
                        graphDegradationCode
                ));
                stages.add(stage(
                        "GRAPH_TRAVERSE", "SKIPPED", 0, 0, 0,
                        graphDegradationCode
                ));
                stages.add(stage(
                        "GRAPH_FUSION", "SKIPPED", 0, 0, 0,
                        graphDegradationCode
                ));
            } else {
                List<Candidate> graphSeedCandidates = verified;
                Map<UUID, AuthorizedRevision> graphAuthorizations =
                        current;
                Future<Expansion> graphFuture = executor.submit(() ->
                        graphs.expand(
                                query,
                                graphSeedCandidates.stream()
                                        .map(Candidate::chunkId)
                                        .toList(),
                                graphAuthorizations.values().stream()
                                        .map(AuthorizedRevision::documentId)
                                        .toList(),
                                target == null
                                        ? null
                                        : target.graphProfileVersion(),
                                target == null
                                        ? null
                                        : target.graphGeneration()
                        )
                );
                try {
                    graphExpansion = await(graphFuture, deadline);
                    stages.add(stage(
                            "GRAPH_SEED",
                            graphExpansion.seedCount() > 0
                                    ? "SUCCESS" : "DEGRADED",
                            verified.size(),
                            graphExpansion.seedCount(),
                            0,
                            graphExpansion.seedCount() > 0
                                    ? null
                                    : graphExpansion.degradationCode()
                    ));
                    stages.add(stage(
                            "GRAPH_TRAVERSE",
                            graphExpansion.used()
                                    ? "SUCCESS" : "DEGRADED",
                            graphExpansion.seedCount(),
                            graphExpansion.edgeCount(),
                            graphExpansion.tookMs(),
                            graphExpansion.degradationCode()
                    ));
                    if (graphExpansion.used()) {
                        List<UUID> graphIds =
                                graphExpansion.candidates().stream()
                                        .map(GraphCandidate::childId)
                                        .toList();
                        Future<TimedSearch> graphChildrenFuture =
                                executor.submit(() -> timedSearch(
                                        active.indexName(),
                                        graphChildrenBody(
                                                graphIds,
                                                accessKeys
                                        )
                                ));
                        TimedSearch graphChildren = await(
                                graphChildrenFuture,
                                deadline
                        );
                        long fusionStarted = System.nanoTime();
                        GraphFusion fusion = fuseGraph(
                                query,
                                verified,
                                graphExpansion,
                                graphChildren.response(),
                                profile.rrfRankConstant()
                        );
                        verified = fusion.candidates().stream()
                                .filter(candidate ->
                                        authorized(
                                                candidate,
                                                retrievalAuthorization
                                        ))
                                .toList();
                        graphUsed = fusion.graphCandidateCount() > 0;
                        if (!graphUsed) {
                            graphDegradationCode =
                                    "GRAPH_CHILD_LOOKUP_EMPTY";
                        }
                        stages.add(stage(
                                "GRAPH_FUSION",
                                graphUsed ? "SUCCESS" : "DEGRADED",
                                graphIds.size(),
                                fusion.graphCandidateCount(),
                                elapsed(fusionStarted),
                                graphDegradationCode
                        ));
                    } else {
                        graphDegradationCode =
                                graphExpansion.degradationCode();
                        stages.add(stage(
                                "GRAPH_FUSION", "SKIPPED",
                                0, 0, 0, graphDegradationCode
                        ));
                    }
                } catch (BranchTimeoutException exception) {
                    graphDegradationCode = "GRAPH_TIMEOUT";
                    stages.add(stage(
                            "GRAPH_SEED", "DEGRADED",
                            verified.size(), 0, 0,
                            graphDegradationCode
                    ));
                    stages.add(stage(
                            "GRAPH_TRAVERSE", "DEGRADED",
                            0, 0, 0, graphDegradationCode
                    ));
                    stages.add(stage(
                            "GRAPH_FUSION", "SKIPPED",
                            0, 0, 0, graphDegradationCode
                    ));
                } catch (ApiException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    graphDegradationCode =
                            "GRAPH_CHILD_LOOKUP_UNAVAILABLE";
                    stages.add(stage(
                            "GRAPH_FUSION", "DEGRADED",
                            0, 0, 0, graphDegradationCode
                    ));
                }
            }
        } else {
            stages.add(stage(
                    "GRAPH_SEED", "SKIPPED", 0, 0, 0, null
            ));
            stages.add(stage(
                    "GRAPH_TRAVERSE", "SKIPPED", 0, 0, 0, null
            ));
            stages.add(stage(
                    "GRAPH_FUSION", "SKIPPED", 0, 0, 0, null
            ));
        }

        List<UUID> supplementalChildIds =
                requestedSupplementalChildIds == null
                        ? List.of()
                        : requestedSupplementalChildIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .limit(20)
                        .toList();
        if (!supplementalChildIds.isEmpty()) {
            long memoryStarted = System.nanoTime();
            try {
                TimedSearch memoryChildren = await(
                        executor.submit(() -> timedSearch(
                                active.indexName(),
                                graphChildrenBody(
                                        supplementalChildIds, accessKeys
                                )
                        )),
                        deadline
                );
                verified = fuseMemory(
                        verified,
                        memoryChildren.response(),
                        profile.rrfRankConstant()
                ).stream()
                        .filter(candidate -> authorized(
                                candidate, retrievalAuthorization
                        ))
                        .toList();
                stages.add(stage(
                        "MEMORY_SEED",
                        "SUCCESS",
                        supplementalChildIds.size(),
                        verified.size(),
                        elapsed(memoryStarted),
                        null
                ));
            } catch (BranchTimeoutException exception) {
                degradationCodes.add("MEMORY_DOCUMENT_LOOKUP_TIMEOUT");
                stages.add(stage(
                        "MEMORY_SEED", "DEGRADED",
                        supplementalChildIds.size(), 0,
                        elapsed(memoryStarted),
                        "MEMORY_DOCUMENT_LOOKUP_TIMEOUT"
                ));
            } catch (ApiException exception) {
                if (exception.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) {
                    throw exception;
                }
                degradationCodes.add("MEMORY_DOCUMENT_LOOKUP_UNAVAILABLE");
                stages.add(stage(
                        "MEMORY_SEED", "DEGRADED",
                        supplementalChildIds.size(), 0,
                        elapsed(memoryStarted),
                        "MEMORY_DOCUMENT_LOOKUP_UNAVAILABLE"
                ));
            }
        } else {
            stages.add(stage(
                    "MEMORY_SEED", "SKIPPED", 0, 0, 0, null
            ));
        }

        List<Candidate> ordered = verified;
        int rerankCallCount = 0;
        if (phase6c) {
            int rerankCount = Math.min(profile.rerankTopK(), verified.size());
            List<Candidate> rerankInput = rerankInput(
                    verified, rerankCount, querySlots
            );
            ordered = rerankInput;
            String rerankCode = null;
            long rerankStarted = System.nanoTime();
            if (rerankInput.isEmpty()) {
                ordered = List.of();
            } else if (!reranker.descriptor().enabled()) {
                rerankCode = "RERANK_DISABLED";
                degradationCodes.add(rerankCode);
            } else {
                rerankCallCount = 1;
                Future<List<RerankScore>> rerankFuture = executor.submit(() ->
                        circuits.call(
                                ModelType.RERANK,
                                () -> reranker.rerank(
                                        query,
                                        rerankInput.stream()
                                                .map(Candidate::rerankText)
                                                .toList()
                                )
                        )
                );
                try {
                    ordered = applyRerank(rerankInput, await(rerankFuture, deadline));
                } catch (BranchTimeoutException exception) {
                    rerankCode = "RERANK_TIMEOUT";
                } catch (ModelCircuitOpenException exception) {
                    rerankCode = "RERANK_CIRCUIT_OPEN";
                } catch (RuntimeException exception) {
                    rerankCode = "RERANK_UNAVAILABLE";
                }
                if (rerankCode != null) {
                    degradationCodes.add(rerankCode);
                }
            }
            stages.add(stage(
                    "RERANK",
                    rerankCode == null ? "SUCCESS" : "DEGRADED",
                    rerankInput.size(),
                    rerankCode == null ? ordered.size() : 0,
                    elapsed(rerankStarted),
                    rerankCode
            ));
        } else {
            stages.add(stage("RERANK", "SKIPPED", 0, 0, 0, null));
        }

        List<Candidate> evidenceCandidates;
        if (phase6c) {
            evidenceCandidates = new ArrayList<>(diversifiedEvidence(
                    query, ordered, profile.evidenceTopK(), querySlots
            ));
            for (int index = 0; index < evidenceCandidates.size(); index++) {
                evidenceCandidates.get(index).setEvidenceRank(index + 1);
            }
            stages.add(stage(
                    "EVIDENCE",
                    "SUCCESS",
                    ordered.size(),
                    evidenceCandidates.size(),
                    0,
                    null
            ));
        } else {
            evidenceCandidates = verified;
            stages.add(stage("EVIDENCE", "SKIPPED", verified.size(), verified.size(), 0, null));
        }

        int graphBudgetLimit = graphUsed && graphExpansion != null
                ? Math.min(
                        graphExpansion.profile()
                                .graphContextTokenBudget(),
                        profile.parentTokenBudget()
                                * graphExpansion.profile()
                                .graphContextPercent() / 100
                )
                : 0;
        int globalBudgetLimit = globalUsed && globalExpansion != null
                && globalExpansion.config() != null
                ? Math.min(
                        globalExpansion.config().contextTokenBudget(),
                        Math.max(
                                0,
                                profile.parentTokenBudget()
                                        - graphBudgetLimit
                        )
                )
                : 0;
        int effectiveContextBudget = Math.max(
                0,
                profile.parentTokenBudget()
                        - graphBudgetLimit
                        - globalBudgetLimit
        );
        ContextPlan contextPlan = ContextPlan.empty();
        if (phase6c) {
            long parentStarted = System.nanoTime();
            List<ContextSeed> seeds = evidenceCandidates.stream()
                    .map(Candidate::contextSeed)
                    .toList();
            try {
                Future<Map<UUID, EvidenceContextService.ContextRow>> loadFuture =
                        executor.submit(() -> contexts.load(
                                seeds.stream().map(ContextSeed::chunkId).toList()
                        ));
                Map<UUID, EvidenceContextService.ContextRow> loaded =
                        await(loadFuture, deadline);
                contextPlan = contexts.plan(
                        seeds,
                        loaded,
                        effectiveContextBudget
                );
                if (contextPlan.trimReasons().contains("PARENT_MISSING")) {
                    degradationCodes.add("PARENT_PARTIAL");
                }
                stages.add(stage(
                        "PARENT",
                        contextPlan.trimReasons().contains("PARENT_MISSING")
                                ? "DEGRADED" : "SUCCESS",
                        evidenceCandidates.size(),
                        contextPlan.parentCount(),
                        elapsed(parentStarted),
                        contextPlan.trimReasons().contains("PARENT_MISSING")
                                ? "PARENT_PARTIAL" : null
                ));
            } catch (BranchTimeoutException exception) {
                contextPlan = contexts.plan(
                        seeds, Map.of(), effectiveContextBudget
                );
                degradationCodes.add("PARENT_TIMEOUT");
                stages.add(stage(
                        "PARENT", "DEGRADED", evidenceCandidates.size(), 0,
                        elapsed(parentStarted), "PARENT_TIMEOUT"
                ));
            } catch (RuntimeException exception) {
                contextPlan = contexts.plan(
                        seeds, Map.of(), effectiveContextBudget
                );
                degradationCodes.add("PARENT_UNAVAILABLE");
                stages.add(stage(
                        "PARENT", "DEGRADED", evidenceCandidates.size(), 0,
                        elapsed(parentStarted), "PARENT_UNAVAILABLE"
                ));
            }
        } else {
            stages.add(stage("PARENT", "SKIPPED", 0, 0, 0, null));
        }

        Map<UUID, AuthorizedRevision> finalAuthorization = current;
        if (phase6c) {
            long finalCheckStarted = System.nanoTime();
            finalAuthorization = authorizedByDocument(
                    user, request.documentId(), request.visibility()
            );
            int before = evidenceCandidates.size();
            Map<UUID, AuthorizedRevision> confirmed = finalAuthorization;
            evidenceCandidates = evidenceCandidates.stream()
                    .filter(candidate -> authorized(candidate, confirmed))
                    .toList();
            for (int index = 0; index < evidenceCandidates.size(); index++) {
                evidenceCandidates.get(index).setEvidenceRank(index + 1);
            }
            stages.add(stage(
                    "ACL_FINAL",
                    "SUCCESS",
                    before,
                    evidenceCandidates.size(),
                    elapsed(finalCheckStarted),
                    null
            ));
        } else {
            stages.add(stage(
                    "ACL_FINAL", "SKIPPED", verified.size(), verified.size(), 0, null
            ));
        }

        GraphBudget graphBudget = budgetGraphPaths(
                evidenceCandidates,
                graphBudgetLimit
        );
        GlobalBudget globalBudget = budgetGlobalClaims(
                evidenceCandidates,
                globalBudgetLimit
        );

        List<SearchHit> accepted = new ArrayList<>();
        Map<UUID, SearchHit> evidenceHits = new LinkedHashMap<>();
        for (Candidate candidate : evidenceCandidates) {
            EvidenceContext evidence = phase6c
                    ? evidence(candidate, contextPlan.materials().get(candidate.chunkId()))
                    : null;
            SearchHit hit = hit(candidate.source(), candidate.highlights(), evidence);
            accepted.add(hit);
            evidenceHits.put(candidate.chunkId(), hit);
        }

        List<DebugCandidate> debug = new ArrayList<>();
        int debugRank = 0;
        Set<UUID> evidenceIds = evidenceHits.keySet();
        for (Candidate candidate : verified) {
            if (!authorized(candidate, finalAuthorization)) {
                continue;
            }
            debugRank++;
            boolean selected = !phase6c || evidenceIds.contains(candidate.chunkId());
            SearchHit result = selected
                    ? evidenceHits.get(candidate.chunkId())
                    : hit(candidate.source(), candidate.highlights(), null);
            debug.add(new DebugCandidate(
                    debugRank,
                    candidate.finalScore(),
                    candidate.bm25Rank(),
                    candidate.vectorRank(),
                    hybridUsed || graphUsed
                            || globalUsed
                            ? candidate.rrfScore() : null,
                    candidate.graphRank(),
                    candidate.graphPathViews(),
                    candidate.globalRank(),
                    candidate.globalClaimViews(),
                    candidate.rerankRank(),
                    candidate.rerankScore(),
                    candidate.evidenceRank(),
                    matchedFields(candidate.highlights()),
                    selected,
                    selected ? null : "NOT_SELECTED_AS_EVIDENCE",
                    result
            ));
        }

        int page = request.safePage();
        int size = request.safeSize();
        int start = Math.min(page * size, accepted.size());
        int end = Math.min(start + size, accepted.size());
        List<SearchHit> items = List.copyOf(accepted.subList(start, end));
        boolean exact = !phase6c
                && !hybridUsed
                && querySlots.size() == 1
                && degradationCodes.isEmpty()
                && ranked.size() == verified.size()
                && rawTotal <= profile.bm25TopK();
        long total = exact ? rawTotal : accepted.size();
        int pages = total == 0
                ? 0
                : Math.min(request.maxPages(), (int) Math.ceil((double) total / size));
        long took = elapsed(started);
        String degradationCode = degradationCodes.isEmpty()
                ? null
                : String.join("+", degradationCodes);
        String graphCode = joinCodes(
                globalDegradationCode,
                graphDegradationCode
        );
        GraphMode graphModeUsed = globalUsed
                ? GraphMode.GLOBAL_GRAPH
                : graphUsed
                ? GraphMode.LOCAL_GRAPH
                : GraphMode.HYBRID;
        boolean graphDegraded = switch (requestedGraphMode) {
            case HYBRID -> false;
            case AUTO, LOCAL_GRAPH -> !graphUsed;
            case GLOBAL_GRAPH ->
                    !globalUsed || globalDegradationCode != null;
        };
        GlobalExecution globalExecution = globalExpansion == null
                || globalExpansion.config() == null
                ? null
                : new GlobalExecution(
                        globalExpansion.config().version(),
                        globalExpansion.globalGeneration(),
                        globalExpansion.candidates().size(),
                        globalExpansion.config().reportLimit(),
                        globalExpansion.config().modelCallLimit(),
                        globalExpansion.config().hardTimeoutMs(),
                        false
                );
        SearchMetadata metadata = new SearchMetadata(
                profile.version(),
                active.generation(),
                requestedMode,
                hybridUsed ? RetrievalMode.HYBRID.name() : RetrievalMode.BM25.name(),
                !degradationCodes.isEmpty(),
                degradationCode,
                graphExpansion == null
                        ? null
                        : graphExpansion.profile().version(),
                graphExpansion == null
                        ? null
                        : graphExpansion.graphGeneration(),
                declaredGraphMode.name(),
                graphModeUsed.name(),
                graphDegraded,
                graphCode,
                globalExecution,
                routeExecution(declaredGraphMode, routing)
        );
        SearchPage result = new SearchPage(
                items,
                page,
                size,
                total,
                pages,
                took,
                metadata.profileVersion(),
                metadata.indexGeneration(),
                metadata.modeRequested(),
                metadata.modeUsed(),
                metadata.degraded(),
                metadata.degradationCode(),
                exact ? "EXACT" : "CAPPED",
                metadata.graphProfileVersion(),
                metadata.graphGeneration(),
                metadata.graphModeRequested(),
                metadata.graphModeUsed(),
                metadata.graphDegraded(),
                metadata.graphDegradationCode(),
                metadata.globalExecution(),
                metadata.routeExecution(),
                new QueryExecution(
                        query,
                        List.copyOf(querySlots),
                        plannerCalls,
                        querySlots.size(),
                        rerankCallCount,
                        coverageSufficient,
                        plan.degraded()
                                || querySlots.stream().anyMatch(slot ->
                                !"SUCCESS".equals(slot.status())),
                        queryDegradationCode(
                                plan.degradationCode(), querySlots
                        ),
                        ranked.size(),
                        verified.size(),
                        ordered.size(),
                        evidenceCandidates.size()
                )
        );
        List<String> trimReasons = new ArrayList<>(
                contextPlan.trimReasons()
        );
        if (graphBudget.truncated()) {
            trimReasons.add("GRAPH_CONTEXT_TRUNCATED");
        }
        if (globalBudget.truncated()) {
            trimReasons.add("GLOBAL_CONTEXT_TRUNCATED");
        }
        ContextBudget budget = new ContextBudget(
                phase6c ? profile.parentTokenBudget() : 0,
                contextPlan.childTokens(),
                contextPlan.parentTokens(),
                contextPlan.totalTokens()
                        + graphBudget.tokens()
                        + globalBudget.tokens(),
                contextPlan.parentCount(),
                graphBudget.tokens(),
                graphBudget.pathCount(),
                globalBudget.tokens(),
                globalBudget.claimCount(),
                List.copyOf(trimReasons)
        );
        Map<UUID, AuthorizedRevision> diagnosticAuthorization =
                finalAuthorization;
        Set<UUID> finalAuthorizedDocuments =
                diagnosticAuthorization.keySet();
        GraphDiagnostics graphDiagnostics = graphExpansion == null
                ? GraphDiagnostics.empty()
                : new GraphDiagnostics(
                graphExpansion.seedCount(),
                graphExpansion.seedDocumentIds().stream()
                        .filter(finalAuthorizedDocuments::contains)
                        .toList(),
                (int) verified.stream()
                        .filter(candidate -> candidate.graphRank() != null)
                        .filter(candidate -> authorized(
                                candidate, diagnosticAuthorization
                        ))
                        .count(),
                (int) verified.stream()
                        .filter(candidate -> candidate.graphRank() != null)
                        .filter(candidate -> candidate.bm25Rank() == null)
                        .filter(candidate -> candidate.vectorRank() == null)
                        .filter(candidate -> authorized(
                                candidate, diagnosticAuthorization
                        ))
                        .count(),
                (int) verified.stream()
                        .filter(candidate -> authorized(
                                candidate, diagnosticAuthorization
                        ))
                        .flatMap(candidate ->
                                candidate.rawGraphPaths().stream())
                        .map(GraphPath::relationshipId)
                        .distinct()
                        .count()
        );
        return new SearchExecution(
                active.indexName(),
                result,
                List.copyOf(debug),
                List.copyOf(stages),
                budget,
                graphDiagnostics,
                metadata
        );
    }

    private static QueryExecution skippedQueryExecution(
            QueryPlan plan,
            String code
    ) {
        List<QuerySlot> slots = new ArrayList<>(plan.queries().size());
        for (int index = 0; index < plan.queries().size(); index++) {
            slots.add(new QuerySlot(
                    1,
                    index + 1,
                    plan.queries().get(index),
                    "SKIPPED",
                    0,
                    code
            ));
        }
        return new QueryExecution(
                plan.standaloneQuery(),
                List.copyOf(slots),
                plan.plannerCallCount(),
                0,
                0,
                false,
                true,
                joinCodes(plan.degradationCode(), code),
                0,
                0,
                0,
                0
        );
    }

    private static RoutingDecision routing(
            GraphMode requested,
            RoutingDecision resolved
    ) {
        if (requested != GraphMode.AUTO) {
            return RoutingDecision.explicit(requested);
        }
        return resolved == null
                ? RoutingDecision.fallback("ROUTER_PROFILE_UNAVAILABLE")
                : resolved;
    }

    private static RouteExecution routeExecution(
            GraphMode requested,
            RoutingDecision routing
    ) {
        return new RouteExecution(
                requested.name(),
                routing.selectedMode().name(),
                routing.routerCallCount(),
                routing.reasonCode(),
                routing.degraded(),
                routing.degradationCode()
        );
    }

    public enum EvaluationFault {
        NONE,
        GLOBAL_OPENSEARCH_UNAVAILABLE,
        GRAPH_STALE
    }

    public record EvaluationTarget(
            String retrievalProfileVersion,
            long indexGeneration,
            String indexName,
            String indexConfigVersion,
            String graphProfileVersion,
            Long graphGeneration,
            Long globalGeneration,
            String queryProfileVersion,
            EvaluationFault fault
    ) {
        public EvaluationTarget {
            fault = fault == null ? EvaluationFault.NONE : fault;
        }

        public EvaluationTarget(
                String retrievalProfileVersion,
                long indexGeneration,
                String indexName,
                String indexConfigVersion,
                String graphProfileVersion,
                Long graphGeneration,
                Long globalGeneration,
                EvaluationFault fault
        ) {
            this(
                    retrievalProfileVersion, indexGeneration, indexName,
                    indexConfigVersion, graphProfileVersion,
                    graphGeneration, globalGeneration, null, fault
            );
        }
    }

    public record QueryPlan(
            String standaloneQuery,
            List<String> queries,
            int plannerCallCount,
            boolean degraded,
            String degradationCode,
            GraphMode routedMode,
            String routeReasonCode
    ) {
        public QueryPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }

        public QueryPlan(
                String standaloneQuery,
                List<String> queries,
                int plannerCallCount,
                boolean degraded,
                String degradationCode
        ) {
            this(
                    standaloneQuery, queries, plannerCallCount,
                    degraded, degradationCode, null, null
            );
        }

        public static QueryPlan single(String query) {
            return new QueryPlan(
                    query, List.of(query), 0, false, null,
                    null, null
            );
        }
    }

    public record QueryExecutionPolicy(
            int maxSubQueries,
            int maxRetrievalRounds,
            int plannerCallLimit,
            int timeoutMs,
            long deadlineNanos
    ) {
        private static final long FIRST_RECALL_RESERVE_NANOS =
                TimeUnit.MILLISECONDS.toNanos(500);

        public QueryExecutionPolicy {
            if (maxSubQueries < 1 || maxSubQueries > 3
                    || maxRetrievalRounds < 1
                    || maxRetrievalRounds > 2
                    || plannerCallLimit < 0
                    || plannerCallLimit > 2
                    || timeoutMs < 100
                    || timeoutMs > 30_000) {
                throw new IllegalArgumentException(
                        "Invalid query execution policy"
                );
            }
        }

        public static QueryExecutionPolicy start(
                int maxSubQueries,
                int maxRetrievalRounds,
                int plannerCallLimit,
                int timeoutMs
        ) {
            return new QueryExecutionPolicy(
                    maxSubQueries,
                    maxRetrievalRounds,
                    plannerCallLimit,
                    timeoutMs,
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            );
        }

        static QueryExecutionPolicy singleRound() {
            return start(1, 1, 0, 30_000);
        }

        public int maxQuerySlots() {
            return maxSubQueries * maxRetrievalRounds;
        }

        public long remainingNanos() {
            return Math.max(0, deadlineNanos - System.nanoTime());
        }

        public long plannerHttpPhaseTimeoutNanos() {
            long plannerBudget = remainingNanos()
                    - FIRST_RECALL_RESERVE_NANOS;
            return Math.max(0, plannerBudget / 2);
        }

        public long initialPlannerHttpPhaseTimeoutNanos() {
            return remainingNanos();
        }
    }

    public record RoutingDecision(
            GraphMode selectedMode,
            int routerCallCount,
            String reasonCode,
            boolean degraded,
            String degradationCode
    ) {
        public RoutingDecision {
            selectedMode = selectedMode == null
                    ? GraphMode.HYBRID : selectedMode;
            routerCallCount = Math.max(0, Math.min(1, routerCallCount));
        }

        public static RoutingDecision explicit(GraphMode mode) {
            return new RoutingDecision(
                    mode, 0, "EXPLICIT_MODE", false, null
            );
        }

        public static RoutingDecision fallback(String code) {
            return new RoutingDecision(
                    GraphMode.HYBRID, 0, "SAFE_DEFAULT",
                    true, code
            );
        }
    }

    public record Coverage(
            int authorizedCandidateCount,
            int remainingSlots
    ) {
    }

    @FunctionalInterface
    public interface SecondRoundPlanner {

        QueryPlan plan(Coverage coverage);
    }

    private RecallSummary recallRound(
            List<String> queries,
            int round,
            String indexName,
            List<String> accessKeys,
            RetrievalProfileView profile,
            IndexConfigView indexConfig,
            boolean hybridRequested,
            long deadline,
            List<DebugStage> stages,
            Map<UUID, Candidate> merged
    ) {
        List<Future<TimedSearch>> bm25Futures =
                new ArrayList<>(queries.size());
        for (String query : queries) {
            bm25Futures.add(executor.submit(() -> timedSearch(
                    indexName,
                    bm25Body(
                            query,
                            accessKeys,
                            profile.bm25TopK()
                    )
            )));
        }

        Future<List<List<Double>>> vectorBatchFuture = null;
        List<List<Double>> vectors = List.of();
        String vectorPrerequisiteCode = null;
        if (hybridRequested && indexConfig.vectorEnabled()) {
            vectorBatchFuture = executor.submit(() ->
                    embeddings.embedQueries(indexConfig, queries));
        } else if (hybridRequested) {
            vectorPrerequisiteCode =
                    "VECTOR_INDEX_UNAVAILABLE";
        }
        if (vectorBatchFuture != null) {
            try {
                vectors = await(vectorBatchFuture, deadline);
            } catch (BranchTimeoutException exception) {
                vectorPrerequisiteCode =
                        "VECTOR_TIMEOUT";
            } catch (ModelCircuitOpenException exception) {
                vectorPrerequisiteCode =
                        "VECTOR_CIRCUIT_OPEN";
            } catch (RuntimeException exception) {
                vectorPrerequisiteCode =
                        "VECTOR_UNAVAILABLE";
            }
        }
        List<Future<TimedSearch>> vectorFutures =
                new ArrayList<>(queries.size());
        for (int index = 0; index < queries.size(); index++) {
            List<Double> vector = vectors.isEmpty()
                    ? null : vectors.get(index);
            vectorFutures.add(vector == null
                    ? null
                    : executor.submit(() -> timedSearch(
                    indexName,
                    vectorBody(
                            vector,
                            accessKeys,
                            profile.vectorTopK()
                    )
            )));
        }

        List<QuerySlot> slots = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        int successful = 0;
        int rawHits = 0;
        long rawTotal = 0;
        for (int index = 0; index < queries.size(); index++) {
            TimedSearch bm25 = null;
            TimedSearch vector = null;
            String bm25Code = null;
            String vectorCode = vectorPrerequisiteCode;
            try {
                bm25 = await(bm25Futures.get(index), deadline);
            } catch (BranchTimeoutException exception) {
                bm25Code = "BM25_TIMEOUT";
            } catch (RuntimeException exception) {
                bm25Code = "BM25_UNAVAILABLE";
            }
            Future<TimedSearch> vectorFuture =
                    vectorFutures.get(index);
            if (vectorFuture != null) {
                try {
                    vector = await(vectorFuture, deadline);
                } catch (BranchTimeoutException exception) {
                    vectorCode = "VECTOR_TIMEOUT";
                } catch (ModelCircuitOpenException exception) {
                    vectorCode = "VECTOR_CIRCUIT_OPEN";
                } catch (RuntimeException exception) {
                    vectorCode = "VECTOR_UNAVAILABLE";
                }
            }
            String code = joinCodes(bm25Code, vectorCode);
            QueryRecall recall = new QueryRecall(
                    round,
                    index + 1,
                    queries.get(index),
                    bm25,
                    vector,
                    bm25Code,
                    vectorCode,
                    code,
                    bm25 != null || vector != null
            );
            String key = round + ":" + recall.slot();
            if (bm25 != null) {
                addBranch(
                        merged, bm25.response(),
                        Branch.BM25, key
                );
                rawHits += hitCount(bm25.response());
                rawTotal = Math.max(
                        rawTotal,
                        bm25.response()
                                .path("hits")
                                .path("total")
                                .path("value")
                                .asLong(0)
                );
            }
            if (vector != null) {
                addBranch(
                        merged, vector.response(),
                        Branch.VECTOR, key
                );
                rawHits += hitCount(vector.response());
            }
            if (recall.successful()) {
                successful++;
            }
            if (recall.code() != null) {
                codes.add(recall.code());
            }
            int candidateCount = distinctHits(
                    recall.bm25() == null
                            ? null : recall.bm25().response(),
                    recall.vector() == null
                            ? null : recall.vector().response()
            );
            slots.add(new QuerySlot(
                    round,
                    recall.slot(),
                    recall.query(),
                    recall.successful()
                            ? recall.code() == null
                            ? "SUCCESS" : "DEGRADED"
                            : "FAILED",
                    candidateCount,
                    recall.code()
            ));
            String suffix = queries.size() == 1 && round == 1
                    ? ""
                    : "_R" + round + "S" + recall.slot();
            stages.add(stage(
                    "BM25" + suffix,
                    recall.bm25() == null
                            ? "DEGRADED" : "SUCCESS",
                    accessKeys.size(),
                    hitCount(recall.bm25() == null
                            ? null : recall.bm25().response()),
                    recall.bm25() == null
                            ? 0 : recall.bm25().searchMs(),
                    recall.bm25Code()
            ));
            stages.add(stage(
                    "VECTOR" + suffix,
                    !hybridRequested
                            ? "SKIPPED"
                            : recall.vector() == null
                            ? "DEGRADED" : "SUCCESS",
                    accessKeys.size(),
                    hitCount(recall.vector() == null
                            ? null : recall.vector().response()),
                    recall.vector() == null
                            ? 0 : recall.vector().totalMs(),
                    recall.vectorCode()
            ));
        }
        return new RecallSummary(
                List.copyOf(slots),
                successful,
                rawHits,
                rawTotal,
                Set.copyOf(codes)
        );
    }

    private static List<Candidate> rank(
            Map<UUID, Candidate> merged,
            int rankConstant
    ) {
        return merged.values().stream()
                .peek(candidate -> candidate.calculateRrf(rankConstant))
                .sorted(Comparator
                        .comparingDouble(Candidate::retrievalScore)
                        .reversed()
                        .thenComparing(candidate ->
                                candidate.chunkId().toString()))
                .toList();
    }

    static double rrfContribution(
            String queryKey,
            int rank,
            int rankConstant
    ) {
        double roundWeight = queryKey.startsWith("1:")
                ? 1.0 : SECOND_ROUND_RRF_WEIGHT;
        return roundWeight / (rankConstant + rank);
    }

    private static QueryPlan normalizePlan(
            QueryPlan requested,
            String fallback,
            int round,
            int limit
    ) {
        String standalone = requested == null
                || requested.standaloneQuery() == null
                || requested.standaloneQuery().isBlank()
                ? fallback
                : requested.standaloneQuery().strip();
        if (standalone.length() > SearchContracts.MAX_QUERY_LENGTH) {
            standalone = fallback;
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (round == 1) {
            queries.add(standalone);
        }
        if (requested != null) {
            requested.queries().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .filter(value -> value.length()
                            <= SearchContracts.MAX_QUERY_LENGTH)
                    .limit(limit)
                    .forEach(queries::add);
        }
        if (queries.isEmpty() && round == 1) {
            queries.add(fallback);
        }
        return new QueryPlan(
                standalone,
                queries.stream().limit(limit).toList(),
                requested == null
                        ? 0
                        : Math.max(0, Math.min(
                        2, requested.plannerCallCount()
                )),
                requested != null && requested.degraded(),
                requested == null
                        ? null : requested.degradationCode()
        );
    }

    private static List<String> withoutExisting(
            List<String> queries,
            List<QuerySlot> existing
    ) {
        Set<String> seen = new LinkedHashSet<>();
        existing.forEach(slot -> seen.add(
                slot.query().strip().toLowerCase()
        ));
        List<String> result = new ArrayList<>();
        for (String query : queries) {
            if (seen.add(query.strip().toLowerCase())) {
                result.add(query);
            }
        }
        return List.copyOf(result);
    }

    static List<String> resultDrivenSecondHopQueries(
            String query,
            List<Candidate> authorizedCandidates,
            List<QuerySlot> existing,
            int limit
    ) {
        if (limit <= 0 || authorizedCandidates.isEmpty()) {
            return List.of();
        }
        Set<String> originalTerms = queryTerms(query);
        Map<String, BridgeTerm> terms = new LinkedHashMap<>();
        int candidateLimit = Math.min(8, authorizedCandidates.size());
        for (int index = 0; index < candidateLimit; index++) {
            Candidate candidate = authorizedCandidates.get(index);
            String title = candidate.source().path("title").asText("")
                    .replaceFirst("^\\[[^]]+][\\s·:_-]*", "")
                    .strip();
            addBridgeTerm(
                    terms, title, 120 - index, originalTerms
            );
            String text = candidate.source().path("text").asText("");
            Matcher matcher = BRIDGE_ENTITY.matcher(text.substring(
                    0, Math.min(1_500, text.length())
            ));
            while (matcher.find()) {
                addBridgeTerm(
                        terms, matcher.group(), 150 - index,
                        originalTerms
                );
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(query.strip().toLowerCase(Locale.ROOT));
        existing.forEach(slot -> seen.add(
                slot.query().strip().toLowerCase(Locale.ROOT)
        ));
        List<String> result = new ArrayList<>();
        List<BridgeTerm> rankedTerms = terms.values().stream()
                .sorted(Comparator
                        .comparingInt(BridgeTerm::score)
                        .reversed()
                        .thenComparing(BridgeTerm::normalized))
                .toList();
        for (BridgeTerm term : rankedTerms) {
            if (result.size() >= limit) {
                break;
            }
            int available = SearchContracts.MAX_QUERY_LENGTH
                    - query.length() - 1;
            if (available <= 0) {
                break;
            }
            String suffix = term.value().substring(
                    0, Math.min(term.value().length(), available)
            ).strip();
            String candidate = (query + " " + suffix).strip();
            if (seen.add(candidate.toLowerCase(Locale.ROOT))) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private static void addBridgeTerm(
            Map<String, BridgeTerm> terms,
            String raw,
            int score,
            Set<String> originalTerms
    ) {
        String value = raw == null ? "" : raw
                .replaceAll("\\s+", " ")
                .strip();
        if (value.length() < 3 || value.length() > 80) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (BRIDGE_STOP_VALUES.contains(normalized)) {
            return;
        }
        Set<String> candidateTerms = queryTerms(value);
        if (candidateTerms.isEmpty()
                || originalTerms.containsAll(candidateTerms)) {
            return;
        }
        terms.merge(
                normalized,
                new BridgeTerm(value, normalized, score),
                (left, right) -> left.score() >= right.score()
                        ? left : right
        );
    }

    private static boolean sameQuery(String left, String right) {
        return left.strip().equalsIgnoreCase(right.strip());
    }

    static boolean coverageSufficient(
            int authorizedCandidates,
            int evidenceTopK
    ) {
        return authorizedCandidates >= Math.max(1, evidenceTopK);
    }

    private static int distinctHits(JsonNode... responses) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode response : responses) {
            if (response == null) {
                continue;
            }
            for (JsonNode raw : response.path("hits").path("hits")) {
                ids.add(raw.path("_source").path("chunkId").asText());
            }
        }
        return ids.size();
    }

    private static String queryDegradationCode(
            String planCode,
            List<QuerySlot> slots
    ) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (planCode != null && !planCode.isBlank()) {
            codes.add(planCode);
        }
        slots.stream()
                .map(QuerySlot::degradationCode)
                .filter(code -> code != null && !code.isBlank())
                .forEach(codes::add);
        return codes.isEmpty() ? null : String.join("+", codes);
    }

    private TimedSearch timedSearch(String indexName, Map<String, Object> body) {
        long started = System.nanoTime();
        JsonNode response = openSearch.search(indexName, body);
        long took = elapsed(started);
        return new TimedSearch(response, took, 0, took);
    }

    private List<AuthorizedRevision> authorized(
            PlatformUserPrincipal user,
            UUID documentId,
            com.example.rag.persistence.DocumentVisibility visibility
    ) {
        try {
            return access.authorized(user, documentId, visibility);
        } catch (DataAccessException exception) {
            throw unavailable(
                    "AUTHORIZATION_RECHECK_UNAVAILABLE",
                    "权限复核暂时不可用",
                    exception
            );
        }
    }

    private Map<UUID, AuthorizedRevision> authorizedByDocument(
            PlatformUserPrincipal user,
            UUID documentId,
            com.example.rag.persistence.DocumentVisibility visibility
    ) {
        try {
            return access.authorizedByDocument(user, documentId, visibility);
        } catch (DataAccessException exception) {
            throw unavailable(
                    "AUTHORIZATION_RECHECK_UNAVAILABLE",
                    "权限复核暂时不可用",
                    exception
            );
        }
    }

    private static boolean authorized(
            Candidate candidate,
            Map<UUID, AuthorizedRevision> authorizations
    ) {
        JsonNode source = candidate.source();
        AuthorizedRevision authorization = authorizations.get(uuid(source, "documentId"));
        return authorization != null
                && authorization.revisionId().equals(uuid(source, "revisionId"))
                && authorization.aclVersion() == source.path("aclVersion").asLong();
    }

    private static List<Candidate> applyRerank(
            List<Candidate> input,
            List<RerankScore> scores
    ) {
        List<Candidate> result = new ArrayList<>(scores.size());
        for (int index = 0; index < scores.size(); index++) {
            RerankScore score = scores.get(index);
            Candidate candidate = input.get(score.index());
            candidate.setRerank(index + 1, score.score());
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    static List<Candidate> rerankInput(
            List<Candidate> verified,
            int limit,
            List<QuerySlot> querySlots
    ) {
        if (limit <= 0 || verified.isEmpty()) {
            return List.of();
        }
        int branchReserve = Math.min(5, Math.max(1, limit / 6));
        Map<UUID, Candidate> selected = new LinkedHashMap<>();
        for (QuerySlot slot : querySlots) {
            String key = slot.round() + ":" + slot.slot();
            reserveCandidates(
                    verified.stream()
                            .filter(candidate -> candidate.matchesQuery(key))
                            .sorted(Comparator
                                    .comparingInt((Candidate candidate) ->
                                            candidate.rankFor(key))
                                    .thenComparing(candidate ->
                                            candidate.chunkId().toString()))
                            .toList(),
                    selected,
                    2,
                    limit
            );
            if (selected.size() >= limit) {
                return orderedSelection(verified, selected, limit);
            }
        }
        reserveCandidates(
                verified.stream()
                        .filter(candidate -> candidate.graphRank() != null)
                        .sorted(Comparator
                                .comparingInt(Candidate::graphRank)
                                .thenComparing(candidate ->
                                        candidate.chunkId().toString()))
                        .toList(),
                selected,
                branchReserve,
                limit
        );
        reserveCandidates(
                verified.stream()
                        .filter(candidate -> candidate.bm25Rank() != null)
                        .sorted(Comparator
                                .comparingInt(Candidate::bm25Rank)
                                .thenComparing(candidate ->
                                        candidate.chunkId().toString()))
                        .toList(),
                selected,
                branchReserve,
                limit
        );
        reserveCandidates(
                verified.stream()
                        .filter(candidate -> candidate.vectorRank() != null)
                        .sorted(Comparator
                                .comparingInt(Candidate::vectorRank)
                                .thenComparing(candidate ->
                                        candidate.chunkId().toString()))
                        .toList(),
                selected,
                branchReserve,
                limit
        );
        reserveCandidates(verified, selected, limit, limit);
        return orderedSelection(verified, selected, limit);
    }

    private static void reserveCandidates(
            List<Candidate> candidates,
            Map<UUID, Candidate> selected,
            int reserve,
            int limit
    ) {
        int added = 0;
        for (Candidate candidate : candidates) {
            if (selected.size() >= limit || added >= reserve) {
                return;
            }
            if (selected.putIfAbsent(candidate.chunkId(), candidate) == null) {
                added++;
            }
        }
    }

    private static List<Candidate> orderedSelection(
            List<Candidate> ordered,
            Map<UUID, Candidate> selected,
            int limit
    ) {
        return ordered.stream()
                .filter(candidate -> selected.containsKey(candidate.chunkId()))
                .limit(limit)
                .toList();
    }

    static List<Candidate> diversifiedEvidence(
            String query,
            List<Candidate> ordered,
            int limit,
            List<QuerySlot> querySlots
    ) {
        if (limit <= 0 || ordered.isEmpty()) {
            return List.of();
        }
        Map<UUID, Candidate> selected = new LinkedHashMap<>();
        Set<UUID> documents = new LinkedHashSet<>();
        reserveBestGraphPair(query, ordered, selected, limit);
        selected.values().forEach(candidate -> documents.add(
                uuid(candidate.source(), "documentId")
        ));
        for (QuerySlot slot : querySlots) {
            if (selected.size() >= limit
                    || (slot.round() == 1 && slot.slot() == 1)) {
                continue;
            }
            String key = slot.round() + ":" + slot.slot();
            Candidate choice = ordered.stream()
                    .filter(candidate -> candidate.matchesQuery(key))
                    .filter(candidate -> !documents.contains(
                            uuid(candidate.source(), "documentId")
                    ))
                    .findFirst()
                    .orElseGet(() -> ordered.stream()
                            .filter(candidate -> candidate.matchesQuery(key))
                            .findFirst()
                            .orElse(null));
            if (choice != null) {
                selected.putIfAbsent(choice.chunkId(), choice);
                documents.add(uuid(choice.source(), "documentId"));
            }
        }
        ordered.forEach(candidate -> {
            if (selected.size() < limit) {
                selected.putIfAbsent(candidate.chunkId(), candidate);
            }
        });
        Set<UUID> selectedIds = selected.keySet();
        return ordered.stream()
                .filter(candidate -> selectedIds.contains(candidate.chunkId()))
                .limit(limit)
                .toList();
    }

    private static void reserveBestGraphPair(
            String query,
            List<Candidate> ordered,
            Map<UUID, Candidate> selected,
            int limit
    ) {
        if (limit < 2) {
            return;
        }
        Map<UUID, Candidate> relationshipEvidence = new LinkedHashMap<>();
        Map<Candidate, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            Candidate candidate = ordered.get(index);
            ranks.put(candidate, index);
            candidate.rawGraphPaths().forEach(path ->
                    relationshipEvidence.putIfAbsent(
                            path.relationshipId(), candidate
                    ));
        }
        List<GraphEvidencePair> pairs = new ArrayList<>();
        ordered.stream()
                .flatMap(child -> child.rawGraphPaths().stream()
                        .filter(path -> path.depth() > 1)
                        .filter(path -> path.parentRelationshipId() != null)
                        .map(path -> explicitPair(
                                relationshipEvidence.get(
                                        path.parentRelationshipId()
                                ),
                                child,
                                String.join(
                                        " ", path.relationshipType(),
                                        path.documentTitle(),
                                        path.evidenceText()
                                )
                        )))
                .filter(pair -> pair.parent() != null)
                .forEach(pairs::add);
        ordered.stream()
                .filter(candidate -> !candidate.rawGraphPaths().isEmpty())
                .forEach(graph -> ordered.stream()
                        .filter(candidate -> !candidate.equals(graph))
                        .filter(candidate -> !sameDocument(graph, candidate))
                        .map(candidate -> bridgePair(graph, candidate))
                        .filter(pair -> pair.bridgeTerms() > 0)
                        .forEach(pairs::add));
        Set<String> queryTerms = queryTerms(query);
        pairs.stream()
                .max(Comparator
                        .comparingInt((GraphEvidencePair pair) ->
                                queryCoverage(
                                pair, queryTerms
                        ))
                        .thenComparingInt(GraphEvidencePair::bridgeTerms)
                        .thenComparingInt(pair ->
                                pair.explicitPath() ? 1 : 0)
                        .thenComparingInt(pair -> -Math.max(
                                ranks.get(pair.parent()),
                                ranks.get(pair.child())
                        ))
                        .thenComparingInt(pair -> -(
                                ranks.get(pair.parent())
                                        + ranks.get(pair.child())))
                        .thenComparing(pair ->
                                pair.parent().chunkId().toString(),
                                Comparator.reverseOrder())
                        .thenComparing(pair ->
                                pair.child().chunkId().toString(),
                                Comparator.reverseOrder()))
                .ifPresent(pair -> {
                    selected.put(pair.parent().chunkId(), pair.parent());
                    selected.putIfAbsent(
                            pair.child().chunkId(), pair.child()
                    );
                });
    }

    private static GraphEvidencePair explicitPair(
            Candidate parent,
            Candidate child,
            String pathText
    ) {
        return new GraphEvidencePair(
                parent,
                child,
                true,
                pathText,
                parent == null ? 0 : bridgeTerms(pathText, parent)
        );
    }

    private static GraphEvidencePair bridgePair(
            Candidate graph,
            Candidate candidate
    ) {
        String pathText = graph.rawGraphPaths().stream()
                .map(path -> String.join(
                        " ", path.relationshipType(), path.documentTitle(),
                        path.evidenceText()
                ))
                .collect(java.util.stream.Collectors.joining(" "));
        return new GraphEvidencePair(
                candidate,
                graph,
                false,
                pathText,
                bridgeTerms(pathText, candidate)
        );
    }

    private static int bridgeTerms(String pathText, Candidate candidate) {
        Set<String> bridge = queryTerms(pathText);
        bridge.retainAll(queryTerms(candidate.rerankText()));
        return bridge.size();
    }

    private static int queryCoverage(
            GraphEvidencePair pair,
            Set<String> queryTerms
    ) {
        Set<String> covered = queryTerms(String.join(
                " ", pair.parent().rerankText(),
                pair.child().rerankText(), pair.pathText()
        ));
        covered.retainAll(queryTerms);
        return covered.size();
    }

    private static boolean sameDocument(
            Candidate first,
            Candidate second
    ) {
        return uuid(first.source(), "documentId").equals(
                uuid(second.source(), "documentId")
        );
    }

    private static EvidenceContext evidence(Candidate candidate, Material material) {
        String childText = material == null
                ? candidate.source().path("text").asText()
                : material.childText();
        int childTokens = material == null
                ? Math.max(1, childText.codePointCount(0, childText.length()))
                : material.childTokenCount();
        return new EvidenceContext(
                candidate.evidenceRank(),
                candidate.retrievalScore(),
                candidate.rerankScore(),
                childText,
                childTokens,
                material == null ? null : material.parent(),
                candidate.graphPathViews(),
                candidate.globalClaimViews(),
                candidate.querySlots()
        );
    }

    private static List<Candidate> fuseMemory(
            List<Candidate> verified,
            JsonNode memoryChildren,
            int rankConstant
    ) {
        Map<UUID, Candidate> fused = new LinkedHashMap<>();
        verified.forEach(candidate -> fused.put(
                candidate.chunkId(), candidate
        ));
        int rank = 1;
        for (JsonNode raw : memoryChildren.path("hits").path("hits")) {
            JsonNode source = raw.path("_source");
            UUID chunkId = uuid(source, "chunkId");
            Candidate candidate = fused.computeIfAbsent(
                    chunkId,
                    ignored -> new Candidate(chunkId, source)
            );
            candidate.addMemory(rank++);
        }
        fused.values().forEach(candidate ->
                candidate.calculateRrf(rankConstant));
        return fused.values().stream()
                .sorted(Comparator
                        .comparingDouble(Candidate::retrievalScore)
                        .reversed()
                        .thenComparing(Candidate::chunkId))
                .toList();
    }

    private static GraphFusion fuseGraph(
            String query,
            List<Candidate> verified,
            Expansion expansion,
            JsonNode graphChildren,
            int rankConstant
    ) {
        Map<UUID, Candidate> fused = new LinkedHashMap<>();
        verified.forEach(candidate -> fused.put(
                candidate.chunkId(),
                candidate
        ));
        List<GraphCandidate> rankedGraph = rankGraphCandidates(
                query, expansion.candidates()
        );
        Map<UUID, GraphCandidate> graphByChild = new LinkedHashMap<>();
        rankedGraph.forEach(candidate -> graphByChild.put(
                candidate.childId(),
                candidate
        ));
        Map<UUID, Integer> graphRanks = new LinkedHashMap<>();
        for (int index = 0; index < rankedGraph.size(); index++) {
            graphRanks.put(rankedGraph.get(index).childId(), index + 1);
        }
        int graphCandidates = 0;
        for (JsonNode raw : graphChildren.path("hits").path("hits")) {
            JsonNode source = raw.path("_source");
            UUID chunkId = uuid(source, "chunkId");
            GraphCandidate graph = graphByChild.get(chunkId);
            if (graph == null) {
                continue;
            }
            Candidate candidate = fused.computeIfAbsent(
                    chunkId,
                    ignored -> new Candidate(chunkId, source)
            );
            candidate.addGraph(graphRanks.get(chunkId), graph.paths());
            graphCandidates++;
        }
        List<Candidate> ordered = fused.values().stream()
                .peek(candidate -> candidate.enableGraphFusion(
                        rankConstant,
                        expansion.profile().graphWeight()
                ))
                .sorted(Comparator
                        .comparingDouble(Candidate::retrievalScore)
                        .reversed()
                        .thenComparing(candidate ->
                                candidate.chunkId().toString()))
                .toList();
        return new GraphFusion(ordered, graphCandidates);
    }

    static List<GraphCandidate> rankGraphCandidates(
            String query,
            List<GraphCandidate> candidates
    ) {
        Set<String> terms = queryTerms(query);
        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble((GraphCandidate candidate) ->
                                graphRelevance(candidate, terms))
                        .reversed()
                        .thenComparingInt(GraphCandidate::rank)
                        .thenComparing(GraphCandidate::childId))
                .toList();
    }

    private static double graphRelevance(
            GraphCandidate candidate,
            Set<String> queryTerms
    ) {
        double lexical = candidate.paths().stream()
                .mapToDouble(path -> pathRelevance(path, queryTerms))
                .max()
                .orElse(0.0);
        int minimumDepth = candidate.paths().stream()
                .mapToInt(GraphPath::depth)
                .min()
                .orElse(2);
        return lexical * 4.0
                + 0.1 / Math.max(1, minimumDepth)
                + 1.0 / (60.0 + candidate.rank());
    }

    private static double pathRelevance(
            GraphPath path,
            Set<String> queryTerms
    ) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        String evidence = String.join(
                " ",
                path.relationshipType(),
                path.documentTitle(),
                path.evidenceText()
        ).toLowerCase(Locale.ROOT);
        long matched = queryTerms.stream()
                .filter(evidence::contains)
                .count();
        return (double) matched / queryTerms.size();
    }

    private static Set<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = QUERY_TERM.matcher(
                query == null ? "" : query.toLowerCase(Locale.ROOT)
        );
        while (matcher.find()) {
            String token = matcher.group();
            if (containsCjk(token)) {
                int[] points = token.codePoints().toArray();
                if (points.length == 1) {
                    terms.add(token);
                } else {
                    for (int index = 0; index < points.length - 1; index++) {
                        terms.add(new String(points, index, 2));
                    }
                }
            } else if ((token.length() >= 3 || token.chars().allMatch(
                    Character::isDigit
            )) && !QUERY_STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        return terms;
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(point ->
                Character.UnicodeScript.of(point)
                        == Character.UnicodeScript.HAN
        );
    }

    private static Map<UUID, GlobalChild> globalChildren(
            List<GlobalCandidate> reports
    ) {
        Map<UUID, MutableGlobalChild> children = new LinkedHashMap<>();
        for (GlobalCandidate report : reports) {
            for (GlobalClaim claim : report.claims()) {
                for (GlobalEvidence evidence : claim.evidence()) {
                    MutableGlobalChild child = children.computeIfAbsent(
                            evidence.childChunkId(),
                            ignored -> new MutableGlobalChild(
                                    report.rank(),
                                    new ArrayList<>()
                            )
                    );
                    child.rank = Math.min(child.rank, report.rank());
                    child.claims.add(new GlobalClaimAnchor(
                            report.reportId(),
                            report.rank(),
                            report.communityKey(),
                            report.title(),
                            claim.claimId(),
                            claim.order(),
                            claim.text(),
                            evidence
                    ));
                }
            }
        }
        Map<UUID, GlobalChild> result = new LinkedHashMap<>();
        children.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    MutableGlobalChild child = entry.getValue();
                    List<GlobalClaimAnchor> claims = child.claims.stream()
                            .sorted(Comparator
                                    .comparingInt(
                                            GlobalClaimAnchor::reportRank
                                    )
                                    .thenComparing(item ->
                                            item.reportId().toString())
                                    .thenComparingInt(
                                            GlobalClaimAnchor::claimOrder
                                    )
                                    .thenComparing(item ->
                                            item.evidence()
                                                    .evidenceId()
                                                    .toString()))
                            .toList();
                    result.put(
                            entry.getKey(),
                            new GlobalChild(child.rank, claims)
                    );
                });
        return result;
    }

    private static GlobalFusion fuseGlobal(
            List<Candidate> verified,
            Map<UUID, GlobalChild> globalByChild,
            JsonNode globalChildren,
            int rankConstant
    ) {
        Map<UUID, Candidate> fused = new LinkedHashMap<>();
        verified.forEach(candidate -> fused.put(
                candidate.chunkId(),
                candidate
        ));
        int globalCandidates = 0;
        for (JsonNode raw : globalChildren.path("hits").path("hits")) {
            JsonNode source = raw.path("_source");
            UUID chunkId = uuid(source, "chunkId");
            GlobalChild global = globalByChild.get(chunkId);
            if (global == null) {
                continue;
            }
            Candidate candidate = fused.computeIfAbsent(
                    chunkId,
                    ignored -> new Candidate(chunkId, source)
            );
            candidate.addGlobal(global.rank(), global.claims());
            globalCandidates++;
        }
        List<Candidate> ordered = fused.values().stream()
                .peek(candidate ->
                        candidate.enableGlobalFusion(rankConstant))
                .sorted(Comparator
                        .comparingDouble(Candidate::retrievalScore)
                        .reversed()
                        .thenComparing(candidate ->
                                candidate.chunkId().toString()))
                .toList();
        return new GlobalFusion(ordered, globalCandidates);
    }

    private static GraphBudget budgetGraphPaths(
            List<Candidate> candidates,
            int limit
    ) {
        int used = 0;
        int pathCount = 0;
        boolean truncated = false;
        Map<Candidate, List<GraphPathView>> views = new LinkedHashMap<>();
        candidates.forEach(candidate -> views.put(
                candidate,
                new ArrayList<>()
        ));
        List<BudgetPath> ordered = new ArrayList<>();
        for (int candidateOrder = 0;
             candidateOrder < candidates.size();
             candidateOrder++) {
            Candidate candidate = candidates.get(candidateOrder);
            for (int pathOrder = 0;
                 pathOrder < candidate.rawGraphPaths().size();
                 pathOrder++) {
                ordered.add(new BudgetPath(
                        candidate,
                        candidateOrder,
                        pathOrder,
                        candidate.rawGraphPaths().get(pathOrder)
                ));
            }
        }
        ordered.sort(Comparator
                .comparingInt((BudgetPath item) -> item.path().depth())
                .thenComparingInt(BudgetPath::candidateOrder)
                .thenComparingInt(BudgetPath::pathOrder));
        Set<UUID> includedRelationships = new LinkedHashSet<>();
        for (BudgetPath item : ordered) {
            GraphPath path = item.path();
            if (includedRelationships.contains(path.relationshipId())) {
                continue;
            }
            if (path.parentRelationshipId() != null
                    && !includedRelationships.contains(
                    path.parentRelationshipId()
            )) {
                truncated = true;
                continue;
            }
            int fixedTokens = graphPromptTokens(path, "");
            int remaining = limit - used;
            if (remaining <= fixedTokens) {
                truncated = true;
                continue;
            }
            String contributed = truncateTokens(
                    path.evidenceText(),
                    remaining - fixedTokens
            );
            int tokens = graphPromptTokens(path, contributed);
            if (contributed.length() < path.evidenceText().length()) {
                truncated = true;
            }
            views.get(item.candidate()).add(new GraphPathView(
                    path.depth(),
                    path.relationshipId(),
                    path.relationshipType(),
                    path.childId(),
                    path.sourceSpanId(),
                    path.documentId(),
                    path.documentTitle(),
                    path.startPage(),
                    path.endPage(),
                    contributed,
                    tokens,
                    path.documentFormat(),
                    path.sourceLocator(),
                    path.sourceLabel()
            ));
            includedRelationships.add(path.relationshipId());
            used += tokens;
            pathCount++;
        }
        for (Candidate candidate : candidates) {
            candidate.setGraphPathViews(List.copyOf(views.get(candidate)));
        }
        return new GraphBudget(used, pathCount, truncated);
    }

    private static int graphPromptTokens(
            GraphPath path,
            String evidence
    ) {
        return tokenCount(
                "citationId=00000000-0000-0000-0000-000000000000"
                        + ";depth=" + path.depth()
                        + ";relationshipType="
                        + path.relationshipType()
                        + ";evidenceText=" + evidence
        );
    }

    private static GlobalBudget budgetGlobalClaims(
            List<Candidate> candidates,
            int limit
    ) {
        int used = 0;
        int claimCount = 0;
        boolean truncated = false;
        Map<Candidate, List<GlobalClaimView>> views =
                new LinkedHashMap<>();
        List<BudgetGlobalClaim> ordered = new ArrayList<>();
        for (int candidateOrder = 0;
             candidateOrder < candidates.size();
             candidateOrder++) {
            Candidate candidate = candidates.get(candidateOrder);
            views.put(candidate, new ArrayList<>());
            for (GlobalClaimAnchor claim :
                    candidate.rawGlobalClaims()) {
                ordered.add(new BudgetGlobalClaim(
                        candidate,
                        candidateOrder,
                        claim
                ));
            }
        }
        ordered.sort(Comparator
                .comparingInt((BudgetGlobalClaim item) ->
                        item.claim().reportRank())
                .thenComparingInt(
                        BudgetGlobalClaim::candidateOrder
                )
                .thenComparing(item ->
                        item.claim().reportId().toString())
                .thenComparingInt(item ->
                        item.claim().claimOrder())
                .thenComparing(item ->
                        item.claim().evidence()
                                .evidenceId().toString()));
        Set<UUID> includedEvidence = new LinkedHashSet<>();
        for (BudgetGlobalClaim item : ordered) {
            GlobalClaimAnchor claim = item.claim();
            GlobalEvidence evidence = claim.evidence();
            if (!includedEvidence.add(evidence.evidenceId())) {
                continue;
            }
            int remaining = limit - used;
            String title = truncateTokens(claim.reportTitle(), 120);
            int overhead = globalPromptTokens(
                    claim,
                    title,
                    "",
                    ""
            );
            if (remaining <= overhead + 1) {
                truncated = true;
                continue;
            }
            int textBudget = remaining - overhead;
            String claimText = truncateTokens(
                    claim.claimText(),
                    Math.max(1, textBudget / 2)
            );
            int evidenceBudget = Math.max(
                    0,
                    remaining - globalPromptTokens(
                            claim,
                            title,
                            claimText,
                            ""
                    )
            );
            String evidenceText = truncateTokens(
                    evidence.evidenceText(),
                    evidenceBudget
            );
            int tokens = globalPromptTokens(
                    claim,
                    title,
                    claimText,
                    evidenceText
            );
            if (claimText.length() < claim.claimText().length()
                    || evidenceText.length()
                    < evidence.evidenceText().length()
                    || title.length() < claim.reportTitle().length()) {
                truncated = true;
            }
            views.get(item.candidate()).add(new GlobalClaimView(
                    claim.reportId(),
                    title,
                    claim.communityKey(),
                    claim.claimId(),
                    claimText,
                    evidence.childChunkId(),
                    evidence.sourceSpanId(),
                    evidence.documentId(),
                    evidence.documentTitle(),
                    evidence.startPage(),
                    evidence.endPage(),
                    evidenceText,
                    tokens,
                    evidence.documentFormat(),
                    evidence.sourceLocator(),
                    evidence.sourceLabel()
            ));
            used += tokens;
            claimCount++;
        }
        candidates.forEach(candidate -> candidate.setGlobalClaimViews(
                List.copyOf(views.get(candidate))
        ));
        return new GlobalBudget(used, claimCount, truncated);
    }

    private static int globalPromptTokens(
            GlobalClaimAnchor claim,
            String title,
            String claimText,
            String evidenceText
    ) {
        return tokenCount(
                "citationId=" + claim.evidence().childChunkId()
                        + ";reportId=" + claim.reportId()
                        + ";reportTitle=" + title
                        + ";communityKey=" + claim.communityKey()
                        + ";claim=" + claimText
                        + ";evidenceText=" + evidenceText
        );
    }

    private static String joinCodes(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()
                || first.equals(second)) {
            return first;
        }
        return first + "+" + second;
    }

    private static int tokenCount(String value) {
        return value == null || value.isEmpty()
                ? 0
                : value.codePointCount(0, value.length());
    }

    private static String truncateTokens(String value, int limit) {
        if (value == null || limit <= 0) {
            return "";
        }
        int end = value.offsetByCodePoints(
                0,
                Math.min(limit, value.codePointCount(0, value.length()))
        );
        return value.substring(0, end);
    }

    private static DebugStage stage(
            String name,
            String status,
            int input,
            int output,
            long tookMs,
            String code
    ) {
        return new DebugStage(name, status, input, output, tookMs, code);
    }

    private static int hitCount(JsonNode response) {
        return response == null ? 0 : response.path("hits").path("hits").size();
    }

    private static ApiException unavailable(String code, String message, Throwable failure) {
        return failure instanceof ApiException api
                ? api
                : new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, failure);
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void addBranch(
            Map<UUID, Candidate> merged,
            JsonNode response,
            Branch branch,
            String queryKey
    ) {
        if (response == null) {
            return;
        }
        int rank = 0;
        for (JsonNode raw : response.path("hits").path("hits")) {
            rank++;
            JsonNode source = raw.path("_source");
            UUID chunkId = uuid(source, "chunkId");
            Candidate candidate = merged.computeIfAbsent(
                    chunkId,
                    ignored -> new Candidate(chunkId, source)
            );
            candidate.add(
                    branch,
                    queryKey,
                    rank,
                    raw.path("_score").asDouble(),
                    raw.path("highlight")
            );
        }
    }

    private static Map<String, Object> bm25Body(
            String query,
            List<String> accessKeys,
            int topK
    ) {
        Map<String, Object> multiMatch = Map.of(
                "query", query,
                "fields", QUERY_FIELDS,
                "type", "best_fields",
                "operator", "or"
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", 0);
        body.put("size", topK);
        body.put("track_total_hits", true);
        body.put("query", Map.of("bool", Map.of(
                "must", List.of(Map.of("multi_match", multiMatch)),
                "filter", List.of(accessFilter(accessKeys))
        )));
        body.put("highlight", highlight());
        body.put("sort", List.of(
                Map.of("_score", "desc"),
                Map.of("chunkId", "asc")
        ));
        body.put("_source", SOURCE_FIELDS);
        return body;
    }

    private static Map<String, Object> vectorBody(
            List<Double> vector,
            List<String> accessKeys,
            int topK
    ) {
        Map<String, Object> knn = Map.of(
                "vector", vector,
                "k", topK,
                "filter", accessFilter(accessKeys)
        );
        return Map.of(
                "size", topK,
                "track_total_hits", false,
                "query", Map.of("knn", Map.of("embedding", knn)),
                "_source", SOURCE_FIELDS
        );
    }

    private static Map<String, Object> graphChildrenBody(
            List<UUID> childIds,
            List<String> accessKeys
    ) {
        return Map.of(
                "size", childIds.size(),
                "track_total_hits", false,
                "query", Map.of("bool", Map.of(
                        "filter", List.of(
                                Map.of("terms", Map.of(
                                        "chunkId",
                                        childIds.stream()
                                                .map(UUID::toString)
                                                .toList()
                                )),
                                accessFilter(accessKeys)
                        )
                )),
                "sort", List.of(Map.of("chunkId", "asc")),
                "_source", SOURCE_FIELDS
        );
    }

    private static Map<String, Object> accessFilter(List<String> accessKeys) {
        return Map.of("terms", Map.of("accessProjectionKey", accessKeys));
    }

    private static Map<String, Object> highlight() {
        return Map.of(
                "pre_tags", List.of("<mark>"),
                "post_tags", List.of("</mark>"),
                "fields", Map.of(
                        "text", Map.of("fragment_size", 220, "number_of_fragments", 1),
                        "text.english", Map.of("fragment_size", 220, "number_of_fragments", 1),
                        "title", Map.of(),
                        "title.english", Map.of(),
                        "heading", Map.of(),
                        "heading.english", Map.of()
                )
        );
    }

    private static <T> T await(Future<T> future, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 && !future.isDone()) {
            future.cancel(true);
            throw new BranchTimeoutException();
        }
        try {
            return future.get(
                    Math.max(0, remaining),
                    TimeUnit.NANOSECONDS
            );
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new BranchTimeoutException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Search request was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Search branch failed", cause);
        }
    }

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static SearchHit hit(
            JsonNode source,
            JsonNode highlights,
            EvidenceContext evidence
    ) {
        String documentFormat = source.path("documentFormat").asText("PDF");
        Integer startPage = nullableInteger(source.path("startPage"));
        Integer endPage = nullableInteger(source.path("endPage"));
        if ("PDF".equals(documentFormat) && startPage != null
                && endPage == null) {
            endPage = startPage;
        }
        SourceLocatorResponse locator = sourceLocator(
                source, documentFormat, startPage, endPage
        );
        return new SearchHit(
                uuid(source, "chunkId"),
                uuid(source, "documentId"),
                source.path("documentTitle").asText(),
                uuid(source, "revisionId"),
                source.path("revisionNumber").asInt(),
                path(source.path("headingPath").asText()),
                startPage,
                endPage,
                snippet(source.path("text").asText(), highlights),
                evidence,
                documentFormat,
                locator,
                nullableText(source.path("sourceLabel")) != null
                        ? nullableText(source.path("sourceLabel"))
                        : locator == null ? null : locator.sourceLabel()
        );
    }

    private static SourceLocatorResponse sourceLocator(
            JsonNode source,
            String documentFormat,
            Integer startPage,
            Integer endPage
    ) {
        JsonNode value = source.path("sourceLocator");
        if (!value.isObject()) {
            return "PDF".equals(documentFormat)
                    && startPage != null && endPage != null
                    ? SourceLocatorResponse.pdfCompatibility(
                    startPage, endPage
            ) : null;
        }
        Integer locatorStartPage = nullableInteger(
                value.path("startPage")
        );
        Integer locatorEndPage = nullableInteger(value.path("endPage"));
        String sourceLabel = nullableText(value.path("sourceLabel"));
        if (sourceLabel == null && "PDF".equals(documentFormat)
                && startPage != null && endPage != null) {
            sourceLabel = SourceLocatorResponse.pdfCompatibility(
                    startPage, endPage
            ).sourceLabel();
        }
        return new SourceLocatorResponse(
                value.path("kind").asText("PAGE"),
                nullableUuid(value.path("startUnit")),
                nullableUuid(value.path("endUnit")),
                value.path("startOffset").asInt(),
                value.path("endOffset").asInt(),
                nullableText(value.path("address")),
                nullableText(value.path("sourceTextHash")),
                value.path("normalizationVersion").asText(
                        "pdf-page-compat-v1"
                ),
                locatorStartPage == null ? startPage : locatorStartPage,
                locatorEndPage == null ? endPage : locatorEndPage,
                sourceLabel
        );
    }

    private static Integer nullableInteger(JsonNode value) {
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private static UUID nullableUuid(JsonNode value) {
        String text = nullableText(value);
        return text == null ? null : UUID.fromString(text);
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static String snippet(String text, JsonNode highlights) {
        for (String field : List.of(
                "text", "text.english", "title", "title.english"
        )) {
            JsonNode fragments = highlights.path(field);
            if (fragments.isArray() && !fragments.isEmpty()) {
                return fragments.get(0).asText()
                        .replace("<mark>", "")
                        .replace("</mark>", "");
            }
        }
        return text.substring(0, Math.min(text.length(), 220));
    }

    private static List<String> matchedFields(JsonNode highlights) {
        Set<String> fields = new LinkedHashSet<>();
        highlights.fieldNames().forEachRemaining(fields::add);
        return List.copyOf(fields);
    }

    private static UUID uuid(JsonNode source, String field) {
        return UUID.fromString(source.path(field).asText());
    }

    static List<String> path(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : Arrays.stream(value.split("\\R"))
                .filter(item -> !item.isBlank())
                .toList();
    }

    enum Branch {
        BM25,
        VECTOR
    }

    static final class Candidate {

        private final UUID chunkId;
        private final JsonNode source;
        private final Map<String, Integer> bm25Ranks =
                new LinkedHashMap<>();
        private final Map<String, Integer> vectorRanks =
                new LinkedHashMap<>();
        private JsonNode highlights = MissingNode.getInstance();
        private Integer bm25Rank;
        private Integer vectorRank;
        private Integer graphRank;
        private Integer globalRank;
        private Integer memoryRank;
        private Integer rerankRank;
        private Integer evidenceRank;
        private double bm25Score;
        private double rrfScore;
        private double graphRrfScore;
        private double globalRrfScore;
        private Double rerankScore;
        private boolean graphFusion;
        private List<GraphPath> rawGraphPaths = List.of();
        private List<GraphPathView> graphPathViews = List.of();
        private List<GlobalClaimAnchor> rawGlobalClaims = List.of();
        private List<GlobalClaimView> globalClaimViews = List.of();

        Candidate(UUID chunkId, JsonNode source) {
            this.chunkId = chunkId;
            this.source = source;
        }

        void add(
                Branch branch,
                String queryKey,
                int rank,
                double score,
                JsonNode branchHighlights
        ) {
            if (branch == Branch.BM25) {
                bm25Ranks.put(queryKey, rank);
                if (bm25Rank == null || rank < bm25Rank) {
                    bm25Rank = rank;
                    bm25Score = score;
                    highlights = branchHighlights;
                }
            } else {
                vectorRanks.put(queryKey, rank);
                if (vectorRank == null || rank < vectorRank) {
                    vectorRank = rank;
                }
            }
        }

        void calculateRrf(int rankConstant) {
            rrfScore = bm25Ranks.entrySet().stream()
                    .mapToDouble(entry -> rrfContribution(
                            entry.getKey(), entry.getValue(), rankConstant
                    ))
                    .sum()
                    + vectorRanks.entrySet().stream()
                    .mapToDouble(entry -> rrfContribution(
                            entry.getKey(), entry.getValue(), rankConstant
                    ))
                    .sum()
                    + (memoryRank == null
                    ? 0.0
                    : 1.0 / (rankConstant + memoryRank));
        }

        void setRerank(int rank, double score) {
            rerankRank = rank;
            rerankScore = score;
        }

        void addMemory(int rank) {
            memoryRank = memoryRank == null
                    ? rank
                    : Math.min(memoryRank, rank);
        }

        void addGraph(int rank, List<GraphPath> paths) {
            graphRank = rank;
            rawGraphPaths = List.copyOf(paths);
            graphPathViews = paths.stream()
                    .map(path -> new GraphPathView(
                            path.depth(),
                            path.relationshipId(),
                            path.relationshipType(),
                            path.childId(),
                            path.sourceSpanId(),
                            path.documentId(),
                            path.documentTitle(),
                            path.startPage(),
                            path.endPage(),
                            path.evidenceText(),
                            0,
                            path.documentFormat(),
                            path.sourceLocator(),
                            path.sourceLabel()
                    ))
                    .toList();
        }

        void enableGraphFusion(int rankConstant, double graphWeight) {
            calculateRrf(rankConstant);
            graphRrfScore = graphRank == null
                    ? 0.0
                    : graphWeight / (rankConstant + graphRank);
            graphFusion = true;
        }

        void addGlobal(
                int rank,
                List<GlobalClaimAnchor> claims
        ) {
            globalRank = rank;
            rawGlobalClaims = List.copyOf(claims);
        }

        void enableGlobalFusion(int rankConstant) {
            calculateRrf(rankConstant);
            globalRrfScore = globalRank == null
                    ? 0.0
                    : 1.0 / (rankConstant + globalRank);
            graphFusion = true;
        }

        void setEvidenceRank(int rank) {
            evidenceRank = rank;
        }

        double retrievalScore() {
            if (graphFusion) {
                return rrfScore + graphRrfScore + globalRrfScore;
            }
            return memoryRank == null
                    && vectorRanks.isEmpty()
                    && bm25Ranks.size() == 1
                    ? bm25Score
                    : rrfScore;
        }

        double finalScore() {
            return rerankScore == null ? retrievalScore() : rerankScore;
        }

        String rerankText() {
            String title = source.path("title").asText();
            String heading = source.path("headingPath").asText();
            String text = source.path("text").asText();
            return java.util.stream.Stream.of(title, heading.replace('\n', ' '), text)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        ContextSeed contextSeed() {
            return new ContextSeed(
                    chunkId,
                    uuid(source, "documentId"),
                    uuid(source, "revisionId"),
                    source.path("text").asText()
            );
        }

        UUID chunkId() {
            return chunkId;
        }

        JsonNode source() {
            return source;
        }

        JsonNode highlights() {
            return highlights;
        }

        Integer bm25Rank() {
            return bm25Rank;
        }

        Integer vectorRank() {
            return vectorRank;
        }

        boolean hasVector() {
            return !vectorRanks.isEmpty();
        }

        boolean matchesQuery(String queryKey) {
            return bm25Ranks.containsKey(queryKey)
                    || vectorRanks.containsKey(queryKey);
        }

        List<String> querySlots() {
            Set<String> keys = new LinkedHashSet<>(bm25Ranks.keySet());
            keys.addAll(vectorRanks.keySet());
            return List.copyOf(keys);
        }

        int rankFor(String queryKey) {
            return Math.min(
                    bm25Ranks.getOrDefault(queryKey, Integer.MAX_VALUE),
                    vectorRanks.getOrDefault(queryKey, Integer.MAX_VALUE)
            );
        }

        Integer graphRank() {
            return graphRank;
        }

        Integer globalRank() {
            return globalRank;
        }

        List<GraphPath> rawGraphPaths() {
            return rawGraphPaths;
        }

        List<GraphPathView> graphPathViews() {
            return graphPathViews;
        }

        void setGraphPathViews(List<GraphPathView> paths) {
            graphPathViews = paths;
        }

        List<GlobalClaimAnchor> rawGlobalClaims() {
            return rawGlobalClaims;
        }

        List<GlobalClaimView> globalClaimViews() {
            return globalClaimViews;
        }

        void setGlobalClaimViews(List<GlobalClaimView> claims) {
            globalClaimViews = claims;
        }

        Double rrfScore() {
            return rrfScore;
        }

        Integer rerankRank() {
            return rerankRank;
        }

        Double rerankScore() {
            return rerankScore;
        }

        Integer evidenceRank() {
            return evidenceRank;
        }
    }

    private record TimedSearch(
            JsonNode response,
            long totalMs,
            long embeddingMs,
            long searchMs
    ) {
    }

    private record QueryRecall(
            int round,
            int slot,
            String query,
            TimedSearch bm25,
            TimedSearch vector,
            String bm25Code,
            String vectorCode,
            String code,
            boolean successful
    ) {
    }

    private record RecallSummary(
            List<QuerySlot> slots,
            int successfulQueries,
            int rawHitCount,
            long rawTotal,
            Set<String> degradationCodes
    ) {
    }

    private record GraphFusion(
            List<Candidate> candidates,
            int graphCandidateCount
    ) {
    }

    private record GraphEvidencePair(
            Candidate parent,
            Candidate child,
            boolean explicitPath,
            String pathText,
            int bridgeTerms
    ) {
    }

    private record GlobalFusion(
            List<Candidate> candidates,
            int globalCandidateCount
    ) {
    }

    private record BudgetPath(
            Candidate candidate,
            int candidateOrder,
            int pathOrder,
            GraphPath path
    ) {
    }

    private record GraphBudget(
            int tokens,
            int pathCount,
            boolean truncated
    ) {
    }

    private static final class MutableGlobalChild {

        private int rank;
        private final List<GlobalClaimAnchor> claims;

        private MutableGlobalChild(
                int rank,
                List<GlobalClaimAnchor> claims
        ) {
            this.rank = rank;
            this.claims = claims;
        }
    }

    private record GlobalChild(
            int rank,
            List<GlobalClaimAnchor> claims
    ) {
    }

    private record GlobalClaimAnchor(
            UUID reportId,
            int reportRank,
            int communityKey,
            String reportTitle,
            UUID claimId,
            int claimOrder,
            String claimText,
            GlobalEvidence evidence
    ) {
    }

    private record BudgetGlobalClaim(
            Candidate candidate,
            int candidateOrder,
            GlobalClaimAnchor claim
    ) {
    }

    private record GlobalBudget(
            int tokens,
            int claimCount,
            boolean truncated
    ) {
    }

    private record SearchExecution(
            String indexName,
            SearchPage page,
            List<DebugCandidate> candidates,
            List<DebugStage> stages,
            ContextBudget contextBudget,
            GraphDiagnostics graphDiagnostics,
            SearchMetadata metadata
    ) {
    }

    private record BridgeTerm(
            String value,
            String normalized,
            int score
    ) {
    }

    private static final class BranchTimeoutException extends RuntimeException {
    }
}
