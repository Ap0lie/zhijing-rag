package com.example.rag.chat;

import com.example.rag.chat.ChatApiContracts.AnswerDeltaEvent;
import com.example.rag.chat.ChatApiContracts.CitationDetail;
import com.example.rag.chat.ChatApiContracts.CitationEvent;
import com.example.rag.chat.ChatApiContracts.CitationSummary;
import com.example.rag.chat.ChatApiContracts.CompletedEvent;
import com.example.rag.chat.ChatApiContracts.FailedEvent;
import com.example.rag.chat.ChatApiContracts.MessageView;
import com.example.rag.chat.ChatApiContracts.MemoryUsedEvent;
import com.example.rag.chat.ChatApiContracts.MemoryUsedSummary;
import com.example.rag.chat.ChatApiContracts.MemorySuggestionStatusItem;
import com.example.rag.chat.ChatApiContracts.MemorySuggestionStatusResponse;
import com.example.rag.chat.ChatApiContracts.RunView;
import com.example.rag.chat.ChatApiContracts.SessionDetailResponse;
import com.example.rag.chat.ChatApiContracts.SessionListResponse;
import com.example.rag.chat.ChatApiContracts.SessionSummary;
import com.example.rag.chat.ChatApiContracts.SourceSpanDetail;
import com.example.rag.chat.ChatPersistenceContracts.ChatMessage;
import com.example.rag.chat.ChatPersistenceContracts.ChatRun;
import com.example.rag.chat.ChatPersistenceContracts.ChatSession;
import com.example.rag.chat.ChatPersistenceContracts.Citation;
import com.example.rag.chat.ChatPersistenceContracts.AnswerStrategy;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.ChatPersistenceContracts.SessionDetail;
import com.example.rag.chat.ChatPersistenceContracts.StartRunCommand;
import com.example.rag.chat.ChatPersistenceContracts.StartedRun;
import com.example.rag.chat.ChatWorkflow.EvidenceItem;
import com.example.rag.chat.ChatWorkflow.PersistedOutcome;
import com.example.rag.chat.ChatWorkflow.RunInput;
import com.example.rag.common.ApiException;
import com.example.rag.memory.MemoryPackService;
import com.example.rag.memory.MemoryPackService.RunMemoryUsageView;
import com.example.rag.memory.MemorySuggestionService;
import com.example.rag.memory.MemorySuggestionService.SuggestionState;
import com.example.rag.search.ChunkContextService;
import com.example.rag.search.SearchContracts.ChunkContext;
import com.example.rag.search.SearchContracts.SourceSpanView;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.QuerySlot;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ChatService.class);

    private final ChatPersistenceRepository repository;
    private final ChatWorkflow workflow;
    private final ChunkContextService chunks;
    private final ChatUserGuard userGuard;
    private final ChatProperties properties;
    private final QueryIntelligenceProfileService queryProfiles;
    private final MemoryPackService memories;
    private final MemorySuggestionService suggestions;
    private final AnswerSourceService answerSources;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Map<UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    ChatService(
            ChatPersistenceRepository repository,
            ChatWorkflow workflow,
            ChunkContextService chunks,
            ChatUserGuard userGuard,
            ChatProperties properties,
            QueryIntelligenceProfileService queryProfiles,
            MemoryPackService memories,
            MemorySuggestionService suggestions,
            AnswerSourceService answerSources,
            ObjectMapper objectMapper,
            @Qualifier("chatExecutor") ExecutorService executor
    ) {
        this.repository = repository;
        this.workflow = workflow;
        this.chunks = chunks;
        this.userGuard = userGuard;
        this.properties = properties;
        this.queryProfiles = queryProfiles;
        this.memories = memories;
        this.suggestions = suggestions;
        this.answerSources = answerSources;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    SessionListResponse listSessions(PlatformUserPrincipal user) {
        return new SessionListResponse(
                repository.listSessions(user.id(), 100, 0).stream()
                        .map(ChatService::summary)
                        .toList()
        );
    }

    SessionSummary createSession(String requestedTitle, PlatformUserPrincipal user) {
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? "新对话"
                : requestedTitle.trim();
        return summary(repository.createSession(user.id(), title));
    }

    SessionDetailResponse session(UUID sessionId, PlatformUserPrincipal user) {
        SessionDetail detail = repository.findSessionDetail(user.id(), sessionId)
                .orElseThrow(ChatService::notFound);
        Map<UUID, ChatRun> byResponse = new LinkedHashMap<>();
        detail.runs().forEach(run -> byResponse.put(run.responseMessageId(), run));
        Map<UUID, SuggestionState> suggestionStates =
                suggestions.statesForSession(user.id(), sessionId);
        Map<UUID, AnswerSourceService.RunSources> sourcesByRun =
                answerSources.load(
                        user,
                        detail.runs().stream()
                                .filter(run -> run.status()
                                        == RunStatus.COMPLETED)
                                .map(ChatRun::id)
                                .toList()
                );
        List<MessageView> messages = detail.messages().stream()
                .map(message -> messageView(
                        message,
                        byResponse.get(message.id()),
                        suggestionStates.get(message.id()),
                        sourcesByRun
                ))
                .toList();
        ChatSession session = detail.session();
        return new SessionDetailResponse(
                session.id(),
                session.title(),
                session.status().name(),
                session.createdAt(),
                session.updatedAt(),
                messages,
                detail.runs().stream().map(this::runView).toList()
        );
    }

    SessionSummary renameSession(
            UUID sessionId,
            String title,
            PlatformUserPrincipal user
    ) {
        if (!repository.updateSessionTitle(user.id(), sessionId, title)) {
            throw notFound();
        }
        return summary(repository.findSession(user.id(), sessionId)
                .orElseThrow(ChatService::notFound));
    }

    MemorySuggestionStatusResponse memorySuggestions(
            UUID sessionId,
            PlatformUserPrincipal user
    ) {
        if (repository.findSession(user.id(), sessionId).isEmpty()) {
            throw notFound();
        }
        List<MemorySuggestionStatusItem> items =
                suggestions.statesForSession(user.id(), sessionId)
                        .entrySet()
                        .stream()
                        .map(entry -> new MemorySuggestionStatusItem(
                                entry.getKey(),
                                entry.getValue().status(),
                                entry.getValue().suggestionCount(),
                                entry.getValue().errorCode()
                        ))
                        .sorted(java.util.Comparator.comparing(
                                MemorySuggestionStatusItem::messageId
                        ))
                        .toList();
        boolean pending = items.stream().anyMatch(item ->
                "PENDING".equals(item.status())
                        || "RUNNING".equals(item.status()));
        return new MemorySuggestionStatusResponse(items, pending);
    }

    void deleteSession(UUID sessionId, PlatformUserPrincipal user) {
        if (repository.deleteSession(user.id(), sessionId)) {
            return;
        }
        if (repository.findSession(user.id(), sessionId).isEmpty()) {
            throw notFound();
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "CHAT_RUN_ACTIVE",
                "请先停止当前回答"
        );
    }

    OpenChatRun start(
            UUID sessionId,
            String question,
            GraphMode graphModeRequested,
            AnswerStrategy answerStrategyRequested,
            PlatformUserPrincipal user
    ) {
        GraphMode graphMode = graphModeRequested == null
                ? GraphMode.HYBRID
                : graphModeRequested;
        AnswerStrategy answerStrategy = answerStrategyRequested == null
                ? AnswerStrategy.STANDARD
                : answerStrategyRequested;
        if (answerStrategy == AnswerStrategy.DEEP_GLOBAL
                && graphMode != GraphMode.GLOBAL_GRAPH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DEEP_GLOBAL_REQUIRES_GLOBAL_GRAPH",
                    "深度全局分析只能与 GLOBAL_GRAPH 模式一起使用"
            );
        }
        return startNewRun(
                sessionId,
                question,
                graphMode,
                answerStrategy,
                user
        );
    }

    OpenChatRun start(
            UUID sessionId,
            String question,
            GraphMode graphModeRequested,
            PlatformUserPrincipal user
    ) {
        return start(
                sessionId, question, graphModeRequested,
                AnswerStrategy.STANDARD, user
        );
    }

    OpenChatRun retry(UUID sourceRunId, PlatformUserPrincipal user) {
        ChatRun source = repository.findRun(user.id(), sourceRunId)
                .orElseThrow(ChatService::notFound);
        if (source.status() != RunStatus.FAILED
                && source.status() != RunStatus.CANCELLED) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CHAT_RUN_NOT_RETRYABLE",
                    "只有失败或已取消的回答可以重试"
            );
        }
        return startNewRun(
                source.sessionId(),
                source.standaloneQuery(),
                graphMode(source.graphModeRequested()),
                answerStrategy(source.answerStrategyRequested()),
                user
        );
    }

    void cancel(UUID runId, PlatformUserPrincipal user) {
        ChatRun run = repository.findRun(user.id(), runId)
                .orElseThrow(ChatService::notFound);
        ActiveRun active = activeRuns.get(runId);
        if (active != null) {
            cancel(active, "USER_CANCELLED", "回答已由你停止。", true);
            return;
        }
        if (run.status() == RunStatus.RUNNING) {
            repository.failRun(
                    user.id(),
                    runId,
                    RunStatus.CANCELLED,
                    "USER_CANCELLED",
                    "Cancelled without an active local execution"
            );
        }
    }

    CitationDetail citation(UUID citationId, PlatformUserPrincipal user) {
        Citation citation = repository.findCitation(user.id(), citationId)
                .orElseThrow(ChatService::notFound);
        ChunkContext context = authorizedContext(citation, user);
        SourceSpanView span = context.sourceSpans().stream()
                .filter(item -> item.id().equals(citation.sourceSpanId()))
                .findFirst()
                .orElseThrow(ChatService::notFound);
        CitationSummary summary = citationSummary(citation, context);
        return new CitationDetail(
                summary.id(),
                summary.documentId(),
                summary.documentTitle(),
                summary.revisionId(),
                summary.revisionNumber(),
                summary.chunkId(),
                summary.startPage(),
                summary.endPage(),
                summary.label(),
                context.child().text(),
                context.child().headingPath(),
                new SourceSpanDetail(
                        span.id(),
                        span.order(),
                        span.startPage(),
                        span.endPage(),
                        span.startOffset(),
                        span.endOffset(),
                        span.sourceTextHash(),
                        span.documentFormat(),
                        span.sourceLocator(),
                        span.sourceLabel()
                ),
                context.parent() == null ? null : context.parent().text(),
                summary.documentFormat(),
                summary.sourceLocator(),
                summary.sourceLabel()
        );
    }

    List<RunMemoryUsageView> memories(
            UUID runId,
            PlatformUserPrincipal user
    ) {
        return memories.runUsages(user, runId);
    }

    private OpenChatRun startNewRun(
            UUID sessionId,
            String question,
            GraphMode graphModeRequested,
            AnswerStrategy answerStrategyRequested,
            PlatformUserPrincipal user
    ) {
        userGuard.requireCurrent(user);
        String normalizedQuestion = question == null ? "" : question.trim();
        String language = language(normalizedQuestion);
        var queryProfile = queryProfiles.active();
        if (queryProfile != null
                && (!queryProfile.enabled()
                || !queryProfiles.matchesRuntime(queryProfile))) {
            queryProfile = null;
        }
        StartedRun started = repository.startRun(
                        user.id(),
                        sessionId,
                        new StartRunCommand(
                                normalizedQuestion,
                                language,
                                ChatWorkflow.ORCHESTRATION_VERSION,
                                UUID.randomUUID().toString(),
                                graphModeRequested.name(),
                                answerStrategyRequested.name(),
                                queryProfile == null
                                        ? null
                                        : queryProfile.version()
                        ),
                        suggestions.runtimeSnapshot()
                )
                .orElseThrow(ChatService::notFound);
        SseEmitter emitter = new SseEmitter(properties.getSseTimeout().toMillis());
        ActiveRun active = new ActiveRun(user, started, emitter);
        activeRuns.put(started.run().id(), active);
        emitter.onTimeout(() -> cancel(
                active,
                "RUN_TIMEOUT",
                "回答超时，请重试。",
                true
        ));
        emitter.onError(exception -> cancel(
                active,
                "STREAM_DISCONNECTED",
                "回答连接已断开。",
                false
        ));
        emitter.onCompletion(() -> {
            if (!active.terminal.get()) {
                cancel(
                        active,
                        "STREAM_DISCONNECTED",
                        "回答连接已断开。",
                        false
                );
            }
        });
        try {
            open(emitter, started);
        } catch (StreamWriteException exception) {
            cancel(
                    active,
                    "STREAM_DISCONNECTED",
                    "回答连接无法建立。",
                    false
            );
            emitter.completeWithError(exception);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CHAT_STREAM_OPEN_FAILED",
                    "回答连接无法建立"
            );
        }
        Future<?> future = executor.submit(() -> execute(active, normalizedQuestion, language));
        active.setFuture(future);
        return new OpenChatRun(
                started.run().id(),
                started.responseMessage().id(),
                emitter
        );
    }

    private void execute(ActiveRun active, String question, String language) {
        try {
            PersistedOutcome outcome = workflow.execute(
                new RunInput(active.user, active.started, question, language)
            );
            synchronized (active) {
                if (active.terminal.get()) {
                    return;
                }
                emitOutcome(active, outcome);
                try {
                    suggestions.enqueue(
                            active.user.id(),
                            active.started.run().id()
                    );
                } catch (RuntimeException exception) {
                    LOGGER.warn(
                            "Memory suggestion enqueue failed for Run {}",
                            active.started.run().id(),
                            exception
                    );
                }
                active.terminal.set(true);
                active.emitter.complete();
            }
        } catch (Throwable throwable) {
            fail(active, throwable);
        } finally {
            activeRuns.remove(active.started.run().id(), active);
        }
    }

    private void emitOutcome(ActiveRun active, PersistedOutcome outcome) {
        userGuard.requireCurrent(active.user);
        Map<UUID, ChunkContext> currentContexts = new LinkedHashMap<>();
        Map<UUID, EvidenceItem> evidence = new LinkedHashMap<>();
        outcome.evidence().forEach(item -> item.citationSpans().keySet()
                .forEach(id -> evidence.put(id, item)));
        if (outcome.status() == RunStatus.COMPLETED) {
            Set<UUID> usedMemoryIds = outcome.memoryUsages().stream()
                    .filter(usage -> "USED".equals(usage.usageStatus()))
                    .filter(RunMemoryUsageView::available)
                    .map(RunMemoryUsageView::memoryId)
                    .collect(java.util.stream.Collectors.toSet());
            Map<UUID, Integer> citationNumbers = new LinkedHashMap<>();
            outcome.citations().forEach(citation ->
                    citationNumbers.put(citation.id(), citation.order() + 1));
            if (outcome.citations().isEmpty() && usedMemoryIds.isEmpty()
                    || outcome.segments().stream()
                    .flatMap(segment -> segment.citationIds().stream())
                    .anyMatch(id -> outcome.citations().stream()
                             .noneMatch(citation -> citation.id().equals(id)))
                    || outcome.segments().stream()
                    .flatMap(segment -> segment.memoryIds().stream())
                    .anyMatch(id -> !usedMemoryIds.contains(id))
                    || outcome.segments().stream().anyMatch(segment ->
                            !segment.text().endsWith(ChatWorkflow.citationMarkers(
                                    segment.citationIds(),
                                    citationNumbers
                            )))) {
                throw new ChatWorkflowException(
                        "CITATION_MAPPING_FAILED",
                        "引用映射不完整"
                );
            }
            if (!usedMemoryIds.isEmpty()
                    && !memories.allUsedCurrent(active.user, outcome.runId())) {
                throw new ChatWorkflowException(
                        "MEMORY_REVOKED",
                        "本轮使用的记忆已经失效"
                );
            }
            try {
                for (Citation citation : outcome.citations()) {
                    if (!evidence.containsKey(citation.id())) {
                        throw new ChatWorkflowException(
                                "CITATION_MAPPING_FAILED",
                                "引用映射不完整"
                        );
                    }
                    currentContexts.put(
                            citation.id(),
                            authorizedContext(citation, active.user)
                    );
                }
            } catch (ApiException exception) {
                if (exception.getStatus() == HttpStatus.NOT_FOUND) {
                    throw new ChatWorkflowException(
                            "EVIDENCE_REVOKED",
                            "证据权限或文档版本已变化"
                    );
                }
                throw exception;
            }
        }
        List<MemoryUsedSummary> usedMemories = outcome.memoryUsages().stream()
                .filter(usage -> "USED".equals(usage.usageStatus())
                        || "DOCUMENT_EVIDENCE".equals(
                        usage.usageStatus()))
                .map(usage -> new MemoryUsedSummary(
                        usage.memoryId(),
                        usage.memoryType(),
                        usage.usageStatus()
                ))
                .toList();
        if (!usedMemories.isEmpty()) {
            send(
                    active,
                    "memory_used",
                    new MemoryUsedEvent(outcome.runId(), usedMemories)
            );
        }
        for (String delta : answerDeltas(outcome)) {
            send(
                    active,
                    "answer_delta",
                    new AnswerDeltaEvent(
                            outcome.runId(),
                            outcome.messageId(),
                            delta
                    )
            );
        }
        if (outcome.status() == RunStatus.COMPLETED) {
            for (Citation citation : outcome.citations()) {
                send(
                        active,
                        "citation",
                        new CitationEvent(
                                outcome.runId(),
                                citationSummary(
                                        citation,
                                        currentContexts.get(citation.id())
                                )
                        )
                );
            }
        }
        send(
                active,
                "completed",
                new CompletedEvent(
                        outcome.runId(),
                        outcome.status().name(),
                        outcome.messageId(),
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
                        outcome.historyTrimReasons(),
                        outcome.standaloneQuery(),
                        outcome.querySlots(),
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
                        outcome.routeDegradationCode()
                )
        );
    }

    static List<String> answerDeltas(PersistedOutcome outcome) {
        if (outcome.status() == RunStatus.REFUSED) {
            return List.of(outcome.content());
        }
        List<String> deltas = new ArrayList<>();
        for (int index = 0; index < outcome.segments().size(); index++) {
            String prefix = index > 0 && "en".equals(outcome.language()) ? " " : "";
            deltas.add(prefix + outcome.segments().get(index).text());
        }
        if (!String.join("", deltas).equals(outcome.content())) {
            throw new ChatWorkflowException(
                    "CITATION_MAPPING_FAILED",
                    "流式回答与保存内容不一致"
            );
        }
        return List.copyOf(deltas);
    }

    private MessageView messageView(
            ChatMessage message,
            ChatRun run,
            SuggestionState suggestion,
            Map<UUID, AnswerSourceService.RunSources> sourcesByRun
    ) {
        if (run == null || run.status() != RunStatus.COMPLETED) {
            return new MessageView(
                    message.id(),
                    message.role().name(),
                    message.status().name(),
                    message.content(),
                    message.language(),
                    run == null ? null : run.id(),
                    false,
                    message.createdAt(),
                    List.of(),
                    suggestion == null ? null : suggestion.status(),
                    suggestion == null ? 0 : suggestion.suggestionCount(),
                    suggestion == null ? null : suggestion.errorCode()
            );
        }
        AnswerSourceService.RunSources sources = sourcesByRun.getOrDefault(
                run.id(),
                AnswerSourceService.RunSources.invalid()
        );
        boolean hidden = !sources.current();
        return new MessageView(
                message.id(),
                message.role().name(),
                message.status().name(),
                hidden ? "" : message.content(),
                message.language(),
                run.id(),
                hidden,
                message.createdAt(),
                hidden ? List.of() : sources.citations(),
                null,
                0,
                null
        );
    }

    private ChunkContext authorizedContext(
            Citation citation,
            PlatformUserPrincipal user
    ) {
        ChunkContext context = chunks.get(citation.childChunkId(), user);
        boolean sourceMatches = context.sourceSpans().stream()
                .anyMatch(span -> span.id().equals(citation.sourceSpanId())
                        && Objects.equals(
                        span.sourceTextHash(), citation.sourceTextHash()
                ));
        if (!context.documentId().equals(citation.documentId())
                || !context.revisionId().equals(citation.revisionId())
                || !sourceMatches) {
            throw notFound();
        }
        return context;
    }

    private static SessionSummary summary(ChatSession session) {
        return new SessionSummary(
                session.id(),
                session.title(),
                session.status().name(),
                session.createdAt(),
                session.updatedAt()
        );
    }

    private RunView runView(ChatRun run) {
        JsonNode budget = jsonObject(run.budgetUsageJson());
        return new RunView(
                run.id(),
                run.status().name(),
                run.errorCode(),
                run.graphProfileVersion(),
                run.graphGeneration(),
                run.graphModeRequested(),
                run.graphModeUsed(),
                run.graphDegraded(),
                run.graphDegradationCode(),
                run.globalConfigVersion(),
                run.globalGeneration(),
                run.answerStrategyRequested(),
                run.answerStrategyUsed(),
                run.mapCallCount(),
                run.reduceCallCount(),
                run.queryIntelligenceProfileVersion(),
                uuidList(run.historyMessageIdsJson()),
                run.historyCounterVersion(),
                run.historyTokenCount(),
                stringList(run.historyTrimReasonsJson()),
                budget.path("memoryUsedCount").asInt(0),
                budget.path("memoryTokenCount").asInt(0),
                nullableText(budget.path("memoryDegradationCode")),
                run.standaloneQuery(),
                querySlots(run.subQueriesJson()),
                budget.path("plannerCallCount").asInt(0),
                budget.path("retrievalCallCount").asInt(0),
                budget.path("rerankCallCount").asInt(0),
                budget.path("coverageSufficient").asBoolean(false),
                budget.path("queryDegraded").asBoolean(false),
                nullableText(
                        budget.path("queryDegradationCode")
                ),
                nullableInt(budget, "retrievedCandidateCount"),
                nullableInt(budget, "authorizedCandidateCount"),
                nullableInt(budget, "rerankedCandidateCount"),
                nullableInt(budget, "evidenceCandidateCount"),
                nullableInt(budget, "validatedEvidenceCount"),
                nullableText(budget.path("routeSelectedMode")),
                budget.path("routerCallCount").asInt(0),
                nullableText(budget.path("routeReasonCode")),
                budget.path("routeDegraded").asBoolean(false),
                nullableText(budget.path("routeDegradationCode")),
                run.createdAt(),
                run.completedAt()
        );
    }

    private List<QuerySlot> querySlots(String value) {
        return readList(value, new TypeReference<>() {
        });
    }

    private JsonNode jsonObject(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isObject()
                    ? node
                    : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored ChatRun budget JSON is invalid",
                    exception
            );
        }
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isMissingNode()
                || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static Integer nullableInt(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isIntegralNumber() ? value.asInt() : null;
    }

    private List<UUID> uuidList(String value) {
        return readList(value, new TypeReference<>() {
        });
    }

    private List<String> stringList(String value) {
        return readList(value, new TypeReference<>() {
        });
    }

    private <T> List<T> readList(
            String value,
            TypeReference<List<T>> type
    ) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(value, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored ChatRun JSON is invalid", exception
            );
        }
    }

    private static GraphMode graphMode(String value) {
        if (value == null || value.isBlank()) {
            return GraphMode.HYBRID;
        }
        return GraphMode.valueOf(value);
    }

    private static AnswerStrategy answerStrategy(String value) {
        if (value == null || value.isBlank()) {
            return AnswerStrategy.STANDARD;
        }
        return AnswerStrategy.valueOf(value);
    }

    static CitationSummary citationSummary(
            Citation citation,
            ChunkContext context
    ) {
        Integer startPage = citation.startPage();
        Integer endPage = citation.endPage();
        SourceSpanView span = context.sourceSpans().stream()
                .filter(item -> item.id().equals(
                        citation.sourceSpanId()
                ))
                .findFirst()
                .orElse(null);
        var locator = span == null
                ? context.child().sourceLocator()
                : span.sourceLocator();
        String sourceLabel = locator == null
                ? context.child().sourceLabel()
                : locator.sourceLabel();
        if (sourceLabel == null || sourceLabel.isBlank()) {
            sourceLabel = "来源位置不可用";
        }
        return new CitationSummary(
                citation.id(),
                context.documentId(),
                context.documentTitle(),
                context.revisionId(),
                context.revisionNumber(),
                context.child().id(),
                startPage,
                endPage,
                "[" + (citation.order() + 1) + "] "
                        + context.documentTitle() + " · " + sourceLabel,
                context.documentFormat(),
                locator,
                sourceLabel
        );
    }

    private void cancel(
            ActiveRun active,
            String code,
            String message,
            boolean notifyClient
    ) {
        synchronized (active) {
            if (active.terminal.get()) {
                return;
            }
            boolean changed = transitionToFailure(
                    active,
                    RunStatus.CANCELLED,
                    code,
                    message
            );
            active.terminal.set(true);
            active.cancelFuture();
            if (changed && notifyClient) {
                trySend(
                        active.emitter,
                        "failed",
                        new FailedEvent(
                                active.started.run().id(),
                                RunStatus.CANCELLED.name(),
                                code,
                                message
                        )
                );
                active.emitter.complete();
            }
        }
        activeRuns.remove(active.started.run().id(), active);
    }

    private void fail(ActiveRun active, Throwable throwable) {
        synchronized (active) {
            if (active.terminal.get()) {
                return;
            }
            Failure failure = failure(throwable);
            boolean changed = transitionToFailure(
                    active,
                    failure.status(),
                    failure.code(),
                    failure.internalDetail()
            );
            active.terminal.set(true);
            if (changed) {
                trySend(
                        active.emitter,
                        "failed",
                        new FailedEvent(
                                active.started.run().id(),
                                failure.status().name(),
                                failure.code(),
                                failure.message()
                        )
                );
                active.emitter.complete();
            }
        }
    }

    private boolean transitionToFailure(
            ActiveRun active,
            RunStatus status,
            String code,
            String detail
    ) {
        boolean changed = repository.failRun(
                active.user.id(),
                active.started.run().id(),
                status,
                code,
                detail
        );
        return changed || repository.abandonFinishedRun(
                active.user.id(),
                active.started.run().id(),
                status,
                code,
                detail
        );
    }

    private static void send(SseEmitter emitter, String name, Object value) {
        try {
            emitter.send(SseEmitter.event().name(name).data(value));
        } catch (IOException | IllegalStateException exception) {
            throw new StreamWriteException(exception);
        }
    }

    private static void open(SseEmitter emitter, StartedRun started) {
        send(
                emitter,
                "answer_delta",
                new AnswerDeltaEvent(
                        started.run().id(),
                        started.responseMessage().id(),
                        ""
                )
        );
    }

    private static void send(ActiveRun active, String name, Object value) {
        if (active.terminal.get()) {
            throw new StreamWriteException(
                    new IllegalStateException("chat run is no longer active")
            );
        }
        send(active.emitter, name, value);
        if (active.terminal.get()) {
            throw new StreamWriteException(
                    new IllegalStateException("chat run stopped while streaming")
            );
        }
    }

    private static void trySend(SseEmitter emitter, String name, Object value) {
        try {
            send(emitter, name, value);
        } catch (StreamWriteException ignored) {
            // The database terminal state is authoritative after a disconnect.
        }
    }

    private static String language(String question) {
        boolean containsLatinLetter = question.codePoints()
                .anyMatch(value -> value < 128 && Character.isLetter(value));
        boolean containsNonAsciiLetter = question.codePoints()
                .anyMatch(value -> value >= 128 && Character.isLetter(value));
        return containsLatinLetter && !containsNonAsciiLetter ? "en" : "zh";
    }

    private static Failure failure(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof StreamWriteException) {
            return new Failure(
                    RunStatus.CANCELLED,
                    "STREAM_DISCONNECTED",
                    "回答连接已断开。",
                    "SSE client disconnected"
            );
        }
        if (cause instanceof ChatModelException exception) {
            return new Failure(
                    RunStatus.FAILED,
                    exception.code(),
                    exception.getMessage(),
                    exception.toString()
            );
        }
        if (cause instanceof ChatWorkflowException exception) {
            return new Failure(
                    RunStatus.FAILED,
                    exception.code(),
                    exception.getMessage(),
                    exception.toString()
            );
        }
        if (cause instanceof ApiException exception) {
            return new Failure(
                    RunStatus.FAILED,
                    exception.getCode(),
                    exception.getMessage(),
                    exception.getClass().getSimpleName()
            );
        }
        return new Failure(
                RunStatus.FAILED,
                "CHAT_RUN_FAILED",
                "回答生成失败，请重试。",
                cause.getClass().getName()
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof RuntimeException
                && current.getClass().getName().contains("Graph"))) {
            current = current.getCause();
        }
        return current;
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "CHAT_RESOURCE_NOT_FOUND",
                "会话、回答或引用不存在"
        );
    }

    record OpenChatRun(UUID runId, UUID messageId, SseEmitter emitter) {
    }

    private record Failure(
            RunStatus status,
            String code,
            String message,
            String internalDetail
    ) {
    }

    private static final class ActiveRun {

        private final PlatformUserPrincipal user;
        private final StartedRun started;
        private final SseEmitter emitter;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Future<?> future;

        private ActiveRun(
                PlatformUserPrincipal user,
                StartedRun started,
                SseEmitter emitter
        ) {
            this.user = user;
            this.started = started;
            this.emitter = emitter;
        }

        private void setFuture(Future<?> value) {
            future = value;
            if (terminal.get()) {
                value.cancel(true);
            }
        }

        private void cancelFuture() {
            Future<?> value = future;
            if (value != null) {
                value.cancel(true);
            }
        }
    }
}

final class StreamWriteException extends RuntimeException {

    StreamWriteException(Throwable cause) {
        super(cause);
    }
}
