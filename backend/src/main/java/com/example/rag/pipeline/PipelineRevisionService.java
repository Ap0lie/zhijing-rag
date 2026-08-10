package com.example.rag.pipeline;

import com.example.rag.common.ApiException;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.PipelineJobStatus;
import com.example.rag.persistence.PipelineStage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.rag.pipeline.PipelineRevisionContracts.AttentionCounts;
import static com.example.rag.pipeline.PipelineRevisionContracts.DownstreamProjection;
import static com.example.rag.pipeline.PipelineRevisionContracts.JobAttempt;
import static com.example.rag.pipeline.PipelineRevisionContracts.ProjectionState;
import static com.example.rag.pipeline.PipelineRevisionContracts.RevisionPage;
import static com.example.rag.pipeline.PipelineRevisionContracts.RevisionSummary;
import static com.example.rag.pipeline.PipelineRevisionContracts.StageFact;

@Service
public class PipelineRevisionService {

    private static final List<PipelineStage> MAIN_STAGES = List.of(
            PipelineStage.INGEST,
            PipelineStage.PARSE,
            PipelineStage.CHUNK,
            PipelineStage.EMBED,
            PipelineStage.INDEX
    );

    private final NamedParameterJdbcTemplate jdbc;

    public PipelineRevisionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RevisionPage list(
            boolean attention,
            String status,
            PipelineStage stage,
            DocumentFormat format,
            ParserProviderKind parser,
            String documentQuery,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        String normalizedStatus = normalizeStatus(status);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("attention", attention)
                .addValue("status", normalizedStatus)
                .addValue("stage", stage == null ? null : stage.name())
                .addValue("format", format == null ? null : format.name())
                .addValue("parser", parser == null ? null : parser.name())
                .addValue("documentQuery", normalizeQuery(documentQuery))
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", safeSize)
                .addValue("offset", safePage * safeSize);

        String cte = rollupCte();
        String where = filterWhere();
        long total = nullableLong(cte + " SELECT count(*) FROM rollup r " + where, parameters);
        List<RevisionRow> rows = jdbc.query(
                cte + """
                        SELECT r.*
                        FROM rollup r
                        """ + where + """
                        ORDER BY r.last_updated_at DESC, r.revision_id
                        LIMIT :limit OFFSET :offset
                        """,
                parameters,
                PipelineRevisionService::mapRevisionRow
        );
        List<RevisionSummary> items = summaries(rows);
        return new RevisionPage(
                items,
                safePage,
                safeSize,
                total,
                (int) Math.ceil((double) total / safeSize),
                counts()
        );
    }

    @Transactional(readOnly = true)
    public RevisionSummary get(UUID documentId, UUID revisionId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("revisionId", revisionId);
        List<RevisionRow> rows = jdbc.query(
                rollupCte() + """
                        SELECT r.* FROM rollup r
                        WHERE r.document_id = :documentId AND r.revision_id = :revisionId
                        """,
                parameters,
                PipelineRevisionService::mapRevisionRow
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PIPELINE_REVISION_NOT_FOUND",
                    "没有找到该 Revision 的 Pipeline 事实"
            );
        }
        return summaries(rows).getFirst();
    }

    private List<RevisionSummary> summaries(List<RevisionRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> revisionIds = rows.stream().map(RevisionRow::revisionId).toList();
        MapSqlParameterSource parameters = new MapSqlParameterSource("revisionIds", revisionIds);
        Map<UUID, List<JobAttempt>> jobsByRevision = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT id, revision_id, stage, status, attempt, max_attempts,
                       parser_provider, parser_decision_code, lease_owner,
                       lease_expires_at, heartbeat_at, error_code, error_message,
                       quarantine_reason, started_at, completed_at, duration_ms,
                       created_at, updated_at
                FROM pipeline_jobs
                WHERE revision_id IN (:revisionIds)
                ORDER BY revision_id, created_at, id
                """,
                parameters,
                (RowCallbackHandler) rs -> jobsByRevision.computeIfAbsent(
                        rs.getObject("revision_id", UUID.class), ignored -> new ArrayList<>()
                ).add(mapJob(rs))
        );
        Map<UUID, Long> chunkCounts = countByRevision(
                "SELECT revision_id, count(*) total FROM chunks WHERE revision_id IN (:revisionIds) GROUP BY revision_id",
                parameters
        );
        ProjectionSnapshot projections = projections(revisionIds);
        return rows.stream()
                .map(row -> summary(
                        row,
                        jobsByRevision.getOrDefault(row.revisionId(), List.of()),
                        chunkCounts.getOrDefault(row.revisionId(), 0L),
                        projections
                ))
                .toList();
    }

    private RevisionSummary summary(
            RevisionRow row,
            List<JobAttempt> jobs,
            long chunkCount,
            ProjectionSnapshot projections
    ) {
        Map<PipelineStage, JobAttempt> latest = new EnumMap<>(PipelineStage.class);
        jobs.forEach(job -> latest.merge(
                job.stage(), job,
                (left, right) -> left.updatedAt().isAfter(right.updatedAt()) ? left : right
        ));
        String aggregateStatus = aggregateStatus(latest.values());
        PipelineStage currentStage = currentStage(latest, chunkCount, projections.index(row));
        JobAttempt actionable = row.operationalRevision()
                ? latest.values().stream()
                        .filter(job -> job.status() == PipelineJobStatus.QUARANTINED
                                || job.status() == PipelineJobStatus.FAILED
                                || job.status() == PipelineJobStatus.CANCELLED)
                        .max(Comparator.comparing(JobAttempt::updatedAt))
                        .orElse(null)
                : null;
        boolean exhausted = actionable != null && actionable.automaticRetryExhausted();
        String nextActionCode = row.operationalRevision()
                ? nextActionCode(actionable, aggregateStatus)
                : "HISTORICAL";
        String isolationCode = actionable != null && actionable.status() == PipelineJobStatus.QUARANTINED
                ? actionable.errorCode() : null;
        String isolationReason = actionable != null && actionable.status() == PipelineJobStatus.QUARANTINED
                ? actionable.quarantineReason() : null;
        String parser = jobs.stream()
                .filter(job -> job.parserProvider() != null)
                .max(Comparator.comparing(JobAttempt::updatedAt))
                .map(JobAttempt::parserProvider)
                .orElse(null);
        return new RevisionSummary(
                row.documentId(), row.revisionId(), row.revisionNumber(), row.documentTitle(),
                row.documentFormat(), row.revisionStatus(), row.currentRevision(), aggregateStatus,
                currentStage, row.lastUpdatedAt(), nextActionCode, nextActionLabel(nextActionCode),
                exhausted, isolationCode, isolationReason, parser,
                stageFacts(row, latest, chunkCount, projections.index(row)),
                List.copyOf(jobs),
                new DownstreamProjection(
                        projections.index(row), projections.graph(row), projections.global(row)
                )
        );
    }

    private static List<StageFact> stageFacts(
            RevisionRow row,
            Map<PipelineStage, JobAttempt> latest,
            long chunkCount,
            ProjectionState indexProjection
    ) {
        List<StageFact> result = new ArrayList<>();
        for (PipelineStage stage : MAIN_STAGES) {
            JobAttempt job = latest.get(stage);
            if (job != null) {
                result.add(new StageFact(stage, job.status().name(), "JOB", job.updatedAt()));
                continue;
            }
            if (stage == PipelineStage.INGEST) {
                result.add(new StageFact(stage, "SUCCEEDED", "REVISION", row.revisionCreatedAt()));
            } else if (stage == PipelineStage.CHUNK && chunkCount > 0) {
                result.add(new StageFact(stage, "SUCCEEDED", "CHUNK_FACT", row.lastUpdatedAt()));
            } else if (stage == PipelineStage.EMBED && "ACTIVE".equals(indexProjection.status())) {
                result.add(new StageFact(stage, "SUCCEEDED", "INDEX_PROJECTION", row.lastUpdatedAt()));
            } else {
                result.add(new StageFact(stage, "NOT_AVAILABLE", "DERIVED", null));
            }
        }
        return List.copyOf(result);
    }

    private ProjectionSnapshot projections(List<UUID> revisionIds) {
        Long indexGeneration = queryLongOrNull("SELECT index_generation FROM index_manifests WHERE status = 'ACTIVE'", new MapSqlParameterSource());
        Long graphGeneration = queryLongOrNull("SELECT graph_generation FROM graph_publications WHERE singleton_id = 1", new MapSqlParameterSource());
        Long globalGeneration = queryLongOrNull("SELECT global_generation FROM global_graph_publications WHERE singleton_id = 1", new MapSqlParameterSource());
        Map<UUID, StateRow> index = new HashMap<>();
        Map<UUID, StateRow> graph = new HashMap<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource("revisionIds", revisionIds);
        if (indexGeneration != null) {
            parameters.addValue("indexGeneration", indexGeneration);
            jdbc.query(
                    """
                    SELECT revision_id, state FROM search_projection_states
                    WHERE index_generation = :indexGeneration AND revision_id IN (:revisionIds)
                    """,
                    parameters,
                    (RowCallbackHandler) rs -> index.put(rs.getObject("revision_id", UUID.class), new StateRow(rs.getString("state"), null))
            );
        }
        if (graphGeneration != null) {
            parameters.addValue("graphGeneration", graphGeneration);
            jdbc.query(
                    """
                    SELECT revision_id, state FROM graph_projection_states
                    WHERE graph_generation = :graphGeneration AND revision_id IN (:revisionIds)
                    """,
                    parameters,
                    (RowCallbackHandler) rs -> graph.put(rs.getObject("revision_id", UUID.class), new StateRow(rs.getString("state"), null))
            );
        }
        return new ProjectionSnapshot(indexGeneration, graphGeneration, globalGeneration, index, graph);
    }

    private AttentionCounts counts() {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String cte = rollupCte();
        return new AttentionCounts(
                nullableLong(cte + " SELECT count(*) FROM rollup WHERE operational_revision AND aggregate_status IN ('FAILED','QUARANTINED')", parameters),
                nullableLong(cte + " SELECT count(*) FROM rollup WHERE operational_revision AND aggregate_status = 'FAILED'", parameters),
                nullableLong(cte + " SELECT count(*) FROM rollup WHERE operational_revision AND aggregate_status = 'QUARANTINED'", parameters),
                nullableLong(cte + " SELECT count(*) FROM rollup WHERE operational_revision AND aggregate_status IN ('RUNNING','PENDING')", parameters),
                nullableLong(cte + " SELECT count(*) FROM rollup WHERE operational_revision AND aggregate_status = 'SUCCEEDED'", parameters)
        );
    }

    private Map<UUID, Long> countByRevision(String sql, MapSqlParameterSource parameters) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(sql, parameters, (RowCallbackHandler) rs -> result.put(
                rs.getObject("revision_id", UUID.class), rs.getLong("total")
        ));
        return result;
    }

    private long nullableLong(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.query(sql, parameters, rs -> rs.next() ? rs.getLong(1) : null);
        return value == null ? 0 : value;
    }

    private Long queryLongOrNull(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, rs -> rs.next() ? rs.getObject(1, Long.class) : null);
    }

    private static String rollupCte() {
        return """
                WITH ranked_jobs AS (
                    SELECT job.*,
                           row_number() OVER (
                               PARTITION BY job.revision_id, job.stage
                               ORDER BY job.updated_at DESC, job.created_at DESC, job.id
                           ) AS stage_rank
                    FROM pipeline_jobs job
                ), current_jobs AS (
                    SELECT * FROM ranked_jobs WHERE stage_rank = 1
                ), rollup AS (
                    SELECT document.id AS document_id,
                           revision.id AS revision_id,
                           revision.revision_number,
                           document.title AS document_title,
                           revision.document_format,
                           revision.status AS revision_status,
                           document.current_revision_id = revision.id AS current_revision,
                           (
                               document.current_revision_id = revision.id
                               OR revision.revision_number = (
                                   SELECT max(latest.revision_number)
                                   FROM document_revisions latest
                                   WHERE latest.document_id = document.id
                               )
                           ) AS operational_revision,
                           document.visibility,
                           revision.created_at AS revision_created_at,
                           GREATEST(revision.updated_at, max(job.updated_at)) AS last_updated_at,
                           CASE
                               WHEN bool_or(job.status = 'RUNNING') THEN 'RUNNING'
                               WHEN bool_or(job.status = 'PENDING') THEN 'PENDING'
                               WHEN bool_or(job.status = 'QUARANTINED') THEN 'QUARANTINED'
                               WHEN bool_or(job.status = 'FAILED') THEN 'FAILED'
                               WHEN bool_or(job.status = 'CANCELLED') THEN 'CANCELLED'
                               ELSE 'SUCCEEDED'
                           END AS aggregate_status,
                           bool_or(job.status = 'QUARANTINED') AS has_quarantine,
                           bool_or(job.status = 'FAILED') AS has_failure,
                           bool_or(job.status IN ('PENDING','RUNNING')) AS has_active,
                           string_agg(DISTINCT job.stage, ',') AS stages,
                           string_agg(DISTINCT COALESCE(job.parser_provider, ''), ',') AS parsers
                    FROM current_jobs job
                    JOIN document_revisions revision ON revision.id = job.revision_id
                    JOIN documents document ON document.id = revision.document_id
                    WHERE document.deleted_at IS NULL
                    GROUP BY document.id, revision.id
                )
                """;
    }

    private static String filterWhere() {
        return """
                WHERE (:attention = FALSE OR (r.operational_revision AND r.aggregate_status IN ('FAILED','QUARANTINED')))
                  AND (CAST(:status AS VARCHAR) IS NULL OR (r.operational_revision AND r.aggregate_status = CAST(:status AS VARCHAR)))
                  AND (CAST(:stage AS VARCHAR) IS NULL OR CAST(:stage AS VARCHAR) = ANY(string_to_array(r.stages, ',')))
                  AND (CAST(:format AS VARCHAR) IS NULL OR r.document_format = CAST(:format AS VARCHAR))
                  AND (CAST(:parser AS VARCHAR) IS NULL OR CAST(:parser AS VARCHAR) = ANY(string_to_array(r.parsers, ',')))
                  AND (CAST(:documentQuery AS VARCHAR) IS NULL OR lower(r.document_title) LIKE CAST(:documentQuery AS VARCHAR))
                  AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR r.last_updated_at >= CAST(:from AS TIMESTAMPTZ))
                  AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR r.last_updated_at <= CAST(:to AS TIMESTAMPTZ))
                """;
    }

    private static RevisionRow mapRevisionRow(ResultSet rs, int row) throws SQLException {
        return new RevisionRow(
                rs.getObject("document_id", UUID.class),
                rs.getObject("revision_id", UUID.class),
                rs.getInt("revision_number"),
                rs.getString("document_title"),
                DocumentFormat.valueOf(rs.getString("document_format")),
                rs.getString("revision_status"),
                rs.getBoolean("current_revision"),
                rs.getBoolean("operational_revision"),
                rs.getString("visibility"),
                rs.getTimestamp("revision_created_at").toInstant(),
                rs.getTimestamp("last_updated_at").toInstant()
        );
    }

    private static JobAttempt mapJob(ResultSet rs) throws SQLException {
        PipelineJobStatus status = PipelineJobStatus.valueOf(rs.getString("status"));
        int attempt = rs.getInt("attempt");
        int maxAttempts = rs.getInt("max_attempts");
        String errorCode = rs.getString("error_code");
        return new JobAttempt(
                rs.getObject("id", UUID.class),
                PipelineStage.valueOf(rs.getString("stage")),
                status,
                attempt,
                maxAttempts,
                rs.getString("parser_provider"),
                rs.getString("parser_decision_code"),
                rs.getString("lease_owner"),
                instant(rs, "lease_expires_at"),
                instant(rs, "heartbeat_at"),
                errorCode,
                rs.getString("error_message"),
                rs.getString("quarantine_reason"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getObject("duration_ms", Long.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                (status == PipelineJobStatus.FAILED || status == PipelineJobStatus.QUARANTINED)
                        && attempt >= maxAttempts,
                manualAction(status, errorCode)
        );
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static String manualAction(PipelineJobStatus status, String errorCode) {
        if (status == PipelineJobStatus.QUARANTINED) {
            return switch (errorCode == null ? "" : errorCode) {
                case "SCANNED_PDF", "GIBBERISH_TEXT", "LOW_QUALITY_TEXT", "MINERU_REQUIRED" -> "SWITCH_PARSER";
                case "GPU_PROFILE_CONFLICT", "PARSER_PROVIDER_DISABLED", "PARSER_PROVIDER_UNAVAILABLE" -> "CHECK_PROVIDER";
                default -> "CREATE_REPARSE_REVISION";
            };
        }
        return status == PipelineJobStatus.FAILED || status == PipelineJobStatus.CANCELLED
                ? "MANUAL_REQUEUE" : null;
    }

    private static String aggregateStatus(Iterable<JobAttempt> jobs) {
        boolean pending = false;
        boolean quarantined = false;
        boolean failed = false;
        boolean cancelled = false;
        for (JobAttempt job : jobs) {
            if (job.status() == PipelineJobStatus.RUNNING) return "RUNNING";
            pending |= job.status() == PipelineJobStatus.PENDING;
            quarantined |= job.status() == PipelineJobStatus.QUARANTINED;
            failed |= job.status() == PipelineJobStatus.FAILED;
            cancelled |= job.status() == PipelineJobStatus.CANCELLED;
        }
        if (pending) return "PENDING";
        if (quarantined) return "QUARANTINED";
        if (failed) return "FAILED";
        if (cancelled) return "CANCELLED";
        return "SUCCEEDED";
    }

    private static PipelineStage currentStage(
            Map<PipelineStage, JobAttempt> latest,
            long chunkCount,
            ProjectionState index
    ) {
        for (int i = MAIN_STAGES.size() - 1; i >= 0; i--) {
            PipelineStage stage = MAIN_STAGES.get(i);
            if (latest.containsKey(stage)) return stage;
            if (stage == PipelineStage.EMBED && "ACTIVE".equals(index.status())) return stage;
            if (stage == PipelineStage.CHUNK && chunkCount > 0) return stage;
        }
        return PipelineStage.INGEST;
    }

    private static String nextActionCode(JobAttempt actionable, String aggregateStatus) {
        if (actionable != null) return actionable.manualActionCode();
        return switch (aggregateStatus) {
            case "RUNNING", "PENDING" -> "WAIT_WORKER";
            case "SUCCEEDED" -> "NONE";
            default -> "REVIEW";
        };
    }

    private static String nextActionLabel(String code) {
        return switch (code == null ? "" : code) {
            case "MANUAL_REQUEUE" -> "人工重新排队";
            case "SWITCH_PARSER" -> "切换 Parser";
            case "CHECK_PROVIDER" -> "检查 Provider";
            case "CREATE_REPARSE_REVISION" -> "创建重解析 Revision";
            case "WAIT_WORKER" -> "等待 Worker";
            case "NONE" -> "已完成";
            case "HISTORICAL" -> "历史记录，无需处理";
            default -> "检查详情";
        };
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        try {
            PipelineJobStatus.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PIPELINE_STATUS_INVALID", "Pipeline 状态筛选无效");
        }
    }

    private static String normalizeQuery(String value) {
        if (value == null || value.isBlank()) return null;
        return "%" + value.trim().toLowerCase() + "%";
    }

    private record RevisionRow(
            UUID documentId,
            UUID revisionId,
            int revisionNumber,
            String documentTitle,
            DocumentFormat documentFormat,
            String revisionStatus,
            boolean currentRevision,
            boolean operationalRevision,
            String visibility,
            Instant revisionCreatedAt,
            Instant lastUpdatedAt
    ) {
    }

    private record StateRow(String status, String reasonCode) {
    }

    private record ProjectionSnapshot(
            Long indexGeneration,
            Long graphGeneration,
            Long globalGeneration,
            Map<UUID, StateRow> indexStates,
            Map<UUID, StateRow> graphStates
    ) {
        ProjectionState index(RevisionRow row) {
            return projection("INDEX", indexGeneration, indexStates.get(row.revisionId()), "INDEX_NOT_PROJECTED");
        }

        ProjectionState graph(RevisionRow row) {
            return projection("GRAPH", graphGeneration, graphStates.get(row.revisionId()), "GRAPH_STALE");
        }

        ProjectionState global(RevisionRow row) {
            if (!"ALL_USERS".equals(row.visibility())) {
                return new ProjectionState("GLOBAL", globalGeneration, "NOT_APPLICABLE", "RESTRICTED_SOURCE");
            }
            StateRow graph = graphStates.get(row.revisionId());
            if (globalGeneration == null || graph == null || !"PROJECTED".equals(graph.status())) {
                return new ProjectionState("GLOBAL", globalGeneration, "STALE", "GLOBAL_SOURCE_NOT_READY");
            }
            return new ProjectionState("GLOBAL", globalGeneration, "ELIGIBLE", null);
        }

        private static ProjectionState projection(
                String kind,
                Long generation,
                StateRow state,
                String missingCode
        ) {
            if (generation == null) return new ProjectionState(kind, null, "NOT_AVAILABLE", kind + "_NOT_PUBLISHED");
            if (state == null) return new ProjectionState(kind, generation, "STALE", missingCode);
            String status = "INDEX".equals(kind) && "ACTIVE".equals(state.status())
                    ? "ACTIVE" : state.status();
            return new ProjectionState(kind, generation, status, state.reasonCode());
        }
    }
}
