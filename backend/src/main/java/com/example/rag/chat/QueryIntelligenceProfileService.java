package com.example.rag.chat;

import com.example.rag.chat.QueryIntelligenceContracts.CreateProfileRequest;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.chat.QueryIntelligenceContracts.PublicationEventView;
import com.example.rag.chat.QueryIntelligenceContracts.RuntimeView;
import com.example.rag.common.ApiException;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class QueryIntelligenceProfileService {

    static final String PROMPT_VERSION = "phase12c-route-rewrite-v1";
    static final String SCHEMA_VERSION = "phase12c-query-route-v1";
    static final String COUNTER_TYPE = "CONSERVATIVE_UTF8";
    static final String COUNTER_VERSION = "conservative-utf8-v1";

    private final JdbcTemplate jdbc;
    private final ChatProperties chatProperties;

    QueryIntelligenceProfileService(
            JdbcTemplate jdbc,
            Environment environment
    ) {
        this.jdbc = jdbc;
        this.chatProperties = Binder.get(environment)
                .bind("rag.chat", ChatProperties.class)
                .orElseGet(ChatProperties::new);
    }

    public RuntimeView runtime() {
        ChatProperties.Llm llm = chatProperties.getLlm();
        return new RuntimeView(
                llm.isEnabled(),
                "OPENAI_COMPATIBLE",
                text(llm.getModel()),
                defaultText(llm.getModelRevision(), "runtime"),
                PROMPT_VERSION,
                SCHEMA_VERSION,
                COUNTER_TYPE,
                COUNTER_VERSION
        );
    }

    public ProfileView active() {
        String version = currentVersion(false);
        return version == null ? null : find(version);
    }

    public ProfileView find(String version) {
        List<ProfileView> rows = jdbc.query(
                profileSelect() + " WHERE profile.version = ?",
                this::profile,
                version
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "QUERY_PROFILE_NOT_FOUND",
                    "QueryIntelligenceProfile 不存在"
            );
        }
        return rows.getFirst();
    }

    public List<ProfileView> profiles() {
        return jdbc.query(
                profileSelect()
                        + " ORDER BY profile.created_at DESC, profile.version",
                this::profile
        );
    }

    @Transactional
    ProfileView create(
            CreateProfileRequest request,
            PlatformUserPrincipal user
    ) {
        try {
            jdbc.update(
                    """
                    INSERT INTO query_intelligence_profiles (
                        version, enabled, planner_provider, planner_model,
                        planner_revision, prompt_version, schema_version,
                        token_counter_type, token_counter_version,
                        model_context_tokens, history_message_limit,
                        history_token_budget, history_context_percent,
                        max_sub_queries, max_retrieval_rounds,
                        planner_call_limit, timeout_ms, fallback_mode,
                        reason, created_by
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?
                    )
                    """,
                    request.version(),
                    request.enabled(),
                    request.plannerProvider().strip(),
                    request.plannerModel().strip(),
                    request.plannerRevision().strip(),
                    request.promptVersion().strip(),
                    request.schemaVersion().strip(),
                    request.tokenCounterType(),
                    request.tokenCounterVersion().strip(),
                    request.modelContextTokens(),
                    request.historyMessageLimit(),
                    request.historyTokenBudget(),
                    request.historyContextPercent(),
                    request.maxSubQueries(),
                    request.maxRetrievalRounds(),
                    request.plannerCallLimit(),
                    request.timeoutMs(),
                    request.fallbackMode(),
                    request.reason().strip(),
                    actor(user)
            );
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "QUERY_PROFILE_EXISTS",
                    "QueryIntelligenceProfile 版本已存在"
            );
        }
        return find(request.version());
    }

    @Transactional
    PublicationEventView publish(
            String version,
            UUID intentRunId,
            UUID multiTurnRunId,
            String reason,
            PlatformUserPrincipal user
    ) {
        ProfileView profile = find(version);
        requireRuntimeMatch(profile);
        requireEvaluationGate(
                version,
                intentRunId,
                "INTENT",
                Set.of("phase12c.hard.intent_route_unique")
        );
        requireEvaluationGate(
                version,
                multiTurnRunId,
                "MULTI_TURN",
                Set.of(
                        "phase12c.hard.multi_turn_history",
                        "phase12c.hard.query_budget",
                        "phase12c.hard.multi_turn_citation"
                )
        );
        lockPublication();
        String current = currentVersion(true);
        if (version.equals(current)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "QUERY_PROFILE_ALREADY_ACTIVE",
                    "该 QueryIntelligenceProfile 已经发布"
            );
        }
        return switchPublication(
                version, current, "PUBLISH", reason, actor(user),
                intentRunId, multiTurnRunId
        );
    }

    @Transactional
    PublicationEventView rollback(
            String version,
            String reason,
            PlatformUserPrincipal user
    ) {
        ProfileView profile = find(version);
        requireRuntimeMatch(profile);
        lockPublication();
        String current = currentVersion(true);
        if (version.equals(current)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "QUERY_PROFILE_ALREADY_ACTIVE",
                    "该 QueryIntelligenceProfile 已经发布"
            );
        }
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM query_intelligence_profile_publication_events
                WHERE profile_version = ?
                """,
                Integer.class,
                version
        );
        if (count == null || count == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "QUERY_PROFILE_NOT_PUBLISHED",
                    "只能回滚到曾发布过的 QueryIntelligenceProfile"
            );
        }
        return switchPublication(
                version, current, "ROLLBACK", reason, actor(user),
                null, null
        );
    }

    List<PublicationEventView> events() {
        return jdbc.query(
                """
                SELECT id, profile_version, previous_profile_version,
                       intent_evaluation_run_id,
                       multi_turn_evaluation_run_id,
                       action, reason, created_at
                FROM query_intelligence_profile_publication_events
                ORDER BY id DESC
                """,
                (rs, row) -> new PublicationEventView(
                        rs.getLong("id"),
                        rs.getString("profile_version"),
                        rs.getString("previous_profile_version"),
                        rs.getObject(
                                "intent_evaluation_run_id", UUID.class
                        ),
                        rs.getObject(
                                "multi_turn_evaluation_run_id", UUID.class
                        ),
                        rs.getString("action"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()
                )
        );
    }

    private PublicationEventView switchPublication(
            String version,
            String previous,
            String action,
            String reason,
            UUID actor,
            UUID intentRunId,
            UUID multiTurnRunId
    ) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO query_intelligence_profile_publication_events (
                    profile_version, previous_profile_version,
                    intent_evaluation_run_id,
                    multi_turn_evaluation_run_id,
                    action, actor, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                version,
                previous,
                intentRunId,
                multiTurnRunId,
                action,
                actor,
                reason.strip()
        );
        if (eventId == null) {
            throw new IllegalStateException(
                    "Query profile publication event was not created"
            );
        }
        jdbc.update(
                """
                INSERT INTO query_intelligence_profile_publications (
                    singleton_id, profile_version, publication_event_id
                ) VALUES (1, ?, ?)
                ON CONFLICT (singleton_id) DO UPDATE
                SET profile_version = EXCLUDED.profile_version,
                    publication_event_id = EXCLUDED.publication_event_id,
                    published_at = CURRENT_TIMESTAMP
                """,
                version,
                eventId
        );
        return events().stream()
                .filter(event -> event.eventId() == eventId)
                .findFirst()
                .orElseThrow();
    }

    private void requireEvaluationGate(
            String profileVersion,
            UUID runId,
            String subjectType,
            Set<String> expectedMetrics
    ) {
        if (runId == null) {
            throw gateBlocked(subjectType + "_RUN_REQUIRED");
        }
        Integer eligible = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_runs run
                JOIN evaluation_subjects subject
                  ON subject.id = run.evaluation_subject_id
                JOIN evaluation_targets target
                  ON target.id = subject.target_id
                WHERE run.id = ?
                  AND run.status = 'SUCCEEDED'
                  AND run.evaluator_version LIKE 'phase12c-real-%'
                  AND run.completed_cases = run.total_cases
                  AND run.failed_cases = 0
                  AND run.blocked_cases = 0
                  AND subject.subject_type = ?
                  AND target.readiness_status = 'READY'
                  AND target.snapshot ->> 'queryProfileVersion' = ?
                  AND COALESCE(
                      (target.snapshot ->>
                          'queryProfileRuntimeMatched')::BOOLEAN,
                      FALSE
                  )
                """,
                Integer.class,
                runId,
                subjectType,
                profileVersion
        );
        if (eligible == null || eligible != 1) {
            throw gateBlocked(subjectType + "_RUN_NOT_ELIGIBLE");
        }
        List<GateMetric> rows = jdbc.query(
                """
                SELECT result.id AS result_id,
                       metric.metric_key,
                       metric.status,
                       metric.metric_value
                FROM evaluation_case_results result
                JOIN evaluation_cases case_row
                  ON case_row.id = result.case_id
                LEFT JOIN evaluation_metric_results metric
                  ON metric.case_result_id = result.id
                 AND (
                     metric.metric_key LIKE 'phase11b.hard.%'
                     OR metric.metric_key LIKE 'phase12c.hard.%'
                 )
                WHERE result.run_id = ?
                  AND case_row.case_type = ?
                ORDER BY result.id, metric.metric_key
                """,
                (rs, row) -> new GateMetric(
                        rs.getObject("result_id", UUID.class),
                        rs.getString("metric_key"),
                        rs.getString("status"),
                        rs.getBigDecimal("metric_value")
                ),
                runId,
                subjectType
        );
        Map<UUID, List<GateMetric>> byResult = new LinkedHashMap<>();
        rows.forEach(metric -> byResult.computeIfAbsent(
                metric.resultId(), ignored -> new ArrayList<>()
        ).add(metric));
        boolean valid = !byResult.isEmpty()
                && byResult.values().stream().allMatch(metrics -> {
            Set<String> actual = metrics.stream()
                    .map(GateMetric::key)
                    .filter(java.util.Objects::nonNull)
                    .collect(
                            LinkedHashSet::new,
                            Set::add,
                            Set::addAll
                    );
            return actual.equals(expectedMetrics)
                    && metrics.stream()
                    .filter(metric -> metric.key() != null)
                    .allMatch(metric ->
                            "MEASURED".equals(metric.status())
                                    && metric.value() != null
                                    && BigDecimal.ONE.compareTo(
                                    metric.value()) == 0
                    );
        });
        if (!valid) {
            throw gateBlocked(subjectType + "_HARD_METRICS_FAILED");
        }
    }

    private void lockPublication() {
        jdbc.execute(
                "SELECT pg_advisory_xact_lock(12012031)"
        );
    }

    private static ApiException gateBlocked(String reason) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "QUERY_PROFILE_EVALUATION_GATE_BLOCKED",
                "QueryIntelligenceProfile 发布门禁未通过：" + reason
        );
    }

    private String currentVersion(boolean lock) {
        List<String> versions = jdbc.query(
                """
                SELECT profile_version
                FROM query_intelligence_profile_publications
                WHERE singleton_id = 1
                """ + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> rs.getString(1)
        );
        return versions.isEmpty() ? null : versions.getFirst();
    }

    private void requireRuntimeMatch(ProfileView profile) {
        if (matchesRuntime(profile)) {
            return;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "QUERY_PROFILE_RUNTIME_MISMATCH",
                "QueryIntelligenceProfile 与当前模型或 Counter 不匹配"
        );
    }

    public boolean matchesRuntime(ProfileView profile) {
        RuntimeView runtime = runtime();
        return runtime.llmEnabled()
                && profile != null
                && profile.plannerProvider().equals(runtime.plannerProvider())
                && profile.plannerModel().equals(runtime.plannerModel())
                && profile.plannerRevision().equals(runtime.plannerRevision())
                && profile.promptVersion().equals(runtime.promptVersion())
                && profile.schemaVersion().equals(runtime.schemaVersion())
                && profile.tokenCounterType().equals(
                runtime.supportedCounterType())
                && profile.tokenCounterVersion().equals(
                runtime.supportedCounterVersion());
    }

    private ProfileView profile(ResultSet rs, int row) throws SQLException {
        return new ProfileView(
                rs.getString("version"),
                rs.getBoolean("enabled"),
                rs.getString("planner_provider"),
                rs.getString("planner_model"),
                rs.getString("planner_revision"),
                rs.getString("prompt_version"),
                rs.getString("schema_version"),
                rs.getString("token_counter_type"),
                rs.getString("token_counter_version"),
                rs.getInt("model_context_tokens"),
                rs.getInt("history_message_limit"),
                rs.getInt("history_token_budget"),
                rs.getInt("history_context_percent"),
                rs.getInt("max_sub_queries"),
                rs.getInt("max_retrieval_rounds"),
                rs.getInt("planner_call_limit"),
                rs.getInt("timeout_ms"),
                rs.getString("fallback_mode"),
                rs.getString("reason"),
                rs.getBoolean("published"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static String profileSelect() {
        return """
                SELECT profile.version, profile.enabled,
                       profile.planner_provider, profile.planner_model,
                       profile.planner_revision, profile.prompt_version,
                       profile.schema_version, profile.token_counter_type,
                       profile.token_counter_version,
                       profile.model_context_tokens,
                       profile.history_message_limit,
                       profile.history_token_budget,
                       profile.history_context_percent,
                       profile.max_sub_queries,
                       profile.max_retrieval_rounds,
                       profile.planner_call_limit, profile.timeout_ms,
                       profile.fallback_mode, profile.reason,
                       profile.created_at,
                       publication.profile_version IS NOT NULL AS published
                FROM query_intelligence_profiles profile
                LEFT JOIN query_intelligence_profile_publications publication
                  ON publication.singleton_id = 1
                 AND publication.profile_version = profile.version
                """;
    }

    private static UUID actor(PlatformUserPrincipal user) {
        if (user == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "请先登录"
            );
        }
        return user.id();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record GateMetric(
            UUID resultId,
            String key,
            String status,
            BigDecimal value
    ) {
    }
}
