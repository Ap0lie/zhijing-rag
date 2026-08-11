package com.example.rag.chat;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.rag.chat.ChatModelProvider.ModelAnswer;
import com.example.rag.chat.ChatModelProvider.AnswerExecution;
import com.example.rag.chat.ChatModelProvider.ModelEvidence;
import com.example.rag.chat.ChatModelProvider.ModelMemory;
import com.example.rag.chat.ChatModelProvider.GraphEvidence;
import com.example.rag.chat.ChatModelProvider.ModelSegment;
import com.example.rag.chat.ChatHistoryWindowService.HistoryWindow;
import com.example.rag.chat.PromptContextPlanner.PromptPlan;
import com.example.rag.chat.ChatPersistenceContracts.Citation;
import com.example.rag.chat.ChatPersistenceContracts.CitationDraft;
import com.example.rag.chat.ChatPersistenceContracts.AnswerStrategy;
import com.example.rag.chat.ChatPersistenceContracts.RunCompletion;
import com.example.rag.chat.ChatPersistenceContracts.RunRetrievalSnapshot;
import com.example.rag.chat.ChatPersistenceContracts.RunQueryPlanSnapshot;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.common.ApiException;
import com.example.rag.memory.MemoryPackService;
import com.example.rag.memory.MemoryPackService.MemoryPack;
import com.example.rag.memory.MemoryPackService.RunMemoryUsageView;
import com.example.rag.search.ChunkContextService;
import com.example.rag.search.SearchContracts.ChunkContext;
import com.example.rag.search.SearchContracts.SearchHit;
import com.example.rag.search.SearchContracts.SearchPage;
import com.example.rag.search.SearchContracts.QueryExecution;
import com.example.rag.search.SearchContracts.QuerySlot;
import com.example.rag.search.SearchContracts.RouteExecution;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.GraphPathView;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.search.SearchContracts.SourceSpanView;
import com.example.rag.search.SearchService;
import com.example.rag.search.SearchService.QueryExecutionPolicy;
import com.example.rag.search.SearchService.QueryPlan;
import com.example.rag.search.SearchService.RoutingDecision;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatWorkflow {

    static final String ORCHESTRATION_VERSION = "phase14b-stategraph-v1";
    private static final int MAX_DIRECT_ANSWER_LENGTH = 512;

    private static final String RUN_ID = "runId";
    private static final String HISTORY = "history";
    private static final String QUERY_PLAN = "queryPlan";
    private static final String MEMORY = "memory";
    private static final String RETRIEVAL = "retrieval";
    private static final String EVIDENCE = "evidence";
    private static final String PROMPT_PLAN = "promptPlan";
    private static final String MODEL_ANSWER = "modelAnswer";
    private static final String ANSWER_EXECUTION = "answerExecution";
    private static final String VALIDATED = "validated";
    private static final String OUTCOME = "outcome";

    private final SearchService search;
    private final ChunkContextService chunks;
    private final ChatModelProvider model;
    private final ChatPersistenceRepository repository;
    private final ChatHistoryWindowService historyWindows;
    private final QueryIntelligenceProfileService queryProfiles;
    private final QueryRoutingService queryRouting;
    private final MemoryPackService memories;
    private final PromptContextPlanner promptPlanner;
    private final ContextCompressionService compression;
    private final TransactionTemplate transactions;
    private final ChatProperties properties;
    private final ChatUserGuard userGuard;
    private final ObjectMapper objectMapper;
    private final CompiledGraph graph;
    private final Map<UUID, RunInput> executions = new ConcurrentHashMap<>();

    @Autowired
    ChatWorkflow(
            SearchService search,
            ChunkContextService chunks,
            ChatModelProvider model,
            ChatPersistenceRepository repository,
            ChatHistoryWindowService historyWindows,
            QueryIntelligenceProfileService queryProfiles,
            QueryRoutingService queryRouting,
            MemoryPackService memories,
            PromptContextPlanner promptPlanner,
            ContextCompressionService compression,
            TransactionTemplate transactions,
            ChatProperties properties,
            ChatUserGuard userGuard,
            ObjectMapper objectMapper
    ) throws GraphStateException {
        this.search = search;
        this.chunks = chunks;
        this.model = model;
        this.repository = repository;
        this.historyWindows = historyWindows;
        this.queryProfiles = queryProfiles;
        this.queryRouting = queryRouting;
        this.memories = memories;
        this.promptPlanner = promptPlanner;
        this.compression = compression;
        this.transactions = transactions;
        this.properties = properties;
        this.userGuard = userGuard;
        this.objectMapper = objectMapper;
        this.graph = buildGraph();
    }

    ChatWorkflow(
            SearchService search,
            ChunkContextService chunks,
            ChatModelProvider model,
            ChatPersistenceRepository repository,
            ChatHistoryWindowService historyWindows,
            QueryIntelligenceProfileService queryProfiles,
            QueryRoutingService queryRouting,
            MemoryPackService memories,
            ChatProperties properties,
            ChatUserGuard userGuard,
            ObjectMapper objectMapper
    ) throws GraphStateException {
        this(
                search, chunks, model, repository, historyWindows,
                queryProfiles, queryRouting, memories,
                new PromptContextPlanner(model, properties, objectMapper),
                null, null,
                properties, userGuard, objectMapper
        );
    }

    PersistedOutcome execute(RunInput input) {
        UUID runId = input.started().run().id();
        if (executions.putIfAbsent(runId, input) != null) {
            throw new ChatWorkflowException(
                    "RUN_ALREADY_EXECUTING",
                    "问答任务正在执行"
            );
        }
        Map<String, Object> initial = Map.of(
                RUN_ID, runId.toString(),
                GraphLifecycleListener.EXECUTION_ID_KEY, runId.toString()
        );
        try {
            OverAllState finalState = graph.invoke(
                            initial,
                            RunnableConfig.builder()
                                    .threadId(runId.toString())
                                    .build()
                    )
                    .orElseThrow(() -> new ChatWorkflowException(
                            "GRAPH_NO_RESULT",
                            "问答流程未产生结果"
                    ));
            PersistedOutcome outcome = finalState.value(
                            OUTCOME,
                            PersistedOutcome.class
                    )
                    .orElseThrow(() -> new ChatWorkflowException(
                            "GRAPH_NO_OUTCOME",
                            "问答流程未产生终态"
                    ));
            try {
                return new PersistedOutcome(
                        outcome.status(),
                        outcome.runId(),
                        outcome.messageId(),
                        outcome.language(),
                        outcome.content(),
                        outcome.directAnswer(),
                        typedList(
                                outcome.directAnswerCitationIds(),
                                UUID.class
                        ),
                        typedList(outcome.segments(), ModelSegment.class),
                        typedList(outcome.citations(), Citation.class),
                        typedList(outcome.evidence(), EvidenceItem.class),
                        outcome.refusalCode(),
                        outcome.graphProfileVersion(),
                        outcome.graphGeneration(),
                        outcome.graphModeRequested(),
                        outcome.graphModeUsed(),
                        outcome.graphDegraded(),
                        outcome.graphDegradationCode(),
                        outcome.globalConfigVersion(),
                        outcome.globalGeneration(),
                        outcome.answerStrategyRequested(),
                        outcome.answerStrategyUsed(),
                        outcome.mapCallCount(),
                        outcome.reduceCallCount(),
                        outcome.queryProfileVersion(),
                        outcome.historyMessageCount(),
                        outcome.historyTokenCount(),
                        typedList(
                                outcome.historyTrimReasons(),
                                String.class
                        ),
                        outcome.standaloneQuery(),
                        typedList(outcome.querySlots(), QuerySlot.class),
                        outcome.plannerCallCount(),
                        outcome.retrievalCallCount(),
                        outcome.rerankCallCount(),
                        outcome.coverageSufficient(),
                        outcome.queryDegraded(),
                        outcome.queryDegradationCode(),
                        outcome.retrievedCandidateCount(),
                        outcome.authorizedCandidateCount(),
                        outcome.rerankedCandidateCount(),
                        outcome.evidenceCandidateCount(),
                        outcome.validatedEvidenceCount(),
                        outcome.routeSelectedMode(),
                        outcome.routerCallCount(),
                        outcome.routeReasonCode(),
                        outcome.routeDegraded(),
                        outcome.routeDegradationCode(),
                        outcome.memoryInjectedCount(),
                        outcome.memoryUsedCount(),
                        outcome.memoryTokenCount(),
                        typedList(
                                outcome.memoryUsages(),
                                RunMemoryUsageView.class
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new ChatWorkflowException(
                        "GRAPH_OUTCOME_INVALID",
                        "问答流程终态无效",
                        exception
                );
            }
        } finally {
            executions.remove(runId, input);
        }
    }

    private <T> List<T> typedList(List<?> values, Class<T> type) {
        return values.stream()
                .map(value -> type.isInstance(value)
                        ? type.cast(value)
                        : objectMapper.convertValue(value, type))
                .toList();
    }

    private CompiledGraph buildGraph() throws GraphStateException {
        StateGraph definition = new StateGraph()
                .addNode("authorize", node_async(this::authorize))
                .addNode("load_history", node_async(this::loadHistory))
                .addNode("plan_query", node_async(this::planQuery))
                .addNode("recall_memory", node_async(this::recallMemory))
                .addNode("retrieve", node_async(this::retrieve))
                .addNode("validate_evidence", node_async(this::validateEvidence))
                .addNode("plan_prompt_context", node_async(
                        this::planPromptContext
                ))
                .addNode("generate", node_async(this::generate))
                .addNode("refuse", node_async(this::refuse))
                .addNode("validate_citations", node_async(this::validateCitations))
                .addNode("persist", node_async(this::persist))
                .addEdge(StateGraph.START, "authorize")
                .addEdge("authorize", "load_history")
                .addEdge("load_history", "plan_query")
                .addEdge("plan_query", "recall_memory")
                .addEdge("recall_memory", "retrieve")
                .addEdge("retrieve", "validate_evidence")
                .addConditionalEdges(
                        "validate_evidence",
                        edge_async(state -> evidence(state).isEmpty()
                                ? "refuse" : "plan_prompt_context"),
                        Map.of(
                                "plan_prompt_context", "plan_prompt_context",
                                "refuse", "refuse"
                        )
                )
                .addEdge("plan_prompt_context", "generate")
                .addEdge("generate", "validate_citations")
                .addEdge("refuse", "validate_citations")
                .addEdge("validate_citations", "persist")
                .addEdge("persist", StateGraph.END);
        CompileConfig config = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().build())
                .withLifecycleListener(new RedactedGraphListener())
                .build();
        return definition.compile(config);
    }

    private Map<String, Object> authorize(OverAllState state) {
        RunInput input = input(state);
        userGuard.requireCurrent(input.user());
        if (!input.user().isEnabled()
                || !input.user().id().equals(input.started().run().ownerUserId())) {
            throw new ChatWorkflowException("CHAT_FORBIDDEN", "无权执行该问答");
        }
        boolean active = repository.findRun(
                        input.user().id(),
                        input.started().run().id()
                )
                .filter(run -> run.status() == RunStatus.RUNNING)
                .isPresent();
        if (!active) {
            throw new ChatWorkflowException("RUN_NOT_ACTIVE", "问答任务已结束");
        }
        return Map.of();
    }

    private Map<String, Object> loadHistory(OverAllState state) {
        RunInput input = input(state);
        userGuard.requireCurrent(input.user());
        return Map.of(
                HISTORY,
                historyWindows.build(input.user(), input.started())
        );
    }

    private Map<String, Object> planQuery(OverAllState state) {
        RunInput input = input(state);
        userGuard.requireCurrent(input.user());
        String profileVersion = input.started().run()
                .queryIntelligenceProfileVersion();
        if (profileVersion == null) {
            GraphMode requested = graphMode(
                    input.started().run().graphModeRequested()
            );
            HistoryWindow history = history(state);
            String standalone = input.question();
            int rewriteCalls = 0;
            boolean degraded = false;
            String degradationCode = null;
            if (!history.messages().isEmpty()) {
                PromptContextPlanner.RewritePlan rewrite =
                        promptPlanner.planRewrite(
                                input.user().id(),
                                input.started().run().sessionId(),
                                input.started().run().id(),
                                input.question(), history
                        );
                if (rewrite.callModel()) {
                    rewriteCalls = 1;
                    try {
                        standalone = model.rewriteWithContext(
                                input.question(), rewrite.history()
                        );
                    } catch (RuntimeException exception) {
                        standalone = input.question();
                        degraded = true;
                        degradationCode = "CONTEXT_REWRITE_FAILED";
                    }
                } else {
                    degraded = true;
                    degradationCode = rewrite.reasonCode();
                }
            }
            return Map.of(
                    QUERY_PLAN,
                    new PlannedQuery(
                            new QueryPlan(
                                    standalone,
                                    List.of(standalone),
                                    rewriteCalls,
                                    degraded,
                                    degradationCode,
                                    null,
                                    null
                            ),
                            defaultRouting(requested),
                            null,
                            null
                    )
            );
        }
        ProfileView profile = queryProfiles.find(profileVersion);
        QueryRoutingService.PlannedRequest planned = queryRouting.plan(
                input.question(),
                history(state).messages(),
                graphMode(input.started().run().graphModeRequested()),
                profile
        );
        return Map.of(
                QUERY_PLAN,
                new PlannedQuery(
                        planned.queryPlan(),
                        planned.routing(),
                        planned.profile(),
                        planned.policy()
                )
        );
    }

    private Map<String, Object> retrieve(OverAllState state) {
        RunInput input = input(state);
        PlannedQuery planned = state.value(
                QUERY_PLAN, PlannedQuery.class
        ).orElseGet(() -> new PlannedQuery(
                QueryPlan.single(input.question()),
                defaultRouting(graphMode(
                        input.started().run().graphModeRequested())),
                null,
                null
        ));
        SearchRequest request = new SearchRequest(
                planned.plan().standaloneQuery(),
                0,
                8,
                input.evaluationDocumentId(),
                null,
                graphMode(input.started().run().graphModeRequested())
        );
        SearchPage page = planned.profile() == null
                ? search.search(
                request,
                input.user(),
                input.evaluationTarget(),
                memory(state).documentChildIds()
        )
                : search.searchPlanned(
                request,
                input.user(),
                input.evaluationTarget(),
                planned.plan(),
                queryRouting.secondRoundPlanner(
                        input.question(),
                        history(state).messages(),
                        planned.plan(),
                        planned.profile(),
                        planned.policy()
                ),
                planned.routing(),
                planned.policy(),
                memory(state).documentChildIds()
        );
        QueryExecution queryExecution = page.queryExecution();
        Map<String, Object> queryBudget = new LinkedHashMap<>();
        queryBudget.put(
                "queryPlanHash", hash(Map.of(
                        "standaloneQuery",
                        queryExecution.standaloneQuery(),
                        "slots",
                        queryExecution.slots()
                ))
        );
        queryBudget.put(
                "plannerCallCount",
                queryExecution.plannerCallCount()
        );
        queryBudget.put(
                "retrievalCallCount",
                queryExecution.retrievalCallCount()
        );
        queryBudget.put(
                "rerankCallCount",
                queryExecution.rerankCallCount()
        );
        queryBudget.put(
                "coverageSufficient",
                queryExecution.coverageSufficient()
        );
        queryBudget.put(
                "retrievedCandidateCount",
                queryExecution.retrievedCandidateCount()
        );
        queryBudget.put(
                "authorizedCandidateCount",
                queryExecution.authorizedCandidateCount()
        );
        queryBudget.put(
                "rerankedCandidateCount",
                queryExecution.rerankedCandidateCount()
        );
        queryBudget.put(
                "evidenceCandidateCount",
                queryExecution.evidenceCandidateCount()
        );
        RouteExecution route = page.routeExecution();
        queryBudget.put("routeRequested", route.requestedMode());
        queryBudget.put("routeSelected", route.selectedMode());
        queryBudget.put("routerCallCount", route.routerCallCount());
        queryBudget.put("routeReasonCode", route.reasonCode());
        queryBudget.put("routeDegraded", route.degraded());
        queryBudget.put("routeDegradationCode", route.degradationCode());
        repository.recordQueryPlanSnapshot(
                input.user().id(),
                input.started().run().id(),
                new RunQueryPlanSnapshot(
                        queryExecution.standaloneQuery(),
                        json(queryExecution.slots()),
                        json(queryBudget),
                        queryExecution.degraded()
                                ? queryExecution.degradationCode()
                                : "QUERY_PLAN_OK"
                )
        );
        repository.recordRetrievalSnapshot(
                input.user().id(),
                input.started().run().id(),
                new RunRetrievalSnapshot(
                        page.degraded()
                                ? page.degradationCode()
                                : page.modeUsed(),
                        page.profileVersion(),
                        page.indexGeneration(),
                        page.graphProfileVersion(),
                        page.graphGeneration(),
                        page.graphModeRequested(),
                        page.graphModeUsed(),
                        page.graphDegraded(),
                        page.graphDegradationCode(),
                        page.globalExecution() == null
                                ? null
                                : page.globalExecution().configVersion(),
                        page.globalExecution() == null
                                ? null
                                : page.globalExecution().globalGeneration()
                )
        );
        return Map.of(RETRIEVAL, page);
    }

    private Map<String, Object> recallMemory(OverAllState state) {
        RunInput input = input(state);
        userGuard.requireCurrent(input.user());
        PlannedQuery planned = state.value(
                QUERY_PLAN, PlannedQuery.class
        ).orElseThrow();
        boolean personalMemoryAllowed =
                properties.getLlm().isLocalEndpoint()
                        || properties.getLlm().isRemoteMemoryAllowed();
        int contextTokens = planned.profile() == null
                ? properties.getLlm().getContextWindowTokens()
                : planned.profile().modelContextTokens();
        return Map.of(
                MEMORY,
                memories.recall(
                        input.user(),
                        planned.plan().standaloneQuery(),
                        personalMemoryAllowed,
                        contextTokens
                )
        );
    }

    private Map<String, Object> validateEvidence(OverAllState state) {
        RunInput input = input(state);
        SearchPage page = state.value(RETRIEVAL, SearchPage.class).orElseThrow();
        List<EvidenceItem> accepted = new ArrayList<>();
        for (SearchHit hit : page.items()) {
            try {
                ChunkContext context = chunks.get(hit.chunkId(), input.user());
                if (!context.documentId().equals(hit.documentId())
                        || !context.revisionId().equals(hit.revisionId())
                        || context.sourceSpans().isEmpty()) {
                    continue;
                }
                UUID citationId = UUID.randomUUID();
                SourceSpanView span = citationAnchor(context);
                String parentText = hit.evidence() == null
                        || hit.evidence().parent() == null
                        ? null
                        : hit.evidence().parent().text();
                GraphContextPlan graphContext = graphContext(
                        hit,
                        context,
                        citationId,
                        span
                );
                accepted.add(new EvidenceItem(
                        citationId,
                        hit,
                        context,
                        graphContext.citationSpans(),
                        new ModelEvidence(
                                citationId,
                                context.documentTitle(),
                                context.revisionNumber(),
                                context.child().headingPath(),
                                context.child().startPage(),
                                context.child().endPage(),
                                context.child().text(),
                                parentText,
                                graphContext.evidence(),
                                context.documentFormat(),
                                context.child().sourceLabel()
                        )
                ));
            } catch (ApiException exception) {
                if (exception.getStatus() != HttpStatus.NOT_FOUND) {
                    throw exception;
                }
            }
        }
        return Map.of(EVIDENCE, List.copyOf(accepted));
    }

    private Map<String, Object> generate(OverAllState state) {
        RunInput input = input(state);
        userGuard.requireCurrent(input.user());
        if (input.modelTimeoutFault()) {
            throw new ChatWorkflowException(
                    "EVALUATION_MODEL_TIMEOUT",
                    "评测请求注入了模型超时"
            );
        }
        PromptPlan promptPlan = promptPlan(state);
        List<ModelEvidence> modelEvidence =
                promptPlan.prompt().evidence();
        List<ModelMemory> modelMemories = promptPlan.prompt().memories();
        List<ChatModelProvider.ModelHistoryMessage> modelHistory =
                promptPlan.prompt().history();
        AnswerStrategy requested = answerStrategy(
                input.started().run().answerStrategyRequested()
        );
        SearchPage page = state.value(RETRIEVAL, SearchPage.class).orElseThrow();
        AnswerExecution execution;
        if (requested == AnswerStrategy.DEEP_GLOBAL
                && GraphMode.GLOBAL_GRAPH.name().equals(page.graphModeUsed())) {
            execution = DeepGlobalAnswerGenerator.answer(
                    model,
                    promptPlan.prompt(),
                    promptPlanner,
                    DeepGlobalAnswerGenerator.HARD_TIMEOUT,
                    (used, mapCalls, reduceCalls) -> recordAnswerProgress(
                            input,
                            used,
                            mapCalls,
                            reduceCalls
                    ),
                    (stage, callIndex, prepared) ->
                            promptPlanner.recordCall(
                                    input.user().id(),
                                    input.started().run().sessionId(),
                                    input.started().run().id(),
                                    history(state).summaryId(),
                                    stage,
                                    callIndex,
                                    prepared
                    )
            );
        } else {
            recordAnswerProgress(
                    input,
                    AnswerStrategy.STANDARD,
                    0,
                    0
            );
            execution = AnswerExecution.standard(
                    model.answer(promptPlan.prompt()),
                    requested,
                    requested == AnswerStrategy.DEEP_GLOBAL
                            ? "DEEP_GLOBAL_RETRIEVAL_FALLBACK"
                            : null
            );
        }
        return Map.of(
                MODEL_ANSWER, execution.answer(),
                ANSWER_EXECUTION, execution
        );
    }

    private Map<String, Object> planPromptContext(OverAllState state) {
        RunInput input = input(state);
        userGuard.requireCurrent(input.user());
        return Map.of(
                PROMPT_PLAN,
                promptPlanner.plan(
                        input.user().id(),
                        input.started().run().sessionId(),
                        input.started().run().id(),
                        input.question(),
                        evidence(state),
                        history(state),
                        memory(state)
                )
        );
    }

    private void recordAnswerProgress(
            RunInput input,
            AnswerStrategy used,
            int mapCalls,
            int reduceCalls
    ) {
        try {
            repository.recordAnswerProgress(
                input.user().id(),
                input.started().run().id(),
                used,
                mapCalls,
                reduceCalls
            );
        } catch (IllegalStateException exception) {
            throw new ChatWorkflowException(
                    "RUN_NOT_ACTIVE",
                    "问答任务已结束",
                    exception
            );
        }
    }

    private Map<String, Object> refuse(OverAllState state) {
        AnswerStrategy requested = answerStrategy(
                input(state).started().run().answerStrategyRequested()
        );
        ModelAnswer answer = new ModelAnswer(
                List.of(),
                "INSUFFICIENT_EVIDENCE"
        );
        return Map.of(
                MODEL_ANSWER, answer,
                ANSWER_EXECUTION, AnswerExecution.standard(
                        answer,
                        requested,
                        requested == AnswerStrategy.DEEP_GLOBAL
                                ? "DEEP_GLOBAL_NO_EVIDENCE"
                                : null
                )
        );
    }

    private Map<String, Object> validateCitations(OverAllState state) {
        RunInput input = input(state);
        ModelAnswer answer = state.value(MODEL_ANSWER, ModelAnswer.class).orElseThrow();
        if (answer.refusalReason() != null && !answer.refusalReason().isBlank()) {
            boolean hasValidatedEvidence = !evidence(state).isEmpty();
            return Map.of(
                    VALIDATED,
                    ValidatedAnswer.refused(
                            hasValidatedEvidence
                                    ? modelRefusalText(input.language())
                                    : refusalText(input.language()),
                            hasValidatedEvidence
                                    ? "MODEL_REFUSED"
                                    : "INSUFFICIENT_EVIDENCE"
                    )
            );
        }

        Map<UUID, EvidenceReference> whitelist = new LinkedHashMap<>();
        promptEvidence(state).forEach(item -> item.citationSpans().forEach(
                (id, span) -> whitelist.put(
                        id,
                        new EvidenceReference(id, item, span)
                )
        ));
        Set<UUID> memoryWhitelist = new LinkedHashSet<>(
                currentMemoryWhitelist(input.user(), memory(state))
        );
        memoryWhitelist.retainAll(promptPlan(state).memoryIds());
        List<ModelSegment> segments = new ArrayList<>();
        Map<UUID, EvidenceReference> used = new LinkedHashMap<>();
        Set<UUID> usedMemories = new LinkedHashSet<>();
        for (ModelSegment segment : answer.segments()) {
            String text = segment.text() == null ? "" : segment.text().trim();
            List<UUID> ids = segment.citationIds() == null
                    ? List.of()
                    : segment.citationIds().stream().distinct().toList();
            List<UUID> memoryIds = segment.memoryIds() == null
                    ? List.of()
                    : segment.memoryIds().stream().distinct().toList();
            if (text.isEmpty() || text.length() > 4_000
                    || ids.isEmpty()
                    || ids.stream().anyMatch(id ->
                    !whitelist.containsKey(id))
                    || memoryIds.stream().anyMatch(id ->
                    !memoryWhitelist.contains(id))) {
                continue;
            }
            boolean current = true;
            for (UUID id : ids) {
                EvidenceReference reference = whitelist.get(id);
                if (!stillAuthorized(reference, input.user())) {
                    current = false;
                    break;
                }
            }
            if (!current) {
                continue;
            }
            segments.add(new ModelSegment(text, ids, memoryIds));
            ids.forEach(id -> used.putIfAbsent(id, whitelist.get(id)));
            usedMemories.addAll(memoryIds);
        }
        if (segments.isEmpty()) {
            return Map.of(
                    VALIDATED,
                    ValidatedAnswer.refused(
                            unsupportedAnswerText(input.language()),
                            "UNSUPPORTED_ANSWER"
                    )
            );
        }
        String directAnswer = answer.directAnswer() == null
                ? null : answer.directAnswer().trim();
        List<UUID> directAnswerCitationIds =
                answer.directAnswerCitationIds() == null
                        ? List.of()
                        : answer.directAnswerCitationIds().stream()
                        .distinct()
                        .toList();
        if (directAnswer == null
                || directAnswer.isBlank()
                || directAnswer.length() > MAX_DIRECT_ANSWER_LENGTH
                || directAnswerCitationIds.isEmpty()
                || !used.keySet().containsAll(directAnswerCitationIds)) {
            directAnswer = null;
            directAnswerCitationIds = List.of();
        }
        Map<UUID, Integer> citationNumbers = new LinkedHashMap<>();
        int citationNumber = 1;
        for (UUID id : used.keySet()) {
            citationNumbers.put(id, citationNumber++);
        }
        List<ModelSegment> numberedSegments = segments.stream()
                .map(segment -> new ModelSegment(
                        segment.text() + citationMarkers(
                                segment.citationIds(),
                                citationNumbers
                        ),
                        segment.citationIds(),
                        segment.memoryIds()
                ))
                .toList();
        String separator = "en".equals(input.language()) ? " " : "";
        String content = String.join(
                separator,
                numberedSegments.stream().map(ModelSegment::text).toList()
        );
        Map<UUID, EvidenceItem> usedItems = new LinkedHashMap<>();
        used.values().forEach(reference -> usedItems.putIfAbsent(
                reference.item().id(),
                reference.item()
        ));
        return Map.of(
                VALIDATED,
                new ValidatedAnswer(
                        RunStatus.COMPLETED,
                        content,
                        numberedSegments,
                        List.copyOf(used.values()),
                        List.copyOf(usedItems.values()),
                        Set.copyOf(usedMemories),
                        directAnswer,
                        directAnswerCitationIds,
                        null
                )
        );
    }

    private Map<String, Object> persist(OverAllState state) {
        RunInput input = input(state);
        SearchPage page = state.value(RETRIEVAL, SearchPage.class).orElseThrow();
        ValidatedAnswer answer = state.value(VALIDATED, ValidatedAnswer.class).orElseThrow();
        AnswerExecution answerExecution = state.value(
                ANSWER_EXECUTION,
                AnswerExecution.class
        ).orElseThrow();
        HistoryWindow history = history(state);
        MemoryPack memory = memory(state);

        List<Citation> citations = List.of();
        if (answer.status() == RunStatus.COMPLETED) {
            citations = repository.saveCitationWhitelist(
                    input.user().id(),
                    input.started().run().id(),
                    answer.usedCitations().stream()
                            .map(reference -> new CitationDraft(
                                    reference.id(),
                                    reference.item().context().documentId(),
                                    reference.item().context().revisionId(),
                                    reference.item().context().child().id(),
                                    reference.span().id()
                            ))
                            .toList()
            );
        }
        memories.saveRunUsages(
                input.user().id(),
                input.started().run().id(),
                memory,
                answer.usedMemoryIds(),
                answer.usedEvidence().stream()
                        .map(item -> item.context().child().id())
                        .collect(java.util.stream.Collectors.toSet())
        );
        List<RunMemoryUsageView> memoryUsages = memories.runUsages(
                input.user(), input.started().run().id()
        );

        List<String> trimReasons = answer.usedEvidence().stream()
                .map(EvidenceItem::hit)
                .filter(hit -> hit.evidence() != null && hit.evidence().parent() != null)
                .filter(hit -> hit.evidence().parent().truncated())
                .map(hit -> "PARENT_TRUNCATED")
                .distinct()
                .toList();
        Map<String, Object> budgetUsage = new LinkedHashMap<>();
        budgetUsage.put("searchTookMs", page.tookMs());
        budgetUsage.put("evidenceCount", evidence(state).size());
        budgetUsage.put("citationCount", citations.size());
        budgetUsage.put(
                "memoryInjectedCount",
                memory.injected().size()
        );
        budgetUsage.put(
                "memoryUsedCount",
                memoryUsages.stream()
                        .filter(usage -> "USED".equals(usage.usageStatus())
                                || "DOCUMENT_EVIDENCE".equals(
                                usage.usageStatus()))
                        .count()
        );
        budgetUsage.put("memoryTokenCount", memory.tokenCount());
        budgetUsage.put("memoryTokenBudget", memory.tokenBudget());
        budgetUsage.put(
                "memoryTokenCounterVersion",
                memory.tokenCounterVersion()
        );
        if (memory.degradationCode() != null) {
            budgetUsage.put(
                    "memoryDegradationCode",
                    memory.degradationCode()
            );
        }
        budgetUsage.put(
                "queryProfileVersion",
                history.profileVersion()
        );
        budgetUsage.put(
                "historyMessageCount",
                history.messageIds().size()
        );
        budgetUsage.put("historyTokenCount", history.tokenCount());
        PromptPlan finalPromptPlan = state.value(
                PROMPT_PLAN, PromptPlan.class
        ).orElse(null);
        if (finalPromptPlan != null) {
            budgetUsage.put(
                    "promptPlanHash", finalPromptPlan.prompt().planHash()
            );
            budgetUsage.put(
                    "promptInputTokenCap",
                    finalPromptPlan.prompt().inputTokenCap()
            );
            budgetUsage.put(
                    "promptInputTokenCount",
                    finalPromptPlan.prompt().inputTokenCount()
            );
            budgetUsage.put(
                    "promptCounterVersion",
                    finalPromptPlan.prompt().counterVersion()
            );
            budgetUsage.put(
                    "promptTrimReasons",
                    finalPromptPlan.prompt().trimReasons()
            );
        }
        QueryExecution queryExecution = page.queryExecution();
        budgetUsage.put(
                "queryPlanHash",
                hash(Map.of(
                        "standaloneQuery",
                        queryExecution.standaloneQuery(),
                        "slots",
                        queryExecution.slots()
                ))
        );
        budgetUsage.put(
                "plannerCallCount",
                queryExecution.plannerCallCount()
        );
        budgetUsage.put(
                "retrievalCallCount",
                queryExecution.retrievalCallCount()
        );
        budgetUsage.put(
                "rerankCallCount",
                queryExecution.rerankCallCount()
        );
        budgetUsage.put(
                "coverageSufficient",
                queryExecution.coverageSufficient()
        );
        budgetUsage.put(
                "retrievedCandidateCount",
                queryExecution.retrievedCandidateCount()
        );
        budgetUsage.put(
                "authorizedCandidateCount",
                queryExecution.authorizedCandidateCount()
        );
        budgetUsage.put(
                "rerankedCandidateCount",
                queryExecution.rerankedCandidateCount()
        );
        budgetUsage.put(
                "evidenceCandidateCount",
                queryExecution.evidenceCandidateCount()
        );
        budgetUsage.put("validatedEvidenceCount", evidence(state).size());
        budgetUsage.put(
                "queryDegraded",
                queryExecution.degraded()
        );
        if (queryExecution.degradationCode() != null) {
            budgetUsage.put(
                    "queryDegradationCode",
                    queryExecution.degradationCode()
            );
        }
        RouteExecution routeExecution = page.routeExecution();
        budgetUsage.put(
                "routeSelectedMode",
                routeExecution.selectedMode()
        );
        budgetUsage.put(
                "routerCallCount",
                routeExecution.routerCallCount()
        );
        budgetUsage.put(
                "routeReasonCode",
                routeExecution.reasonCode()
        );
        budgetUsage.put(
                "routeDegraded",
                routeExecution.degraded()
        );
        if (routeExecution.degradationCode() != null) {
            budgetUsage.put(
                    "routeDegradationCode",
                    routeExecution.degradationCode()
            );
        }
        budgetUsage.put(
                "answerStrategyRequested",
                answerExecution.strategyRequested().name()
        );
        budgetUsage.put(
                "answerStrategyUsed",
                answerExecution.strategyUsed().name()
        );
        budgetUsage.put("mapCallCount", answerExecution.mapCallCount());
        budgetUsage.put("reduceCallCount", answerExecution.reduceCallCount());
        if (answerExecution.fallbackCode() != null) {
            budgetUsage.put(
                    "answerFallbackCode",
                    answerExecution.fallbackCode()
            );
        }
        budgetUsage.put(
                "graphPathCount",
                page.items().stream()
                        .filter(hit -> hit.evidence() != null)
                        .mapToInt(hit -> hit.evidence().graphPaths().size())
                        .sum()
        );
        RunCompletion completion = new RunCompletion(
                answer.status(),
                answer.content(),
                input.language(),
                null,
                json(budgetUsage),
                page.degraded()
                        ? page.degradationCode()
                        : page.modeUsed(),
                page.profileVersion(),
                page.indexGeneration(),
                page.graphProfileVersion(),
                page.graphGeneration(),
                page.graphModeRequested(),
                page.graphModeUsed(),
                page.graphDegraded(),
                page.graphDegradationCode(),
                page.globalExecution() == null
                        ? null
                        : page.globalExecution().configVersion(),
                page.globalExecution() == null
                        ? null
                        : page.globalExecution().globalGeneration(),
                answerExecution.strategyRequested().name(),
                answerExecution.strategyUsed().name(),
                answerExecution.mapCallCount(),
                answerExecution.reduceCallCount(),
                json(citations.stream().map(Citation::id).toList()),
                json(answer.usedCitations().stream()
                        .map(EvidenceReference::span)
                        .map(SourceSpanView::id)
                        .distinct()
                        .toList()),
                json(trimReasons)
        );
        boolean finished = finishAndScheduleCompression(input, completion);
        if (!finished) {
            throw new ChatWorkflowException("RUN_NOT_ACTIVE", "问答任务已结束");
        }
        return Map.of(
                OUTCOME,
                new PersistedOutcome(
                        answer.status(),
                        input.started().run().id(),
                        input.started().responseMessage().id(),
                        input.language(),
                        answer.content(),
                        answer.directAnswer(),
                        answer.directAnswerCitationIds(),
                        answer.segments(),
                        citations,
                        answer.usedEvidence(),
                        answer.refusalCode(),
                        page.graphProfileVersion(),
                        page.graphGeneration(),
                        page.graphModeRequested(),
                        page.graphModeUsed(),
                        page.graphDegraded(),
                        page.graphDegradationCode(),
                        page.globalExecution() == null
                                ? null
                                : page.globalExecution().configVersion(),
                        page.globalExecution() == null
                                ? null
                                : page.globalExecution().globalGeneration(),
                        answerExecution.strategyRequested().name(),
                        answerExecution.strategyUsed().name(),
                        answerExecution.mapCallCount(),
                        answerExecution.reduceCallCount(),
                        history.profileVersion(),
                        history.messageIds().size(),
                        history.tokenCount(),
                        history.trimReasons(),
                        queryExecution.standaloneQuery(),
                        queryExecution.slots(),
                        queryExecution.plannerCallCount(),
                        queryExecution.retrievalCallCount(),
                        queryExecution.rerankCallCount(),
                        queryExecution.coverageSufficient(),
                        queryExecution.degraded(),
                        queryExecution.degradationCode(),
                        queryExecution.retrievedCandidateCount(),
                        queryExecution.authorizedCandidateCount(),
                        queryExecution.rerankedCandidateCount(),
                        queryExecution.evidenceCandidateCount(),
                        evidence(state).size(),
                        page.routeExecution().selectedMode(),
                        page.routeExecution().routerCallCount(),
                        page.routeExecution().reasonCode(),
                        page.routeExecution().degraded(),
                        page.routeExecution().degradationCode(),
                        memory.injected().size(),
                        (int) memoryUsages.stream()
                                .filter(usage -> "USED".equals(
                                        usage.usageStatus())
                                        || "DOCUMENT_EVIDENCE".equals(
                                        usage.usageStatus()))
                                .count(),
                        memory.tokenCount(),
                        memoryUsages
                )
        );
    }

    private boolean finishAndScheduleCompression(
            RunInput input,
            RunCompletion completion
    ) {
        if (transactions == null || compression == null) {
            return repository.finishRun(
                    input.user().id(), input.started().run().id(), completion
            );
        }
        Boolean finished = transactions.execute(ignored -> {
            boolean completed = repository.finishRun(
                    input.user().id(), input.started().run().id(), completion
            );
            if (completed) {
                compression.prepare(
                        input.user(), input.started().run().sessionId()
                );
            }
            return completed;
        });
        return Boolean.TRUE.equals(finished);
    }

    private boolean stillAuthorized(
            EvidenceReference reference,
            PlatformUserPrincipal user
    ) {
        try {
            EvidenceItem item = reference.item();
            ChunkContext current = chunks.get(item.context().child().id(), user);
            return current.documentId().equals(item.context().documentId())
                    && current.revisionId().equals(item.context().revisionId())
                    && current.sourceSpans().stream()
                    .anyMatch(span -> span.id().equals(reference.span().id())
                            && span.sourceTextHash().equals(
                                    reference.span().sourceTextHash()
                            ));
        } catch (ApiException exception) {
            if (exception.getStatus() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }

    private Set<UUID> currentMemoryWhitelist(
            PlatformUserPrincipal user,
            MemoryPack pack
    ) {
        try {
            return memories.currentInjectedIds(user, pack);
        } catch (DataAccessException exception) {
            return Set.of();
        }
    }

    private static GraphContextPlan graphContext(
            SearchHit hit,
            ChunkContext context,
            UUID baseCitationId,
            SourceSpanView baseSpan
    ) {
        Map<UUID, SourceSpanView> citationSpans = new LinkedHashMap<>();
        citationSpans.put(baseCitationId, baseSpan);
        if (hit.evidence() == null) {
            return new GraphContextPlan(
                    List.of(),
                    Map.copyOf(citationSpans)
            );
        }
        Map<UUID, SourceSpanView> spans = new LinkedHashMap<>();
        context.sourceSpans().forEach(span -> spans.put(span.id(), span));
        Map<UUID, UUID> citationBySpan = new LinkedHashMap<>();
        citationBySpan.put(baseSpan.id(), baseCitationId);
        List<GraphEvidence> evidence = new ArrayList<>();
        List<GraphPathView> graphPaths = hit.evidence().graphPaths() == null
                ? List.of()
                : hit.evidence().graphPaths();
        graphPaths.stream()
                .filter(path -> path.supportingChunkId().equals(
                        context.child().id()
                ))
                .filter(path -> spans.containsKey(path.sourceSpanId()))
                .forEach(path -> {
                    SourceSpanView graphSpan = spans.get(path.sourceSpanId());
                    UUID citationId = citationBySpan.computeIfAbsent(
                            graphSpan.id(),
                            ignored -> UUID.randomUUID()
                    );
                    citationSpans.putIfAbsent(citationId, graphSpan);
                    evidence.add(new GraphEvidence(
                            citationId,
                            path.depth(),
                            path.relationshipType(),
                            path.evidenceText()
                    ));
                });
        if (hit.evidence().globalClaims() != null) {
            hit.evidence().globalClaims().stream()
                    .filter(claim -> claim.supportingChunkId().equals(
                            context.child().id()
                    ))
                    .filter(claim -> spans.containsKey(claim.sourceSpanId()))
                    .forEach(claim -> {
                        SourceSpanView globalSpan = spans.get(
                                claim.sourceSpanId()
                        );
                        UUID citationId = citationBySpan.computeIfAbsent(
                                globalSpan.id(),
                                ignored -> UUID.randomUUID()
                        );
                        citationSpans.putIfAbsent(citationId, globalSpan);
                        evidence.add(new GraphEvidence(
                                citationId,
                                0,
                                "GLOBAL_CLAIM",
                                claim.reportTitle() + "："
                                        + claim.claimText() + "；证据："
                                        + claim.evidenceText()
                        ));
                    });
        }
        return new GraphContextPlan(
                List.copyOf(evidence),
                Map.copyOf(citationSpans)
        );
    }

    private static GraphMode graphMode(String value) {
        return value == null || value.isBlank()
                ? GraphMode.HYBRID
                : GraphMode.valueOf(value);
    }

    private static RoutingDecision defaultRouting(GraphMode requested) {
        return requested == GraphMode.AUTO
                ? RoutingDecision.fallback("ROUTER_PROFILE_UNAVAILABLE")
                : RoutingDecision.explicit(requested);
    }

    private static AnswerStrategy answerStrategy(String value) {
        return value == null || value.isBlank()
                ? AnswerStrategy.STANDARD
                : AnswerStrategy.valueOf(value);
    }

    static SourceSpanView citationAnchor(ChunkContext context) {
        Integer childStartPage = context.child().startPage();
        Integer childEndPage = context.child().endPage();
        return orderedSourceSpans(context).stream()
                .min(Comparator
                        .comparing((SourceSpanView span) ->
                                !isPreciseCellRange(span))
                        .thenComparing(
                                span -> !coversPages(
                                        span, childStartPage, childEndPage
                                )
                        )
                        .thenComparing(
                                Comparator.comparingInt(
                                        ChatWorkflow::pageRange
                                ).reversed()
                        )
                        .thenComparingInt(SourceSpanView::order)
                        .thenComparing(SourceSpanView::id))
                .orElseThrow();
    }

    private static boolean isPreciseCellRange(SourceSpanView span) {
        return span.sourceLocator() != null
                && "CELL_RANGE".equals(span.sourceLocator().kind())
                && span.sourceLabel() != null
                && span.sourceLabel().contains("!");
    }

    private static boolean coversPages(
            SourceSpanView span,
            Integer startPage,
            Integer endPage
    ) {
        return startPage != null
                && endPage != null
                && span.startPage() != null
                && span.endPage() != null
                && span.startPage() <= startPage
                && span.endPage() >= endPage;
    }

    private static int pageRange(SourceSpanView span) {
        return span.startPage() == null || span.endPage() == null
                ? 0
                : span.endPage() - span.startPage();
    }

    private static List<SourceSpanView> orderedSourceSpans(ChunkContext context) {
        return context.sourceSpans().stream()
                .sorted(Comparator
                        .comparingInt(SourceSpanView::order)
                        .thenComparing(SourceSpanView::id))
                .toList();
    }

    static String citationMarkers(
            List<UUID> citationIds,
            Map<UUID, Integer> citationNumbers
    ) {
        StringBuilder markers = new StringBuilder();
        for (UUID id : citationIds) {
            Integer number = citationNumbers.get(id);
            if (number == null) {
                throw new ChatWorkflowException(
                        "CITATION_MAPPING_FAILED",
                        "引用映射不完整"
                );
            }
            markers.append('[').append(number).append(']');
        }
        return markers.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ChatWorkflowException(
                    "CHAT_STATE_SERIALIZATION_FAILED",
                    "问答状态暂时无法保存",
                    exception
            );
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            json(value).getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", exception
            );
        }
    }

    private RunInput input(OverAllState state) {
        String value = state.value(RUN_ID, String.class).orElseThrow();
        try {
            RunInput input = executions.get(UUID.fromString(value));
            if (input == null) {
                throw new ChatWorkflowException(
                        "RUN_CONTEXT_MISSING",
                        "问答运行上下文不存在"
                );
            }
            return input;
        } catch (IllegalArgumentException exception) {
            throw new ChatWorkflowException(
                    "RUN_CONTEXT_INVALID",
                    "问答运行上下文无效",
                    exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EvidenceItem> evidence(OverAllState state) {
        return (List<EvidenceItem>) state.value(EVIDENCE).orElse(List.of());
    }

    private static HistoryWindow history(OverAllState state) {
        return state.value(HISTORY, HistoryWindow.class)
                .orElseGet(HistoryWindow::off);
    }

    private static MemoryPack memory(OverAllState state) {
        return state.value(MEMORY, MemoryPack.class)
                .orElseGet(MemoryPack::off);
    }

    private static PromptPlan promptPlan(OverAllState state) {
        return state.value(PROMPT_PLAN, PromptPlan.class).orElseThrow();
    }

    private static List<EvidenceItem> promptEvidence(OverAllState state) {
        return promptPlan(state).evidence();
    }

    private static String refusalText(String language) {
        return "en".equals(language)
                ? "I couldn't find enough authorized evidence to answer this question."
                : "没有找到足够且有权限的证据来回答这个问题。";
    }

    private static String modelRefusalText(String language) {
        return "en".equals(language)
                ? "I found traceable candidate sources, but the model could not form a reliable answer supported by citations."
                : "找到了可追溯的候选来源，但当前模型未能形成有引用支撑的可靠答案。";
    }

    private static String unsupportedAnswerText(String language) {
        return "en".equals(language)
                ? "The generated answer did not pass citation validation, so unsupported content was withheld."
                : "生成内容未通过引用校验，系统已拒绝输出缺少证据支持的答案。";
    }

    record RunInput(
            PlatformUserPrincipal user,
            StartedRun started,
            String question,
            String language,
            SearchService.EvaluationTarget evaluationTarget,
            boolean modelTimeoutFault,
            UUID evaluationDocumentId
    ) {
        RunInput(
                PlatformUserPrincipal user,
                StartedRun started,
                String question,
                String language,
                SearchService.EvaluationTarget evaluationTarget,
                boolean modelTimeoutFault
        ) {
            this(
                    user, started, question, language,
                    evaluationTarget, modelTimeoutFault, null
            );
        }

        RunInput(
                PlatformUserPrincipal user,
                StartedRun started,
                String question,
                String language
        ) {
            this(user, started, question, language, null, false, null);
        }
    }

    private record PlannedQuery(
            QueryPlan plan,
            RoutingDecision routing,
            ProfileView profile,
            QueryExecutionPolicy policy
    ) {
    }

    record PersistedOutcome(
            RunStatus status,
            UUID runId,
            UUID messageId,
            String language,
            String content,
            String directAnswer,
            List<UUID> directAnswerCitationIds,
            List<ModelSegment> segments,
            List<Citation> citations,
            List<EvidenceItem> evidence,
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
            String routeDegradationCode,
            int memoryInjectedCount,
            int memoryUsedCount,
            int memoryTokenCount,
            List<RunMemoryUsageView> memoryUsages
    ) {
    }

    record EvidenceItem(
            UUID id,
            SearchHit hit,
            ChunkContext context,
            Map<UUID, SourceSpanView> citationSpans,
            ModelEvidence modelEvidence
    ) {
    }

    private record EvidenceReference(
            UUID id,
            EvidenceItem item,
            SourceSpanView span
    ) {
    }

    private record GraphContextPlan(
            List<GraphEvidence> evidence,
            Map<UUID, SourceSpanView> citationSpans
    ) {
    }

    private record ValidatedAnswer(
            RunStatus status,
            String content,
            List<ModelSegment> segments,
            List<EvidenceReference> usedCitations,
            List<EvidenceItem> usedEvidence,
            Set<UUID> usedMemoryIds,
            String directAnswer,
            List<UUID> directAnswerCitationIds,
            String refusalCode
    ) {
        static ValidatedAnswer refused(String content, String code) {
            return new ValidatedAnswer(
                    RunStatus.REFUSED,
                    content,
                    List.of(),
                    List.of(),
                    List.of(),
                    Set.of(),
                    null,
                    List.of(),
                    code
            );
        }
    }

    private static final class RedactedGraphListener
            implements GraphLifecycleListener {

        private static final Logger LOGGER =
                LoggerFactory.getLogger(RedactedGraphListener.class);

        private final Map<String, Long> starts = new ConcurrentHashMap<>();

        @Override
        public void before(
                String nodeId,
                Map<String, Object> state,
                RunnableConfig config,
                Long currentTime
        ) {
            starts.put(key(nodeId, state), System.nanoTime());
        }

        @Override
        public void after(
                String nodeId,
                Map<String, Object> state,
                RunnableConfig config,
                Long currentTime
        ) {
            Long started = starts.remove(key(nodeId, state));
            long tookMs = started == null
                    ? 0
                    : (System.nanoTime() - started) / 1_000_000;
            LOGGER.info(
                    "chat_graph_node runId={} node={} tookMs={} status=SUCCESS",
                    runId(state),
                    nodeId,
                    tookMs
            );
        }

        @Override
        public void onError(
                String nodeId,
                Map<String, Object> state,
                Throwable exception,
                RunnableConfig config
        ) {
            starts.remove(key(nodeId, state));
            LOGGER.warn(
                    "chat_graph_node runId={} node={} status=FAILED errorType={}",
                    runId(state),
                    nodeId,
                    exception.getClass().getSimpleName()
            );
        }

        private static String key(String nodeId, Map<String, Object> state) {
            return runId(state) + ':' + nodeId;
        }

        private static String runId(Map<String, Object> state) {
            Object value = state.get(GraphLifecycleListener.EXECUTION_ID_KEY);
            return value == null ? "unknown" : value.toString();
        }
    }
}

final class ChatWorkflowException extends RuntimeException {

    private final String code;

    ChatWorkflowException(String code, String message) {
        super(message);
        this.code = code;
    }

    ChatWorkflowException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
