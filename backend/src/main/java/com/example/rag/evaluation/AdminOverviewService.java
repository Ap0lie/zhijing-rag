package com.example.rag.evaluation;

import com.example.rag.document.DocumentFormatCapabilityService;
import com.example.rag.evaluation.AdminOverviewResponse.AttentionItem;
import com.example.rag.evaluation.AdminOverviewResponse.TaskDomain;
import com.example.rag.evaluation.AdminOverviewResponse.TaskLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
class AdminOverviewService {

    private final JdbcTemplate jdbc;
    private final EvaluationObservabilityService observability;
    private final DocumentFormatCapabilityService documentFormats;

    AdminOverviewService(
            JdbcTemplate jdbc,
            EvaluationObservabilityService observability,
            DocumentFormatCapabilityService documentFormats
    ) {
        this.jdbc = jdbc;
        this.observability = observability;
        this.documentFormats = documentFormats;
    }

    AdminOverviewResponse overview() {
        Instant capturedAt = Instant.now();
        List<AttentionItem> attention = new ArrayList<>();

        collect(attention, "PIPELINE_STATUS_UNAVAILABLE", "/admin/pipeline", () -> {
            addCount(
                    attention,
                    "PIPELINE_FAILED",
                    "失败任务",
                    "需要检查错误原因或人工重试。",
                    countPipeline("FAILED"),
                    "ERROR",
                    "ERROR",
                    "PIPELINE_FAILED",
                    "/admin/pipeline?status=FAILED"
            );
            addCount(
                    attention,
                    "PIPELINE_QUARANTINED",
                    "隔离任务",
                    "需要核对隔离原因并决定是否解除。",
                    countPipeline("QUARANTINED"),
                    "WARNING",
                    "BLOCKED",
                    "PIPELINE_QUARANTINED",
                    "/admin/pipeline?status=QUARANTINED"
            );
        }, capturedAt);

        collect(attention, "GRAPH_STATUS_UNAVAILABLE", "/admin/graph", () -> addCount(
                attention,
                "GRAPH_REBUILD_PENDING",
                "图谱投影待重建",
                "文档 Revision 或权限变化后，在线图谱会跳过这些 stale 文档。",
                count(
                        """
                        SELECT COUNT(*), MAX(requested_at)
                        FROM graph_rebuild_requests
                        WHERE state IN ('REQUESTED', 'BUILDING')
                        """
                ),
                "WARNING",
                "STALE",
                "GRAPH_PROJECTION_STALE",
                "/admin/graph"
        ), capturedAt);

        collect(attention, "PROVIDER_STATUS_UNAVAILABLE", "/admin/pipeline", () -> {
            long unavailable = documentFormats.capabilities().formats().stream()
                    .filter(format -> "ENABLED".equals(format.policyStatus()))
                    .filter(format -> !format.enabled())
                    .count();
            if (unavailable > 0) {
                attention.add(new AttentionItem(
                        "DOCUMENT_PROVIDER_UNAVAILABLE",
                        "文档解析能力不可用",
                        "已启用格式没有可用 Parser，请检查 Worker 心跳或运行策略。",
                        unavailable,
                        "ERROR",
                        "ERROR",
                        "DOCUMENT_PROVIDER_UNAVAILABLE",
                        capturedAt,
                        "/admin/pipeline"
                ));
            }
        }, capturedAt);

        collect(attention, "PUBLICATION_GATE_UNAVAILABLE", "/admin/evaluations", () -> {
            List<EvaluationContracts.GateView> blocked = observability.gates().stream()
                    .filter(gate -> "BLOCKED".equals(gate.gateStatus()) || !gate.blockers().isEmpty())
                    .toList();
            if (!blocked.isEmpty()) {
                Instant updatedAt = blocked.stream()
                        .map(EvaluationContracts.GateView::createdAt)
                        .max(Instant::compareTo)
                        .orElse(capturedAt);
                attention.add(new AttentionItem(
                        "PUBLICATION_GATE_BLOCKED",
                        "发布门禁阻断",
                        "候选基线存在未通过的硬门禁。",
                        (long) blocked.size(),
                        "WARNING",
                        "BLOCKED",
                        "PUBLICATION_GATE_BLOCKED",
                        updatedAt,
                        "/admin/evaluations?tab=baselines"
                ));
            }
        }, capturedAt);

        collect(attention, "FEEDBACK_STATUS_UNAVAILABLE", "/admin/evaluations", () -> addCount(
                attention,
                "FEEDBACK_PENDING_REVIEW",
                "反馈待审核",
                "用户已同意分享的脱敏反馈等待管理员处理。",
                count(
                        """
                        SELECT COUNT(*), MAX(feedback.created_at)
                        FROM evaluation_feedback feedback
                        LEFT JOIN evaluation_feedback_reviews review
                          ON review.feedback_id = feedback.id
                        WHERE feedback.consent_to_share = TRUE
                          AND review.id IS NULL
                        """
                ),
                "INFO",
                "VALUE",
                "FEEDBACK_PENDING_REVIEW",
                "/admin/evaluations?tab=feedback"
        ), capturedAt);

        return new AdminOverviewResponse(
                "admin-overview-v1",
                capturedAt,
                domains(),
                List.copyOf(attention)
        );
    }

    private CountSnapshot countPipeline(String status) {
        return jdbc.queryForObject(
                """
                WITH ranked_jobs AS (
                    SELECT job.*,
                           row_number() OVER (
                               PARTITION BY job.revision_id, job.stage
                               ORDER BY job.updated_at DESC, job.created_at DESC, job.id
                           ) AS stage_rank
                    FROM pipeline_jobs job
                    JOIN document_revisions revision ON revision.id = job.revision_id
                    JOIN documents document ON document.id = revision.document_id
                    WHERE document.current_revision_id = revision.id
                      AND document.deleted_at IS NULL
                ), current_jobs AS (
                    SELECT * FROM ranked_jobs WHERE stage_rank = 1
                ), revision_status AS (
                    SELECT revision_id,
                           MAX(updated_at) AS updated_at,
                           CASE
                               WHEN bool_or(status = 'RUNNING') THEN 'RUNNING'
                               WHEN bool_or(status = 'PENDING') THEN 'PENDING'
                               WHEN bool_or(status = 'QUARANTINED') THEN 'QUARANTINED'
                               WHEN bool_or(status = 'FAILED') THEN 'FAILED'
                               WHEN bool_or(status = 'CANCELLED') THEN 'CANCELLED'
                               ELSE 'SUCCEEDED'
                           END AS aggregate_status
                    FROM current_jobs
                    GROUP BY revision_id
                )
                SELECT COUNT(*), MAX(updated_at)
                FROM revision_status
                WHERE aggregate_status = ?
                """,
                (result, row) -> snapshot(result.getLong(1), result.getTimestamp(2)),
                status
        );
    }

    private CountSnapshot count(String sql) {
        return jdbc.queryForObject(
                sql,
                (result, row) -> snapshot(result.getLong(1), result.getTimestamp(2))
        );
    }

    private CountSnapshot snapshot(long count, Timestamp updatedAt) {
        return new CountSnapshot(count, updatedAt == null ? null : updatedAt.toInstant());
    }

    private void addCount(
            List<AttentionItem> attention,
            String code,
            String title,
            String description,
            CountSnapshot snapshot,
            String severity,
            String valueState,
            String reasonCode,
            String href
    ) {
        if (snapshot.count() == 0) {
            return;
        }
        attention.add(new AttentionItem(
                code,
                title,
                description,
                snapshot.count(),
                severity,
                valueState,
                reasonCode,
                snapshot.updatedAt(),
                href
        ));
    }

    private void collect(
            List<AttentionItem> attention,
            String errorCode,
            String href,
            Runnable collector,
            Instant capturedAt
    ) {
        try {
            collector.run();
        } catch (RuntimeException ignored) {
            attention.add(new AttentionItem(
                    errorCode,
                    "状态获取失败",
                    "该模块暂时无法汇总，请进入目标页面重试。",
                    null,
                    "ERROR",
                    "ERROR",
                    errorCode,
                    capturedAt,
                    href
            ));
        }
    }

    private List<TaskDomain> domains() {
        return List.of(
                new TaskDomain(
                        "OVERVIEW", "管理总览", "只看需要处理的事项。", "/admin", List.of()
                ),
                new TaskDomain(
                        "ACCESS_CONTENT", "访问与内容", "管理用户、文档和处理任务。", "/admin/users",
                        List.of(
                                new TaskLink("用户管理", "/admin/users"),
                                new TaskLink("操作日志", "/admin/audit"),
                                new TaskLink("Pipeline 任务", "/admin/pipeline"),
                                new TaskLink("文档中心", "/")
                        )
                ),
                new TaskDomain(
                        "RETRIEVAL_KNOWLEDGE", "检索与知识", "管理检索、图谱和查询策略。", "/admin/retrieval",
                        List.of(
                                new TaskLink("检索管理", "/admin/retrieval"),
                                new TaskLink("知识图谱", "/admin/graph"),
                                new TaskLink("查询智能", "/admin/query-intelligence")
                        )
                ),
                new TaskDomain(
                        "QUALITY_OPERATIONS", "质量与运维", "评测质量、发布门禁和运行状态。", "/admin/evaluations",
                        List.of(new TaskLink("评测中心", "/admin/evaluations"))
                )
        );
    }

    private record CountSnapshot(long count, Instant updatedAt) {
    }
}
