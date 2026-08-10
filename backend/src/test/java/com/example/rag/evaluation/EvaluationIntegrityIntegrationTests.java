package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.CreateAnswerProfileRequest;
import com.example.rag.evaluation.EvaluationContracts.RunView;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "rag.evaluation.worker-enabled=false",
        "rag.evaluation.catalog-import-enabled=true",
        "rag.chat.llm.enabled=false"
})
@Transactional
class EvaluationIntegrityIntegrationTests {

    @Autowired
    private EvaluationService evaluations;

    @Autowired
    private EvaluationGovernanceService governance;

    @Autowired
    private EvaluationReleaseReportService releaseReports;

    @Autowired
    private AnswerProfileService answerProfiles;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private PlatformUserPrincipal admin;

    @BeforeEach
    void createAdmin() {
        UserEntity user = users.saveAndFlush(new UserEntity(
                "evaluation-fix-" + UUID.randomUUID().toString().substring(0, 8),
                "test-hash",
                UserRole.ADMIN
        ));
        admin = PlatformUserPrincipal.from(user);
    }

    @Test
    void releaseComponentDatasetsUseMappedPublicEvidence() {
        for (Map.Entry<String, Integer> release : Map.of(
                "graph-local-release-v1", 6,
                "answer-citation-release-v4", 4
        ).entrySet()) {
            Integer cases = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM evaluation_cases c
                    JOIN evaluation_dataset_versions v
                      ON v.id = c.dataset_version_id
                    WHERE v.version = ?
                      AND c.mapping_status = 'MAPPED'
                      AND jsonb_array_length(c.mapping_requirements) > 0
                    """,
                    Integer.class, release.getKey()
            );
            assertThat(cases).isEqualTo(release.getValue());
        }
    }

    @Test
    void blockedRunCannotBeRetriedIntoPending() {
        var subject = evaluations.createSubject(
                "blocked-answer",
                evaluations.targets().stream()
                        .filter(target -> target.subjectType()
                                == SubjectType.ANSWER_CITATION)
                        .filter(target -> "BLOCKED_PREREQUISITE".equals(
                                target.readinessStatus()
                        ))
                        .findFirst()
                        .orElseThrow()
                        .id(),
                admin
        );
        RunView blocked = evaluations.createRun(
                subject.id(),
                "answer-citation-release-v4",
                "blocked-run",
                admin
        );

        assertThat(blocked.status()).isEqualTo("BLOCKED_PREREQUISITE");
        assertThatThrownBy(() -> evaluations.retry(
                blocked.id(), "retry blocked", "retry-blocked", admin
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode())
                        .isEqualTo("RUN_PREREQUISITE_BLOCKED")
        );
    }

    @Test
    void cancellingAnExpiredLeaseConvergesToCancelled() {
        UUID subjectId = readySubject(SubjectType.RETRIEVAL);
        RunView pending = evaluations.createRun(
                subjectId, "retrieval-golden-v2", "expired-cancel", admin
        );
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'RUNNING', attempt = 1,
                    lease_owner = 'dead-worker',
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                WHERE id = ?
                """,
                pending.id()
        );

        RunView cancelled = evaluations.cancel(
                pending.id(), "cancel expired lease", admin
        );

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.leaseOwner()).isNull();
        Integer terminalEvents = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_run_events
                WHERE run_id = ? AND event_type = 'CANCELLED'
                """,
                Integer.class, pending.id()
        );
        assertThat(terminalEvents).isEqualTo(1);
    }

    @Test
    void idempotencyKeyRejectsDifferentRunRequest() {
        UUID first = readySubject(SubjectType.RETRIEVAL);
        UUID second = readySubject(SubjectType.RETRIEVAL);
        evaluations.createRun(
                first, "retrieval-golden-v2", "same-request-key", admin
        );

        assertThatThrownBy(() -> evaluations.createRun(
                second, "retrieval-golden-v2", "same-request-key", admin
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED")
        );
    }

    @Test
    void contractSmokeCannotPublishARealBaseline() {
        UUID subjectId = readySubject(SubjectType.RETRIEVAL);
        RunView run = evaluations.createRun(
                subjectId, "retrieval-golden-v2", "smoke-baseline", admin
        );
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'SUCCEEDED',
                    completed_cases = total_cases,
                    succeeded_cases = total_cases,
                    completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                run.id()
        );

        assertThatThrownBy(() -> governance.publishBaseline(
                run.id(), "must-not-publish", "real evaluator missing", admin
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode())
                        .isEqualTo("BASELINE_GATE_BLOCKED")
        );
    }

    @Test
    void answerProfileMustMatchTheEnabledRuntimeBeforePublication() {
        answerProfiles.create(
                new CreateAnswerProfileRequest(
                        "disabled-runtime-profile",
                        "OPENAI_COMPATIBLE",
                        "test-model",
                        "runtime",
                        "https://example.invalid/v1",
                        "phase7b-evidence-citation-v1",
                        "phase10-stategraph-v1",
                        15000,
                        512,
                        false,
                        false,
                        "test mismatch"
                ),
                admin
        );

        assertThatThrownBy(() -> answerProfiles.publish(
                "disabled-runtime-profile", "must match runtime", admin
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode())
                        .isEqualTo("ANSWER_PROFILE_RUNTIME_MISMATCH")
        );
    }

    @Test
    void multiformatSubjectCannotBypassTheReleaseFreezeContract() {
        UUID targetId = evaluations.targets().stream()
                .filter(target -> target.subjectType()
                        == SubjectType.MULTIFORMAT_RELEASE)
                .findFirst()
                .orElseThrow()
                .id();

        assertThatThrownBy(() -> evaluations.createSubject(
                "bypass-multiformat-freeze", targetId, admin
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode())
                        .isEqualTo("MULTIFORMAT_RELEASE_FREEZE_REQUIRED")
        );
    }

    @Test
    void executionReportUsesImmutableCaseResultSnapshotWithoutChatRun() {
        UUID subjectId = readySubject(SubjectType.RETRIEVAL);
        RunView run = evaluations.createRun(
                subjectId, "retrieval-golden-v2", "snapshot-report", admin
        );
        UUID caseId = jdbc.queryForObject(
                """
                SELECT case_row.id
                FROM evaluation_cases case_row
                JOIN evaluation_runs run
                  ON run.dataset_version_id = case_row.dataset_version_id
                WHERE run.id = ?
                ORDER BY case_row.case_key
                LIMIT 1
                """,
                UUID.class,
                run.id()
        );
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'RUNNING', attempt = 1,
                    lease_owner = 'snapshot-test',
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute',
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                run.id()
        );
        jdbc.update(
                """
                INSERT INTO evaluation_case_results (
                    id, run_id, case_id, dataset_version_id,
                    evaluator_version, status,
                    output_data, duration_ms
                ) VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?::jsonb, 1)
                """,
                UUID.randomUUID(), run.id(), caseId,
                run.datasetVersionId(), run.evaluatorVersion(),
                """
                {
                  "queryProfileVersion":"query-profile-v1",
                  "plannerCallCount":2,
                  "retrievalCallCount":6,
                  "rerankCallCount":1,
                  "queryDegraded":true,
                  "memoryInjectedCount":3,
                  "memoryUsedCount":2,
                  "memoryTokenCount":144
                }
                """
        );

        var snapshot = releaseReports.executionBaseline(
                run.id(), Map.of("memoryContractVersion", "memory-v1")
        );

        assertThat(snapshot.queryProfileVersion())
                .isEqualTo("query-profile-v1");
        assertThat(snapshot.plannerCallCount()).isEqualTo(2);
        assertThat(snapshot.retrievalCallCount()).isEqualTo(6);
        assertThat(snapshot.rerankCallCount()).isEqualTo(1);
        assertThat(snapshot.queryDegradedCount()).isEqualTo(1);
        assertThat(snapshot.memoryContractVersion()).isEqualTo("memory-v1");
        assertThat(snapshot.memoryInjectedCount()).isEqualTo(3);
        assertThat(snapshot.memoryUsedCount()).isEqualTo(2);
        assertThat(snapshot.memoryTokenCount()).isEqualTo(144);
    }

    private UUID readySubject(SubjectType type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO evaluation_subjects (
                    id, name, subject_type, snapshot, snapshot_hash,
                    readiness_status, created_by
                ) VALUES (?, ?, ?, '{}'::JSONB, ?, 'READY', ?)
                """,
                id,
                "ready-" + id.toString().substring(0, 8),
                type.name(),
                "a".repeat(64),
                admin.id()
        );
        return id;
    }
}
