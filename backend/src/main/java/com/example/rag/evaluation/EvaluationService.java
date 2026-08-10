package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.CaseMappingView;
import com.example.rag.evaluation.EvaluationContracts.CaseEvaluation;
import com.example.rag.evaluation.EvaluationContracts.CaseSeed;
import com.example.rag.evaluation.EvaluationContracts.CaseWork;
import com.example.rag.evaluation.EvaluationContracts.ClaimedRun;
import com.example.rag.evaluation.EvaluationContracts.DatasetSeed;
import com.example.rag.evaluation.EvaluationContracts.DatasetVersionView;
import com.example.rag.evaluation.EvaluationContracts.DatasetView;
import com.example.rag.evaluation.EvaluationContracts.MappingPage;
import com.example.rag.evaluation.EvaluationContracts.MetricResult;
import com.example.rag.evaluation.EvaluationContracts.MetricView;
import com.example.rag.evaluation.EvaluationContracts.ResultPage;
import com.example.rag.evaluation.EvaluationContracts.ResultView;
import com.example.rag.evaluation.EvaluationContracts.RunEventView;
import com.example.rag.evaluation.EvaluationContracts.RunPage;
import com.example.rag.evaluation.EvaluationContracts.RunView;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.evaluation.EvaluationContracts.SubjectView;
import com.example.rag.evaluation.EvaluationContracts.TargetView;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EvaluationService {

    static final String SMOKE_EVALUATOR_VERSION =
            "phase11b-contract-smoke-v2";
    private static final String LEGACY_REAL_EVALUATOR_VERSION =
            "phase12c-real-query-intelligence-v1";
    private static final String LEGACY_REAL_EVALUATOR_VERSION_V2 =
            "phase12c-real-query-intelligence-v2";
    static final String REAL_EVALUATOR_VERSION =
            "phase12c-real-query-intelligence-v3";
    static final String MULTIFORMAT_EVALUATOR_VERSION =
            "phase18d-real-multiformat-v4";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EvaluationProperties properties;
    private final EvaluationTargetService targets;
    private final RealEvaluationExecutor realEvaluator;
    private final TransactionTemplate transactions;

    public EvaluationService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            EvaluationProperties properties,
            EvaluationTargetService targets,
            RealEvaluationExecutor realEvaluator,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.targets = targets;
        this.realEvaluator = realEvaluator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public void seed(DatasetSeed dataset, List<CaseSeed> cases) {
        jdbc.update(
                """
                INSERT INTO evaluation_datasets (
                    id, dataset_key, title, description
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (dataset_key) DO NOTHING
                """,
                dataset.datasetId(), dataset.datasetKey(),
                dataset.title(), dataset.description()
        );
        requireSeededDataset(dataset);
        jdbc.update(
                """
                INSERT INTO evaluation_dataset_versions (
                    id, dataset_id, version, schema_version, case_type,
                    source_revision, source_license, source_sha256,
                    source_manifest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (dataset_id, version) DO NOTHING
                """,
                dataset.versionId(), dataset.datasetId(), dataset.version(),
                dataset.schemaVersion(), dataset.caseType(), dataset.sourceRevision(),
                dataset.sourceLicense(), dataset.sourceSha256(),
                dataset.sourceManifest()
        );
        requireSeededVersion(dataset);
        for (CaseSeed seed : cases) {
            if (!seededCaseExists(seed)) {
                jdbc.update(
                        """
                        INSERT INTO evaluation_cases (
                            id, dataset_version_id, case_key, language, case_type,
                            input_data, expected_data, mapping_status,
                            mapping_requirements, metadata
                        ) VALUES (
                            ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?,
                            CAST(? AS JSONB), CAST(? AS JSONB)
                        )
                        """,
                        seed.id(), seed.datasetVersionId(), seed.key(),
                        seed.language(), seed.caseType(), seed.inputData(),
                        seed.expectedData(), seed.mappingStatus(),
                        seed.mappingRequirements(), seed.metadata()
                );
            }
            requireSeededCase(seed);
        }
        Integer storedCases = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_cases
                WHERE dataset_version_id = ?
                """,
                Integer.class, dataset.versionId()
        );
        if (storedCases == null || storedCases != cases.size()) {
            throw new IllegalStateException(
                    "Evaluation DatasetVersion case set changed: "
                            + dataset.version()
            );
        }
    }

    public List<DatasetView> datasets() {
        List<DatasetView> datasets = jdbc.query(
                """
                SELECT id, dataset_key, title, description
                FROM evaluation_datasets
                ORDER BY dataset_key
                """,
                (rs, row) -> new DatasetView(
                        rs.getObject("id", UUID.class),
                        rs.getString("dataset_key"),
                        rs.getString("title"),
                        rs.getString("description"),
                        new ArrayList<>()
                )
        );
        return datasets.stream().map(dataset -> new DatasetView(
                dataset.id(), dataset.key(), dataset.title(), dataset.description(),
                versions(dataset.id())
        )).toList();
    }

    private List<DatasetVersionView> versions(UUID datasetId) {
        return jdbc.query(
                """
                SELECT version.id, version.version, version.schema_version,
                       version.case_type, version.source_revision,
                       version.source_license, version.source_sha256,
                       version.created_at,
                       COUNT(case_row.id) AS case_count,
                       COUNT(*) FILTER (
                           WHERE case_row.mapping_status = 'MAPPED'
                       ) AS mapped_cases,
                       COUNT(*) FILTER (
                           WHERE case_row.mapping_status = 'UNMAPPED'
                       ) AS unmapped_cases,
                       COUNT(*) FILTER (
                           WHERE case_row.mapping_status = 'NOT_REQUIRED'
                       ) AS not_required_cases,
                       COUNT(*) FILTER (
                           WHERE case_row.mapping_status = 'READY'
                       ) AS ready_cases,
                       COUNT(*) FILTER (
                           WHERE case_row.mapping_status =
                                 'BLOCKED_PREREQUISITE'
                       ) AS blocked_prerequisite_cases
                FROM evaluation_dataset_versions version
                LEFT JOIN evaluation_cases case_row
                  ON case_row.dataset_version_id = version.id
                WHERE version.dataset_id = ?
                GROUP BY version.id
                ORDER BY version.created_at DESC, version.id DESC
                """,
                (rs, row) -> new DatasetVersionView(
                        rs.getObject("id", UUID.class),
                        rs.getString("version"),
                        rs.getString("schema_version"),
                        rs.getString("case_type"),
                        rs.getString("source_revision"),
                        rs.getString("source_license"),
                        rs.getString("source_sha256"),
                        rs.getInt("case_count"),
                        rs.getInt("mapped_cases"),
                        rs.getInt("unmapped_cases"),
                        rs.getInt("not_required_cases"),
                        rs.getInt("ready_cases"),
                        rs.getInt("blocked_prerequisite_cases"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                datasetId
        );
    }

    public MappingPage mappings(UUID versionId, int page, int size) {
        requireVersion(versionId);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_cases WHERE dataset_version_id = ?",
                Long.class, versionId
        );
        List<CaseMappingView> items = jdbc.query(
                """
                SELECT case_row.id, case_row.case_key, case_row.language,
                       case_row.mapping_status,
                       case_row.mapping_requirements::TEXT,
                       fact.document_format, fact.original_filename,
                       fact.file_sha256, fact.source_license,
                       fact.document_id, fact.revision_id,
                       fact.child_chunk_id, fact.source_span_id,
                       fact.locator_kind, fact.source_label,
                       fact.locator_hash,
                       CASE
                         WHEN fact.case_id IS NULL THEN NULL
                         WHEN document.deleted_at IS NULL
                          AND document.current_revision_id = fact.revision_id
                          AND revision.status = 'READY'
                          AND revision.content_hash = fact.file_sha256
                          AND child.chunk_type = 'CHILD'
                          AND span.source_text_hash = fact.source_text_hash
                          AND locator.locator_kind = fact.locator_kind
                          AND locator.source_label = fact.source_label
                         THEN TRUE
                         ELSE FALSE
                       END AS multiformat_current
                FROM evaluation_cases case_row
                LEFT JOIN evaluation_multiformat_case_facts fact
                  ON fact.case_id = case_row.id
                LEFT JOIN documents document
                  ON document.id = fact.document_id
                LEFT JOIN document_revisions revision
                  ON revision.id = fact.revision_id
                 AND revision.document_id = fact.document_id
                LEFT JOIN chunks child
                  ON child.id = fact.child_chunk_id
                 AND child.document_id = fact.document_id
                 AND child.revision_id = fact.revision_id
                LEFT JOIN source_spans span
                  ON span.id = fact.source_span_id
                 AND span.chunk_id = fact.child_chunk_id
                 AND span.document_id = fact.document_id
                 AND span.revision_id = fact.revision_id
                LEFT JOIN source_locator_projection locator
                  ON locator.source_kind = 'SOURCE_SPAN'
                 AND locator.source_id = fact.source_span_id
                WHERE case_row.dataset_version_id = ?
                ORDER BY case_row.created_at, case_row.id
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> {
                    UUID caseId = rs.getObject("id", UUID.class);
                    List<String> requirements = strings(
                            rs.getString("mapping_requirements")
                    );
                    List<String> missing = missingEvidence(requirements);
                    String stored = rs.getString("mapping_status");
                    Boolean current = rs.getObject(
                            "multiformat_current", Boolean.class
                    );
                    String effective = current != null
                            ? current ? "READY" : "BLOCKED_PREREQUISITE"
                            : "NOT_REQUIRED".equals(stored)
                            ? stored
                            : missing.isEmpty() && !requirements.isEmpty()
                            ? "MAPPED" : "UNMAPPED";
                    return new CaseMappingView(
                            caseId, rs.getString("case_key"),
                            rs.getString("language"), stored, effective, missing,
                            rs.getString("document_format"),
                            rs.getString("original_filename"),
                            rs.getString("file_sha256"),
                            rs.getString("source_license"),
                            rs.getObject("document_id", UUID.class),
                            rs.getObject("revision_id", UUID.class),
                            rs.getObject("child_chunk_id", UUID.class),
                            rs.getObject("source_span_id", UUID.class),
                            rs.getString("locator_kind"),
                            rs.getString("source_label"),
                            rs.getString("locator_hash"),
                            current != null && !current
                                    ? "CURRENT_REVISION_OR_LOCATOR_CHANGED"
                                    : null
                    );
                },
                versionId, size, (long) page * size
        );
        return new MappingPage(versionId, page, size, total, items);
    }

    @Transactional
    public SubjectView createSubject(
            String name,
            UUID targetId,
            PlatformUserPrincipal user
    ) {
        UUID id = UUID.randomUUID();
        TargetView target = targets.target(targetId);
        if (target.subjectType() == SubjectType.MULTIFORMAT_RELEASE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MULTIFORMAT_RELEASE_FREEZE_REQUIRED",
                    "多格式发布 Subject 必须从 Phase 18A 冻结流程创建"
            );
        }
        jdbc.update(
                """
                INSERT INTO evaluation_subjects (
                    id, name, subject_type, snapshot, snapshot_hash,
                    readiness_status, blocked_reason, created_by, target_id
                ) VALUES (?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
                """,
                id, name.strip(), target.subjectType().name(),
                json(target.snapshot()), target.snapshotHash(),
                target.readinessStatus(), target.blockedReason(),
                actor(user), target.id()
        );
        return subject(id);
    }

    @Transactional
    SubjectView freezeMultiformatSubject(
            UUID datasetVersionId,
            String datasetVersion,
            String datasetSourceSha256,
            List<Map<String, Object>> formatCoverage,
            TargetView target,
            PlatformUserPrincipal user
    ) {
        if (target.subjectType() != SubjectType.MULTIFORMAT_RELEASE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MULTIFORMAT_TARGET_REQUIRED",
                    "Phase 18A 必须使用多格式发布 Target"
            );
        }
        requireVersion(datasetVersionId);
        Map<String, Object> snapshot =
                new LinkedHashMap<>(target.snapshot());
        snapshot.put("datasetVersionId", datasetVersionId.toString());
        snapshot.put("datasetVersion", datasetVersion);
        snapshot.put("datasetSourceSha256", datasetSourceSha256);
        snapshot.put("formatCoverage", formatCoverage);
        String payload = json(snapshot);
        String hash = sha256(payload);
        UUID id = UUID.nameUUIDFromBytes((
                "evaluation-subject:multiformat-release:"
                        + datasetVersionId + ":" + target.snapshotHash()
        ).getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                """
                INSERT INTO evaluation_subjects (
                    id, name, subject_type, snapshot, snapshot_hash,
                    readiness_status, blocked_reason, created_by,
                    target_id, dataset_version_id
                ) VALUES (
                    ?, ?, 'MULTIFORMAT_RELEASE',
                    CAST(? AS JSONB), ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT (id) DO NOTHING
                """,
                id, "多格式发布 · " + datasetVersion,
                payload, hash, target.readinessStatus(),
                target.blockedReason(), actor(user), target.id(),
                datasetVersionId
        );
        SubjectView stored = subject(id);
        if (!hash.equals(stored.snapshotHash())
                || !datasetVersionId.equals(stored.datasetVersionId())
                || stored.subjectType() != SubjectType.MULTIFORMAT_RELEASE) {
            throw new IllegalStateException(
                    "Multiformat EvaluationSubject definition changed"
            );
        }
        return stored;
    }

    public List<TargetView> targets() {
        return targets.targets();
    }

    public List<SubjectView> subjects() {
        return jdbc.query(
                """
                SELECT subject.id, subject.name, subject.subject_type,
                       subject.target_id, target.target_key,
                       target.target_kind, subject.dataset_version_id,
                       version.version AS dataset_version,
                       subject.snapshot::TEXT,
                       subject.snapshot_hash, subject.readiness_status,
                       subject.blocked_reason, subject.created_at
                FROM evaluation_subjects subject
                LEFT JOIN evaluation_targets target
                  ON target.id = subject.target_id
                LEFT JOIN evaluation_dataset_versions version
                  ON version.id = subject.dataset_version_id
                ORDER BY subject.created_at DESC, subject.id DESC
                """,
                this::subjectRow
        );
    }

    @Transactional
    public RunView createRun(
            UUID subjectId,
            String datasetVersion,
            String idempotencyKey,
            PlatformUserPrincipal user
    ) {
        UUID actor = actor(user);
        SubjectView subject = subject(subjectId);
        VersionIdentity version = version(datasetVersion);
        if (subject.datasetVersionId() != null
                && !subject.datasetVersionId().equals(version.id())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "EVALUATION_SUBJECT_DATASET_MISMATCH",
                    "DatasetVersion 与冻结的 EvaluationSubject 不一致"
            );
        }
        if (!subject.subjectType().name().equals(version.caseType())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "EVALUATION_TYPE_MISMATCH",
                    "Dataset 类型与 EvaluationSubject 不匹配"
            );
        }
        RunView existing = runByIdempotency(actor, idempotencyKey);
        if (existing != null) {
            return requireSameRunRequest(
                    existing, subjectId, version.id(), null
            );
        }
        UUID runId = UUID.randomUUID();
        int total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_cases WHERE dataset_version_id = ?",
                Integer.class, version.id()
        );
        String initialStatus = "READY".equals(subject.readinessStatus())
                ? "PENDING" : "BLOCKED_PREREQUISITE";
        int blocked = "BLOCKED_PREREQUISITE".equals(initialStatus) ? total : 0;
        int completed = blocked;
        int inserted = jdbc.update(
                """
                INSERT INTO evaluation_runs (
                    id, evaluation_subject_id, dataset_version_id,
                    status, evaluator_version, idempotency_key, requested_by,
                    total_cases, completed_cases, blocked_cases, max_attempts,
                    error_code, error_message, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'BLOCKED_PREREQUISITE'
                         THEN CURRENT_TIMESTAMP ELSE NULL END)
                ON CONFLICT (requested_by, idempotency_key) DO NOTHING
                """,
                runId, subjectId, version.id(), initialStatus,
                evaluatorVersion(subject),
                idempotencyKey, actor, total,
                completed, blocked, properties.maxAttempts(),
                "BLOCKED_PREREQUISITE".equals(initialStatus)
                        ? "SUBJECT_NOT_READY" : null,
                "BLOCKED_PREREQUISITE".equals(initialStatus)
                        ? subject.blockedReason() : null,
                initialStatus
        );
        if (inserted == 0) {
            return requireSameRunRequest(
                    runByIdempotency(actor, idempotencyKey),
                    subjectId, version.id(), null
            );
        }
        appendEvent(
                runId,
                "BLOCKED_PREREQUISITE".equals(initialStatus)
                        ? "BLOCKED_PREREQUISITE" : "CREATED",
                null, initialStatus,
                Map.of("datasetVersion", datasetVersion)
        );
        return run(runId);
    }

    public RunPage runs(int page, int size) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_runs", Long.class
        );
        List<RunView> items = jdbc.query(
                runSelect() + " ORDER BY run.created_at DESC, run.id DESC LIMIT ? OFFSET ?",
                this::runRow, size, (long) page * size
        );
        return new RunPage(page, size, total, items);
    }

    public RunView run(UUID id) {
        List<RunView> rows = jdbc.query(
                runSelect() + " WHERE run.id = ?",
                this::runRow, id
        );
        if (rows.isEmpty()) {
            throw notFound("Evaluation Run 不存在");
        }
        return rows.getFirst();
    }

    public List<RunEventView> events(UUID runId) {
        run(runId);
        return jdbc.query(
                """
                SELECT id, sequence, event_type, from_status, to_status,
                       details::TEXT, created_at
                FROM evaluation_run_events
                WHERE run_id = ?
                ORDER BY sequence
                """,
                (rs, row) -> new RunEventView(
                        rs.getLong("id"),
                        rs.getInt("sequence"),
                        rs.getString("event_type"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        objectMap(rs.getString("details")),
                        rs.getTimestamp("created_at").toInstant()
                ),
                runId
        );
    }

    public ResultPage results(UUID runId, int page, int size) {
        run(runId);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_case_results WHERE run_id = ?",
                Long.class, runId
        );
        List<ResultView> items = jdbc.query(
                """
                SELECT result.id, result.case_id, case_row.case_key,
                       case_row.language, case_row.case_type, result.status,
                       result.output_data::TEXT, result.error_code,
                       result.error_message, result.duration_ms,
                       result.created_at
                FROM evaluation_case_results result
                JOIN evaluation_cases case_row ON case_row.id = result.case_id
                WHERE result.run_id = ?
                ORDER BY result.created_at, result.id
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new ResultView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        rs.getString("case_key"),
                        rs.getString("language"),
                        rs.getString("case_type"),
                        rs.getString("status"),
                        objectMap(rs.getString("output_data")),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        rs.getLong("duration_ms"),
                        rs.getTimestamp("created_at").toInstant(),
                        metrics(rs.getObject("id", UUID.class))
                ),
                runId, size, (long) page * size
        );
        return new ResultPage(runId, page, size, total, items);
    }

    private List<MetricView> metrics(UUID resultId) {
        return jdbc.query(
                """
                SELECT metric_key, status, metric_value, details::TEXT
                FROM evaluation_metric_results
                WHERE case_result_id = ?
                ORDER BY metric_key
                """,
                (rs, row) -> new MetricView(
                        rs.getString("metric_key"),
                        rs.getString("status"),
                        rs.getObject("metric_value") == null
                                ? null : rs.getDouble("metric_value"),
                        objectMap(rs.getString("details"))
                ),
                resultId
        );
    }

    @Transactional
    public RunView cancel(
            UUID runId,
            String reason,
            PlatformUserPrincipal user
    ) {
        actor(user);
        RunView current = lockRun(runId);
        if (terminal(current.status())) {
            return current;
        }
        if ("PENDING".equals(current.status())) {
            jdbc.update(
                    """
                    UPDATE evaluation_runs
                    SET status = 'CANCELLED', cancel_requested = TRUE,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    runId
            );
            appendEvent(
                    runId, "CANCELLED", "PENDING", "CANCELLED",
                    Map.of("reason", reason.strip())
            );
        } else {
            int cancelled = jdbc.update(
                    """
                    UPDATE evaluation_runs
                    SET status = 'CANCELLED', cancel_requested = TRUE,
                        lease_owner = NULL, lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'RUNNING'
                      AND (
                        lease_expires_at IS NULL
                        OR lease_expires_at <= CURRENT_TIMESTAMP
                      )
                    """,
                    runId
            );
            if (cancelled == 1) {
                appendEvent(
                        runId, "CANCELLED", "RUNNING", "CANCELLED",
                        Map.of(
                                "reason", reason.strip(),
                                "leaseExpired", true
                        )
                );
                return run(runId);
            }
            jdbc.update(
                    """
                    UPDATE evaluation_runs
                    SET cancel_requested = TRUE, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    runId
            );
            appendEvent(
                    runId, "CANCEL_REQUESTED", "RUNNING", "RUNNING",
                    Map.of("reason", reason.strip())
            );
        }
        return run(runId);
    }

    @Transactional
    public RunView retry(
            UUID runId,
            String reason,
            String idempotencyKey,
            PlatformUserPrincipal user
    ) {
        RunView source = lockRun(runId);
        if (!terminal(source.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "RUN_NOT_TERMINAL",
                    "只能重试已结束的 Evaluation Run"
            );
        }
        if ("BLOCKED_PREREQUISITE".equals(source.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RUN_PREREQUISITE_BLOCKED",
                    "前置条件未满足，需重新创建 EvaluationSubject"
            );
        }
        UUID requestedBy = actor(user);
        String retryKey = "retry:" + runId + ":" + idempotencyKey;
        RunView existing = runByIdempotency(requestedBy, retryKey);
        if (existing != null) {
            return requireSameRunRequest(
                    existing, source.evaluationSubjectId(),
                    source.datasetVersionId(), runId
            );
        }
        UUID newId = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                INSERT INTO evaluation_runs (
                    id, evaluation_subject_id, dataset_version_id,
                    original_run_id, status, evaluator_version,
                    idempotency_key, requested_by, total_cases, max_attempts
                ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
                ON CONFLICT (requested_by, idempotency_key) DO NOTHING
                """,
                newId, source.evaluationSubjectId(),
                source.datasetVersionId(), runId, source.evaluatorVersion(),
                retryKey, requestedBy, source.totalCases(),
                properties.maxAttempts()
        );
        if (inserted == 0) {
            return requireSameRunRequest(
                    runByIdempotency(requestedBy, retryKey),
                    source.evaluationSubjectId(),
                    source.datasetVersionId(), runId
            );
        }
        appendEvent(
                newId, "RETRIED", null, "PENDING",
                Map.of(
                        "originalRunId", runId.toString(),
                        "reason", reason.strip()
                )
        );
        return run(newId);
    }

    @Transactional
    public Optional<ClaimedRun> claim() {
        cancelExpiredRuns();
        failExhaustedExpiredRuns();
        List<ClaimedRun> claimed = jdbc.query(
                """
                WITH candidate AS (
                    SELECT run.id, subject.subject_type
                    FROM evaluation_runs run
                    JOIN evaluation_subjects subject
                      ON subject.id = run.evaluation_subject_id
                    WHERE (
                        run.status = 'PENDING'
                        OR (
                            run.status = 'RUNNING'
                            AND run.lease_expires_at < CURRENT_TIMESTAMP
                        )
                    )
                      AND run.cancel_requested = FALSE
                      AND run.attempt < run.max_attempts
                    ORDER BY run.created_at, run.id
                    FOR UPDATE OF run SKIP LOCKED
                    LIMIT 1
                )
                UPDATE evaluation_runs run
                SET status = 'RUNNING',
                    attempt = run.attempt + 1,
                    lease_owner = ?,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = COALESCE(run.started_at, CURRENT_TIMESTAMP),
                    error_code = NULL,
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE run.id = candidate.id
                RETURNING run.id, run.evaluation_subject_id,
                          run.dataset_version_id, candidate.subject_type,
                          run.evaluator_version, run.attempt
                """,
                (rs, row) -> new ClaimedRun(
                        rs.getObject("id", UUID.class),
                        rs.getObject("evaluation_subject_id", UUID.class),
                        rs.getObject("dataset_version_id", UUID.class),
                        rs.getString("subject_type"),
                        rs.getString("evaluator_version"),
                        rs.getInt("attempt")
                ),
                properties.workerId(), properties.leaseDuration().toMillis()
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedRun run = claimed.getFirst();
        appendEvent(
                run.id(),
                run.attempt() > 1 ? "LEASE_RECOVERED" : "CLAIMED",
                run.attempt() > 1 ? "RUNNING" : "PENDING",
                "RUNNING",
                Map.of("attempt", run.attempt())
        );
        return Optional.of(run);
    }

    public boolean heartbeat(ClaimedRun run) {
        return jdbc.update(
                """
                UPDATE evaluation_runs
                SET lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                properties.leaseDuration().toMillis(), run.id(),
                properties.workerId(), run.attempt()
        ) == 1;
    }

    @Transactional
    public boolean yieldToOnlineChat(ClaimedRun run) {
        if (!owns(run)) {
            return false;
        }
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'PENDING',
                    attempt = GREATEST(attempt - 1, 0),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                run.id()
        );
        appendEvent(
                run.id(),
                "YIELDED_TO_CHAT",
                "RUNNING",
                "PENDING",
                Map.of("reason", "ONLINE_CHAT_RESERVED")
        );
        return true;
    }

    public Optional<CaseWork> nextCase(ClaimedRun run) {
        List<CaseWork> cases = jdbc.query(
                """
                SELECT case_row.id, case_row.case_key, case_row.language,
                       case_row.case_type, case_row.mapping_status,
                       case_row.mapping_requirements::TEXT,
                       case_row.input_data::TEXT,
                       case_row.expected_data::TEXT,
                       case_row.metadata::TEXT
                FROM evaluation_cases case_row
                WHERE case_row.dataset_version_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM evaluation_case_results result
                    WHERE result.run_id = ?
                      AND result.case_id = case_row.id
                      AND result.evaluator_version = ?
                  )
                ORDER BY case_row.created_at, case_row.id
                LIMIT 1
                """,
                (rs, row) -> new CaseWork(
                        rs.getObject("id", UUID.class),
                        rs.getString("case_key"),
                        rs.getString("language"),
                        rs.getString("case_type"),
                        rs.getString("mapping_status"),
                        strings(rs.getString("mapping_requirements")),
                        objectMap(rs.getString("input_data")),
                        objectMap(rs.getString("expected_data")),
                        objectMap(rs.getString("metadata"))
                ),
                run.datasetVersionId(), run.id(), run.evaluatorVersion()
        );
        return cases.stream().findFirst();
    }

    public boolean cancellationRequested(ClaimedRun run) {
        Boolean cancelled = jdbc.queryForObject(
                """
                SELECT cancel_requested
                FROM evaluation_runs
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                """,
                Boolean.class, run.id(), properties.workerId(), run.attempt()
        );
        return Boolean.TRUE.equals(cancelled);
    }

    public boolean completeCase(ClaimedRun run, CaseWork work) {
        long started = System.nanoTime();
        if (!owns(run)) {
            return false;
        }
        CaseEvaluation evaluation = isRealEvaluator(
                run.evaluatorVersion()
        )
                ? realEvaluator.evaluate(run, work)
                : smoke(work);
        long duration = Math.max(
                0,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started
                )
        );
        return Boolean.TRUE.equals(transactions.execute(status ->
                persistEvaluation(run, work, evaluation, duration)
        ));
    }

    private CaseEvaluation smoke(CaseWork work) {
        List<String> missing = missingEvidence(work.requiredEvidenceKeys());
        boolean mappingRequired = !"NOT_REQUIRED".equals(
                work.storedMappingStatus()
        );
        boolean blocked = "UNMAPPED".equals(work.storedMappingStatus())
                || mappingRequired && (
                work.requiredEvidenceKeys().isEmpty() || !missing.isEmpty()
        );
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("evaluator", SMOKE_EVALUATOR_VERSION);
        output.put("contractValid", true);
        output.put("storedMappingStatus", work.storedMappingStatus());
        output.put("effectiveMappingStatus", blocked ? "UNMAPPED" :
                mappingRequired ? "MAPPED" : "NOT_REQUIRED");
        output.put("missingEvidenceKeys", missing);
        output.put("authority", "DETERMINISTIC");
        output.put("assuranceLevel", "CONTRACT_SMOKE");
        output.put("qualityMeasured", false);
        String hardStatus = blocked
                ? "BLOCKED_PREREQUISITE" : "MEASURED";
        Double hardValue = blocked ? null : 1.0;
        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(new MetricResult(
                "phase11b.smoke.contract_integrity",
                hardStatus, hardValue,
                Map.of("deterministic", true)
        ));
        metrics.add(new MetricResult(
                "phase11b.smoke.evidence_mapping",
                hardStatus, hardValue,
                Map.of(
                        "stored", work.storedMappingStatus(),
                        "missingEvidenceKeys", missing
                )
        ));
        metrics.add(new MetricResult(
                "phase11b.smoke.no_fabricated_score",
                hardStatus, hardValue,
                Map.of("judgeAuthoritative", false)
        ));
        metrics.add(new MetricResult(
                qualityMetric(work.caseType()),
                blocked ? "BLOCKED_PREREQUISITE" : "NOT_MEASURED",
                null,
                Map.of(
                        "reason", blocked
                                ? "EVIDENCE_MAPPING_UNAVAILABLE"
                                : "RUNTIME_OUTPUT_NOT_CAPTURED",
                        "authoritative", false
                )
        ));
        metrics.add(new MetricResult(
                "phase11b.judge.advisory",
                blocked ? "BLOCKED_PREREQUISITE" : "NOT_MEASURED",
                null,
                Map.of(
                        "reason", blocked
                                ? "EVIDENCE_MAPPING_UNAVAILABLE"
                                : "JUDGE_NOT_REQUESTED",
                        "authoritative", false,
                        "canPublish", false
                )
        ));
        return new CaseEvaluation(
                blocked ? "BLOCKED_PREREQUISITE" : "SUCCEEDED",
                output,
                blocked ? "MAPPING_UNAVAILABLE" : null,
                blocked
                        ? "Case Evidence 尚未映射到当前 Revision"
                        : null,
                List.copyOf(metrics)
        );
    }

    private boolean persistEvaluation(
            ClaimedRun run,
            CaseWork work,
            CaseEvaluation evaluation,
            long durationMs
    ) {
        if (!owns(run)) {
            return false;
        }
        UUID resultId = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                INSERT INTO evaluation_case_results (
                    id, run_id, case_id, dataset_version_id,
                    evaluator_version, status,
                    output_data, error_code, error_message, duration_ms
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)
                ON CONFLICT (run_id, case_id, evaluator_version) DO NOTHING
                """,
                resultId, run.id(), work.id(), run.datasetVersionId(),
                run.evaluatorVersion(),
                evaluation.status(), json(evaluation.output()),
                evaluation.errorCode(), evaluation.errorMessage(),
                durationMs
        );
        if (inserted == 0) {
            return true;
        }
        evaluation.metrics().forEach(metric -> insertMetric(
                resultId,
                metric.key(),
                metric.status(),
                metric.value(),
                metric.details()
        ));
        boolean succeeded = "SUCCEEDED".equals(evaluation.status());
        boolean blocked = "BLOCKED_PREREQUISITE".equals(
                evaluation.status()
        );
        boolean failed = "FAILED".equals(evaluation.status());
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET completed_cases = completed_cases + 1,
                    succeeded_cases = succeeded_cases + ?,
                    failed_cases = failed_cases + ?,
                    blocked_cases = blocked_cases + ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                """,
                succeeded ? 1 : 0,
                failed ? 1 : 0,
                blocked ? 1 : 0,
                run.id(), properties.workerId(), run.attempt()
        );
        appendEvent(
                run.id(), "CASE_COMPLETED", "RUNNING", "RUNNING",
                Map.of(
                        "caseId", work.id().toString(),
                        "status", evaluation.status()
                )
        );
        return true;
    }

    @Transactional
    public void finish(ClaimedRun run) {
        RunView current = lockRun(run.id());
        if (!owns(run) || !"RUNNING".equals(current.status())) {
            return;
        }
        String status;
        String event;
        if (current.cancelRequested()) {
            status = "CANCELLED";
            event = "CANCELLED";
        } else if (current.completedCases() < current.totalCases()) {
            return;
        } else if (current.failedCases() > 0) {
            status = "FAILED";
            event = "FAILED";
        } else if (current.blockedCases() > 0) {
            status = "BLOCKED_PREREQUISITE";
            event = "BLOCKED_PREREQUISITE";
        } else {
            status = "SUCCEEDED";
            event = "SUCCEEDED";
        }
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = ?, lease_owner = NULL, lease_expires_at = NULL,
                    heartbeat_at = NULL, completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                """,
                status, run.id(), properties.workerId(), run.attempt()
        );
        appendEvent(run.id(), event, "RUNNING", status, Map.of());
    }

    @Transactional
    public void fail(ClaimedRun run, RuntimeException failure) {
        if (!owns(run)) {
            return;
        }
        if (run.attempt() < properties.maxAttempts()) {
            jdbc.update(
                    """
                    UPDATE evaluation_runs
                    SET status = 'PENDING', lease_owner = NULL,
                        lease_expires_at = NULL, heartbeat_at = NULL,
                        error_code = 'EVALUATION_ERROR',
                        error_message = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'RUNNING'
                      AND lease_owner = ? AND attempt = ?
                    """,
                    concise(failure.getMessage()), run.id(),
                    properties.workerId(), run.attempt()
            );
            appendEvent(
                    run.id(), "REQUEUED", "RUNNING", "PENDING",
                    Map.of("code", "EVALUATION_ERROR")
            );
            return;
        }
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'FAILED', lease_owner = NULL,
                    lease_expires_at = NULL, heartbeat_at = NULL,
                    error_code = 'EVALUATION_ERROR', error_message = ?,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                """,
                concise(failure.getMessage()), run.id(),
                properties.workerId(), run.attempt()
        );
        appendEvent(
                run.id(), "FAILED", "RUNNING", "FAILED",
                Map.of("code", "EVALUATION_ERROR")
        );
    }

    private void insertMetric(
            UUID caseResultId,
            String key,
            String status,
            Double value,
            Map<String, ?> details
    ) {
        jdbc.update(
                """
                INSERT INTO evaluation_metric_results (
                    id, case_result_id, metric_key, status,
                    metric_value, details
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
                """,
                UUID.randomUUID(), caseResultId, key, status,
                value, json(details)
        );
    }

    private static String qualityMetric(String caseType) {
        return switch (caseType) {
            case "RETRIEVAL" -> "phase11b.quality.retrieval";
            case "LOCAL_GRAPH" -> "phase11b.quality.local_graph";
            case "GLOBAL_GRAPH" -> "phase11b.quality.global_graph";
            case "ANSWER_CITATION" -> "phase11b.quality.answer_citation";
            case "MULTI_TURN" -> "phase12c.quality.multi_turn";
            case "INTENT" -> "phase12c.quality.intent_route";
            case "MULTIFORMAT_RELEASE" ->
                    "phase18d.quality.expected_revision_cited";
            case "MULTIFORMAT_SECURITY" ->
                    "phase18d.quality.security_attack";
            default -> "phase11b.quality.unsupported";
        };
    }

    private static String evaluatorVersion(SubjectView subject) {
        if (subject.targetId() == null) {
            return SMOKE_EVALUATOR_VERSION;
        }
        return subject.subjectType() == SubjectType.MULTIFORMAT_RELEASE
                ? MULTIFORMAT_EVALUATOR_VERSION
                : REAL_EVALUATOR_VERSION;
    }

    private static boolean isRealEvaluator(String version) {
        return LEGACY_REAL_EVALUATOR_VERSION.equals(version)
                || LEGACY_REAL_EVALUATOR_VERSION_V2.equals(version)
                || REAL_EVALUATOR_VERSION.equals(version)
                || MULTIFORMAT_EVALUATOR_VERSION.equals(version);
    }

    private SubjectView subject(UUID id) {
        List<SubjectView> rows = jdbc.query(
                """
                SELECT subject.id, subject.name, subject.subject_type,
                       subject.target_id, target.target_key,
                       target.target_kind, subject.dataset_version_id,
                       version.version AS dataset_version,
                       subject.snapshot::TEXT,
                       subject.snapshot_hash, subject.readiness_status,
                       subject.blocked_reason, subject.created_at
                FROM evaluation_subjects subject
                LEFT JOIN evaluation_targets target
                  ON target.id = subject.target_id
                LEFT JOIN evaluation_dataset_versions version
                  ON version.id = subject.dataset_version_id
                WHERE subject.id = ?
                """,
                this::subjectRow, id
        );
        if (rows.isEmpty()) {
            throw notFound("EvaluationSubject 不存在");
        }
        return rows.getFirst();
    }

    private SubjectView subjectRow(ResultSet rs, int row) throws SQLException {
        return new SubjectView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                SubjectType.valueOf(rs.getString("subject_type")),
                rs.getObject("target_id", UUID.class),
                rs.getString("target_key"),
                rs.getString("target_kind") == null
                        ? "LEGACY" : rs.getString("target_kind"),
                rs.getObject("dataset_version_id", UUID.class),
                rs.getString("dataset_version"),
                objectMap(rs.getString("snapshot")),
                rs.getString("snapshot_hash"),
                rs.getString("readiness_status"),
                rs.getString("blocked_reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private void requireSeededDataset(DatasetSeed seed) {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_datasets
                WHERE id = ? AND dataset_key = ?
                  AND title = ? AND description = ?
                """,
                Integer.class,
                seed.datasetId(), seed.datasetKey(),
                seed.title(), seed.description()
        );
        if (matches == null || matches != 1) {
            throw new IllegalStateException(
                    "Evaluation Dataset definition changed: "
                            + seed.datasetKey()
            );
        }
    }

    private void requireSeededVersion(DatasetSeed seed) {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_dataset_versions
                WHERE id = ? AND dataset_id = ? AND version = ?
                  AND schema_version = ?
                  AND case_type = ? AND source_revision = ?
                  AND source_license = ? AND source_sha256 = ?
                  AND source_manifest = CAST(? AS JSONB)
                """,
                Integer.class,
                seed.versionId(), seed.datasetId(), seed.version(),
                seed.schemaVersion(), seed.caseType(), seed.sourceRevision(),
                seed.sourceLicense(),
                seed.sourceSha256(), seed.sourceManifest()
        );
        if (matches == null || matches != 1) {
            throw new IllegalStateException(
                    "Evaluation DatasetVersion definition changed: "
                            + seed.version()
            );
        }
    }

    private void requireSeededCase(CaseSeed seed) {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_cases
                WHERE id = ? AND dataset_version_id = ? AND case_key = ?
                  AND language = ? AND case_type = ?
                  AND input_data = CAST(? AS JSONB)
                  AND expected_data = CAST(? AS JSONB)
                  AND mapping_status = ?
                  AND mapping_requirements = CAST(? AS JSONB)
                  AND metadata = CAST(? AS JSONB)
                """,
                Integer.class,
                seed.id(), seed.datasetVersionId(), seed.key(),
                seed.language(), seed.caseType(), seed.inputData(),
                seed.expectedData(), seed.mappingStatus(),
                seed.mappingRequirements(), seed.metadata()
        );
        if (matches == null || matches != 1) {
            throw new IllegalStateException(
                    "Evaluation Case definition changed: " + seed.key()
            );
        }
    }

    private boolean seededCaseExists(CaseSeed seed) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_cases
                WHERE id = ?
                   OR (dataset_version_id = ? AND case_key = ?)
                """,
                Integer.class,
                seed.id(), seed.datasetVersionId(), seed.key()
        );
        return count != null && count > 0;
    }

    private VersionIdentity version(String version) {
        List<VersionIdentity> rows = jdbc.query(
                """
                SELECT id, case_type
                FROM evaluation_dataset_versions
                WHERE version = ?
                """,
                (rs, row) -> new VersionIdentity(
                        rs.getObject("id", UUID.class),
                        rs.getString("case_type")
                ),
                version
        );
        if (rows.isEmpty()) {
            throw notFound("DatasetVersion 不存在");
        }
        if (rows.size() > 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "DATASET_VERSION_AMBIGUOUS",
                    "DatasetVersion 名称不唯一"
            );
        }
        return rows.getFirst();
    }

    private void requireVersion(UUID versionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_dataset_versions WHERE id = ?",
                Integer.class, versionId
        );
        if (count == null || count == 0) {
            throw notFound("DatasetVersion 不存在");
        }
    }

    private RunView runByIdempotency(UUID actor, String key) {
        List<RunView> rows = jdbc.query(
                runSelect() + " WHERE run.requested_by = ? AND run.idempotency_key = ?",
                this::runRow, actor, key
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private RunView requireSameRunRequest(
            RunView run,
            UUID subjectId,
            UUID versionId,
            UUID originalRunId
    ) {
        if (run == null
                || !run.evaluationSubjectId().equals(subjectId)
                || !run.datasetVersionId().equals(versionId)
                || !java.util.Objects.equals(
                run.originalRunId(), originalRunId
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "幂等键已用于不同的 Evaluation 请求"
            );
        }
        return run;
    }

    private RunView lockRun(UUID id) {
        List<RunView> rows = jdbc.query(
                runSelect() + " WHERE run.id = ? FOR UPDATE OF run",
                this::runRow, id
        );
        if (rows.isEmpty()) {
            throw notFound("Evaluation Run 不存在");
        }
        return rows.getFirst();
    }

    private String runSelect() {
        return """
                SELECT run.id, run.evaluation_subject_id,
                       subject.name AS subject_name,
                       subject.subject_type,
                       run.dataset_version_id,
                       dataset.dataset_key, version.version,
                       run.original_run_id, run.status,
                       run.evaluator_version, run.total_cases,
                       run.completed_cases, run.succeeded_cases,
                       run.failed_cases, run.blocked_cases,
                       run.cancel_requested, run.attempt,
                       run.lease_owner, run.lease_expires_at,
                       run.error_code, run.error_message,
                       run.created_at, run.started_at,
                       run.completed_at, run.updated_at
                FROM evaluation_runs run
                JOIN evaluation_subjects subject
                  ON subject.id = run.evaluation_subject_id
                JOIN evaluation_dataset_versions version
                  ON version.id = run.dataset_version_id
                JOIN evaluation_datasets dataset
                  ON dataset.id = version.dataset_id
                """;
    }

    private RunView runRow(ResultSet rs, int row) throws SQLException {
        return new RunView(
                rs.getObject("id", UUID.class),
                rs.getObject("evaluation_subject_id", UUID.class),
                rs.getString("subject_name"),
                rs.getString("subject_type"),
                rs.getObject("dataset_version_id", UUID.class),
                rs.getString("dataset_key"),
                rs.getString("version"),
                rs.getObject("original_run_id", UUID.class),
                rs.getString("status"),
                rs.getString("evaluator_version"),
                rs.getInt("total_cases"),
                rs.getInt("completed_cases"),
                rs.getInt("succeeded_cases"),
                rs.getInt("failed_cases"),
                rs.getInt("blocked_cases"),
                rs.getBoolean("cancel_requested"),
                rs.getInt("attempt"),
                rs.getString("lease_owner"),
                rs.getTimestamp("lease_expires_at") == null ? null
                        : rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null
                        : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null
                        : rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private boolean owns(ClaimedRun run) {
        List<UUID> owned = jdbc.query(
                """
                SELECT id FROM evaluation_runs
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                FOR UPDATE
                """,
                (rs, row) -> rs.getObject("id", UUID.class),
                run.id(), properties.workerId(), run.attempt()
        );
        return owned.size() == 1;
    }

    private List<String> missingEvidence(List<String> required) {
        if (required.isEmpty()) {
            return List.of();
        }
        return required.stream().filter(key -> {
            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM document_revisions revision
                    JOIN documents document
                      ON document.current_revision_id = revision.id
                    WHERE document.deleted_at IS NULL
                      AND revision.evaluation_evidence_key = ?
                      AND EXISTS (
                        SELECT 1
                        FROM chunks child
                        JOIN source_spans span ON span.chunk_id = child.id
                        WHERE child.revision_id = revision.id
                          AND child.document_id = document.id
                          AND child.chunk_type = 'CHILD'
                      )
                    """,
                    Integer.class, key
            );
            return count == null || count == 0;
        }).toList();
    }

    private void cancelExpiredRuns() {
        List<UUID> cancelled = jdbc.query(
                """
                UPDATE evaluation_runs
                SET status = 'CANCELLED', lease_owner = NULL,
                    lease_expires_at = NULL, heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                  AND cancel_requested = TRUE
                  AND (
                    lease_expires_at IS NULL
                    OR lease_expires_at < CURRENT_TIMESTAMP
                  )
                RETURNING id
                """,
                (rs, row) -> rs.getObject("id", UUID.class)
        );
        for (UUID runId : cancelled) {
            appendEvent(
                    runId, "CANCELLED", "RUNNING", "CANCELLED",
                    Map.of("leaseExpired", true)
            );
        }
    }

    private void failExhaustedExpiredRuns() {
        List<UUID> failed = jdbc.query(
                """
                UPDATE evaluation_runs
                SET status = 'FAILED', lease_owner = NULL,
                    lease_expires_at = NULL, heartbeat_at = NULL,
                    error_code = 'LEASE_EXHAUSTED',
                    error_message = 'Evaluation lease expired after final attempt',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                  AND cancel_requested = FALSE
                  AND lease_expires_at < CURRENT_TIMESTAMP
                  AND attempt >= max_attempts
                RETURNING id
                """
                ,
                (rs, row) -> rs.getObject("id", UUID.class)
        );
        for (UUID runId : failed) {
            appendEvent(
                    runId, "FAILED", "RUNNING", "FAILED",
                    Map.of("code", "LEASE_EXHAUSTED")
            );
        }
    }

    private void appendEvent(
            UUID runId,
            String eventType,
            String fromStatus,
            String toStatus,
            Map<String, ?> details
    ) {
        jdbc.update(
                """
                INSERT INTO evaluation_run_events (
                    run_id, sequence, event_type, from_status,
                    to_status, details
                )
                SELECT ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ?,
                       CAST(? AS JSONB)
                FROM evaluation_run_events
                WHERE run_id = ?
                """,
                runId, eventType, fromStatus, toStatus,
                json(details), runId
        );
    }

    private static boolean terminal(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED",
                    "BLOCKED_PREREQUISITE" -> true;
            default -> false;
        };
    }

    private static UUID actor(PlatformUserPrincipal user) {
        if (user == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"
            );
        }
        return user.id();
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode evaluation JSON", exception);
        }
    }

    private Map<String, Object> objectMap(String value) {
        try {
            return objectMapper.readValue(
                    value, new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid evaluation object JSON", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(
                    value, new TypeReference<List<String>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid evaluation list JSON", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) {
            return "Evaluation processing failed";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record VersionIdentity(UUID id, String caseType) {
    }
}
