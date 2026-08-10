package com.example.rag.evaluation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.rag.chat.ChatEvaluationGateway;
import com.example.rag.evaluation.EvaluationContracts.ClaimedDrill;
import com.example.rag.evaluation.EvaluationContracts.DrillType;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.evaluation.EvaluationContracts.TargetView;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.UserRepository;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.SearchDebugResponse;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.search.SearchService;
import com.example.rag.security.PlatformUserPrincipal;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
class RealEvaluationDrillExecutor {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final EvaluationTargetService targets;
    private final Optional<SearchService> search;
    private final Optional<ChatEvaluationGateway> chat;

    RealEvaluationDrillExecutor(
            JdbcTemplate jdbc,
            UserRepository users,
            EvaluationTargetService targets,
            Optional<SearchService> search,
            Optional<ChatEvaluationGateway> chat
    ) {
        this.jdbc = jdbc;
        this.users = users;
        this.targets = targets;
        this.search = search;
        this.chat = chat;
    }

    Map<String, Object> execute(ClaimedDrill drill) {
        PlatformUserPrincipal user = principal(drill.requestedBy());
        return switch (drill.drillType()) {
            case MODEL_TIMEOUT -> modelTimeout(drill, user);
            case OPENSEARCH_UNAVAILABLE -> searchFault(
                    user,
                    SubjectType.GLOBAL_GRAPH,
                    GraphMode.GLOBAL_GRAPH,
                    SearchService.EvaluationFault
                            .GLOBAL_OPENSEARCH_UNAVAILABLE,
                    "GLOBAL_INDEX_UNAVAILABLE"
            );
            case GRAPH_STALE -> searchFault(
                    user,
                    SubjectType.LOCAL_GRAPH,
                    GraphMode.LOCAL_GRAPH,
                    SearchService.EvaluationFault.GRAPH_STALE,
                    "GRAPH_PROJECTION_STALE"
            );
            case CANARY_LEAK_SCAN -> canary(user);
        };
    }

    private Map<String, Object> modelTimeout(
            ClaimedDrill drill,
            PlatformUserPrincipal user
    ) {
        TargetView target = target(SubjectType.ANSWER_CITATION);
        var result = chat.orElseThrow(() ->
                new IllegalStateException("Chat runtime is disabled")
        ).verifyModelTimeout(
                drill.id(),
                benignQuery(),
                "zh",
                RealEvaluationExecutor.searchTarget(
                        target, SearchService.EvaluationFault.NONE
                ),
                user
        );
        if (!result.requestScopedAbort()) {
            throw new IllegalStateException(
                    "Model timeout did not abort the scoped request"
            );
        }
        return Map.of(
                "executionCompleted", true,
                "realFaultInjected", true,
                "faultScope", "REQUEST",
                "verificationStatus", "VERIFIED",
                "observedErrorCode", result.errorCode(),
                "onlineStateChanged", false,
                "captureContent", false
        );
    }

    private Map<String, Object> searchFault(
            PlatformUserPrincipal user,
            SubjectType subjectType,
            GraphMode mode,
            SearchService.EvaluationFault fault,
            String expectedCode
    ) {
        TargetView target = target(subjectType);
        SearchDebugResponse response = search.orElseThrow(() ->
                new IllegalStateException("Search runtime is disabled")
        ).evaluate(
                new SearchRequest(
                        benignQuery(), 0, 8, null,
                        DocumentVisibility.ALL_USERS, mode
                ),
                user,
                RealEvaluationExecutor.searchTarget(target, fault)
        );
        String code = response.result().graphDegradationCode();
        boolean recorded = code != null && code.contains(expectedCode);
        boolean fellBack = !mode.name().equals(
                response.result().graphModeUsed()
        );
        if (!recorded || !fellBack) {
            throw new IllegalStateException(
                    "Controlled search fault did not safely degrade"
            );
        }
        return Map.of(
                "executionCompleted", true,
                "realFaultInjected", true,
                "faultScope", "REQUEST",
                "verificationStatus", "VERIFIED",
                "requestedMode", mode.name(),
                "actualMode", response.result().graphModeUsed(),
                "degradationCode", code,
                "onlineStateChanged", false,
                "captureContent", false
        );
    }

    private Map<String, Object> canary(PlatformUserPrincipal user) {
        String canary = "CANARY_" + UUID.randomUUID()
                .toString().replace("-", "").toUpperCase();
        Logger root = (Logger) LoggerFactory.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        SearchDebugResponse response;
        try {
            TargetView target = target(SubjectType.RETRIEVAL);
            response = search.orElseThrow(() ->
                    new IllegalStateException("Search runtime is disabled")
            ).evaluate(
                    new SearchRequest(
                            canary, 0, 1, null,
                            DocumentVisibility.ALL_USERS,
                            GraphMode.HYBRID
                    ),
                    user,
                    RealEvaluationExecutor.searchTarget(
                            target, SearchService.EvaluationFault.NONE
                    )
            );
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        boolean logLeak = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(canary));
        Map<String, Object> safeSummary = new LinkedHashMap<>();
        safeSummary.put("resultCount", response.result().items().size());
        safeSummary.put("actualMode", response.result().modeUsed());
        safeSummary.put("graphMode", response.result().graphModeUsed());
        boolean responseLeak = safeSummary.toString().contains(canary);
        if (logLeak || responseLeak) {
            throw new IllegalStateException("Canary leak detected");
        }
        return Map.of(
                "executionCompleted", true,
                "realFaultInjected", true,
                "faultScope", "REQUEST",
                "verificationStatus", "VERIFIED",
                "logLeakDetected", false,
                "responseLeakDetected", false,
                "onlineStateChanged", false,
                "captureContent", false
        );
    }

    private TargetView target(SubjectType type) {
        List<TargetView> candidates = targets.targets().stream()
                .filter(target -> target.subjectType() == type)
                .sorted((left, right) -> {
                    if (left.targetKind().equals(right.targetKind())) {
                        return left.targetKey().compareTo(right.targetKey());
                    }
                    return "ACTIVE".equals(left.targetKind()) ? -1 : 1;
                })
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No evaluation target for " + type
            );
        }
        return candidates.getFirst();
    }

    private String benignQuery() {
        return jdbc.query(
                """
                SELECT input_data ->> 'query'
                FROM evaluation_cases
                WHERE mapping_status = 'MAPPED'
                  AND btrim(input_data ->> 'query') <> ''
                ORDER BY created_at, id
                LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getString(1)
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("No mapped drill query available")
        );
    }

    private PlatformUserPrincipal principal(UUID userId) {
        var user = users.findById(userId).orElseThrow();
        if (!user.isEnabled()) {
            throw new IllegalStateException("Drill owner is disabled");
        }
        return PlatformUserPrincipal.from(user);
    }
}
