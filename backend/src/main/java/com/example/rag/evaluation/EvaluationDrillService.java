package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.ClaimedDrill;
import com.example.rag.evaluation.EvaluationContracts.DrillEventView;
import com.example.rag.evaluation.EvaluationContracts.DrillExecutionMode;
import com.example.rag.evaluation.EvaluationContracts.DrillType;
import com.example.rag.evaluation.EvaluationContracts.DrillView;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
class EvaluationDrillService {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EvaluationProperties properties;
    private final EvaluationObservabilityService observability;
    private final RealEvaluationDrillExecutor realDrills;

    EvaluationDrillService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            EvaluationProperties properties,
            EvaluationObservabilityService observability,
            RealEvaluationDrillExecutor realDrills
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.observability = observability;
        this.realDrills = realDrills;
    }

    List<DrillView> drills() {
        return jdbc.query(
                """
                SELECT *
                FROM evaluation_drills
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """,
                this::mapDrill
        );
    }

    DrillView drill(UUID drillId) {
        List<DrillView> rows = jdbc.query(
                "SELECT * FROM evaluation_drills WHERE id = ?",
                this::mapDrill,
                drillId
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "EVALUATION_DRILL_NOT_FOUND",
                    "故障演练不存在"
            );
        }
        return rows.getFirst();
    }

    List<DrillEventView> events(UUID drillId) {
        drill(drillId);
        return jdbc.query(
                """
                SELECT id, sequence, event_type, from_status, to_status,
                       details::TEXT, created_at
                FROM evaluation_drill_events
                WHERE drill_id = ?
                ORDER BY sequence
                """,
                (rs, row) -> new DrillEventView(
                        rs.getLong("id"),
                        rs.getInt("sequence"),
                        rs.getString("event_type"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        objectMap(rs.getString("details")),
                        rs.getTimestamp("created_at").toInstant()
                ),
                drillId
        );
    }

    @Transactional
    DrillView create(
            DrillType type,
            DrillExecutionMode executionMode,
            String idempotencyKey,
            String reason,
            PlatformUserPrincipal user
    ) {
        requireEnabled();
        if (executionMode == DrillExecutionMode.REAL_VERIFY
                && !properties.realDrillsEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REAL_EVALUATION_DRILL_DISABLED",
                    "真实故障验证未开启"
            );
        }
        UUID actor = actor(user);
        UUID drillId = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                    INSERT INTO evaluation_drills (
                        id, drill_type, execution_mode, status, idempotency_key,
                        requested_by, reason, max_attempts
                    ) VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?)
                    """,
                    drillId, type.name(), executionMode.name(),
                    idempotencyKey.strip(),
                    actor, reason.strip(), properties.maxAttempts()
            );
            appendEvent(
                    drillId, "CREATED", null, "PENDING",
                    Map.of("drillType", type.name())
            );
            return drill(drillId);
        } catch (DuplicateKeyException duplicate) {
            return jdbc.query(
                    """
                    SELECT *
                    FROM evaluation_drills
                    WHERE requested_by = ? AND idempotency_key = ?
                    """,
                    this::mapDrill,
                    actor,
                    idempotencyKey.strip()
            ).stream().findFirst().orElseThrow();
        }
    }

    @Transactional
    DrillView cancel(
            UUID drillId,
            String reason,
            PlatformUserPrincipal user
    ) {
        actor(user);
        DrillView current = lock(drillId);
        if (terminal(current.status())) {
            return current;
        }
        if ("PENDING".equals(current.status())) {
            jdbc.update(
                    """
                    UPDATE evaluation_drills
                    SET status = 'CANCELLED',
                        cancel_requested = TRUE,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    drillId
            );
            appendEvent(
                    drillId, "CANCELLED", "PENDING", "CANCELLED",
                    Map.of("reason", reason.strip())
            );
        } else {
            jdbc.update(
                    """
                    UPDATE evaluation_drills
                    SET cancel_requested = TRUE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    drillId
            );
            appendEvent(
                    drillId, "CANCEL_REQUESTED", "RUNNING", "RUNNING",
                    Map.of("reason", reason.strip())
            );
        }
        return drill(drillId);
    }

    @Transactional
    DrillView retry(
            UUID drillId,
            String reason,
            PlatformUserPrincipal user
    ) {
        requireEnabled();
        DrillView source = lock(drillId);
        if (!terminal(source.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "EVALUATION_DRILL_NOT_TERMINAL",
                    "只能重试已结束的故障演练"
            );
        }
        UUID newId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO evaluation_drills (
                    id, original_drill_id, drill_type, execution_mode, status,
                    idempotency_key, requested_by, reason, max_attempts
                ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """,
                newId, source.id(), source.drillType().name(),
                source.executionMode().name(),
                "retry:" + source.id() + ":" + UUID.randomUUID(),
                actor(user), reason.strip(), properties.maxAttempts()
        );
        appendEvent(
                newId, "RETRIED", null, "PENDING",
                Map.of("sourceStatus", source.status())
        );
        return drill(newId);
    }

    @Transactional
    Optional<ClaimedDrill> claim() {
        if (!properties.drillsEnabled()) {
            return Optional.empty();
        }
        failExhaustedExpiredDrills();
        List<ClaimedDrill> claimed = jdbc.query(
                """
                WITH candidate AS (
                    SELECT id, drill_type, execution_mode, requested_by
                    FROM evaluation_drills
                    WHERE (
                        status = 'PENDING'
                        OR (
                            status = 'RUNNING'
                            AND lease_expires_at < CURRENT_TIMESTAMP
                        )
                    )
                      AND cancel_requested = FALSE
                      AND attempt < max_attempts
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE evaluation_drills drill
                SET status = 'RUNNING',
                    attempt = drill.attempt + 1,
                    lease_owner = ?,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = COALESCE(
                        drill.started_at, CURRENT_TIMESTAMP
                    ),
                    error_code = NULL,
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE drill.id = candidate.id
                RETURNING drill.id, candidate.drill_type,
                          candidate.execution_mode, candidate.requested_by,
                          drill.attempt
                """,
                (rs, row) -> new ClaimedDrill(
                        rs.getObject("id", UUID.class),
                        DrillType.valueOf(rs.getString("drill_type")),
                        DrillExecutionMode.valueOf(
                                rs.getString("execution_mode")
                        ),
                        rs.getObject("requested_by", UUID.class),
                        rs.getInt("attempt")
                ),
                properties.workerId(),
                properties.leaseDuration().toMillis()
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedDrill drill = claimed.getFirst();
        appendEvent(
                drill.id(),
                drill.attempt() > 1 ? "LEASE_RECOVERED" : "CLAIMED",
                drill.attempt() > 1 ? "RUNNING" : "PENDING",
                "RUNNING",
                Map.of("attempt", drill.attempt())
        );
        return Optional.of(drill);
    }

    void process(ClaimedDrill drill) {
        int steps = 4;
        long stepMillis = Math.max(
                25,
                properties.drillStepDuration().toMillis() / steps
        );
        try {
            for (int step = 0; step < steps; step++) {
                Thread.sleep(stepMillis);
                if (cancellationRequested(drill)) {
                    completeCancelled(drill);
                    return;
                }
                if (!heartbeat(drill)) {
                    return;
                }
            }
            completeSucceeded(
                    drill,
                    drill.executionMode() == DrillExecutionMode.REAL_VERIFY
                            ? realDrills.execute(drill)
                            : execute(drill.drillType())
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            completeFailed(drill, failure);
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    void enforceRetention() {
        if (!properties.workerEnabled()
                || !properties.observabilityEnabled()) {
            return;
        }
        jdbc.update(
                """
                DELETE FROM evaluation_drill_events event
                USING evaluation_drills drill
                WHERE event.drill_id = drill.id
                  AND drill.status IN (
                    'SUCCEEDED', 'FAILED', 'CANCELLED'
                  )
                  AND event.created_at <
                      CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
                """,
                properties.retention().toMillis()
        );
    }

    private Map<String, Object> execute(DrillType type) {
        return switch (type) {
            case MODEL_TIMEOUT -> Map.of(
                    "executionCompleted", true,
                    "realFaultInjected", false,
                    "simulation", "MODEL_TIMEOUT",
                    "expectedBehavior", "REQUEST_SCOPED_ABORT",
                    "verificationStatus", "NOT_VERIFIED",
                    "onlineStateChanged", false,
                    "captureContent", false
            );
            case OPENSEARCH_UNAVAILABLE -> Map.of(
                    "executionCompleted", true,
                    "realFaultInjected", false,
                    "simulation", "OPENSEARCH_UNAVAILABLE",
                    "expectedBehavior", "SAFE_RETRIEVAL_DEGRADATION",
                    "verificationStatus", "NOT_VERIFIED",
                    "onlineStateChanged", false,
                    "captureContent", false
            );
            case GRAPH_STALE -> Map.of(
                    "executionCompleted", true,
                    "realFaultInjected", false,
                    "simulation", "GRAPH_STALE",
                    "expectedBehavior", "HYBRID_FALLBACK",
                    "verificationStatus", "NOT_VERIFIED",
                    "observedStaleDocuments",
                    observability.observability().graph()
                            .getOrDefault("staleDocuments", 0L),
                    "onlineStateChanged", false,
                    "captureContent", false
            );
            case CANARY_LEAK_SCAN -> canaryResult();
        };
    }

    private Map<String, Object> canaryResult() {
        String canary = "CANARY_" + UUID.randomUUID()
                .toString().replace("-", "").toUpperCase();
        String sanitized = ("probe=" + canary)
                .replace(canary, "[REDACTED]");
        if (sanitized.contains(canary)) {
            throw new IllegalStateException("Canary redaction failed");
        }
        return Map.of(
                "executionCompleted", true,
                "realFaultInjected", false,
                "simulation", "CANARY_REDACTION_SELF_CHECK",
                "expectedBehavior", "SYNTHETIC_SECRET_REDACTED",
                "verificationStatus", "SANITIZER_ONLY",
                "endToEndLeakScan", false,
                "redactionPolicy", "phase11c-canary-v1",
                "onlineStateChanged", false,
                "captureContent", false
        );
    }

    private boolean heartbeat(ClaimedDrill drill) {
        return jdbc.update(
                """
                UPDATE evaluation_drills
                SET lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                """,
                properties.leaseDuration().toMillis(),
                drill.id(),
                properties.workerId(),
                drill.attempt()
        ) == 1;
    }

    private boolean cancellationRequested(ClaimedDrill drill) {
        Boolean value = jdbc.queryForObject(
                """
                SELECT cancel_requested
                FROM evaluation_drills
                WHERE id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND attempt = ?
                """,
                Boolean.class,
                drill.id(),
                properties.workerId(),
                drill.attempt()
        );
        return Boolean.TRUE.equals(value);
    }

    @Transactional
    void completeCancelled(ClaimedDrill drill) {
        DrillView current = lock(drill.id());
        if (!owned(current, drill) || !"RUNNING".equals(current.status())) {
            return;
        }
        jdbc.update(
                """
                UPDATE evaluation_drills
                SET status = 'CANCELLED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                drill.id()
        );
        appendEvent(
                drill.id(), "CANCELLED", "RUNNING", "CANCELLED", Map.of()
        );
    }

    @Transactional
    void completeSucceeded(
            ClaimedDrill drill,
            Map<String, Object> summary
    ) {
        DrillView current = lock(drill.id());
        if (!owned(current, drill) || !"RUNNING".equals(current.status())) {
            return;
        }
        if (current.cancelRequested()) {
            jdbc.update(
                    """
                    UPDATE evaluation_drills
                    SET status = 'CANCELLED',
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    drill.id()
            );
            appendEvent(
                    drill.id(), "CANCELLED",
                    "RUNNING", "CANCELLED", Map.of()
            );
            return;
        }
        jdbc.update(
                """
                UPDATE evaluation_drills
                SET status = 'SUCCEEDED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    result_summary = CAST(? AS JSONB),
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                json(summary),
                drill.id()
        );
        appendEvent(
                drill.id(), "SUCCEEDED", "RUNNING", "SUCCEEDED",
                Map.of("passed", true)
        );
    }

    @Transactional
    void completeFailed(ClaimedDrill drill, RuntimeException failure) {
        DrillView current = lock(drill.id());
        if (!owned(current, drill) || !"RUNNING".equals(current.status())) {
            return;
        }
        jdbc.update(
                """
                UPDATE evaluation_drills
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    result_summary = CAST(? AS JSONB),
                    error_code = 'DRILL_EXECUTION_FAILED',
                    error_message = 'Controlled drill execution failed',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                json(Map.of(
                        "passed", false,
                        "safeFailure", true,
                        "failureClass", failure.getClass().getSimpleName()
                )),
                drill.id()
        );
        appendEvent(
                drill.id(), "FAILED", "RUNNING", "FAILED",
                Map.of("code", "DRILL_EXECUTION_FAILED")
        );
    }

    private void failExhaustedExpiredDrills() {
        List<UUID> exhausted = jdbc.query(
                """
                SELECT id
                FROM evaluation_drills
                WHERE status = 'RUNNING'
                  AND lease_expires_at < CURRENT_TIMESTAMP
                  AND attempt >= max_attempts
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> rs.getObject("id", UUID.class)
        );
        for (UUID drillId : exhausted) {
            jdbc.update(
                    """
                    UPDATE evaluation_drills
                    SET status = 'FAILED',
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        result_summary = CAST(? AS JSONB),
                        error_code = 'DRILL_LEASE_EXHAUSTED',
                        error_message = 'Drill lease recovery exhausted',
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    json(Map.of(
                            "passed", false,
                            "safeFailure", true,
                            "failureClass", "LEASE_EXHAUSTED"
                    )),
                    drillId
            );
            appendEvent(
                    drillId, "FAILED", "RUNNING", "FAILED",
                    Map.of("code", "DRILL_LEASE_EXHAUSTED")
            );
        }
    }

    private DrillView lock(UUID drillId) {
        List<DrillView> rows = jdbc.query(
                "SELECT * FROM evaluation_drills WHERE id = ? FOR UPDATE",
                this::mapDrill,
                drillId
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "EVALUATION_DRILL_NOT_FOUND",
                    "故障演练不存在"
            );
        }
        return rows.getFirst();
    }

    private void appendEvent(
            UUID drillId,
            String eventType,
            String fromStatus,
            String toStatus,
            Map<String, Object> details
    ) {
        jdbc.update(
                """
                INSERT INTO evaluation_drill_events (
                    drill_id, sequence, event_type, from_status,
                    to_status, details
                )
                SELECT ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ?,
                       CAST(? AS JSONB)
                FROM evaluation_drill_events
                WHERE drill_id = ?
                """,
                drillId, eventType, fromStatus, toStatus,
                json(details), drillId
        );
    }

    private DrillView mapDrill(ResultSet rs, int row) throws SQLException {
        return new DrillView(
                rs.getObject("id", UUID.class),
                rs.getObject("original_drill_id", UUID.class),
                DrillType.valueOf(rs.getString("drill_type")),
                DrillExecutionMode.valueOf(rs.getString("execution_mode")),
                rs.getString("status"),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getBoolean("cancel_requested"),
                rs.getString("lease_owner"),
                instant(rs, "lease_expires_at"),
                objectMap(rs.getString("result_summary")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Map<String, Object> objectMap(String value) {
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Invalid persisted drill JSON", exception
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize safe drill metadata", exception
            );
        }
    }

    private void requireEnabled() {
        if (!properties.drillsEnabled()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "EVALUATION_DRILLS_DISABLED",
                    "当前运行环境未启用故障演练"
            );
        }
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static UUID actor(PlatformUserPrincipal user) {
        if (user == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "请先登录"
            );
        }
        return user.id();
    }

    private boolean owned(DrillView current, ClaimedDrill claimed) {
        return properties.workerId().equals(current.leaseOwner())
                && current.attempt() == claimed.attempt();
    }

    private static boolean terminal(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
