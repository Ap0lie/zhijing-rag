package com.example.rag.chat;

import com.example.rag.chat.QueryIntelligenceContracts.CreateProfileRequest;
import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.RunView;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.evaluation.EvaluationService;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "rag.evaluation.worker-enabled=false",
        "rag.evaluation.catalog-import-enabled=true",
        "rag.evaluation.real-enabled=true",
        "rag.chat.llm.enabled=true",
        "rag.chat.llm.model=phase12-test-model",
        "rag.chat.llm.model-revision=phase12-test-revision"
})
@Transactional
class QueryIntelligenceProfileIntegrationTests {

    @Autowired
    private QueryIntelligenceProfileService profiles;

    @Autowired
    private EvaluationService evaluations;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private PlatformUserPrincipal admin;

    @BeforeEach
    void setUp() {
        UserEntity user = users.saveAndFlush(new UserEntity(
                "query-gate-"
                        + UUID.randomUUID().toString().substring(0, 8),
                "test-hash",
                UserRole.ADMIN
        ));
        admin = PlatformUserPrincipal.from(user);
    }

    @Test
    void publicationRequiresPassingIntentAndMultiTurnRuns() {
        String version = createProfile();
        RunView intent = run(
                version,
                SubjectType.INTENT,
                "query-intent-golden-v1",
                "intent-gate"
        );
        RunView multiTurn = run(
                version,
                SubjectType.MULTI_TURN,
                "query-multiturn-golden-v1",
                "multi-turn-gate"
        );

        assertThatThrownBy(() -> profiles.publish(
                version,
                intent.id(),
                multiTurn.id(),
                "runs are not complete",
                admin
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo(
                        "QUERY_PROFILE_EVALUATION_GATE_BLOCKED")
        );

        pass(intent, List.of(
                "phase12c.hard.intent_route_unique"
        ));
        pass(multiTurn, List.of(
                "phase12c.hard.multi_turn_history",
                "phase12c.hard.query_budget",
                "phase12c.hard.multi_turn_citation"
        ));

        var event = profiles.publish(
                version,
                intent.id(),
                multiTurn.id(),
                "deterministic Phase 12 gates passed",
                admin
        );

        assertThat(event.intentRunId()).isEqualTo(intent.id());
        assertThat(event.multiTurnRunId()).isEqualTo(multiTurn.id());
        assertThat(profiles.active().version()).isEqualTo(version);
    }

    private String createProfile() {
        String version = "phase12-gate-"
                + UUID.randomUUID().toString().substring(0, 8);
        var runtime = profiles.runtime();
        profiles.create(
                new CreateProfileRequest(
                        version,
                        true,
                        runtime.plannerProvider(),
                        runtime.plannerModel(),
                        runtime.plannerRevision(),
                        runtime.promptVersion(),
                        runtime.schemaVersion(),
                        runtime.supportedCounterType(),
                        runtime.supportedCounterVersion(),
                        32_768,
                        12,
                        2_048,
                        20,
                        3,
                        2,
                        2,
                        3_000,
                        "ORIGINAL_QUERY",
                        "Phase 12 publication gate test"
                ),
                admin
        );
        return version;
    }

    private RunView run(
            String profileVersion,
            SubjectType type,
            String datasetVersion,
            String idempotencyKey
    ) {
        var frozenTarget = evaluations.targets().stream()
                .filter(target -> target.subjectType() == type)
                .filter(target -> profileVersion.equals(
                        target.snapshot().get("queryProfileVersion")))
                .findFirst()
                .orElseThrow();
        UUID targetId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO evaluation_targets (
                    id, target_key, subject_type, target_kind,
                    snapshot, snapshot_hash, readiness_status
                ) VALUES (
                    ?, ?, ?, 'READY', CAST(? AS JSONB), ?, 'READY'
                )
                """,
                targetId,
                "phase12-gate-" + type.name().toLowerCase()
                        + "-" + UUID.randomUUID(),
                type.name(),
                json(frozenTarget.snapshot()),
                frozenTarget.snapshotHash()
        );
        var subject = evaluations.createSubject(
                type.name() + " " + profileVersion,
                targetId,
                admin
        );
        return evaluations.createRun(
                subject.id(),
                datasetVersion,
                idempotencyKey,
                admin
        );
    }

    private void pass(RunView run, List<String> metricKeys) {
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'RUNNING', attempt = 1,
                    completed_cases = 0,
                    succeeded_cases = 0,
                    failed_cases = 0,
                    blocked_cases = 0,
                    lease_owner = 'phase12-gate-test',
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                    heartbeat_at = CURRENT_TIMESTAMP,
                    error_code = NULL,
                    error_message = NULL,
                    started_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                run.id()
        );
        List<UUID> caseIds = jdbc.queryForList(
                """
                SELECT id
                FROM evaluation_cases
                WHERE dataset_version_id = ?
                ORDER BY id
                """,
                UUID.class,
                run.datasetVersionId()
        );
        for (UUID caseId : caseIds) {
            UUID resultId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO evaluation_case_results (
                        id, run_id, case_id, dataset_version_id,
                        evaluator_version, status, output_data,
                        duration_ms
                    ) VALUES (
                        ?, ?, ?, ?, ?, 'SUCCEEDED', '{}'::JSONB, 1
                    )
                    """,
                    resultId,
                    run.id(),
                    caseId,
                    run.datasetVersionId(),
                    run.evaluatorVersion()
            );
            for (String key : metricKeys) {
                jdbc.update(
                        """
                        INSERT INTO evaluation_metric_results (
                            id, case_result_id, metric_key,
                            status, metric_value, details
                        ) VALUES (
                            ?, ?, ?, 'MEASURED', 1, '{}'::JSONB
                        )
                        """,
                        UUID.randomUUID(),
                        resultId,
                        key
                );
            }
        }
        jdbc.update(
                """
                UPDATE evaluation_runs
                SET status = 'SUCCEEDED',
                    completed_cases = total_cases,
                    succeeded_cases = total_cases,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                run.id()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
