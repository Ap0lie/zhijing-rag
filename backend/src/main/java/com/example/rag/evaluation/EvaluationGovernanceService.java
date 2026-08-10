package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.BaselinePublicationEventView;
import com.example.rag.evaluation.EvaluationContracts.BaselineView;
import com.example.rag.evaluation.EvaluationContracts.CaseDeltaView;
import com.example.rag.evaluation.EvaluationContracts.CompareView;
import com.example.rag.evaluation.EvaluationContracts.FeedbackView;
import com.example.rag.evaluation.EvaluationContracts.MetricDeltaView;
import com.example.rag.evaluation.EvaluationContracts.RunView;
import com.example.rag.evaluation.EvaluationContracts.SliceDeltaView;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
class EvaluationGovernanceService {

    private static final Pattern EMAIL =
            Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern SECRET =
            Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{8,}|bearer\\s+[A-Za-z0-9._-]{8,})");
    private static final DateTimeFormatter VERSION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    .withZone(ZoneOffset.UTC);
    private static final Set<String> MULTIFORMAT_COMPONENT_BASELINES = Set.of(
            "LOCAL_GRAPH",
            "GLOBAL_GRAPH",
            "ANSWER_CITATION",
            "MULTI_TURN",
            "INTENT"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EvaluationService evaluations;

    EvaluationGovernanceService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            EvaluationService evaluations
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.evaluations = evaluations;
    }

    CompareView compare(UUID leftId, UUID rightId, String reason) {
        RunView left = evaluations.run(leftId);
        RunView right = evaluations.run(rightId);
        requireComparable(left);
        requireComparable(right);
        boolean leftReal = isRealEvaluator(left.evaluatorVersion());
        boolean rightReal = isRealEvaluator(right.evaluatorVersion());
        if (leftReal != rightReal) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "COMPARE_ASSURANCE_LEVEL_MISMATCH",
                    "真实评测不能与 Contract Smoke 直接比较"
            );
        }
        boolean sameVersion = left.datasetVersionId()
                .equals(right.datasetVersionId());
        String explanation = reason == null ? "" : reason.strip();
        if (!sameVersion && explanation.length() < 10) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "COMPARE_DATASET_VERSION_MISMATCH",
                    "不同 DatasetVersion 的比较必须提供至少 10 个字符的说明"
            );
        }
        return new CompareView(
                left, right, sameVersion, explanation,
                metricDeltas(leftId, rightId),
                sliceDeltas(leftId, rightId),
                changedCases(leftId, rightId)
        );
    }

    List<BaselineView> baselines() {
        return jdbc.query(
                baselineSelect() + """
                        ORDER BY baseline.created_at DESC, baseline.id DESC
                        """,
                (rs, row) -> baseline(rs, row)
        ).stream().map(this::effectiveBaseline).toList();
    }

    List<BaselinePublicationEventView> baselineEvents() {
        return jdbc.query(
                """
                SELECT id, baseline_key, baseline_id,
                       previous_baseline_id, action, reason, created_at
                FROM evaluation_baseline_publication_events
                ORDER BY id DESC
                """,
                (rs, row) -> new BaselinePublicationEventView(
                        rs.getLong("id"),
                        rs.getString("baseline_key"),
                        rs.getObject("baseline_id", UUID.class),
                        rs.getObject("previous_baseline_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()
                )
        );
    }

    @Transactional
    BaselineView publishBaseline(
            UUID runId,
            String name,
            String reason,
            PlatformUserPrincipal user
    ) {
        RunView run = evaluations.run(runId);
        lockMultiformatFactDocuments(run);
        Map<String, Object> gates = gateSummary(run);
        if (!Boolean.TRUE.equals(gates.get("passed"))) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "BASELINE_GATE_BLOCKED",
                    "Run 未通过真实评测硬门禁，不能发布 Baseline"
            );
        }
        UUID actor = actor(user);
        String baselineKey = run.datasetVersion() + ":" + run.subjectType();
        UUID baselineId = findBaselineForRun(runId);
        if (baselineId == null) {
            baselineId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO evaluation_baselines (
                        id, name, baseline_key, dataset_version_id,
                        evaluation_subject_id, run_id, gate_status,
                        gate_summary, metric_summary, judge_advisory,
                        created_by
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, 'PASSED',
                        CAST(? AS JSONB), CAST(? AS JSONB),
                        CAST(? AS JSONB), ?
                    )
                    """,
                    baselineId,
                    requiredName(name),
                    baselineKey,
                    run.datasetVersionId(),
                    run.evaluationSubjectId(),
                    run.id(),
                    json(gates),
                    json(metricSummary(run.id())),
                    json(judgeSummary(run.id())),
                    actor
            );
        }
        switchBaseline(
                baselineKey, baselineId, "PUBLISH", reason, actor
        );
        return baseline(baselineId);
    }

    @Transactional
    BaselineView rollbackBaseline(
            UUID baselineId,
            String reason,
            PlatformUserPrincipal user
    ) {
        BaselineView target = baseline(baselineId);
        RunView targetRun = evaluations.run(target.runId());
        lockMultiformatFactDocuments(targetRun);
        Map<String, Object> currentGate = gateSummary(
                targetRun
        );
        if (!Boolean.TRUE.equals(currentGate.get("passed"))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "BASELINE_GATE_BLOCKED",
                    "目标 Baseline 未通过当前真实评测门禁"
            );
        }
        UUID current = currentBaseline(target.baselineKey());
        if (target.id().equals(current)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "BASELINE_ALREADY_ACTIVE",
                    "该 Baseline 已经发布"
            );
        }
        Integer publishedBefore = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_baseline_publication_events
                WHERE baseline_id = ?
                """,
                Integer.class, baselineId
        );
        if (publishedBefore == null || publishedBefore == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "BASELINE_NOT_PUBLISHED",
                    "只能回滚到曾经发布过的 Baseline"
            );
        }
        switchBaseline(
                target.baselineKey(), baselineId, "ROLLBACK",
                reason, actor(user)
        );
        return baseline(baselineId);
    }

    @Transactional
    FeedbackView createFeedback(
            UUID chatRunId,
            int rating,
            String comment,
            boolean consent,
            PlatformUserPrincipal user
    ) {
        UUID owner = actor(user);
        Map<String, Object> sample = feedbackSample(
                chatRunId, owner, comment
        );
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                    INSERT INTO evaluation_feedback (
                        id, owner_user_id, chat_run_id, rating, comment,
                        consent_to_share, redacted_sample, redaction_version
                    ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
                              'phase11b-redaction-v1')
                    """,
                    id, owner, chatRunId, rating,
                    blankToNull(comment), consent, json(sample)
            );
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "FEEDBACK_ALREADY_EXISTS",
                    "该问答 Run 已提交反馈"
            );
        }
        return ownerFeedback(id, owner);
    }

    List<FeedbackView> reviewQueue() {
        return jdbc.query(
                feedbackSelect() + """
                        WHERE feedback.consent_to_share = TRUE
                        ORDER BY feedback.created_at, feedback.id
                        """,
                this::feedback
        );
    }

    @Transactional
    FeedbackView reviewFeedback(
            UUID feedbackId,
            String decision,
            String reason,
            PlatformUserPrincipal user
    ) {
        FeedbackView feedback = sharedFeedback(feedbackId);
        if (!"PENDING".equals(feedback.reviewStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "FEEDBACK_ALREADY_REVIEWED",
                    "反馈已经完成审核"
            );
        }
        UUID versionId = "APPROVED".equals(decision)
                ? createFeedbackDatasetVersion(feedback, actor(user))
                : null;
        jdbc.update(
                """
                INSERT INTO evaluation_feedback_reviews (
                    feedback_id, decision, reviewer, reason,
                    created_dataset_version_id
                ) VALUES (?, ?, ?, ?, ?)
                """,
                feedbackId, decision, actor(user), reason.strip(), versionId
        );
        return sharedFeedback(feedbackId);
    }

    Map<String, Object> gateSummary(RunView run) {
        List<String> blockers = new ArrayList<>();
        if (!"SUCCEEDED".equals(run.status())) {
            blockers.add("RUN_NOT_SUCCEEDED");
        }
        if (!isRealEvaluator(run.evaluatorVersion())) {
            blockers.add("REAL_EVALUATION_REQUIRED");
        }
        if (run.failedCases() > 0 || run.blockedCases() > 0) {
            blockers.add("CASE_FAILURE_OR_BLOCK");
        }
        List<HardMetricRow> hardMetrics = jdbc.query(
                """
                SELECT result.id AS result_id, case_row.case_type,
                       metric.metric_key, metric.status,
                       metric.metric_value
                FROM evaluation_case_results result
                JOIN evaluation_cases case_row
                  ON case_row.id = result.case_id
                LEFT JOIN evaluation_metric_results metric
                  ON metric.case_result_id = result.id
                 AND (
                     metric.metric_key LIKE 'phase11b.hard.%'
                     OR metric.metric_key LIKE 'phase12c.hard.%'
                     OR metric.metric_key LIKE 'phase18d.hard.%'
                 )
                WHERE result.run_id = ?
                ORDER BY result.id, metric.metric_key
                """,
                (resultSet, rowNumber) -> new HardMetricRow(
                        resultSet.getObject("result_id", UUID.class),
                        resultSet.getString("case_type"),
                        resultSet.getString("metric_key"),
                        resultSet.getString("status"),
                        resultSet.getObject("metric_value") == null
                                ? null : resultSet.getDouble("metric_value")
                ),
                run.id()
        );
        Map<UUID, List<HardMetricRow>> metricsByResult =
                new LinkedHashMap<>();
        hardMetrics.forEach(metric -> metricsByResult
                .computeIfAbsent(
                        metric.resultId(), ignored -> new ArrayList<>()
                )
                .add(metric));
        boolean invalidSet = metricsByResult.values().stream().anyMatch(rows -> {
            Set<String> actual = rows.stream()
                    .map(HardMetricRow::key)
                    .filter(java.util.Objects::nonNull)
                    .collect(
                            LinkedHashSet::new,
                            Set::add,
                            Set::addAll
                    );
            Set<String> expected =
                    RealEvaluationExecutor.requiredHardMetrics(
                            rows.getFirst().caseType()
                    );
            return !actual.equals(expected);
        });
        if (metricsByResult.size() != run.totalCases() || invalidSet) {
            blockers.add("HARD_METRIC_SET_INCOMPLETE");
        }
        boolean failedHardMetrics = hardMetrics.stream()
                .filter(metric -> metric.key() != null)
                .anyMatch(metric ->
                        !"MEASURED".equals(metric.status())
                                || !Double.valueOf(1.0).equals(metric.value())
                );
        if (failedHardMetrics) {
            blockers.add("HARD_METRIC_FAILED");
        }
        Integer resultCount = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_case_results
                WHERE run_id = ?
                """,
                Integer.class, run.id()
        );
        if (resultCount == null || resultCount != run.totalCases()) {
            blockers.add("RESULT_SET_INCOMPLETE");
        }
        if ("MULTIFORMAT_RELEASE".equals(run.subjectType())) {
            addMultiformatCurrentEligibility(run, blockers);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("passed", blockers.isEmpty());
        summary.put("blockers", blockers);
        summary.put("authority", "DETERMINISTIC");
        summary.put("judgeCanPublish", false);
        return summary;
    }

    private void addMultiformatCurrentEligibility(
            RunView run,
            List<String> blockers
    ) {
        if (!currentMultiformatFactsReady(run.datasetVersionId())) {
            blockers.add("MULTIFORMAT_FACT_CLOSURE_STALE");
        }
        List<ComponentBaseline> publications = jdbc.query(
                """
                SELECT subject.subject_type, baseline.run_id
                FROM evaluation_baseline_publications publication
                JOIN evaluation_baselines baseline
                  ON baseline.id = publication.baseline_id
                 AND baseline.gate_status = 'PASSED'
                JOIN evaluation_runs component_run
                  ON component_run.id = baseline.run_id
                 AND component_run.status = 'SUCCEEDED'
                JOIN evaluation_subjects subject
                  ON subject.id = component_run.evaluation_subject_id
                WHERE subject.subject_type IN (
                    'LOCAL_GRAPH', 'GLOBAL_GRAPH', 'ANSWER_CITATION',
                    'MULTI_TURN', 'INTENT'
                )
                """,
                (resultSet, rowNumber) -> new ComponentBaseline(
                        resultSet.getString("subject_type"),
                        resultSet.getObject("run_id", UUID.class)
                )
        );
        Set<String> published = new LinkedHashSet<>();
        publications.forEach(publication -> {
            RunView componentRun = evaluations.run(publication.runId());
            if (Boolean.TRUE.equals(gateSummary(componentRun).get("passed"))) {
                published.add(publication.subjectType());
            }
        });
        MULTIFORMAT_COMPONENT_BASELINES.stream()
                .filter(required -> !published.contains(required))
                .sorted()
                .forEach(required -> blockers.add(
                        "COMPONENT_BASELINE_MISSING:" + required
                ));
        Boolean memoryExecuted = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM evaluation_case_results result
                    WHERE result.run_id = ?
                      AND result.status = 'SUCCEEDED'
                      AND COALESCE(
                          (result.output_data ->> 'memoryInjectedCount')::INTEGER,
                          0
                      ) > 0
                      AND COALESCE(
                          (result.output_data ->> 'memoryUsedCount')::INTEGER,
                          0
                      ) > 0
                )
                """,
                Boolean.class,
                run.id()
        );
        if (!Boolean.TRUE.equals(memoryExecuted)) {
            blockers.add("MEMORY_ENABLED_PATH_NOT_EVALUATED");
        }
    }

    private boolean currentMultiformatFactsReady(UUID datasetVersionId) {
        Integer invalid = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM evaluation_cases case_row
                LEFT JOIN evaluation_multiformat_case_facts fact
                  ON fact.case_id = case_row.id
                 AND fact.dataset_version_id = case_row.dataset_version_id
                LEFT JOIN documents document
                  ON document.id = fact.document_id
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
                 AND locator.document_id = fact.document_id
                 AND locator.revision_id = fact.revision_id
                WHERE case_row.dataset_version_id = ?
                  AND case_row.case_type = 'MULTIFORMAT_RELEASE'
                  AND (
                    fact.case_id IS NULL
                    OR document.deleted_at IS NOT NULL
                    OR document.current_revision_id IS DISTINCT FROM fact.revision_id
                    OR document.acl_version IS DISTINCT FROM fact.acl_version
                    OR document.visibility::TEXT IS DISTINCT FROM fact.document_visibility
                    OR revision.status IS DISTINCT FROM 'READY'
                    OR revision.content_hash IS DISTINCT FROM fact.file_sha256
                    OR revision.parser_provider::TEXT
                        IS DISTINCT FROM fact.expected_parser_provider
                    OR child.searchable IS DISTINCT FROM TRUE
                    OR child.parser_version IS DISTINCT FROM fact.expected_parser_version
                    OR child.chunker_version IS DISTINCT FROM fact.expected_chunker_version
                    OR span.source_text_hash IS DISTINCT FROM fact.source_text_hash
                    OR locator.locator_kind::TEXT IS DISTINCT FROM fact.locator_kind
                    OR locator.source_label IS DISTINCT FROM fact.source_label
                    OR jsonb_build_object(
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
                        'normalizationVersion', locator.normalization_version
                    ) IS DISTINCT FROM fact.locator
                  )
                """,
                Integer.class,
                datasetVersionId
        );
        Integer expected = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM evaluation_cases
                WHERE dataset_version_id = ?
                  AND case_type = 'MULTIFORMAT_RELEASE'
                """,
                Integer.class,
                datasetVersionId
        );
        return invalid != null && invalid == 0
                && expected != null && expected == 8;
    }

    private void lockMultiformatFactDocuments(RunView run) {
        if (!"MULTIFORMAT_RELEASE".equals(run.subjectType())) {
            return;
        }
        jdbc.query(
                """
                SELECT document.id
                FROM evaluation_multiformat_case_facts fact
                JOIN documents document ON document.id = fact.document_id
                WHERE fact.dataset_version_id = ?
                ORDER BY document.id
                FOR UPDATE OF document
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("id", UUID.class),
                run.datasetVersionId()
        );
    }

    private record HardMetricRow(
            UUID resultId,
            String caseType,
            String key,
            String status,
            Double value
    ) {
    }

    private record ComponentBaseline(String subjectType, UUID runId) {
    }

    private static boolean isRealEvaluator(String version) {
        return version != null
                && (version.startsWith("phase11b-real-")
                || version.startsWith("phase12c-real-")
                || version.startsWith("phase18d-real-"));
    }

    private Map<String, Object> metricSummary(UUID runId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT metric.metric_key,
                       COUNT(*) FILTER (
                           WHERE metric.status = 'MEASURED'
                       ) AS measured_count,
                       AVG(metric.metric_value) FILTER (
                           WHERE metric.status = 'MEASURED'
                       ) AS average_value,
                       COUNT(*) FILTER (
                           WHERE metric.status = 'BLOCKED_PREREQUISITE'
                       ) AS blocked_count,
                       COUNT(*) FILTER (
                           WHERE metric.status = 'NOT_MEASURED'
                       ) AS not_measured_count
                FROM evaluation_metric_results metric
                JOIN evaluation_case_results result
                  ON result.id = metric.case_result_id
                WHERE result.run_id = ?
                GROUP BY metric.metric_key
                ORDER BY metric.metric_key
                """,
                rs -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("measured", rs.getLong("measured_count"));
                    value.put("average", rs.getObject("average_value"));
                    value.put("blocked", rs.getLong("blocked_count"));
                    value.put("notMeasured", rs.getLong("not_measured_count"));
                    summary.put(rs.getString("metric_key"), value);
                },
                runId
        );
        return summary;
    }

    private Map<String, Object> judgeSummary(UUID runId) {
        Integer measured = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_metric_results metric
                JOIN evaluation_case_results result
                  ON result.id = metric.case_result_id
                WHERE result.run_id = ?
                  AND metric.metric_key LIKE 'phase11b.judge.%'
                  AND metric.status = 'MEASURED'
                """,
                Integer.class, runId
        );
        return Map.of(
                "status", measured != null && measured > 0
                        ? "ADVISORY_AVAILABLE" : "NOT_MEASURED",
                "authoritative", false,
                "canPublish", false
        );
    }

    private List<MetricDeltaView> metricDeltas(UUID left, UUID right) {
        Map<String, MetricAggregate> leftMetrics = aggregateMetrics(left);
        Map<String, MetricAggregate> rightMetrics = aggregateMetrics(right);
        Set<String> keys = new LinkedHashSet<>(leftMetrics.keySet());
        keys.addAll(rightMetrics.keySet());
        return keys.stream().sorted().map(key -> {
            MetricAggregate a = leftMetrics.getOrDefault(
                    key, MetricAggregate.empty()
            );
            MetricAggregate b = rightMetrics.getOrDefault(
                    key, MetricAggregate.empty()
            );
            Double delta = a.average() == null || b.average() == null
                    ? null : b.average() - a.average();
            return new MetricDeltaView(
                    key, a.average(), b.average(), delta,
                    a.measured(), b.measured()
            );
        }).toList();
    }

    private Map<String, MetricAggregate> aggregateMetrics(UUID runId) {
        Map<String, MetricAggregate> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT metric.metric_key,
                       AVG(metric.metric_value) FILTER (
                           WHERE metric.status = 'MEASURED'
                       ) AS average_value,
                       COUNT(*) FILTER (
                           WHERE metric.status = 'MEASURED'
                       ) AS measured_count
                FROM evaluation_metric_results metric
                JOIN evaluation_case_results case_result
                  ON case_result.id = metric.case_result_id
                WHERE case_result.run_id = ?
                GROUP BY metric.metric_key
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    result.put(
                            rs.getString("metric_key"),
                            new MetricAggregate(
                                    rs.getObject("average_value") == null
                                            ? null : rs.getDouble("average_value"),
                                    rs.getLong("measured_count")
                            )
                    );
                },
                runId
        );
        return result;
    }

    private List<SliceDeltaView> sliceDeltas(UUID left, UUID right) {
        Map<SliceKey, SliceCounts> leftSlices = slices(left);
        Map<SliceKey, SliceCounts> rightSlices = slices(right);
        Set<SliceKey> keys = new LinkedHashSet<>(leftSlices.keySet());
        keys.addAll(rightSlices.keySet());
        return keys.stream()
                .sorted((a, b) -> (a.dimension() + a.value())
                        .compareTo(b.dimension() + b.value()))
                .map(key -> {
                    SliceCounts a = leftSlices.getOrDefault(
                            key, SliceCounts.empty()
                    );
                    SliceCounts b = rightSlices.getOrDefault(
                            key, SliceCounts.empty()
                    );
                    return new SliceDeltaView(
                            key.dimension(), key.value(),
                            a.succeeded(), a.failed(), a.blocked(),
                            b.succeeded(), b.failed(), b.blocked()
                    );
                }).toList();
    }

    private Map<SliceKey, SliceCounts> slices(UUID runId) {
        Map<SliceKey, SliceCounts> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT case_row.language, case_row.case_type,
                       result.status, COUNT(*) AS count
                FROM evaluation_case_results result
                JOIN evaluation_cases case_row ON case_row.id = result.case_id
                WHERE result.run_id = ?
                GROUP BY case_row.language, case_row.case_type, result.status
                """,
                rs -> {
                    mergeSlice(
                            result,
                            new SliceKey("language", rs.getString("language")),
                            rs.getString("status"), rs.getLong("count")
                    );
                    mergeSlice(
                            result,
                            new SliceKey("caseType", rs.getString("case_type")),
                            rs.getString("status"), rs.getLong("count")
                    );
                },
                runId
        );
        return result;
    }

    private void mergeSlice(
            Map<SliceKey, SliceCounts> slices,
            SliceKey key,
            String status,
            long count
    ) {
        SliceCounts previous = slices.getOrDefault(key, SliceCounts.empty());
        slices.put(key, switch (status) {
            case "SUCCEEDED" -> previous.withSucceeded(count);
            case "FAILED" -> previous.withFailed(count);
            default -> previous.withBlocked(count);
        });
    }

    private List<CaseDeltaView> changedCases(UUID left, UUID right) {
        return jdbc.query(
                """
                SELECT case_row.case_key, case_row.language,
                       case_row.case_type,
                       left_result.status AS left_status,
                       right_result.status AS right_status
                FROM evaluation_case_results left_result
                JOIN evaluation_cases case_row
                  ON case_row.id = left_result.case_id
                JOIN evaluation_cases right_case
                  ON right_case.case_key = case_row.case_key
                JOIN evaluation_case_results right_result
                  ON right_result.case_id = right_case.id
                 AND right_result.run_id = ?
                WHERE left_result.run_id = ?
                  AND left_result.status <> right_result.status
                ORDER BY case_row.case_key
                LIMIT 200
                """,
                (rs, row) -> new CaseDeltaView(
                        rs.getString("case_key"),
                        rs.getString("language"),
                        rs.getString("case_type"),
                        rs.getString("left_status"),
                        rs.getString("right_status")
                ),
                right, left
        );
    }

    private void switchBaseline(
            String baselineKey,
            UUID baselineId,
            String action,
            String reason,
            UUID actor
    ) {
        UUID previous = currentBaseline(baselineKey);
        if (baselineId.equals(previous)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "BASELINE_ALREADY_ACTIVE",
                    "该 Baseline 已经发布"
            );
        }
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO evaluation_baseline_publication_events (
                    baseline_key, baseline_id, previous_baseline_id,
                    action, actor, reason
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, baselineKey, baselineId, previous,
                action, actor, reason.strip()
        );
        jdbc.update(
                """
                INSERT INTO evaluation_baseline_publications (
                    baseline_key, baseline_id, publication_event_id
                ) VALUES (?, ?, ?)
                ON CONFLICT (baseline_key) DO UPDATE
                SET baseline_id = EXCLUDED.baseline_id,
                    publication_event_id = EXCLUDED.publication_event_id,
                    published_at = CURRENT_TIMESTAMP
                """,
                baselineKey, baselineId, eventId
        );
    }

    private UUID currentBaseline(String key) {
        List<UUID> rows = jdbc.query(
                """
                SELECT baseline_id
                FROM evaluation_baseline_publications
                WHERE baseline_key = ?
                FOR UPDATE
                """,
                (rs, row) -> rs.getObject(1, UUID.class), key
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID findBaselineForRun(UUID runId) {
        List<UUID> rows = jdbc.query(
                "SELECT id FROM evaluation_baselines WHERE run_id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), runId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private BaselineView baseline(UUID id) {
        List<BaselineView> rows = jdbc.query(
                baselineSelect() + " WHERE baseline.id = ?",
                (rs, row) -> baseline(rs, row), id
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "BASELINE_NOT_FOUND",
                    "Baseline 不存在"
            );
        }
        return effectiveBaseline(rows.getFirst());
    }

    private String baselineSelect() {
        return """
                SELECT baseline.id, baseline.name, baseline.baseline_key,
                       baseline.dataset_version_id,
                       baseline.evaluation_subject_id, baseline.run_id,
                       baseline.gate_status, baseline.gate_summary::TEXT,
                       baseline.metric_summary::TEXT,
                       baseline.judge_advisory::TEXT,
                       publication.baseline_id IS NOT NULL AS published,
                       baseline.created_at
                FROM evaluation_baselines baseline
                LEFT JOIN evaluation_baseline_publications publication
                  ON publication.baseline_key = baseline.baseline_key
                 AND publication.baseline_id = baseline.id
                """;
    }

    private BaselineView baseline(ResultSet rs, int row) throws SQLException {
        return new BaselineView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("baseline_key"),
                rs.getObject("dataset_version_id", UUID.class),
                rs.getObject("evaluation_subject_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("gate_status"),
                objectMap(rs.getString("gate_summary")),
                objectMap(rs.getString("metric_summary")),
                objectMap(rs.getString("judge_advisory")),
                rs.getBoolean("published"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private BaselineView effectiveBaseline(BaselineView stored) {
        Map<String, Object> effectiveGate = gateSummary(
                evaluations.run(stored.runId())
        );
        return new BaselineView(
                stored.id(),
                stored.name(),
                stored.baselineKey(),
                stored.datasetVersionId(),
                stored.evaluationSubjectId(),
                stored.runId(),
                Boolean.TRUE.equals(effectiveGate.get("passed"))
                        ? "PASSED" : "BLOCKED",
                effectiveGate,
                stored.metricSummary(),
                stored.judgeAdvisory(),
                stored.published(),
                stored.createdAt()
        );
    }

    private Map<String, Object> feedbackSample(
            UUID runId,
            UUID owner,
            String comment
    ) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT run.status, request.content AS question,
                       response.content AS answer,
                       request.language
                FROM chat_runs run
                JOIN chat_messages request
                  ON request.id = run.request_message_id
                 AND request.owner_user_id = run.owner_user_id
                JOIN chat_messages response
                  ON response.id = run.response_message_id
                 AND response.owner_user_id = run.owner_user_id
                WHERE run.id = ? AND run.owner_user_id = ?
                  AND run.status IN ('COMPLETED', 'REFUSED')
                """,
                (rs, row) -> {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("runStatus", rs.getString("status"));
                    sample.put("language", rs.getString("language"));
                    sample.put("question", redact(rs.getString("question")));
                    sample.put("answer", redact(rs.getString("answer")));
                    sample.put("comment", redact(comment));
                    return sample;
                },
                runId, owner
        );
        if (rows.isEmpty()) {
            Integer owned = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM chat_runs
                    WHERE id = ? AND owner_user_id = ?
                    """,
                    Integer.class, runId, owner
            );
            if (owned != null && owned > 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CHAT_RUN_NOT_TERMINAL",
                        "只能对已完成或已拒答的问答 Run 提交反馈"
                );
            }
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "CHAT_RUN_NOT_FOUND",
                    "问答 Run 不存在"
            );
        }
        return rows.getFirst();
    }

    private FeedbackView ownerFeedback(UUID id, UUID owner) {
        List<FeedbackView> rows = jdbc.query(
                feedbackSelect() + """
                        WHERE feedback.id = ?
                          AND feedback.owner_user_id = ?
                        """,
                this::feedback, id, owner
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "FEEDBACK_NOT_FOUND",
                    "反馈不存在"
            );
        }
        return rows.getFirst();
    }

    private FeedbackView sharedFeedback(UUID id) {
        List<FeedbackView> rows = jdbc.query(
                feedbackSelect() + """
                        WHERE feedback.id = ?
                          AND feedback.consent_to_share = TRUE
                        """,
                this::feedback, id
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "FEEDBACK_NOT_FOUND",
                    "可审核反馈不存在"
            );
        }
        return rows.getFirst();
    }

    private String feedbackSelect() {
        return """
                SELECT feedback.id, feedback.chat_run_id, feedback.rating,
                       feedback.consent_to_share,
                       feedback.redacted_sample::TEXT,
                       COALESCE(review.decision, 'PENDING') AS review_status,
                       review.reason AS review_reason,
                       review.created_dataset_version_id,
                       feedback.created_at
                FROM evaluation_feedback feedback
                LEFT JOIN evaluation_feedback_reviews review
                  ON review.feedback_id = feedback.id
                """;
    }

    private FeedbackView feedback(ResultSet rs, int row) throws SQLException {
        Map<String, Object> sample = objectMap(
                rs.getString("redacted_sample")
        );
        return new FeedbackView(
                rs.getObject("id", UUID.class),
                rs.getObject("chat_run_id", UUID.class),
                rs.getInt("rating"),
                String.valueOf(sample.getOrDefault("comment", "")),
                rs.getBoolean("consent_to_share"),
                sample,
                rs.getString("review_status"),
                rs.getString("review_reason"),
                rs.getObject("created_dataset_version_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private UUID createFeedbackDatasetVersion(
            FeedbackView feedback,
            UUID reviewer
    ) {
        UUID datasetId = UUID.nameUUIDFromBytes(
                "dataset:feedback-reviewed"
                        .getBytes(StandardCharsets.UTF_8)
        );
        UUID versionId = UUID.randomUUID();
        String version = "feedback-"
                + VERSION_TIME.format(Instant.now()) + "-"
                + feedback.id().toString().substring(0, 8);
        String sampleJson = json(feedback.redactedSample());
        jdbc.update(
                """
                INSERT INTO evaluation_datasets (
                    id, dataset_key, title, description
                ) VALUES (
                    ?, 'feedback-reviewed', 'Reviewed User Feedback',
                    '用户明确同意分享并经管理员审核的脱敏单轮样本'
                )
                ON CONFLICT (dataset_key) DO NOTHING
                """,
                datasetId
        );
        jdbc.update(
                """
                INSERT INTO evaluation_dataset_versions (
                    id, dataset_id, version, schema_version, case_type,
                    source_revision, source_license, source_sha256,
                    source_manifest
                ) VALUES (
                    ?, ?, ?, 'phase11b-feedback-v1', 'ANSWER_CITATION',
                    ?, 'USER_CONSENT', ?, CAST(? AS JSONB)
                )
                """,
                versionId, datasetId, version,
                feedback.id().toString(), sha256(sampleJson),
                json(Map.of(
                        "feedbackId", feedback.id(),
                        "redactionVersion", "phase11b-redaction-v1",
                        "reviewedBy", reviewer
                ))
        );
        jdbc.update(
                """
                INSERT INTO evaluation_cases (
                    id, dataset_version_id, case_key, language, case_type,
                    input_data, expected_data, mapping_status,
                    mapping_requirements, metadata
                ) VALUES (
                    ?, ?, ?, ?, 'ANSWER_CITATION',
                    CAST(? AS JSONB), CAST(? AS JSONB), 'UNMAPPED',
                    '[]'::JSONB, CAST(? AS JSONB)
                )
                """,
                UUID.randomUUID(), versionId,
                "feedback:" + feedback.id(),
                String.valueOf(feedback.redactedSample()
                        .getOrDefault("language", "und")),
                json(Map.of(
                        "query", feedback.redactedSample()
                                .getOrDefault("question", "")
                )),
                json(Map.of(
                        "answer", feedback.redactedSample()
                                .getOrDefault("answer", "")
                )),
                json(Map.of(
                        "feedbackId", feedback.id(),
                        "rating", feedback.rating(),
                        "consentToShare", true
                ))
        );
        return versionId;
    }

    private static void requireComparable(RunView run) {
        if (!Set.of("SUCCEEDED", "BLOCKED_PREREQUISITE")
                .contains(run.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "RUN_NOT_COMPARABLE",
                    "只能比较已完成或前置条件不足的 Run"
            );
        }
    }

    private static String requiredName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "BASELINE_NAME_REQUIRED",
                    "请输入 Baseline 名称"
            );
        }
        return name.strip();
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = EMAIL.matcher(value).replaceAll("[EMAIL]");
        redacted = SECRET.matcher(redacted).replaceAll("[SECRET]");
        redacted = redacted.replaceAll(
                "(?i)(password|密码)\\s*[:=]\\s*\\S+",
                "$1=[REDACTED]"
        );
        return redacted.length() <= 600
                ? redacted : redacted.substring(0, 600) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static UUID actor(PlatformUserPrincipal user) {
        if (user == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"
            );
        }
        return user.id();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode evaluation governance JSON",
                    exception
            );
        }
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
                    "Invalid evaluation governance JSON",
                    exception
            );
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record MetricAggregate(Double average, long measured) {
        static MetricAggregate empty() {
            return new MetricAggregate(null, 0);
        }
    }

    private record SliceKey(String dimension, String value) {
    }

    private record SliceCounts(
            long succeeded,
            long failed,
            long blocked
    ) {
        static SliceCounts empty() {
            return new SliceCounts(0, 0, 0);
        }

        SliceCounts withSucceeded(long value) {
            return new SliceCounts(succeeded + value, failed, blocked);
        }

        SliceCounts withFailed(long value) {
            return new SliceCounts(succeeded, failed + value, blocked);
        }

        SliceCounts withBlocked(long value) {
            return new SliceCounts(succeeded, failed, blocked + value);
        }
    }
}
