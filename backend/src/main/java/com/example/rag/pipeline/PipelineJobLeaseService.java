package com.example.rag.pipeline;

import com.example.rag.persistence.PipelineStage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PipelineJobLeaseService {

    private final JdbcTemplate jdbc;
    private final PipelineProperties properties;

    public PipelineJobLeaseService(JdbcTemplate jdbc, PipelineProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public Optional<ClaimedJob> claimNext() {
        closeExpiredFinalAttempts();
        List<ClaimedJob> claimed = jdbc.query(
                """
                WITH candidate AS (
                    SELECT job.id
                    FROM pipeline_jobs job
                    JOIN document_revisions revision ON revision.id = job.revision_id
                    JOIN documents document ON document.id = revision.document_id
                    JOIN document_runtime_policies format_policy
                      ON format_policy.policy_key = 'FORMAT:' || revision.document_format
                     AND format_policy.status = 'ENABLED'
                    LEFT JOIN document_runtime_policies parser_policy
                      ON parser_policy.policy_key = 'PARSER:' || revision.document_format
                          || ':' || COALESCE(
                              job.parser_provider,
                              NULLIF(job.parser_requested_engine, 'AUTO')
                          )
                    WHERE job.stage = ?
                      AND job.pipeline_version = ?
                      AND job.attempt < job.max_attempts
                      AND (
                        job.status = 'PENDING'
                        OR (job.status = 'RUNNING' AND job.lease_expires_at < CURRENT_TIMESTAMP)
                      )
                      AND (
                        (job.stage = 'PARSE' AND revision.status IN ('UPLOADED', 'PROCESSING'))
                        OR (job.stage = 'INDEX' AND revision.status = 'READY')
                      )
                      AND document.deleted_at IS NULL
                      AND (
                        (job.stage = 'PARSE'
                         AND job.parser_provider IS NULL
                         AND (job.parser_requested_engine IS NULL
                              OR job.parser_requested_engine = 'AUTO'))
                        OR parser_policy.status = 'ENABLED'
                      )
                    ORDER BY job.created_at, job.id
                    FOR UPDATE OF job, revision, document SKIP LOCKED
                    LIMIT 1
                )
                UPDATE pipeline_jobs job
                SET status = 'RUNNING',
                    attempt = job.attempt + 1,
                    lease_owner = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = CURRENT_TIMESTAMP,
                    completed_at = NULL,
                    duration_ms = NULL,
                    error_code = NULL,
                    error_message = NULL,
                    quarantine_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.revision_id, job.stage, job.attempt, job.max_attempts,
                          job.parser_requested_engine
                """,
                (resultSet, rowNumber) -> task(resultSet),
                properties.workerStage().name(),
                properties.pipelineVersion(),
                properties.workerId(),
                properties.leaseDuration().toMillis()
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedJob job = claimed.getFirst();
        if (job.stage() == PipelineStage.PARSE) {
            int revisionUpdated = jdbc.update(
                    """
                    UPDATE document_revisions
                    SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status IN ('UPLOADED', 'PROCESSING')
                    """,
                    job.revisionId()
            );
            if (revisionUpdated != 1) {
                throw new IllegalStateException("Claimed revision is no longer processable");
            }
        }
        return Optional.of(loadSource(job));
    }

    public boolean heartbeat(UUID jobId, int attempt) {
        return jdbc.update(
                """
                UPDATE pipeline_jobs
                SET heartbeat_at = CURRENT_TIMESTAMP,
                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                  AND EXISTS (
                    SELECT 1
                    FROM document_revisions revision
                    JOIN documents document ON document.id = revision.document_id
                    JOIN document_runtime_policies format_policy
                      ON format_policy.policy_key = 'FORMAT:' || revision.document_format
                     AND format_policy.status = 'ENABLED'
                    LEFT JOIN document_runtime_policies parser_policy
                      ON parser_policy.policy_key = 'PARSER:' || revision.document_format
                         || ':' || COALESCE(
                             pipeline_jobs.parser_provider,
                             NULLIF(pipeline_jobs.parser_requested_engine, 'AUTO')
                         )
                    WHERE revision.id = pipeline_jobs.revision_id
                      AND (
                        (pipeline_jobs.stage = 'PARSE' AND revision.status = 'PROCESSING')
                        OR (pipeline_jobs.stage = 'INDEX' AND revision.status = 'READY')
                      )
                      AND document.deleted_at IS NULL
                      AND (
                        (pipeline_jobs.stage = 'PARSE'
                         AND pipeline_jobs.parser_provider IS NULL
                         AND (pipeline_jobs.parser_requested_engine IS NULL
                              OR pipeline_jobs.parser_requested_engine = 'AUTO'))
                        OR parser_policy.status = 'ENABLED'
                      )
                  )
                """,
                properties.leaseDuration().toMillis(),
                jobId,
                properties.workerId(),
                attempt
        ) == 1;
    }

    public boolean lockOwned(UUID jobId, int attempt) {
        return !jdbc.query(
                """
                UPDATE pipeline_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                  AND EXISTS (
                    SELECT 1
                    FROM document_revisions revision
                    JOIN documents document ON document.id = revision.document_id
                    JOIN document_runtime_policies format_policy
                      ON format_policy.policy_key = 'FORMAT:' || revision.document_format
                     AND format_policy.status = 'ENABLED'
                    LEFT JOIN document_runtime_policies parser_policy
                      ON parser_policy.policy_key = 'PARSER:' || revision.document_format
                         || ':' || COALESCE(
                             pipeline_jobs.parser_provider,
                             NULLIF(pipeline_jobs.parser_requested_engine, 'AUTO')
                         )
                    WHERE revision.id = pipeline_jobs.revision_id
                      AND (
                        (pipeline_jobs.stage = 'PARSE' AND revision.status = 'PROCESSING')
                        OR (pipeline_jobs.stage = 'INDEX' AND revision.status = 'READY')
                      )
                      AND document.deleted_at IS NULL
                      AND (
                        (pipeline_jobs.stage = 'PARSE'
                         AND pipeline_jobs.parser_provider IS NULL
                         AND (pipeline_jobs.parser_requested_engine IS NULL
                              OR pipeline_jobs.parser_requested_engine = 'AUTO'))
                        OR parser_policy.status = 'ENABLED'
                      )
                  )
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                properties.taskTimeout().toMillis(),
                jobId,
                properties.workerId(),
                attempt
        ).isEmpty();
    }

    public boolean markSucceeded(UUID jobId, int attempt) {
        return finish(jobId, attempt, "SUCCEEDED", null, null, null);
    }

    public boolean recordParserDecision(UUID jobId, int attempt, ParserDecision decision) {
        return jdbc.update(
                """
                UPDATE pipeline_jobs
                SET parser_provider = ?,
                    parser_decision_code = ?,
                    parser_provider_version = ?,
                    parser_source_unit_count = ?,
                    parser_scanned_candidate = ?,
                    parser_ocr_required = ?,
                    parser_multicolumn_candidate = ?,
                    parser_table_candidate = ?,
                    parser_image_candidate = ?,
                    parser_model_revision = ?,
                    parser_model_manifest_checksum = ?,
                    parser_decided_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND stage = 'PARSE'
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                decision.selectedProvider().name(),
                decision.code(),
                decision.providerVersion(),
                decision.sourceUnitCount(),
                decision.scannedCandidate(),
                decision.ocrRequired(),
                decision.multicolumnCandidate(),
                decision.tableCandidate(),
                decision.imageCandidate(),
                decision.modelRevision(),
                decision.modelManifestChecksum(),
                jobId,
                properties.workerId(),
                attempt
        ) == 1;
    }

    @Transactional
    public boolean quarantine(UUID jobId, int attempt, String code, String reason) {
        boolean updated = finish(jobId, attempt, "QUARANTINED", code, reason, reason);
        if (updated) {
            updateRevisionStatus(jobId, "QUARANTINED");
        }
        return updated;
    }

    @Transactional
    public boolean failOrRetry(UUID jobId, int attempt, String code, String message) {
        Integer remaining = jdbc.query(
                """
                SELECT max_attempts - attempt
                FROM pipeline_jobs
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                FOR UPDATE
                """,
                resultSet -> resultSet.next() ? resultSet.getInt(1) : null,
                jobId,
                properties.workerId(),
                attempt
        );
        if (remaining == null) {
            return false;
        }
        if (remaining > 0) {
            return jdbc.update(
                    """
                    UPDATE pipeline_jobs
                    SET status = 'PENDING',
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        started_at = NULL,
                        error_code = ?,
                        error_message = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND attempt = ?
                    """,
                    code,
                    concise(message, 2000),
                    jobId,
                    properties.workerId(),
                    attempt
            ) == 1;
        }
        boolean updated = finish(jobId, attempt, "FAILED", code, message, null);
        if (updated) {
            updateRevisionStatus(jobId, "FAILED");
        }
        return updated;
    }

    @Transactional
    public boolean failTerminal(UUID jobId, int attempt, String code, String message) {
        boolean updated = finish(jobId, attempt, "FAILED", code, message, null);
        if (updated) {
            updateRevisionStatus(jobId, "FAILED");
        }
        return updated;
    }

    private boolean finish(
            UUID jobId,
            int attempt,
            String status,
            String errorCode,
            String errorMessage,
            String quarantineReason
    ) {
        return jdbc.update(
                """
                UPDATE pipeline_jobs
                SET status = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    duration_ms = GREATEST(
                        0,
                        (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000)::BIGINT
                    ),
                    error_code = ?,
                    error_message = ?,
                    quarantine_reason = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                status,
                errorCode,
                concise(errorMessage, 2000),
                concise(quarantineReason, 512),
                jobId,
                properties.workerId(),
                attempt
        ) == 1;
    }

    private void closeExpiredFinalAttempts() {
        List<UUID> jobs = jdbc.queryForList(
                """
                SELECT id
                FROM pipeline_jobs
                WHERE status = 'RUNNING'
                  AND stage = ?
                  AND pipeline_version = ?
                  AND lease_expires_at < CURRENT_TIMESTAMP
                  AND attempt >= max_attempts
                ORDER BY lease_expires_at
                FOR UPDATE SKIP LOCKED
                LIMIT 50
                """,
                UUID.class,
                properties.workerStage().name(),
                properties.pipelineVersion()
        );
        for (UUID jobId : jobs) {
            jdbc.update(
                    """
                    UPDATE pipeline_jobs
                    SET status = 'FAILED',
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        completed_at = CURRENT_TIMESTAMP,
                        duration_ms = GREATEST(
                            0,
                            (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000)::BIGINT
                        ),
                        error_code = 'LEASE_EXPIRED',
                        error_message = 'Worker lease expired after the final attempt',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'RUNNING'
                    """,
                    jobId
            );
            updateRevisionStatus(jobId, "FAILED");
        }
    }

    private void updateRevisionStatus(UUID jobId, String status) {
        jdbc.update(
                """
                UPDATE document_revisions revision
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                FROM pipeline_jobs job
                WHERE job.id = ?
                  AND revision.id = job.revision_id
                  AND job.stage = 'PARSE'
                  AND revision.status = 'PROCESSING'
                """,
                status,
                jobId
        );
    }

    private ClaimedJob loadSource(ClaimedJob claimed) {
        return jdbc.queryForObject(
                """
                SELECT revision.document_id, revision.source_object_key
                FROM document_revisions revision
                WHERE revision.id = ?
                """,
                (resultSet, rowNumber) -> new ClaimedJob(
                        claimed.id(),
                        claimed.revisionId(),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("source_object_key"),
                        claimed.stage(),
                        claimed.attempt(),
                        claimed.maxAttempts(),
                        claimed.requestedParser()
                ),
                claimed.revisionId()
        );
    }

    private static ClaimedJob task(ResultSet resultSet) throws SQLException {
        String requestedParser = resultSet.getString("parser_requested_engine");
        return new ClaimedJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("revision_id", UUID.class),
                null,
                null,
                PipelineStage.valueOf(resultSet.getString("stage")),
                resultSet.getInt("attempt"),
                resultSet.getInt("max_attempts"),
                requestedParser == null ? ParserEngine.AUTO : ParserEngine.valueOf(requestedParser)
        );
    }

    private static String concise(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    public record ClaimedJob(
            UUID id,
            UUID revisionId,
            UUID documentId,
            String sourceObjectKey,
            PipelineStage stage,
            int attempt,
            int maxAttempts,
            ParserEngine requestedParser
    ) {
        public ClaimedJob(
                UUID id,
                UUID revisionId,
                UUID documentId,
                String sourceObjectKey,
                PipelineStage stage,
                int attempt,
                int maxAttempts
        ) {
            this(id, revisionId, documentId, sourceObjectKey, stage, attempt, maxAttempts, ParserEngine.AUTO);
        }
    }

    public record ParserDecision(
            ParserProviderKind selectedProvider,
            String code,
            String providerVersion,
            int sourceUnitCount,
            boolean scannedCandidate,
            boolean ocrRequired,
            boolean multicolumnCandidate,
            boolean tableCandidate,
            boolean imageCandidate,
            String modelRevision,
            String modelManifestChecksum
    ) {
    }
}
