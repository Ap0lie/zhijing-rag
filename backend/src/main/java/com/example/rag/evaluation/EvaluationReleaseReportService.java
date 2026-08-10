package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.FormatReleaseResultView;
import com.example.rag.evaluation.EvaluationContracts.ExecutionBaselineView;
import com.example.rag.evaluation.EvaluationContracts.PerformanceStatsView;
import com.example.rag.evaluation.EvaluationContracts.ReleaseReportView;
import com.example.rag.evaluation.EvaluationContracts.RunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class EvaluationReleaseReportService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EvaluationService evaluations;
    private final EvaluationGovernanceService governance;

    EvaluationReleaseReportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            EvaluationService evaluations,
            EvaluationGovernanceService governance
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.evaluations = evaluations;
        this.governance = governance;
    }

    ReleaseReportView report(UUID runId) {
        RunView run = evaluations.run(runId);
        if (!"MULTIFORMAT_RELEASE".equals(run.subjectType())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MULTIFORMAT_RELEASE_RUN_REQUIRED",
                    "发布收口报告只适用于多格式真实评测 Run"
            );
        }
        SubjectSnapshot subject = subject(run.evaluationSubjectId());
        List<ResultRow> results = results(run.id());
        Map<UUID, List<MetricRow>> metrics = metrics(run.id());
        ExecutionBaselineView execution = executionBaseline(
                run.id(), subject.snapshot()
        );

        List<FormatReleaseResultView> formats = results.stream()
                .map(result -> formatResult(
                        result,
                        metrics.getOrDefault(result.id(), List.of())
                ))
                .toList();
        int hardFailures = (int) formats.stream()
                .filter(result -> !result.hardGatePassed())
                .count();
        int degradationCount = (int) formats.stream()
                .filter(FormatReleaseResultView::degraded)
                .count();

        Map<String, PerformanceStatsView> performance = new LinkedHashMap<>();
        performance.put("case", stats(
                results.stream().map(ResultRow::durationMs).toList(),
                run.failedCases(), run.totalCases()
        ));
        performance.put("search", metricStats(
                metrics, "phase18d.performance.search_ms",
                run.failedCases(), run.totalCases()
        ));
        performance.put("chat", metricStats(
                metrics, "phase18d.performance.chat_ms",
                run.failedCases(), run.totalCases()
        ));

        List<String> blockers = strings(
                governance.gateSummary(run).get("blockers")
        );
        List<String> unmeasured = metrics.values().stream()
                .flatMap(List::stream)
                .filter(metric -> !"MEASURED".equals(metric.status()))
                .map(MetricRow::key)
                .distinct()
                .sorted()
                .toList();
        String recommendation = blockers.isEmpty()
                ? "READY_FOR_BASELINE"
                : "BLOCKED";

        return new ReleaseReportView(
                run.id(), run.status(), run.evaluatorVersion(),
                run.datasetVersion(), run.evaluationSubjectId(),
                subject.hash(), subject.snapshot(),
                run.totalCases(), run.succeededCases(), run.failedCases(),
                run.blockedCases(),
                rate(metrics, "phase18d.hard.locator_resolved"),
                rate(metrics, "phase18d.hard.citation_resolved"),
                hardFailures, degradationCount,
                execution,
                Map.copyOf(performance), formats,
                List.copyOf(blockers), unmeasured, recommendation
        );
    }

    private SubjectSnapshot subject(UUID subjectId) {
        return jdbc.queryForObject(
                """
                SELECT snapshot::text, snapshot_hash
                FROM evaluation_subjects
                WHERE id = ?
                """,
                (rs, row) -> new SubjectSnapshot(
                        objectMap(rs.getString("snapshot")),
                        rs.getString("snapshot_hash")
                ),
                subjectId
        );
    }

    private List<ResultRow> results(UUID runId) {
        return jdbc.query(
                """
                SELECT result.id, result.case_id, case_row.case_key,
                       case_row.case_type,
                       result.status, result.error_code, result.duration_ms,
                       COALESCE(result.output_data ->> 'documentFormat',
                                case_row.metadata ->> 'documentFormat',
                                case_row.expected_data ->> 'documentFormat')
                           AS document_format,
                       result.output_data ->> 'documentId' AS document_id,
                       result.output_data ->> 'revisionId' AS revision_id,
                       result.output_data ->> 'locatorKind' AS locator_kind,
                       result.output_data ->> 'sourceLabel' AS source_label,
                       COALESCE((result.output_data ->> 'degraded')::boolean,
                                false)
                         OR COALESCE(
                                (result.output_data ->> 'graphDegraded')::boolean,
                                false
                            )
                         OR COALESCE(
                                (result.output_data ->> 'queryDegraded')::boolean,
                                false
                            ) AS degraded,
                       COALESCE(result.output_data ->> 'degradationCode',
                                result.output_data ->> 'graphDegradationCode',
                                result.output_data ->> 'queryDegradationCode')
                           AS degradation_code
                FROM evaluation_case_results result
                JOIN evaluation_cases case_row ON case_row.id = result.case_id
                WHERE result.run_id = ?
                ORDER BY case_row.case_key, result.id
                """,
                (rs, row) -> new ResultRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        rs.getString("case_key"),
                        rs.getString("case_type"),
                        rs.getString("status"),
                        nullableUuid(rs.getString("document_id")),
                        nullableUuid(rs.getString("revision_id")),
                        rs.getString("document_format"),
                        rs.getString("locator_kind"),
                        rs.getString("source_label"),
                        rs.getBoolean("degraded"),
                        rs.getString("degradation_code"),
                        rs.getString("error_code"),
                        rs.getLong("duration_ms")
                ),
                runId
        );
    }

    private Map<UUID, List<MetricRow>> metrics(UUID runId) {
        Map<UUID, List<MetricRow>> rows = new LinkedHashMap<>();
        List<MetricJoinRow> joined = jdbc.query(
                """
                SELECT result.id AS result_id, metric.metric_key,
                       metric.status, metric.metric_value
                FROM evaluation_case_results result
                JOIN evaluation_metric_results metric
                  ON metric.case_result_id = result.id
                WHERE result.run_id = ?
                ORDER BY result.id, metric.metric_key
                """,
                (rs, row) -> new MetricJoinRow(
                        rs.getObject("result_id", UUID.class),
                        rs.getString("metric_key"),
                        rs.getString("status"),
                        rs.getObject("metric_value") == null
                                ? null : rs.getDouble("metric_value")
                ),
                runId
        );
        joined.forEach(metric -> rows.computeIfAbsent(
                metric.resultId(), ignored -> new ArrayList<>()
        ).add(new MetricRow(
                metric.key(), metric.status(), metric.value()
        )));
        return rows;
    }

    ExecutionBaselineView executionBaseline(
            UUID runId,
            Map<String, Object> subject
    ) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(MIN(NULLIF(
                           result.output_data ->> 'queryProfileVersion', ''
                       )), '')
                           AS query_profile_version,
                       COALESCE(SUM((result.output_data ->> 'plannerCallCount')::INTEGER), 0)
                           AS planner_calls,
                       COALESCE(SUM((result.output_data ->> 'retrievalCallCount')::INTEGER), 0)
                           AS retrieval_calls,
                       COALESCE(SUM((result.output_data ->> 'rerankCallCount')::INTEGER), 0)
                           AS rerank_calls,
                       COUNT(*) FILTER (
                           WHERE COALESCE(
                               (result.output_data ->> 'queryDegraded')::BOOLEAN,
                               FALSE
                           )
                       ) AS query_degraded,
                       COALESCE(SUM((result.output_data ->> 'memoryInjectedCount')::INTEGER), 0)
                           AS memory_injected,
                       COALESCE(SUM((result.output_data ->> 'memoryUsedCount')::INTEGER), 0)
                           AS memory_used,
                       COALESCE(SUM((result.output_data ->> 'memoryTokenCount')::INTEGER), 0)
                           AS memory_tokens
                FROM evaluation_case_results result
                WHERE result.run_id = ?
                """,
                (rs, row) -> new ExecutionBaselineView(
                        rs.getString("query_profile_version"),
                        rs.getInt("planner_calls"),
                        rs.getInt("retrieval_calls"),
                        rs.getInt("rerank_calls"),
                        rs.getInt("query_degraded"),
                        String.valueOf(subject.getOrDefault(
                                "memoryContractVersion", "unknown"
                        )),
                        rs.getInt("memory_injected"),
                        rs.getInt("memory_used"),
                        rs.getInt("memory_tokens")
                ),
                runId
        );
    }

    private FormatReleaseResultView formatResult(
            ResultRow result,
            List<MetricRow> metrics
    ) {
        Set<String> expected = RealEvaluationExecutor.requiredHardMetrics(
                result.caseType()
        );
        Map<String, MetricRow> hard = new LinkedHashMap<>();
        metrics.stream()
                .filter(metric -> metric.key().startsWith("phase18d.hard."))
                .forEach(metric -> hard.put(metric.key(), metric));
        boolean hardPassed = hard.keySet().equals(expected)
                && hard.values().stream().allMatch(metric ->
                "MEASURED".equals(metric.status())
                        && Double.valueOf(1.0).equals(metric.value())
        );
        boolean securityProbe = "MULTIFORMAT_SECURITY".equals(
                result.caseType()
        );
        return new FormatReleaseResultView(
                result.documentFormat(), result.caseId(), result.caseKey(),
                result.status(), result.documentId(), result.revisionId(),
                securityProbe ? "SECURITY_PROBE" : result.locatorKind(),
                securityProbe ? "攻击样本" : result.sourceLabel(),
                hardPassed,
                securityProbe || metricPassed(
                        metrics, "phase18d.hard.citation_resolved"
                ),
                result.degraded(), result.degradationCode(),
                result.errorCode(), result.durationMs()
        );
    }

    private static boolean metricPassed(
            List<MetricRow> metrics,
            String key
    ) {
        return metrics.stream().anyMatch(metric -> key.equals(metric.key())
                && "MEASURED".equals(metric.status())
                && Double.valueOf(1.0).equals(metric.value()));
    }

    private static Double rate(
            Map<UUID, List<MetricRow>> metrics,
            String key
    ) {
        List<MetricRow> values = metrics.values().stream()
                .flatMap(List::stream)
                .filter(metric -> key.equals(metric.key()))
                .filter(metric -> "MEASURED".equals(metric.status()))
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(metric -> Double.valueOf(1.0).equals(metric.value()))
                .count() / (double) values.size();
    }

    private static PerformanceStatsView metricStats(
            Map<UUID, List<MetricRow>> metrics,
            String key,
            int failures,
            int total
    ) {
        List<Long> values = metrics.values().stream()
                .flatMap(List::stream)
                .filter(metric -> key.equals(metric.key()))
                .filter(metric -> "MEASURED".equals(metric.status()))
                .map(MetricRow::value)
                .filter(java.util.Objects::nonNull)
                .map(Math::round)
                .toList();
        return stats(values, failures, total);
    }

    private static PerformanceStatsView stats(
            List<Long> values,
            int failures,
            int total
    ) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder())
                .toList();
        double errorRate = total == 0 ? 0 : failures / (double) total;
        if (sorted.isEmpty()) {
            return new PerformanceStatsView(
                    0, null, null, null, errorRate
            );
        }
        return new PerformanceStatsView(
                sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sorted.getLast().doubleValue(),
                errorRate
        );
    }

    private static double percentile(List<Long> values, double percentile) {
        int index = Math.max(
                0,
                (int) Math.ceil(percentile * values.size()) - 1
        );
        return values.get(index).doubleValue();
    }

    private Map<String, Object> objectMap(String value) {
        try {
            return objectMapper.readValue(
                    value,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid EvaluationSubject snapshot JSON", exception
            );
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(String::valueOf).toList();
    }

    private static UUID nullableUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private record SubjectSnapshot(
            Map<String, Object> snapshot,
            String hash
    ) {
    }

    private record ResultRow(
            UUID id,
            UUID caseId,
            String caseKey,
            String caseType,
            String status,
            UUID documentId,
            UUID revisionId,
            String documentFormat,
            String locatorKind,
            String sourceLabel,
            boolean degraded,
            String degradationCode,
            String errorCode,
            long durationMs
    ) {
    }

    private record MetricRow(
            String key,
            String status,
            Double value
    ) {
    }

    private record MetricJoinRow(
            UUID resultId,
            String key,
            String status,
            Double value
    ) {
    }
}
