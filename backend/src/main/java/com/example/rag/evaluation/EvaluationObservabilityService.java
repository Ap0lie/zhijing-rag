package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.GateView;
import com.example.rag.evaluation.EvaluationContracts.ObservabilityView;
import com.example.rag.evaluation.EvaluationContracts.WorkloadPermitView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class EvaluationObservabilityService {

    private static final int WINDOW_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final EvaluationProperties properties;
    private final EvaluationGovernanceService governance;
    private final EvaluationService evaluations;

    EvaluationObservabilityService(
            JdbcTemplate jdbc,
            EvaluationProperties properties,
            EvaluationGovernanceService governance,
            EvaluationService evaluations
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.governance = governance;
        this.evaluations = evaluations;
    }

    WorkloadPermitView workloadPermit() {
        long active = scalarLong(
                """
                SELECT COUNT(*)
                FROM chat_runs
                WHERE status IN ('PENDING', 'RUNNING')
                """
        );
        return new WorkloadPermitView(
                active > 0,
                active,
                active == 0,
                active == 0 ? null : "ONLINE_CHAT_RESERVED"
        );
    }

    boolean evaluationMayClaim() {
        return workloadPermit().evaluationMayClaim();
    }

    ObservabilityView observability() {
        WorkloadPermitView permit = workloadPermit();
        if (!properties.observabilityEnabled()) {
            return new ObservabilityView(
                    false, Instant.now(), WINDOW_HOURS,
                    false, false,
                    Math.toIntExact(properties.retention().toDays()),
                    permit, Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of()
            );
        }

        Map<String, Long> queues = new LinkedHashMap<>();
        queues.put("chatPending", countStatus("chat_runs", "PENDING"));
        queues.put("chatRunning", countStatus("chat_runs", "RUNNING"));
        queues.put(
                "evaluationPending",
                countStatus("evaluation_runs", "PENDING")
        );
        queues.put(
                "evaluationRunning",
                countStatus("evaluation_runs", "RUNNING")
        );
        queues.put("drillPending", countStatus("evaluation_drills", "PENDING"));
        queues.put("drillRunning", countStatus("evaluation_drills", "RUNNING"));
        queues.put("pipelinePending", countStatus("pipeline_jobs", "PENDING"));
        queues.put("pipelineRunning", countStatus("pipeline_jobs", "RUNNING"));

        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put(
                "chatFailure",
                ratio(
                        """
                        SELECT COUNT(*) FROM chat_runs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND status = 'FAILED'
                        """,
                        """
                        SELECT COUNT(*) FROM chat_runs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND status IN (
                            'COMPLETED', 'REFUSED', 'FAILED', 'CANCELLED'
                          )
                        """
                )
        );
        rates.put(
                "chatDegradation",
                ratio(
                        """
                        SELECT COUNT(*) FROM chat_runs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND graph_degraded = TRUE
                        """,
                        """
                        SELECT COUNT(*) FROM chat_runs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                        """
                )
        );
        rates.put(
                "evaluationFailure",
                ratio(
                        """
                        SELECT COUNT(*) FROM evaluation_runs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND status = 'FAILED'
                        """,
                        """
                        SELECT COUNT(*) FROM evaluation_runs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND status IN (
                            'SUCCEEDED', 'FAILED', 'CANCELLED',
                            'BLOCKED_PREREQUISITE'
                          )
                        """
                )
        );
        rates.put(
                "pipelineFailure",
                ratio(
                        """
                        SELECT COUNT(*) FROM pipeline_jobs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND status IN ('FAILED', 'QUARANTINED')
                        """,
                        """
                        SELECT COUNT(*) FROM pipeline_jobs
                        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                          AND status IN (
                            'SUCCEEDED', 'FAILED', 'QUARANTINED'
                          )
                        """
                )
        );

        Map<String, Double> p50 = new LinkedHashMap<>();
        Map<String, Double> p95 = new LinkedHashMap<>();
        addLatency(
                p50, p95, "chat",
                """
                SELECT EXTRACT(
                    EPOCH FROM (completed_at - started_at)
                ) * 1000
                FROM chat_runs
                WHERE completed_at IS NOT NULL
                  AND started_at IS NOT NULL
                  AND created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """
        );
        addLatency(
                p50, p95, "evaluationCase",
                """
                SELECT duration_ms::DOUBLE PRECISION
                FROM evaluation_case_results
                WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """
        );
        addLatency(
                p50, p95, "pipeline",
                """
                SELECT duration_ms::DOUBLE PRECISION
                FROM pipeline_jobs
                WHERE duration_ms IS NOT NULL
                  AND created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """
        );

        Map<String, Long> cache = new LinkedHashMap<>();
        cache.put("artifactCount", scalarLong(
                "SELECT COUNT(*) FROM embedding_artifacts"
        ));
        cache.put("bytes", scalarLong(
                "SELECT COALESCE(SUM(byte_size), 0) FROM embedding_artifacts"
        ));
        cache.put("usedWithin24h", scalarLong(
                """
                SELECT COUNT(*) FROM embedding_artifacts
                WHERE last_used_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """
        ));

        Map<String, Long> graph = new LinkedHashMap<>();
        graph.put("activeGeneration", scalarLong(
                """
                SELECT COALESCE(MAX(graph_generation), 0)
                FROM graph_publications
                WHERE singleton_id = 1
                """
        ));
        graph.put("activeProjectedDocuments", scalarLong(
                """
                SELECT COUNT(*)
                FROM graph_projection_states projection
                JOIN graph_publications publication
                  ON publication.graph_generation =
                     projection.graph_generation
                 AND publication.singleton_id = 1
                WHERE projection.state = 'PROJECTED'
                """
        ));
        graph.put("staleDocuments", scalarLong(
                """
                SELECT COUNT(*)
                FROM graph_projection_states projection
                JOIN graph_publications publication
                  ON publication.graph_generation =
                     projection.graph_generation
                 AND publication.singleton_id = 1
                JOIN documents document
                  ON document.id = projection.document_id
                WHERE document.deleted_at IS NOT NULL
                   OR document.current_revision_id IS DISTINCT FROM
                      projection.revision_id
                   OR document.acl_version <> projection.acl_version
                   OR projection.state <> 'PROJECTED'
                """
        ));

        return new ObservabilityView(
                true, Instant.now(), WINDOW_HOURS,
                false, false,
                Math.toIntExact(properties.retention().toDays()),
                permit, queues, rates, p50, p95, cache, graph
        );
    }

    List<GateView> gates() {
        List<GateView> views = new ArrayList<>();
        for (var baseline : governance.baselines()) {
            var run = evaluations.run(baseline.runId());
            if (!terminal(run.status())) {
                continue;
            }
            views.add(new GateView(
                    baseline.id(),
                    baseline.name(),
                    baseline.baselineKey(),
                    baseline.runId(),
                    run.status(),
                    baseline.gateStatus(),
                    baseline.published(),
                    blockers(baseline.gateSummary().get("blockers")),
                    baseline.metricSummary(),
                    baseline.createdAt()
            ));
        }
        return views;
    }

    private long countStatus(String table, String status) {
        return scalarLong(
                "SELECT COUNT(*) FROM " + table + " WHERE status = ?",
                status
        );
    }

    private double ratio(String numeratorSql, String denominatorSql) {
        long denominator = scalarLong(denominatorSql);
        return denominator == 0
                ? 0.0
                : (double) scalarLong(numeratorSql) / denominator;
    }

    private void addLatency(
            Map<String, Double> p50,
            Map<String, Double> p95,
            String key,
            String samplesSql
    ) {
        p50.put(key, percentile(samplesSql, 0.50));
        p95.put(key, percentile(samplesSql, 0.95));
    }

    private double percentile(String samplesSql, double percentile) {
        Double value = jdbc.queryForObject(
                """
                SELECT COALESCE(
                    percentile_cont(?) WITHIN GROUP (ORDER BY sample),
                    0
                )
                FROM (
                """ + samplesSql + "\n) samples(sample)",
                Double.class,
                percentile
        );
        return value == null ? 0.0 : value;
    }

    private long scalarLong(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static List<String> blockers(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static boolean terminal(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED",
                    "BLOCKED_PREREQUISITE" -> true;
            default -> false;
        };
    }
}
