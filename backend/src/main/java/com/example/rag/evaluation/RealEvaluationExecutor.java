package com.example.rag.evaluation;

import com.example.rag.chat.ChatEvaluationGateway;
import com.example.rag.chat.ChatEvaluationGateway.EvaluationAnswer;
import com.example.rag.chat.ChatPersistenceContracts.RunStatus;
import com.example.rag.chat.QueryIntelligenceProfileService;
import com.example.rag.chat.QueryRoutingService;
import com.example.rag.evaluation.EvaluationContracts.CaseEvaluation;
import com.example.rag.evaluation.EvaluationContracts.CaseWork;
import com.example.rag.evaluation.EvaluationContracts.ClaimedRun;
import com.example.rag.evaluation.EvaluationContracts.MetricResult;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.evaluation.EvaluationContracts.TargetView;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.UserRepository;
import com.example.rag.search.SearchContracts.DebugCandidate;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.SearchDebugResponse;
import com.example.rag.search.SearchContracts.SearchHit;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.search.SearchService;
import com.example.rag.security.PlatformUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class RealEvaluationExecutor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RealEvaluationExecutor.class);

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final EvaluationTargetService targets;
    private final Optional<SearchService> search;
    private final Optional<ChatEvaluationGateway> chat;
    private final Optional<QueryRoutingService> routing;
    private final QueryIntelligenceProfileService queryProfiles;
    private final MultiformatSecurityProbe securityProbe;

    RealEvaluationExecutor(
            JdbcTemplate jdbc,
            UserRepository users,
            EvaluationTargetService targets,
            Optional<SearchService> search,
            Optional<ChatEvaluationGateway> chat,
            Optional<QueryRoutingService> routing,
            QueryIntelligenceProfileService queryProfiles,
            MultiformatSecurityProbe securityProbe
    ) {
        this.jdbc = jdbc;
        this.users = users;
        this.targets = targets;
        this.search = search;
        this.chat = chat;
        this.routing = routing;
        this.queryProfiles = queryProfiles;
        this.securityProbe = securityProbe;
    }

    CaseEvaluation evaluate(ClaimedRun run, CaseWork work) {
        EvaluationContext context;
        try {
            context = context(run);
        } catch (RuntimeException exception) {
            return failed(work, "EVALUATION_TARGET_INVALID");
        }
        SubjectType subjectType = SubjectType.valueOf(run.subjectType());
        if (subjectType == SubjectType.MULTIFORMAT_RELEASE) {
            try {
                if ("MULTIFORMAT_SECURITY".equals(work.caseType())) {
                    return multiformatSecurity(work);
                }
                return multiformat(run, work, context);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Multiformat evaluation failed for Run {} Case {}",
                        run.id(), work.id(), exception
                );
                return failed(work, "MULTIFORMAT_EVALUATION_FAILED");
            }
        }
        if (subjectType == SubjectType.PARSER) {
            return blocked(
                    work,
                    "PARSER_SAMPLE_NOT_MAPPED",
                    List.of()
            );
        }
        String query = text(work.input().get("query"));
        if (subjectType == SubjectType.INTENT) {
            return intent(work, context, query);
        }
        Map<String, EvidenceRevision> evidence = evidence(
                work.requiredEvidenceKeys()
        );
        List<String> missing = work.requiredEvidenceKeys().stream()
                .filter(key -> !evidence.containsKey(key))
                .toList();
        List<String> turns = strings(work.input().get("turns"));
        if (subjectType == SubjectType.MULTI_TURN) {
            query = turns.isEmpty() ? "" : turns.getLast();
        }
        if (query.isBlank() || work.requiredEvidenceKeys().isEmpty()
                || !missing.isEmpty()) {
            return blocked(
                    work,
                    query.isBlank()
                            ? "CASE_QUERY_UNAVAILABLE"
                            : "EVIDENCE_MAPPING_UNAVAILABLE",
                    missing
            );
        }
        try {
            PlatformUserPrincipal user = principal(context.createdBy());
            SearchService.EvaluationTarget searchTarget =
                    searchTarget(
                            context.target(),
                            SearchService.EvaluationFault.NONE
                    );
            GraphMode graphMode = graphMode(run.subjectType());
            if (subjectType == SubjectType.MULTI_TURN) {
                return multiTurn(
                        run, work, turns, evidence, searchTarget,
                        user, context.target()
                );
            }
            if (SubjectType.ANSWER_CITATION.name().equals(
                    run.subjectType()
            )) {
                return answer(
                        run, work, query, evidence, graphMode,
                        searchTarget, user, context.target()
                );
            }
            return retrieval(
                    work, query, evidence, graphMode,
                    searchTarget, user, context.target()
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Real evaluation failed for Run {} Case {}",
                    run.id(), work.id(), exception
            );
            return failed(work, "REAL_EVALUATION_EXECUTION_FAILED");
        }
    }

    private CaseEvaluation multiformat(
            ClaimedRun run,
            CaseWork work,
            EvaluationContext context
    ) {
        MultiformatFact fact = multiformatFact(
                run.datasetVersionId(), work.id()
        );
        PlatformUserPrincipal user = principal(context.createdBy());
        SearchService.EvaluationTarget target = searchTarget(
                context.target(), SearchService.EvaluationFault.NONE
        );
        String query = text(work.input().get("query"));
        if (query.isBlank()) {
            return blocked(work, "CASE_QUERY_UNAVAILABLE", List.of());
        }

        boolean factIntegrity = factMatchesCase(fact, work);
        boolean current = fact.currentRevision()
                && fact.currentAcl()
                && fact.revisionReady()
                && fact.searchable();
        boolean parser = fact.expectedParserProvider().equals(
                fact.actualParserProvider())
                && fact.expectedParserVersion().equals(
                fact.actualParserVersion())
                && fact.expectedChunkerVersion().equals(
                fact.actualChunkerVersion());
        boolean locator = fact.locatorKind().equals(
                fact.actualLocatorKind())
                && fact.sourceLabel().equals(fact.actualSourceLabel())
                && fact.locatorHash().equals(sha256(
                fact.actualLocatorJson()
        ));
        List<String> assertions = securityAssertions(work.expected());
        boolean securityPolicy = !assertions.isEmpty()
                && assertions.equals(fact.securityAssertions());
        List<String> stale = new ArrayList<>();
        if (!factIntegrity) {
            stale.add("FACT_INTEGRITY_CHANGED");
        }
        if (!current) {
            stale.add("CURRENT_REVISION_OR_ACL_CHANGED");
        }
        if (!parser) {
            stale.add("PARSER_OR_CHUNKER_CHANGED");
        }
        if (!locator) {
            stale.add("SOURCE_LOCATOR_CHANGED");
        }
        if (!securityPolicy) {
            stale.add("SECURITY_SUITE_BINDING_CHANGED");
        }
        if (!stale.isEmpty()) {
            return blocked(
                    work,
                    "MULTIFORMAT_FACT_CLOSURE_STALE",
                    stale
            );
        }

        long heapBefore = usedHeap();
        SearchRequest request = new SearchRequest(
                query, 0, 8, fact.documentId(), null, GraphMode.HYBRID
        );
        SearchDebugResponse warmup = search.orElseThrow(() ->
                new IllegalStateException("Search runtime is disabled")
        ).evaluate(request, user, target);
        SearchDebugResponse measuredResponse = search.get().evaluate(
                request, user, target
        );
        boolean retrieval = measuredResponse.result().items().stream()
                .anyMatch(hit -> hit.documentId().equals(fact.documentId())
                        && hit.revisionId().equals(fact.revisionId()))
                && measuredResponse.result().items().stream()
                .allMatch(this::currentHit);
        boolean budget = measuredResponse.contextBudget().totalTokens()
                <= measuredResponse.contextBudget().limitTokens()
                && measuredResponse.result().items().size() <= 8;
        boolean degradation = degradationConsistent(measuredResponse);

        long chatStarted = System.nanoTime();
        EvaluationAnswer answer = chat.orElseThrow(() ->
                new IllegalStateException("Chat runtime is disabled")
        ).evaluate(
                run.id(), work.id(), query, work.language(),
                GraphMode.HYBRID, target, user, false,
                fact.documentId()
        );
        long chatMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - chatStarted
        );
        boolean citations = answer.status() == RunStatus.COMPLETED
                && !answer.citations().isEmpty()
                && answer.citations().stream().allMatch(citation ->
                currentEvaluationCitation(
                        citation.revisionId(), citation.childChunkId(),
                        citation.sourceSpanId()
                ));
        boolean expectedRevisionCited = answer.citations().stream()
                .anyMatch(citation ->
                        citation.revisionId().equals(fact.revisionId()));
        boolean queryBudget = answer.plannerCallCount() <= 2
                && answer.retrievalCallCount() <= 6
                && answer.rerankCallCount() <= 1;

        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(measured("phase18d.hard.fact_integrity", factIntegrity,
                Map.of("documentFormat", fact.documentFormat())));
        metrics.add(measured("phase18d.hard.current_revision_acl", current,
                Map.of(
                        "visibility", fact.documentVisibility(),
                        "aclVersion", fact.aclVersion()
                )));
        metrics.add(measured("phase18d.hard.parser_contract", parser,
                Map.of(
                        "expectedProvider", fact.expectedParserProvider(),
                        "actualProvider", fact.actualParserProvider(),
                        "parserVersion", fact.actualParserVersion(),
                        "chunkerVersion", fact.actualChunkerVersion()
                )));
        metrics.add(measured("phase18d.hard.locator_resolved", locator,
                Map.of(
                        "locatorKind", fact.locatorKind(),
                        "sourceLabel", fact.sourceLabel()
                )));
        metrics.add(measured("phase18d.hard.retrieval_resolved", retrieval,
                Map.of("resultCount", measuredResponse.result().items().size())));
        metrics.add(measured("phase18d.hard.citation_resolved", citations,
                Map.of("citationCount", answer.citations().size())));
        metrics.add(measured("phase18d.hard.budget_respected", budget,
                Map.of(
                        "usedTokens", measuredResponse.contextBudget().totalTokens(),
                        "limitTokens", measuredResponse.contextBudget().limitTokens()
                )));
        metrics.add(measured("phase18d.hard.degradation_recorded", degradation,
                Map.of(
                        "degraded", measuredResponse.result().degraded()
                                || measuredResponse.result().graphDegraded()
                )));
        metrics.add(measured("phase18d.hard.query_budget", queryBudget,
                Map.of(
                        "plannerCalls", answer.plannerCallCount(),
                        "retrievalCalls", answer.retrievalCallCount(),
                        "rerankCalls", answer.rerankCallCount()
                )));
        metrics.add(measured("phase18d.contract.security_policy_bound", securityPolicy,
                Map.of(
                        "assertions", assertions,
                        "verification", "FROZEN_POLICY_AND_PRODUCTION_ARTIFACT"
                )));
        metrics.add(measured(
                "phase18d.quality.expected_revision_cited",
                expectedRevisionCited,
                Map.of("expectedRevisionId", fact.revisionId().toString())
        ));
        metrics.add(new MetricResult(
                "phase18d.performance.search_ms", "MEASURED",
                (double) measuredResponse.tookMs(),
                Map.of("warmupMs", warmup.tookMs(), "measurementRuns", 1)
        ));
        metrics.add(new MetricResult(
                "phase18d.performance.chat_ms", "MEASURED",
                (double) chatMs,
                Map.of("measurementRuns", 1)
        ));
        metrics.add(notMeasured(
                "phase11b.judge.advisory", "JUDGE_NOT_REQUESTED"
        ));

        Map<String, Object> output = baseOutput(
                context.target(), query, GraphMode.HYBRID.name(),
                measuredResponse.result().graphModeUsed()
        );
        output.put("evaluator", EvaluationService.MULTIFORMAT_EVALUATOR_VERSION);
        output.put("documentFormat", fact.documentFormat());
        output.put("documentId", fact.documentId().toString());
        output.put("revisionId", fact.revisionId().toString());
        output.put("childChunkId", fact.childChunkId().toString());
        output.put("sourceSpanId", fact.sourceSpanId().toString());
        output.put("locatorKind", fact.locatorKind());
        output.put("sourceLabel", fact.sourceLabel());
        output.put("locatorHash", fact.locatorHash());
        output.put("parserProvider", fact.actualParserProvider());
        output.put("parserVersion", fact.actualParserVersion());
        output.put("chunkerVersion", fact.actualChunkerVersion());
        output.put("securityAssertions", assertions);
        output.put("searchWarmupMs", warmup.tookMs());
        output.put("searchMeasuredMs", measuredResponse.tookMs());
        output.put("chatMeasuredMs", chatMs);
        output.put("heapBeforeBytes", heapBefore);
        output.put("heapAfterBytes", usedHeap());
        output.put("degraded", measuredResponse.result().degraded());
        output.put("degradationCode",
                measuredResponse.result().degradationCode());
        output.put("graphDegraded",
                measuredResponse.result().graphDegraded());
        output.put("graphDegradationCode",
                measuredResponse.result().graphDegradationCode());
        output.put("chatRunId", answer.chatRunId().toString());
        output.put("queryProfileVersion", answer.queryProfileVersion());
        output.put("plannerCallCount", answer.plannerCallCount());
        output.put("retrievalCallCount", answer.retrievalCallCount());
        output.put("rerankCallCount", answer.rerankCallCount());
        output.put("queryDegraded", answer.queryDegraded());
        output.put("queryDegradationCode", answer.queryDegradationCode());
        output.put("memoryInjectedCount", answer.memoryInjectedCount());
        output.put("memoryUsedCount", answer.memoryUsedCount());
        output.put("memoryTokenCount", answer.memoryTokenCount());
        output.put("terminalStatus", answer.status().name());
        output.put("citationCount", answer.citations().size());
        output.put("citationRevisionIds", answer.citations().stream()
                .map(citation -> citation.revisionId().toString())
                .distinct().toList());
        output.put("qualityMeasured", true);
        boolean passed = hardPassed(work.caseType(), metrics);
        return new CaseEvaluation(
                passed ? "SUCCEEDED" : "FAILED",
                output,
                passed ? null : "HARD_GATE_FAILED",
                passed ? null : "Multiformat release hard gate failed",
                List.copyOf(metrics)
        );
    }

    private CaseEvaluation multiformatSecurity(CaseWork work) {
        String key = text(work.input().get("securityProbe"));
        String expectedVersion = text(work.expected().get("suiteVersion"));
        String expectedHash = text(work.expected().get("inputSha256"));
        if (key.isBlank() || expectedVersion.isBlank()
                || expectedHash.isBlank()) {
            return blocked(
                    work,
                    "SECURITY_PROBE_CONTRACT_MISSING",
                    List.of()
            );
        }
        MultiformatSecurityProbe.ProbeResult result = securityProbe.execute(key);
        boolean executed = expectedVersion.equals(result.suiteVersion())
                && expectedHash.equals(result.inputSha256());
        boolean passed = executed && result.passed();
        List<MetricResult> metrics = List.of(
                measured(
                        "phase18d.hard.security_attack_executed",
                        executed,
                        Map.of(
                                "probe", result.key(),
                                "suiteVersion", result.suiteVersion(),
                                "inputSha256", result.inputSha256()
                        )
                ),
                measured(
                        "phase18d.hard.security_attack_passed",
                        passed,
                        Map.of(
                                "probe", result.key(),
                                "documentFormat", result.documentFormat(),
                                "resultCode", result.code(),
                                "error", result.error() == null
                                        ? "" : result.error()
                        )
                )
        );
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evaluator", EvaluationService.MULTIFORMAT_EVALUATOR_VERSION);
        output.put("authority", "DETERMINISTIC");
        output.put("assuranceLevel", "PRODUCTION_SECURITY_PROBE");
        output.put("qualityMeasured", true);
        output.put("securityProbe", result.key());
        output.put("documentFormat", result.documentFormat());
        output.put("suiteVersion", result.suiteVersion());
        output.put("inputSha256", result.inputSha256());
        output.put("resultCode", result.code());
        output.put("details", result.details());
        return new CaseEvaluation(
                passed ? "SUCCEEDED" : "FAILED",
                Map.copyOf(output),
                passed ? null : result.code(),
                passed ? null : "Security attack probe failed",
                metrics
        );
    }

    private CaseEvaluation intent(
            CaseWork work,
            EvaluationContext context,
            String query
    ) {
        if (query.isBlank()) {
            return blocked(
                    work, "CASE_QUERY_UNAVAILABLE", List.of()
            );
        }
        String profileVersion = optionalText(
                context.target().snapshot(), "queryProfileVersion"
        );
        if (profileVersion == null) {
            return blocked(
                    work, "QUERY_PROFILE_UNAVAILABLE", List.of()
            );
        }
        if (routing.isEmpty() || search.isEmpty()) {
            return blocked(
                    work, "REAL_QUERY_INTELLIGENCE_DISABLED", List.of()
            );
        }
        try {
            var profile = queryProfiles.find(profileVersion);
            var planned = routing.get().plan(
                    query,
                    List.of(),
                    GraphMode.AUTO,
                    profile
            );
            SearchDebugResponse response = search.get().evaluatePlanned(
                    new SearchRequest(
                            query, 0, 8, null, null, GraphMode.AUTO
                    ),
                    principal(context.createdBy()),
                    searchTarget(
                            context.target(),
                            SearchService.EvaluationFault.NONE
                    ),
                    planned.queryPlan(),
                    routing.get().secondRoundPlanner(
                            query,
                            List.of(),
                            planned.queryPlan(),
                            planned.profile(),
                            planned.policy()
                    ),
                    planned.routing(),
                    planned.policy()
            );
            var result = response.result();
            var route = result.routeExecution();
            var queryExecution = result.queryExecution();
            String selected = route.selectedMode();
            String expected = text(work.expected().get("expectedMode"));
            boolean contract = GraphMode.AUTO.name().equals(
                    route.requestedMode())
                    && !GraphMode.AUTO.name().equals(selected)
                    && route.routerCallCount() <= 1
                    && queryExecution.plannerCallCount()
                    <= profile.plannerCallLimit()
                    && queryExecution.retrievalCallCount()
                    <= planned.policy().maxQuerySlots()
                    && queryExecution.rerankCallCount() <= 1
                    && (!route.degraded()
                    || route.degradationCode() != null)
                    && (!result.graphDegraded()
                    || result.graphDegradationCode() != null);
            boolean quality = expected.equals(selected);
            List<MetricResult> metrics = new ArrayList<>();
            metrics.add(measured(
                    "phase12c.hard.intent_route_unique",
                    contract,
                    Map.of(
                            "routerCallCount",
                            route.routerCallCount(),
                            "plannerCallCount",
                            queryExecution.plannerCallCount(),
                            "retrievalCallCount",
                            queryExecution.retrievalCallCount(),
                            "rerankCallCount",
                            queryExecution.rerankCallCount()
                    )
            ));
            metrics.add(measured(
                    "phase12c.quality.intent_route",
                    quality,
                    Map.of(
                            "expectedMode", expected,
                            "selectedMode", selected
                    )
            ));
            Map<String, Object> output = baseOutput(
                    context.target(), query, GraphMode.AUTO.name(),
                    result.graphModeUsed()
            );
            output.put("queryProfileVersion", profileVersion);
            output.put("routeSelectedMode", selected);
            output.put("routerCallCount", route.routerCallCount());
            output.put("routeReasonCode", route.reasonCode());
            output.put("routeDegraded", route.degraded());
            output.put("routeDegradationCode", route.degradationCode());
            output.put(
                    "plannerCallCount",
                    queryExecution.plannerCallCount()
            );
            output.put(
                    "retrievalCallCount",
                    queryExecution.retrievalCallCount()
            );
            output.put(
                    "rerankCallCount",
                    queryExecution.rerankCallCount()
            );
            output.put("graphDegraded", result.graphDegraded());
            output.put(
                    "graphDegradationCode",
                    result.graphDegradationCode()
            );
            output.put("qualityMeasured", true);
            return new CaseEvaluation(
                    contract ? "SUCCEEDED" : "FAILED",
                    output,
                    contract ? null : "HARD_GATE_FAILED",
                    contract ? null : "Intent route contract failed",
                    List.copyOf(metrics)
            );
        } catch (RuntimeException exception) {
            return failed(work, "INTENT_EVALUATION_FAILED");
        }
    }

    private CaseEvaluation multiTurn(
            ClaimedRun run,
            CaseWork work,
            List<String> turns,
            Map<String, EvidenceRevision> evidence,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user,
            TargetView targetView
    ) {
        if (turns.size() < 2 || turns.size() > 4) {
            return blocked(
                    work, "MULTI_TURN_INPUT_INVALID", List.of()
            );
        }
        List<EvaluationAnswer> answers = chat.orElseThrow(() ->
                new IllegalStateException("Chat runtime is disabled")
        ).evaluateTurns(
                run.id(), work.id(), turns, work.language(),
                GraphMode.AUTO, target, user
        );
        var profile = queryProfiles.find(target.queryProfileVersion());
        boolean completeRun = answers.size() == turns.size()
                && answers.stream().allMatch(answer ->
                answer.status() == RunStatus.COMPLETED
                        || answer.status() == RunStatus.REFUSED
        );
        boolean history = completeRun && answers.stream().skip(1)
                .allMatch(answer -> answer.historyMessageCount() > 0);
        boolean budget = completeRun && answers.stream().allMatch(answer ->
                answer.plannerCallCount()
                        <= profile.plannerCallLimit()
                        && answer.retrievalCallCount()
                        <= profile.maxSubQueries()
                        * profile.maxRetrievalRounds()
                        && answer.rerankCallCount() <= 1
                        && answer.routerCallCount() <= 1
                        && !GraphMode.AUTO.name().equals(
                        answer.routeSelectedMode())
                        && (!answer.routeDegraded()
                        || answer.routeDegradationCode() != null)
                        && (!answer.graphDegraded()
                        || answer.graphDegradationCode() != null)
        );
        int citationCount = answers.stream()
                .mapToInt(answer -> answer.citations().size())
                .sum();
        boolean citations = completeRun
                && citationCount > 0
                && answers.stream().allMatch(answer ->
                answer.status() != RunStatus.COMPLETED
                        || !answer.citations().isEmpty()
        )
                && answers.stream()
                .flatMap(answer -> answer.citations().stream())
                .allMatch(citation -> currentCitation(
                        citation.revisionId(),
                        citation.childChunkId(),
                        citation.sourceSpanId()
                ));
        Set<UUID> expectedRevisions = evidence.values().stream()
                .map(EvidenceRevision::revisionId)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll
                );
        long matched = answers.stream()
                .flatMap(answer -> answer.citations().stream())
                .map(ChatEvaluationGateway.CitationReference::revisionId)
                .filter(expectedRevisions::contains)
                .distinct()
                .count();
        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(measured(
                "phase12c.hard.multi_turn_history",
                history,
                Map.of("turnCount", turns.size())
        ));
        metrics.add(measured(
                "phase12c.hard.query_budget",
                budget,
                Map.of("turnCount", turns.size())
        ));
        metrics.add(measured(
                "phase12c.hard.multi_turn_citation",
                citations,
                Map.of(
                        "citationCount", citationCount
                )
        ));
        metrics.add(measured(
                "phase12c.quality.multi_turn",
                matched == expectedRevisions.size(),
                Map.of(
                        "expectedRevisions", expectedRevisions.size(),
                        "matchedRevisions", matched
                )
        ));
        boolean hardPassed = history && budget && citations;
        Map<String, Object> output = baseOutput(
                targetView,
                turns.getLast(),
                GraphMode.AUTO.name(),
                answers.getLast().graphModeUsed()
        );
        output.put(
                "chatRunIds",
                answers.stream()
                        .map(answer -> answer.chatRunId().toString())
                        .toList()
        );
        output.put("turnCount", turns.size());
        output.put(
                "routeSelectedModes",
                answers.stream()
                        .map(EvaluationAnswer::routeSelectedMode)
                        .toList()
        );
        output.put(
                "historyMessageCounts",
                answers.stream()
                        .map(EvaluationAnswer::historyMessageCount)
                        .toList()
        );
        output.put(
                "plannerCallCounts",
                answers.stream()
                        .map(EvaluationAnswer::plannerCallCount)
                        .toList()
        );
        output.put(
                "retrievalCallCounts",
                answers.stream()
                        .map(EvaluationAnswer::retrievalCallCount)
                        .toList()
        );
        output.put(
                "rerankCallCounts",
                answers.stream()
                        .map(EvaluationAnswer::rerankCallCount)
                        .toList()
        );
        output.put(
                "terminalStatuses",
                answers.stream()
                        .map(answer -> answer.status().name())
                        .toList()
        );
        output.put("matchedExpectedRevisions", matched);
        output.put("qualityMeasured", true);
        return new CaseEvaluation(
                hardPassed ? "SUCCEEDED" : "FAILED",
                output,
                hardPassed ? null : "HARD_GATE_FAILED",
                hardPassed ? null : "Multi-turn hard gate failed",
                List.copyOf(metrics)
        );
    }

    private CaseEvaluation retrieval(
            CaseWork work,
            String query,
            Map<String, EvidenceRevision> evidence,
            GraphMode graphMode,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user,
            TargetView targetView
    ) {
        SearchService searchService = search.orElseThrow(() ->
                new IllegalStateException("Search runtime is disabled")
        );
        SearchRequest request = new SearchRequest(
                query, 0, 8, null,
                DocumentVisibility.ALL_USERS, graphMode
        );
        String queryProfileVersion = optionalText(
                targetView.snapshot(), "queryProfileVersion"
        );
        boolean planned = queryProfileVersion != null
                && routing.isPresent();
        SearchDebugResponse response;
        if (planned) {
            var profile = queryProfiles.find(queryProfileVersion);
            var requestPlan = routing.orElseThrow().plan(
                    query, List.of(), graphMode, profile
            );
            response = searchService.evaluatePlanned(
                    request,
                    user,
                    target,
                    requestPlan.queryPlan(),
                    routing.orElseThrow().secondRoundPlanner(
                            query,
                            List.of(),
                            requestPlan.queryPlan(),
                            requestPlan.profile(),
                            requestPlan.policy()
                    ),
                    requestPlan.routing(),
                    requestPlan.policy()
            );
        } else {
            response = searchService.evaluate(request, user, target);
        }
        List<SearchHit> hits = response.result().items();
        Set<UUID> expectedRevisions = evidence.values().stream()
                .map(EvidenceRevision::revisionId)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll
                );
        Set<UUID> expectedDocuments = evidence.values().stream()
                .map(EvidenceRevision::documentId)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll
                );
        long matched = hits.stream()
                .map(SearchHit::revisionId)
                .filter(expectedRevisions::contains)
                .distinct()
                .count();
        StageRecall stageRecall = stageRecall(
                response.candidates(), expectedRevisions
        );
        GraphRecall graphRecall = graphRecall(
                response, expectedDocuments, expectedRevisions
        );
        boolean current = hits.stream().allMatch(this::currentPublicHit);
        boolean budget = response.contextBudget().totalTokens()
                <= response.contextBudget().limitTokens()
                && response.result().items().size() <= 8;
        boolean degradation = degradationConsistent(response);
        boolean anchors = anchorsResolve(response.candidates(), work.caseType());

        List<MetricResult> metrics = commonMetrics(
                current, budget, degradation, true
        );
        metrics.add(measured(
                typeHardMetric(work.caseType()), anchors,
                Map.of("productionPath", true)
        ));
        metrics.add(observedRecall(
                "hotpotqa.retrieval.bm25_recall",
                stageRecall.bm25(), expectedRevisions.size()
        ));
        metrics.add(observedRecall(
                "hotpotqa.retrieval.vector_recall",
                stageRecall.vector(), expectedRevisions.size()
        ));
        metrics.add(observedRecall(
                "hotpotqa.retrieval.authorized_candidate_recall",
                stageRecall.authorized(), expectedRevisions.size()
        ));
        metrics.add(observedRecall(
                "hotpotqa.retrieval.reranked_recall",
                stageRecall.reranked(), expectedRevisions.size()
        ));
        metrics.add(observedRecall(
                "hotpotqa.retrieval.evidence_recall",
                stageRecall.evidence(), expectedRevisions.size()
        ));
        if (graphMode == GraphMode.LOCAL_GRAPH) {
            metrics.add(observedRecall(
                    "hotpotqa.graph.entity_seed_recall",
                    graphRecall.seededDocuments(),
                    expectedDocuments.size()
            ));
            metrics.add(observedRecall(
                    "hotpotqa.graph.candidate_gold_recall",
                    graphRecall.graphCandidates(),
                    expectedRevisions.size()
            ));
            metrics.add(observedRecall(
                    "hotpotqa.graph.path_coverage",
                    graphRecall.pathDocuments(),
                    expectedDocuments.size()
            ));
            metrics.add(observedCount(
                    "hotpotqa.graph.added_candidate_count",
                    graphRecall.addedCandidates()
            ));
            metrics.add(observedBoolean(
                    "hotpotqa.graph.complete_path_before_rerank",
                    graphRecall.pathDocuments()
                            == expectedDocuments.size()
            ));
            metrics.add(observedBoolean(
                    "hotpotqa.graph.complete_path_after_rerank",
                    stageRecall.reranked()
                            == expectedRevisions.size()
            ));
            metrics.add(observedBoolean(
                    "hotpotqa.graph.complete_path_in_evidence",
                    stageRecall.evidence()
                            == expectedRevisions.size()
            ));
        }
        metrics.add(measured(
                qualityMetric(work.caseType()),
                matched == expectedRevisions.size(),
                Map.of(
                        "expectedRevisions", expectedRevisions.size(),
                        "matchedRevisions", matched
                )
        ));
        metrics.add(notMeasured(
                "phase11b.judge.advisory",
                "JUDGE_NOT_REQUESTED"
        ));

        Map<String, Object> output = baseOutput(
                targetView, query, response.result().graphModeRequested(),
                response.result().graphModeUsed()
        );
        output.put("resultCount", hits.size());
        output.put("expectedRevisions", expectedRevisions.size());
        output.put(
                "revisionIds",
                hits.stream().map(SearchHit::revisionId)
                        .distinct().map(UUID::toString).toList()
        );
        output.put("matchedExpectedRevisions", matched);
        output.put("plannedRetrieval", planned);
        output.put(
                "plannerCallCount",
                response.result().queryExecution().plannerCallCount()
        );
        output.put(
                "retrievalCallCount",
                response.result().queryExecution().retrievalCallCount()
        );
        output.put(
                "rerankCallCount",
                response.result().queryExecution().rerankCallCount()
        );
        Map<String, Object> stageOutput = new LinkedHashMap<>();
        stageOutput.put("expected", expectedRevisions.size());
        stageOutput.put("bm25", stageRecall.bm25());
        stageOutput.put("vector", stageRecall.vector());
        stageOutput.put(
                "authorizedCandidates", stageRecall.authorized()
        );
        stageOutput.put("reranked", stageRecall.reranked());
        stageOutput.put("evidence", stageRecall.evidence());
        output.put("stageRecall", Map.copyOf(stageOutput));
        if (graphMode == GraphMode.LOCAL_GRAPH) {
            output.put("graphDiagnostics", Map.of(
                    "expectedDocuments", expectedDocuments.size(),
                    "expectedRevisions", expectedRevisions.size(),
                    "seededDocuments", graphRecall.seededDocuments(),
                    "graphCandidateGoldRevisions",
                    graphRecall.graphCandidates(),
                    "pathDocuments", graphRecall.pathDocuments(),
                    "rerankedPathGoldRevisions", stageRecall.reranked(),
                    "evidencePathGoldRevisions", stageRecall.evidence(),
                    "addedCandidates", graphRecall.addedCandidates(),
                    "pathCount", graphRecall.pathCount()
            ));
        }
        output.put("degraded", response.result().degraded());
        output.put("degradationCode", response.result().degradationCode());
        output.put("graphDegraded", response.result().graphDegraded());
        output.put(
                "graphDegradationCode",
                response.result().graphDegradationCode()
        );
        output.put("totalTokens", response.contextBudget().totalTokens());
        output.put("qualityMeasured", true);
        return new CaseEvaluation(
                hardPassed(work.caseType(), metrics)
                        ? "SUCCEEDED" : "FAILED",
                output,
                hardPassed(work.caseType(), metrics)
                        ? null : "HARD_GATE_FAILED",
                hardPassed(work.caseType(), metrics) ? null
                        : "Real evaluation hard gate failed",
                List.copyOf(metrics)
        );
    }

    private static StageRecall stageRecall(
            List<DebugCandidate> candidates,
            Set<UUID> expectedRevisions
    ) {
        return new StageRecall(
                matchedRevisions(
                        candidates, expectedRevisions,
                        candidate -> candidate.bm25Rank() != null
                ),
                matchedRevisions(
                        candidates, expectedRevisions,
                        candidate -> candidate.vectorRank() != null
                ),
                matchedRevisions(
                        candidates, expectedRevisions,
                        candidate -> true
                ),
                matchedRevisions(
                        candidates, expectedRevisions,
                        candidate -> candidate.rerankRank() != null
                ),
                matchedRevisions(
                        candidates, expectedRevisions,
                        DebugCandidate::accepted
                )
        );
    }

    private static int matchedRevisions(
            List<DebugCandidate> candidates,
            Set<UUID> expectedRevisions,
            java.util.function.Predicate<DebugCandidate> included
    ) {
        return (int) candidates.stream()
                .filter(included)
                .map(DebugCandidate::result)
                .filter(java.util.Objects::nonNull)
                .map(SearchHit::revisionId)
                .filter(expectedRevisions::contains)
                .distinct()
                .count();
    }

    private static GraphRecall graphRecall(
            SearchDebugResponse response,
            Set<UUID> expectedDocuments,
            Set<UUID> expectedRevisions
    ) {
        int seeded = (int) response.graphDiagnostics()
                .seedDocumentIds().stream()
                .filter(expectedDocuments::contains)
                .distinct()
                .count();
        int candidates = matchedRevisions(
                response.candidates(), expectedRevisions,
                candidate -> candidate.graphRank() != null
        );
        Set<UUID> pathDocumentIds = new LinkedHashSet<>();
        response.graphDiagnostics().seedDocumentIds().stream()
                .filter(expectedDocuments::contains)
                .forEach(pathDocumentIds::add);
        response.candidates().stream()
                .flatMap(candidate -> candidate.graphPaths().stream())
                .map(path -> path.documentId())
                .filter(expectedDocuments::contains)
                .forEach(pathDocumentIds::add);
        return new GraphRecall(
                seeded,
                candidates,
                pathDocumentIds.size(),
                response.graphDiagnostics().graphAddedCandidateCount(),
                response.graphDiagnostics().pathCount()
        );
    }

    private static MetricResult observedRecall(
            String key,
            int matched,
            int expected
    ) {
        return new MetricResult(
                key,
                "MEASURED",
                expected == 0 ? 0.0 : (double) matched / expected,
                Map.of(
                        "matchedRevisions", matched,
                        "expectedRevisions", expected,
                        "blocking", false
                )
        );
    }

    private static MetricResult observedCount(String key, int value) {
        return new MetricResult(
                key,
                "MEASURED",
                (double) value,
                Map.of("count", value, "blocking", false)
        );
    }

    private static MetricResult observedBoolean(
            String key,
            boolean value
    ) {
        return new MetricResult(
                key,
                "MEASURED",
                value ? 1.0 : 0.0,
                Map.of("retained", value, "blocking", false)
        );
    }

    private record StageRecall(
            int bm25,
            int vector,
            int authorized,
            int reranked,
            int evidence
    ) {
    }

    private record GraphRecall(
            int seededDocuments,
            int graphCandidates,
            int pathDocuments,
            int addedCandidates,
            int pathCount
    ) {
    }

    static String normalizeAnswer(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\b(a|an|the)\\b", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    static double tokenF1(String prediction, String expected) {
        String normalizedPrediction = normalizeAnswer(prediction);
        String normalizedExpected = normalizeAnswer(expected);
        if (normalizedPrediction.isEmpty() || normalizedExpected.isEmpty()) {
            return normalizedPrediction.equals(normalizedExpected) ? 1.0 : 0.0;
        }
        Map<String, Integer> predicted = tokenCounts(normalizedPrediction);
        Map<String, Integer> gold = tokenCounts(normalizedExpected);
        int overlap = predicted.entrySet().stream()
                .mapToInt(entry -> Math.min(
                        entry.getValue(), gold.getOrDefault(entry.getKey(), 0)
                ))
                .sum();
        if (overlap == 0) {
            return 0.0;
        }
        int predictedCount = predicted.values().stream()
                .mapToInt(Integer::intValue).sum();
        int goldCount = gold.values().stream()
                .mapToInt(Integer::intValue).sum();
        double precision = (double) overlap / predictedCount;
        double recall = (double) overlap / goldCount;
        return 2.0 * precision * recall / (precision + recall);
    }

    private static Map<String, Integer> tokenCounts(String normalized) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : normalized.split(" ")) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private CaseEvaluation answer(
            ClaimedRun run,
            CaseWork work,
            String query,
            Map<String, EvidenceRevision> evidence,
            GraphMode graphMode,
            SearchService.EvaluationTarget target,
            PlatformUserPrincipal user,
            TargetView targetView
    ) {
        EvaluationAnswer result = chat.orElseThrow(() ->
                new IllegalStateException("Chat runtime is disabled")
        ).evaluate(
                run.id(),
                work.id(),
                query,
                work.language(),
                graphMode,
                target,
                user,
                false
        );
        Set<UUID> expectedRevisions = evidence.values().stream()
                .map(EvidenceRevision::revisionId)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll
                );
        long matched = result.citations().stream()
                .map(ChatEvaluationGateway.CitationReference::revisionId)
                .filter(expectedRevisions::contains)
                .distinct()
                .count();
        boolean citationRequired = result.status() == RunStatus.COMPLETED
                && !expectedRevisions.isEmpty();
        boolean citations = (!citationRequired
                || !result.citations().isEmpty())
                && result.citations().stream()
                .allMatch(citation -> currentCitation(
                        citation.revisionId(),
                        citation.childChunkId(),
                        citation.sourceSpanId()
                ));
        boolean shouldRefuse = Boolean.TRUE.equals(
                work.expected().get("shouldRefuse")
        );
        boolean refusal = shouldRefuse
                ? result.status() == RunStatus.REFUSED
                : result.status() == RunStatus.COMPLETED;
        boolean calls = result.mapCallCount() <= 8
                && result.reduceCallCount() <= 1;
        boolean degradation = !result.graphDegraded()
                || result.graphDegradationCode() != null;

        List<MetricResult> metrics = commonMetrics(
                citations, calls, degradation, true
        );
        metrics.add(measured(
                "phase11b.hard.citation_resolved",
                citations,
                Map.of("citationCount", result.citations().size())
        ));
        metrics.add(measured(
                "phase11b.hard.refusal_contract",
                refusal,
                Map.of(
                        "expectedStatus",
                        shouldRefuse ? "REFUSED" : "COMPLETED",
                        "terminalStatus", result.status().name()
                )
        ));
        metrics.add(measured(
                qualityMetric(work.caseType()),
                matched == expectedRevisions.size(),
                Map.of(
                        "expectedRevisions", expectedRevisions.size(),
                        "matchedRevisions", matched
                )
        ));
        String expectedAnswer = text(work.expected().get("expectedAnswer"));
        if (!expectedAnswer.isBlank()) {
            String directAnswer = result.directAnswer() == null
                    ? "" : result.directAnswer().strip();
            double answerF1 = tokenF1(directAnswer, expectedAnswer);
            boolean answerExactMatch = normalizeAnswer(directAnswer)
                    .equals(normalizeAnswer(expectedAnswer));
            metrics.add(measured(
                    "hotpotqa.answer_exact_match",
                    answerExactMatch,
                    Map.of(
                            "normalizer", "hotpotqa-en-v1",
                            "projection", "validated-direct-answer-v1",
                            "available", !directAnswer.isBlank()
                    )
            ));
            metrics.add(new MetricResult(
                    "hotpotqa.answer_f1", "MEASURED", answerF1,
                    Map.of(
                            "normalizer", "hotpotqa-en-v1",
                            "projection", "validated-direct-answer-v1",
                            "available", !directAnswer.isBlank()
                    )
            ));
            metrics.add(new MetricResult(
                    "hotpotqa.rendered_answer_f1", "MEASURED",
                    tokenF1(result.answerContent(), expectedAnswer),
                    Map.of(
                            "normalizer", "hotpotqa-en-v1",
                            "projection", "rendered-cited-answer-v1",
                            "blocking", false
                    )
            ));
        }
        metrics.add(notMeasured(
                "phase11b.judge.advisory",
                "JUDGE_NOT_REQUESTED"
        ));

        Map<String, Object> output = baseOutput(
                targetView, query,
                result.graphModeRequested(), result.graphModeUsed()
        );
        output.put("chatRunId", result.chatRunId().toString());
        output.put("terminalStatus", result.status().name());
        output.put("refusalCode", result.refusalCode());
        output.put("citationCount", result.citations().size());
        output.put(
                "citationRevisionIds",
                result.citations().stream()
                        .map(ChatEvaluationGateway.CitationReference::revisionId)
                        .distinct().map(UUID::toString).toList()
        );
        output.put("matchedExpectedRevisions", matched);
        if (!expectedAnswer.isBlank()) {
            output.put("expectedAnswerHash", sha256(expectedAnswer));
            output.put("answerHash", sha256(result.answerContent()));
            String directAnswer = result.directAnswer() == null
                    ? "" : result.directAnswer().strip();
            output.put("directAnswerAvailable", !directAnswer.isBlank());
            output.put(
                    "directAnswerCitationCount",
                    result.directAnswerCitationIds().size()
            );
            if (!directAnswer.isBlank()) {
                output.put("directAnswerHash", sha256(directAnswer));
            }
        }
        output.put("graphDegraded", result.graphDegraded());
        output.put("graphDegradationCode", result.graphDegradationCode());
        output.put("mapCallCount", result.mapCallCount());
        output.put("reduceCallCount", result.reduceCallCount());
        output.put("qualityMeasured", true);
        return new CaseEvaluation(
                hardPassed(work.caseType(), metrics)
                        ? "SUCCEEDED" : "FAILED",
                output,
                hardPassed(work.caseType(), metrics)
                        ? null : "HARD_GATE_FAILED",
                hardPassed(work.caseType(), metrics) ? null
                        : "Real answer hard gate failed",
                List.copyOf(metrics)
        );
    }

    private EvaluationContext context(ClaimedRun run) {
        return jdbc.query(
                """
                SELECT subject.target_id, subject.created_by
                FROM evaluation_subjects subject
                WHERE subject.id = ?
                """,
                (resultSet, rowNumber) -> new EvaluationContext(
                        targets.target(resultSet.getObject(
                                "target_id", UUID.class
                        )),
                        resultSet.getObject("created_by", UUID.class)
                ),
                run.subjectId()
        ).stream().findFirst().orElseThrow();
    }

    private PlatformUserPrincipal principal(UUID userId) {
        var user = users.findById(userId).orElseThrow();
        if (!user.isEnabled()) {
            throw new IllegalStateException("Evaluation owner is disabled");
        }
        return PlatformUserPrincipal.from(user);
    }

    static SearchService.EvaluationTarget searchTarget(
            TargetView target,
            SearchService.EvaluationFault fault
    ) {
        Map<String, Object> snapshot = target.snapshot();
        return new SearchService.EvaluationTarget(
                requiredText(snapshot, "retrievalProfileVersion"),
                number(snapshot, "indexGeneration"),
                requiredText(snapshot, "indexName"),
                requiredText(snapshot, "indexConfigVersion"),
                optionalText(snapshot, "graphRetrievalProfileVersion"),
                optionalNumber(snapshot, "graphGeneration"),
                optionalNumber(snapshot, "globalGeneration"),
                optionalText(snapshot, "queryProfileVersion"),
                fault
        );
    }

    private Map<String, EvidenceRevision> evidence(List<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, EvidenceRevision> result = new LinkedHashMap<>();
        for (String key : keys) {
            jdbc.query(
                    """
                    SELECT revision.evaluation_evidence_key,
                           revision.id AS revision_id,
                           document.id AS document_id
                    FROM document_revisions revision
                    JOIN documents document
                      ON document.current_revision_id = revision.id
                     AND document.deleted_at IS NULL
                     AND document.visibility = 'ALL_USERS'
                    WHERE revision.evaluation_evidence_key = ?
                      AND revision.status = 'READY'
                      AND EXISTS (
                        SELECT 1
                        FROM chunks child
                        JOIN source_spans span
                          ON span.chunk_id = child.id
                         AND span.revision_id = revision.id
                        WHERE child.revision_id = revision.id
                          AND child.document_id = document.id
                          AND child.chunk_type = 'CHILD'
                          AND child.searchable = TRUE
                      )
                    """,
                    row -> {
                        if (row.next()) {
                            result.put(
                                    row.getString("evaluation_evidence_key"),
                                    new EvidenceRevision(
                                            row.getObject(
                                                    "document_id", UUID.class
                                            ),
                                            row.getObject(
                                                    "revision_id", UUID.class
                                            )
                                    )
                            );
                        }
                        return null;
                    },
                    key
            );
        }
        return Map.copyOf(result);
    }

    private boolean currentPublicHit(SearchHit hit) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                JOIN chunks child
                  ON child.id = ?
                 AND child.document_id = document.id
                 AND child.revision_id = revision.id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                WHERE document.id = ?
                  AND revision.id = ?
                  AND document.deleted_at IS NULL
                  AND document.visibility = 'ALL_USERS'
                  AND revision.status = 'READY'
                """,
                Integer.class,
                hit.chunkId(), hit.documentId(), hit.revisionId()
        );
        return count != null && count == 1;
    }

    private boolean currentHit(SearchHit hit) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                JOIN chunks child
                  ON child.id = ?
                 AND child.document_id = document.id
                 AND child.revision_id = revision.id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                WHERE document.id = ?
                  AND revision.id = ?
                  AND document.deleted_at IS NULL
                  AND revision.status = 'READY'
                """,
                Integer.class,
                hit.chunkId(), hit.documentId(), hit.revisionId()
        );
        return count != null && count == 1;
    }

    private MultiformatFact multiformatFact(
            UUID datasetVersionId,
            UUID caseId
    ) {
        return jdbc.query(
                """
                SELECT fact.document_format, fact.document_id,
                       fact.revision_id, fact.child_chunk_id,
                       fact.source_span_id, fact.document_visibility,
                       fact.acl_version, fact.file_sha256,
                       fact.expected_parser_provider,
                       fact.expected_parser_version,
                       fact.expected_chunker_version,
                       fact.locator_kind, fact.source_label,
                       fact.locator_hash,
                       document.current_revision_id = fact.revision_id
                           AND document.deleted_at IS NULL AS current_revision,
                       document.acl_version = fact.acl_version
                           AND document.visibility = fact.document_visibility
                           AS current_acl,
                       revision.status = 'READY'
                           AND revision.content_hash = fact.file_sha256
                           AS revision_ready,
                       child.searchable,
                       COALESCE(revision.parser_provider, '')
                           AS actual_parser_provider,
                       COALESCE(child.parser_version, '')
                           AS actual_parser_version,
                       COALESCE(child.chunker_version, '')
                           AS actual_chunker_version,
                       COALESCE(locator.locator_kind, '')
                           AS actual_locator_kind,
                       COALESCE(locator.source_label, '')
                           AS actual_source_label,
                       COALESCE(jsonb_build_object(
                           'kind', locator.locator_kind,
                           'startSourceUnitId', locator.start_source_unit_id,
                           'endSourceUnitId', locator.end_source_unit_id,
                           'startUnitAddress', locator.start_unit_address,
                           'endUnitAddress', locator.end_unit_address,
                           'startOffset', locator.start_offset,
                           'endOffset', locator.end_offset,
                           'address', locator.address,
                           'sourceLabel', locator.source_label,
                           'sourceTextHash', locator.source_text_hash,
                           'normalizationVersion',
                               locator.normalization_version
                       )::TEXT, '{}') AS actual_locator_json
                FROM evaluation_multiformat_case_facts fact
                LEFT JOIN documents document ON document.id = fact.document_id
                LEFT JOIN document_revisions revision
                  ON revision.id = fact.revision_id
                 AND revision.document_id = fact.document_id
                LEFT JOIN chunks child
                  ON child.id = fact.child_chunk_id
                 AND child.document_id = fact.document_id
                 AND child.revision_id = fact.revision_id
                 AND child.chunk_type = 'CHILD'
                LEFT JOIN source_spans span
                  ON span.id = fact.source_span_id
                 AND span.chunk_id = fact.child_chunk_id
                 AND span.document_id = fact.document_id
                 AND span.revision_id = fact.revision_id
                LEFT JOIN source_locator_projection locator
                  ON locator.source_kind = 'SOURCE_SPAN'
                 AND locator.source_id = fact.source_span_id
                WHERE fact.dataset_version_id = ? AND fact.case_id = ?
                """,
                (rs, row) -> new MultiformatFact(
                        rs.getString("document_format"),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getObject("child_chunk_id", UUID.class),
                        rs.getObject("source_span_id", UUID.class),
                        rs.getString("document_visibility"),
                        rs.getLong("acl_version"),
                        rs.getString("file_sha256"),
                        rs.getString("expected_parser_provider"),
                        rs.getString("expected_parser_version"),
                        rs.getString("expected_chunker_version"),
                        rs.getString("locator_kind"),
                        rs.getString("source_label"),
                        rs.getString("locator_hash"),
                        rs.getBoolean("current_revision"),
                        rs.getBoolean("current_acl"),
                        rs.getBoolean("revision_ready"),
                        rs.getBoolean("searchable"),
                        rs.getString("actual_parser_provider"),
                        rs.getString("actual_parser_version"),
                        rs.getString("actual_chunker_version"),
                        rs.getString("actual_locator_kind"),
                        rs.getString("actual_source_label"),
                        rs.getString("actual_locator_json"),
                        securityAssertions(caseId)
                ),
                datasetVersionId, caseId
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Multiformat fact unavailable")
        );
    }

    private List<String> securityAssertions(UUID caseId) {
        return jdbc.queryForList(
                """
                SELECT item.value
                FROM evaluation_multiformat_case_facts fact
                CROSS JOIN LATERAL jsonb_array_elements_text(
                    fact.security_assertions
                ) WITH ORDINALITY item(value, position)
                WHERE fact.case_id = ?
                ORDER BY item.position
                """,
                String.class, caseId
        );
    }

    private static List<String> securityAssertions(
            Map<String, Object> expected
    ) {
        Object value = expected.get("securityAssertions");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(RealEvaluationExecutor::text)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static boolean factMatchesCase(
            MultiformatFact fact,
            CaseWork work
    ) {
        return fact.documentFormat().equals(text(
                work.input().get("documentFormat")))
                && fact.documentId().toString().equals(text(
                work.expected().get("documentId")))
                && fact.revisionId().toString().equals(text(
                work.expected().get("revisionId")))
                && fact.childChunkId().toString().equals(text(
                work.expected().get("childChunkId")))
                && fact.sourceSpanId().toString().equals(text(
                work.expected().get("sourceSpanId")))
                && fact.locatorKind().equals(text(
                work.expected().get("locatorKind")))
                && fact.locatorHash().equals(text(
                work.expected().get("locatorHash")))
                && fact.sourceLabel().equals(text(
                work.expected().get("sourceLabel")))
                && fact.fileSha256().equals(text(
                work.metadata().get("fileSha256")));
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0, runtime.totalMemory() - runtime.freeMemory());
    }

    private boolean currentCitation(
            UUID revisionId,
            UUID childId,
            UUID spanId
    ) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                JOIN chunks child
                  ON child.id = ?
                 AND child.document_id = document.id
                 AND child.revision_id = revision.id
                 AND child.chunk_type = 'CHILD'
                JOIN source_spans span
                  ON span.id = ?
                 AND span.chunk_id = child.id
                 AND span.revision_id = revision.id
                WHERE revision.id = ?
                  AND document.deleted_at IS NULL
                  AND document.visibility = 'ALL_USERS'
                  AND revision.status = 'READY'
                """,
                Integer.class, childId, spanId, revisionId
        );
        return count != null && count == 1;
    }

    private boolean currentEvaluationCitation(
            UUID revisionId,
            UUID childId,
            UUID spanId
    ) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                JOIN chunks child
                  ON child.id = ?
                 AND child.document_id = document.id
                 AND child.revision_id = revision.id
                 AND child.chunk_type = 'CHILD'
                JOIN source_spans span
                  ON span.id = ?
                 AND span.chunk_id = child.id
                 AND span.revision_id = revision.id
                WHERE revision.id = ?
                  AND document.deleted_at IS NULL
                  AND revision.status = 'READY'
                """,
                Integer.class, childId, spanId, revisionId
        );
        return count != null && count == 1;
    }

    private static boolean anchorsResolve(
            List<DebugCandidate> candidates,
            String caseType
    ) {
        return candidates.stream().allMatch(candidate -> switch (caseType) {
            case "LOCAL_GRAPH" -> candidate.graphPaths().stream()
                    .allMatch(path ->
                            path.supportingChunkId() != null
                                    && path.sourceSpanId() != null
                    );
            case "GLOBAL_GRAPH" -> candidate.globalClaims().stream()
                    .allMatch(claim ->
                            claim.supportingChunkId() != null
                                    && claim.sourceSpanId() != null
                    );
            default -> true;
        });
    }

    private static boolean degradationConsistent(
            SearchDebugResponse response
    ) {
        return (!response.result().degraded()
                || response.result().degradationCode() != null)
                && (!response.result().graphDegraded()
                || response.result().graphDegradationCode() != null);
    }

    private static List<MetricResult> commonMetrics(
            boolean authorization,
            boolean budget,
            boolean degradation,
            boolean requestLimit
    ) {
        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(measured(
                "phase11b.hard.authorization_recheck",
                authorization, Map.of("productionPath", true)
        ));
        metrics.add(measured(
                "phase11b.hard.revision_recheck",
                authorization, Map.of("productionPath", true)
        ));
        metrics.add(measured(
                "phase11b.hard.budget_respected",
                budget, Map.of("serverControlled", true)
        ));
        metrics.add(measured(
                "phase11b.hard.degradation_recorded",
                degradation, Map.of("serverControlled", true)
        ));
        metrics.add(measured(
                "phase11b.hard.request_limit",
                requestLimit, Map.of("serverControlled", true)
        ));
        return metrics;
    }

    private static MetricResult measured(
            String key,
            boolean passed,
            Map<String, Object> details
    ) {
        return new MetricResult(
                key, "MEASURED", passed ? 1.0 : 0.0, details
        );
    }

    private static MetricResult notMeasured(String key, String reason) {
        return new MetricResult(
                key, "NOT_MEASURED", null,
                Map.of("reason", reason, "authoritative", false)
        );
    }

    private static CaseEvaluation blocked(
            CaseWork work,
            String code,
            List<String> missing
    ) {
        List<MetricResult> metrics = requiredHardMetrics(work.caseType())
                .stream()
                .map(key -> new MetricResult(
                        key,
                        "BLOCKED_PREREQUISITE",
                        null,
                        Map.of(
                                "reason", code,
                                "missingEvidenceKeys", missing
                        )
                ))
                .collect(ArrayList::new, List::add, List::addAll);
        metrics.add(new MetricResult(
                qualityMetric(work.caseType()),
                "BLOCKED_PREREQUISITE",
                null,
                Map.of("reason", code, "authoritative", false)
        ));
        metrics.add(new MetricResult(
                "phase11b.judge.advisory",
                "BLOCKED_PREREQUISITE",
                null,
                Map.of("reason", code, "authoritative", false)
        ));
        return new CaseEvaluation(
                "BLOCKED_PREREQUISITE",
                Map.of(
                        "evaluator", evaluatorVersion(work.caseType()),
                        "authority", "DETERMINISTIC",
                        "assuranceLevel", "PRODUCTION_PATH",
                        "qualityMeasured", false,
                        "missingEvidenceKeys", missing
                ),
                code,
                "Case 尚未映射到当前公开 Revision",
                List.copyOf(metrics)
        );
    }

    private static CaseEvaluation failed(CaseWork work, String code) {
        List<MetricResult> metrics = requiredHardMetrics(work.caseType())
                .stream()
                .map(key -> new MetricResult(
                        key, "NOT_MEASURED", null,
                        Map.of("reason", code)
                ))
                .collect(ArrayList::new, List::add, List::addAll);
        metrics.add(notMeasured(qualityMetric(work.caseType()), code));
        metrics.add(notMeasured("phase11b.judge.advisory", code));
        return new CaseEvaluation(
                "FAILED",
                Map.of(
                        "evaluator", evaluatorVersion(work.caseType()),
                        "authority", "DETERMINISTIC",
                        "assuranceLevel", "PRODUCTION_PATH",
                        "qualityMeasured", false
                ),
                code,
                "Real evaluation execution failed",
                List.copyOf(metrics)
        );
    }

    static Set<String> requiredHardMetrics(String caseType) {
        if ("MULTIFORMAT_RELEASE".equals(caseType)) {
            return Set.of(
                    "phase18d.hard.fact_integrity",
                    "phase18d.hard.current_revision_acl",
                    "phase18d.hard.parser_contract",
                    "phase18d.hard.locator_resolved",
                    "phase18d.hard.retrieval_resolved",
                    "phase18d.hard.citation_resolved",
                    "phase18d.hard.budget_respected",
                    "phase18d.hard.degradation_recorded",
                    "phase18d.hard.query_budget"
            );
        }
        if ("MULTIFORMAT_SECURITY".equals(caseType)) {
            return Set.of(
                    "phase18d.hard.security_attack_executed",
                    "phase18d.hard.security_attack_passed"
            );
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>(List.of(
                "phase11b.hard.authorization_recheck",
                "phase11b.hard.revision_recheck",
                "phase11b.hard.budget_respected",
                "phase11b.hard.degradation_recorded",
                "phase11b.hard.request_limit"
        ));
        if ("ANSWER_CITATION".equals(caseType)) {
            keys.add("phase11b.hard.citation_resolved");
            keys.add("phase11b.hard.refusal_contract");
        } else if ("MULTI_TURN".equals(caseType)) {
            keys.clear();
            keys.add("phase12c.hard.multi_turn_history");
            keys.add("phase12c.hard.query_budget");
            keys.add("phase12c.hard.multi_turn_citation");
        } else if ("INTENT".equals(caseType)) {
            keys.clear();
            keys.add("phase12c.hard.intent_route_unique");
        } else {
            keys.add(typeHardMetric(caseType));
        }
        return Set.copyOf(keys);
    }

    private static String typeHardMetric(String caseType) {
        return switch (caseType) {
            case "RETRIEVAL" -> "phase11b.hard.evidence_mapping";
            case "LOCAL_GRAPH" -> "phase11b.hard.graph_path_resolved";
            case "GLOBAL_GRAPH" -> "phase11b.hard.global_claim_resolved";
            case "MULTI_TURN" -> "phase12c.hard.multi_turn_history";
            case "INTENT" -> "phase12c.hard.intent_route_unique";
            default -> "phase11b.hard.unsupported";
        };
    }

    private static String qualityMetric(String caseType) {
        return switch (caseType) {
            case "RETRIEVAL" -> "phase11b.quality.retrieval";
            case "LOCAL_GRAPH" -> "phase11b.quality.local_graph";
            case "GLOBAL_GRAPH" -> "phase11b.quality.global_graph";
            case "ANSWER_CITATION" ->
                    "phase11b.quality.answer_citation";
            case "MULTI_TURN" -> "phase12c.quality.multi_turn";
            case "INTENT" -> "phase12c.quality.intent_route";
            case "MULTIFORMAT_RELEASE" ->
                    "phase18d.quality.expected_revision_cited";
            case "MULTIFORMAT_SECURITY" ->
                    "phase18d.quality.security_attack";
            default -> "phase11b.quality.unsupported";
        };
    }

    private static boolean hardPassed(
            String caseType,
            List<MetricResult> metrics
    ) {
        Map<String, MetricResult> actual = new LinkedHashMap<>();
        metrics.stream()
                .filter(metric ->
                        metric.key().startsWith("phase11b.hard.")
                                || metric.key().startsWith(
                                "phase12c.hard.")
                                || metric.key().startsWith(
                                "phase18d.hard."))
                .forEach(metric -> actual.put(metric.key(), metric));
        Set<String> expected = requiredHardMetrics(caseType);
        return actual.keySet().equals(expected)
                && actual.values().stream().allMatch(metric ->
                "MEASURED".equals(metric.status())
                        && Double.valueOf(1.0).equals(metric.value())
        );
    }

    private static Map<String, Object> baseOutput(
            TargetView target,
            String query,
            String requestedMode,
            String usedMode
    ) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evaluator", EvaluationService.REAL_EVALUATOR_VERSION);
        output.put("authority", "DETERMINISTIC");
        output.put("assuranceLevel", "PRODUCTION_PATH");
        output.put("targetId", target.id().toString());
        output.put("targetKey", target.targetKey());
        output.put("targetKind", target.targetKind());
        output.put("queryHash", sha256(query));
        output.put("graphModeRequested", requestedMode);
        output.put("graphModeUsed", usedMode);
        return output;
    }

    private static String evaluatorVersion(String caseType) {
        return "MULTIFORMAT_RELEASE".equals(caseType)
                ? EvaluationService.MULTIFORMAT_EVALUATOR_VERSION
                : EvaluationService.REAL_EVALUATOR_VERSION;
    }

    private static GraphMode graphMode(String subjectType) {
        return switch (SubjectType.valueOf(subjectType)) {
            case RETRIEVAL, ANSWER_CITATION, PARSER,
                    MULTIFORMAT_RELEASE -> GraphMode.HYBRID;
            case LOCAL_GRAPH -> GraphMode.LOCAL_GRAPH;
            case GLOBAL_GRAPH -> GraphMode.GLOBAL_GRAPH;
            case MULTI_TURN, INTENT -> GraphMode.AUTO;
        };
    }

    private static String requiredText(
            Map<String, Object> source,
            String key
    ) {
        String value = optionalText(source, key);
        if (value == null) {
            throw new IllegalStateException("Missing target field " + key);
        }
        return value;
    }

    private static String optionalText(
            Map<String, Object> source,
            String key
    ) {
        Object value = source.get(key);
        return value == null || value.toString().isBlank()
                ? null : value.toString();
    }

    private static long number(Map<String, Object> source, String key) {
        Long value = optionalNumber(source, key);
        if (value == null) {
            throw new IllegalStateException("Missing target field " + key);
        }
        return value;
    }

    private static Long optionalNumber(
            Map<String, Object> source,
            String key
    ) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(RealEvaluationExecutor::text)
                .filter(item -> !item.isBlank())
                .limit(4)
                .toList();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record EvaluationContext(
            TargetView target,
            UUID createdBy
    ) {
    }

    private record MultiformatFact(
            String documentFormat,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            String documentVisibility,
            long aclVersion,
            String fileSha256,
            String expectedParserProvider,
            String expectedParserVersion,
            String expectedChunkerVersion,
            String locatorKind,
            String sourceLabel,
            String locatorHash,
            boolean currentRevision,
            boolean currentAcl,
            boolean revisionReady,
            boolean searchable,
            String actualParserProvider,
            String actualParserVersion,
            String actualChunkerVersion,
            String actualLocatorKind,
            String actualSourceLabel,
            String actualLocatorJson,
            List<String> securityAssertions
    ) {
    }

    private record EvidenceRevision(
            UUID documentId,
            UUID revisionId
    ) {
    }
}
