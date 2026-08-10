package com.example.rag.evaluation;

import com.example.rag.chat.ChatProperties;
import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.AnswerProfilePublicationView;
import com.example.rag.evaluation.EvaluationContracts.AnswerProfileView;
import com.example.rag.evaluation.EvaluationContracts.CreateAnswerProfileRequest;
import com.example.rag.evaluation.EvaluationContracts.RuntimeAnswerProfileView;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
class AnswerProfileService {

    private static final String PROMPT_VERSION =
            "phase7b-evidence-citation-v2";
    private static final String ORCHESTRATION_VERSION =
            "phase10-stategraph-v1";

    private final JdbcTemplate jdbc;
    private final Environment environment;

    AnswerProfileService(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    RuntimeAnswerProfileView runtime() {
        ChatProperties.Llm llm = Binder.get(environment)
                .bind("rag.chat", ChatProperties.class)
                .orElseGet(ChatProperties::new)
                .getLlm();
        return new RuntimeAnswerProfileView(
                llm.isEnabled(),
                "OPENAI_COMPATIBLE",
                llm.getModel() == null ? "" : llm.getModel(),
                llm.getModelRevision() == null ? "runtime"
                        : llm.getModelRevision(),
                endpointIdentity(llm.getBaseUrl()),
                PROMPT_VERSION,
                ORCHESTRATION_VERSION,
                Math.toIntExact(llm.getTimeout().toMillis()),
                llm.getMaxOutputTokens(),
                llm.isRemoteEvidenceAllowed(),
                llm.isRemoteMemoryAllowed()
        );
    }

    AnswerProfileView active() {
        String version = currentVersion();
        return version == null ? null : find(version);
    }

    boolean matchesRuntime(AnswerProfileView profile) {
        RuntimeAnswerProfileView runtime = runtime();
        return runtime.enabled()
                && profile.modelProvider().equals(runtime.modelProvider())
                && profile.modelId().equals(runtime.modelId())
                && profile.modelRevision().equals(runtime.modelRevision())
                && profile.endpointIdentity().equals(runtime.endpointIdentity())
                && profile.promptVersion().equals(runtime.promptVersion())
                && profile.orchestrationVersion().equals(
                runtime.orchestrationVersion()
        )
                && profile.timeoutMs() == runtime.timeoutMs()
                && profile.maxOutputTokens() == runtime.maxOutputTokens()
                && profile.remoteEvidenceAllowed()
                == runtime.remoteEvidenceAllowed()
                && profile.remoteMemoryAllowed()
                == runtime.remoteMemoryAllowed();
    }

    List<AnswerProfileView> profiles() {
        return jdbc.query(
                """
                SELECT profile.version, profile.model_provider,
                       profile.model_id, profile.model_revision,
                       profile.endpoint_identity, profile.prompt_version,
                       profile.orchestration_version, profile.timeout_ms,
                       profile.max_output_tokens,
                       profile.remote_evidence_allowed,
                       profile.remote_memory_allowed, profile.reason,
                       profile.created_at,
                       publication.profile_version IS NOT NULL AS published
                FROM answer_profiles profile
                LEFT JOIN answer_profile_publications publication
                  ON publication.singleton_id = 1
                 AND publication.profile_version = profile.version
                ORDER BY profile.created_at DESC, profile.version
                """,
                this::profile
        );
    }

    @Transactional
    AnswerProfileView create(
            CreateAnswerProfileRequest request,
            PlatformUserPrincipal user
    ) {
        rejectSecret(request.endpointIdentity());
        try {
            jdbc.update(
                    """
                    INSERT INTO answer_profiles (
                        version, model_provider, model_id, model_revision,
                        endpoint_identity, prompt_version,
                        orchestration_version, timeout_ms,
                        max_output_tokens, remote_evidence_allowed,
                        remote_memory_allowed, reason, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    request.version(), request.modelProvider().strip(),
                    request.modelId().strip(), request.modelRevision().strip(),
                    request.endpointIdentity().strip(),
                    request.promptVersion().strip(),
                    request.orchestrationVersion().strip(),
                    request.timeoutMs(), request.maxOutputTokens(),
                    request.remoteEvidenceAllowed(),
                    request.remoteMemoryAllowed(), request.reason().strip(),
                    actor(user)
            );
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_PROFILE_EXISTS",
                    "AnswerProfile 版本已存在"
            );
        }
        return find(request.version());
    }

    @Transactional
    AnswerProfilePublicationView publish(
            String profileVersion,
            String reason,
            PlatformUserPrincipal user
    ) {
        AnswerProfileView profile = find(profileVersion);
        requireRuntimeMatch(profile);
        String current = currentVersion();
        if (profileVersion.equals(current)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_PROFILE_ALREADY_ACTIVE",
                    "该 AnswerProfile 已经发布"
            );
        }
        return switchPublication(
                profileVersion, current, "PUBLISH", reason, actor(user)
        );
    }

    @Transactional
    AnswerProfilePublicationView rollback(
            String profileVersion,
            String reason,
            PlatformUserPrincipal user
    ) {
        AnswerProfileView profile = find(profileVersion);
        requireRuntimeMatch(profile);
        String current = currentVersion();
        if (profileVersion.equals(current)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_PROFILE_ALREADY_ACTIVE",
                    "该 AnswerProfile 已经发布"
            );
        }
        Integer publishedBefore = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM answer_profile_publication_events
                WHERE profile_version = ?
                """,
                Integer.class, profileVersion
        );
        if (publishedBefore == null || publishedBefore == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ANSWER_PROFILE_NOT_PUBLISHED",
                    "只能回滚到曾经发布过的 AnswerProfile"
            );
        }
        return switchPublication(
                profileVersion, current, "ROLLBACK", reason, actor(user)
        );
    }

    List<AnswerProfilePublicationView> events() {
        return jdbc.query(
                """
                SELECT id, profile_version, previous_profile_version,
                       action, reason, created_at
                FROM answer_profile_publication_events
                ORDER BY id DESC
                """,
                (rs, row) -> new AnswerProfilePublicationView(
                        rs.getLong("id"),
                        rs.getString("profile_version"),
                        rs.getString("previous_profile_version"),
                        rs.getString("action"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()
                )
        );
    }

    private AnswerProfilePublicationView switchPublication(
            String profileVersion,
            String previous,
            String action,
            String reason,
            UUID actor
    ) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO answer_profile_publication_events (
                    profile_version, previous_profile_version,
                    action, actor, reason
                ) VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, profileVersion, previous,
                action, actor, reason.strip()
        );
        if (eventId == null) {
            throw new IllegalStateException(
                    "AnswerProfile publication event was not created"
            );
        }
        jdbc.update(
                """
                INSERT INTO answer_profile_publications (
                    singleton_id, profile_version, publication_event_id
                ) VALUES (1, ?, ?)
                ON CONFLICT (singleton_id) DO UPDATE
                SET profile_version = EXCLUDED.profile_version,
                    publication_event_id = EXCLUDED.publication_event_id,
                    published_at = CURRENT_TIMESTAMP
                """,
                profileVersion, eventId
        );
        return events().stream()
                .filter(event -> event.eventId() == eventId)
                .findFirst()
                .orElseThrow();
    }

    private AnswerProfileView find(String version) {
        List<AnswerProfileView> rows = jdbc.query(
                """
                SELECT profile.version, profile.model_provider,
                       profile.model_id, profile.model_revision,
                       profile.endpoint_identity, profile.prompt_version,
                       profile.orchestration_version, profile.timeout_ms,
                       profile.max_output_tokens,
                       profile.remote_evidence_allowed,
                       profile.remote_memory_allowed, profile.reason,
                       profile.created_at,
                       publication.profile_version IS NOT NULL AS published
                FROM answer_profiles profile
                LEFT JOIN answer_profile_publications publication
                  ON publication.singleton_id = 1
                 AND publication.profile_version = profile.version
                WHERE profile.version = ?
                """,
                this::profile, version
        );
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "ANSWER_PROFILE_NOT_FOUND",
                    "AnswerProfile 不存在"
            );
        }
        return rows.getFirst();
    }

    private AnswerProfileView profile(ResultSet rs, int row)
            throws SQLException {
        return new AnswerProfileView(
                rs.getString("version"),
                rs.getString("model_provider"),
                rs.getString("model_id"),
                rs.getString("model_revision"),
                rs.getString("endpoint_identity"),
                rs.getString("prompt_version"),
                rs.getString("orchestration_version"),
                rs.getInt("timeout_ms"),
                rs.getInt("max_output_tokens"),
                rs.getBoolean("remote_evidence_allowed"),
                rs.getBoolean("remote_memory_allowed"),
                rs.getString("reason"),
                rs.getBoolean("published"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String currentVersion() {
        List<String> versions = jdbc.query(
                """
                SELECT profile_version
                FROM answer_profile_publications
                WHERE singleton_id = 1
                FOR UPDATE
                """,
                (rs, row) -> rs.getString(1)
        );
        return versions.isEmpty() ? null : versions.getFirst();
    }

    private void requireRuntimeMatch(AnswerProfileView profile) {
        if (!matchesRuntime(profile)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ANSWER_PROFILE_RUNTIME_MISMATCH",
                    "AnswerProfile 与当前 LLM 运行配置不一致，不能发布"
            );
        }
    }

    private static String endpointIdentity(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost() == null ? "local" : uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            return uri.getScheme() + "://" + host
                    + (port < 0 ? "" : ":" + port) + path;
        } catch (IllegalArgumentException invalid) {
            return "configured-endpoint";
        }
    }

    private static void rejectSecret(String endpointIdentity) {
        String lower = endpointIdentity.toLowerCase();
        if (lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("token=")
                || lower.contains("sk-")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "ANSWER_PROFILE_SECRET_REJECTED",
                    "endpointIdentity 不能包含 API Key 或 Token"
            );
        }
    }

    private static UUID actor(PlatformUserPrincipal user) {
        if (user == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"
            );
        }
        return user.id();
    }
}
