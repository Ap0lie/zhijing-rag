package com.example.rag.document;

import com.example.rag.common.ApiException;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.pipeline.ParserProviderKind;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentRuntimePolicyService {

    private final JdbcTemplate jdbc;

    public DocumentRuntimePolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot snapshot() {
        Map<String, Policy> policies = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT policy_key, scope_type, document_format, parser_provider,
                       status, policy_version, reason, updated_at
                FROM document_runtime_policies
                ORDER BY scope_type, document_format, parser_provider NULLS FIRST
                """,
                resultSet -> {
                    Policy policy = policy(resultSet);
                    policies.put(policy.policyKey(), policy);
                }
        );
        Map<String, Long> running = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT revision.document_format,
                       COALESCE(job.parser_provider, NULLIF(job.parser_requested_engine, 'AUTO')) AS parser_provider,
                       count(*) AS running_count
                FROM pipeline_jobs job
                JOIN document_revisions revision ON revision.id = job.revision_id
                JOIN documents document ON document.id = revision.document_id
                WHERE job.status IN ('PENDING', 'RUNNING')
                  AND document.deleted_at IS NULL
                GROUP BY revision.document_format,
                         COALESCE(job.parser_provider, NULLIF(job.parser_requested_engine, 'AUTO'))
                """,
                resultSet -> {
                    String format = resultSet.getString("document_format");
                    long count = resultSet.getLong("running_count");
                    running.merge(formatKey(DocumentFormat.valueOf(format)), count, Long::sum);
                    String provider = resultSet.getString("parser_provider");
                    if (provider != null) {
                        running.merge(
                                parserKey(
                                        DocumentFormat.valueOf(format),
                                        ParserProviderKind.valueOf(provider)
                                ),
                                count,
                                Long::sum
                        );
                    }
                }
        );
        return new Snapshot(Map.copyOf(policies), Map.copyOf(running));
    }

    public void requireFormatEnabled(DocumentFormat format) {
        requireEnabled(formatKey(format), "DOCUMENT_FORMAT_DISABLED", "该文档格式已被管理员禁用");
    }

    public void requireFormatEnabledForWrite(DocumentFormat format) {
        requireEnabledForWrite(
                formatKey(format),
                "DOCUMENT_FORMAT_DISABLED",
                "该文档格式已被管理员禁用"
        );
    }

    public void requireProviderEnabled(
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        requireFormatEnabled(format);
        requireEnabled(
                parserKey(format, provider),
                "PARSER_PROVIDER_DISABLED",
                "该解析器已被管理员禁用"
        );
    }

    public void requireProviderEnabledForWrite(
            DocumentFormat format,
            ParserProviderKind provider
    ) {
        requireFormatEnabledForWrite(format);
        requireEnabledForWrite(
                parserKey(format, provider),
                "PARSER_PROVIDER_DISABLED",
                "该解析器已被管理员禁用"
        );
    }

    @Transactional
    public Policy change(
            DocumentFormat format,
            ParserProviderKind provider,
            PolicyAction action,
            String confirmation,
            String reason,
            UUID actorId
    ) {
        String safeReason = validateReason(reason);
        String expectedConfirmation = confirmation(action, provider != null);
        if (!expectedConfirmation.equals(confirmation)) {
            throw invalid("CONFIRMATION_INVALID", "确认字段无效");
        }
        String key = provider == null
                ? formatKey(format)
                : parserKey(format, provider);
        Policy current = jdbc.query(
                """
                SELECT policy_key, scope_type, document_format, parser_provider,
                       status, policy_version, reason, updated_at
                FROM document_runtime_policies
                WHERE policy_key = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next() ? policy(resultSet) : null,
                key
        );
        if (current == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "DOCUMENT_RUNTIME_POLICY_NOT_FOUND",
                    "文档运行策略不存在"
            );
        }
        PolicyStatus target = action == PolicyAction.DISABLE
                ? PolicyStatus.DISABLED : PolicyStatus.ENABLED;
        if (current.status() == target) {
            return current;
        }
        long version = current.policyVersion() + 1;
        int updated = jdbc.update(
                """
                UPDATE document_runtime_policies
                SET status = ?, policy_version = ?, reason = ?, changed_by = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE policy_key = ? AND policy_version = ?
                """,
                target.name(),
                version,
                safeReason,
                actorId,
                key,
                current.policyVersion()
        );
        if (updated != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DOCUMENT_RUNTIME_POLICY_CONFLICT",
                    "运行策略已被其他请求更新，请刷新后重试"
            );
        }
        int eventInserted = jdbc.update(
                """
                INSERT INTO document_runtime_policy_events (
                    policy_key, action, previous_status, new_status,
                    policy_version, reason, created_by, actor_username
                ) SELECT ?, ?, ?, ?, ?, ?, user_account.id, user_account.username
                  FROM users user_account
                 WHERE user_account.id = ? AND user_account.enabled
                """,
                key,
                action.name(),
                current.status().name(),
                target.name(),
                version,
                safeReason,
                actorId
        );
        if (eventInserted != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DOCUMENT_RUNTIME_POLICY_ACTOR_INVALID",
                    "当前管理员状态已变化，请重新登录"
            );
        }
        return load(key);
    }

    public List<PolicyEvent> events(DocumentFormat format, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                """
                SELECT event.id, event.policy_key, policy.scope_type,
                       policy.document_format, policy.parser_provider,
                       event.action, event.previous_status, event.new_status,
                       event.policy_version, event.reason, event.actor_username,
                       event.created_at
                FROM document_runtime_policy_events event
                JOIN document_runtime_policies policy
                  ON policy.policy_key = event.policy_key
                WHERE policy.document_format = ?
                ORDER BY event.created_at DESC, event.id DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new PolicyEvent(
                        resultSet.getLong("id"),
                        resultSet.getString("policy_key"),
                        PolicyScope.valueOf(resultSet.getString("scope_type")),
                        DocumentFormat.valueOf(resultSet.getString("document_format")),
                        nullableProvider(resultSet.getString("parser_provider")),
                        PolicyAction.valueOf(resultSet.getString("action")),
                        PolicyStatus.valueOf(resultSet.getString("previous_status")),
                        PolicyStatus.valueOf(resultSet.getString("new_status")),
                        resultSet.getLong("policy_version"),
                        resultSet.getString("reason"),
                        resultSet.getString("actor_username"),
                        resultSet.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
                ),
                format.name(),
                safeLimit
        );
    }

    private void requireEnabled(String key, String code, String message) {
        Boolean enabled = jdbc.queryForObject(
                "SELECT status = 'ENABLED' FROM document_runtime_policies WHERE policy_key = ?",
                Boolean.class,
                key
        );
        if (!Boolean.TRUE.equals(enabled)) {
            throw new ApiException(HttpStatus.CONFLICT, code, message);
        }
    }

    private void requireEnabledForWrite(String key, String code, String message) {
        String status = jdbc.query(
                "SELECT status FROM document_runtime_policies WHERE policy_key = ? FOR SHARE",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                key
        );
        if (!"ENABLED".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, code, message);
        }
    }

    private Policy load(String key) {
        return jdbc.queryForObject(
                """
                SELECT policy_key, scope_type, document_format, parser_provider,
                       status, policy_version, reason, updated_at
                FROM document_runtime_policies
                WHERE policy_key = ?
                """,
                (resultSet, rowNumber) -> policy(resultSet),
                key
        );
    }

    private static Policy policy(ResultSet resultSet) throws SQLException {
        return new Policy(
                resultSet.getString("policy_key"),
                PolicyScope.valueOf(resultSet.getString("scope_type")),
                DocumentFormat.valueOf(resultSet.getString("document_format")),
                nullableProvider(resultSet.getString("parser_provider")),
                PolicyStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("policy_version"),
                resultSet.getString("reason"),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private static ParserProviderKind nullableProvider(String value) {
        return value == null ? null : ParserProviderKind.valueOf(value);
    }

    private static String validateReason(String value) {
        String reason = value == null ? "" : value.strip();
        if (reason.length() < 8 || reason.length() > 500) {
            throw invalid("AUDIT_REASON_INVALID", "审计理由必须为 8–500 个字符");
        }
        return reason;
    }

    private static String confirmation(PolicyAction action, boolean parser) {
        if (parser) {
            return action == PolicyAction.DISABLE
                    ? "DISABLE_PARSER" : "RESTORE_PARSER";
        }
        return action == PolicyAction.DISABLE
                ? "DISABLE_DOCUMENT_FORMAT" : "RESTORE_DOCUMENT_FORMAT";
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    static String formatKey(DocumentFormat format) {
        return "FORMAT:" + format.name();
    }

    static String parserKey(DocumentFormat format, ParserProviderKind provider) {
        return "PARSER:" + format.name() + ":" + provider.name();
    }

    public enum PolicyScope { FORMAT, PARSER }

    public enum PolicyStatus { ENABLED, DISABLED }

    public enum PolicyAction { DISABLE, RESTORE }

    public record Policy(
            String policyKey,
            PolicyScope scope,
            DocumentFormat documentFormat,
            ParserProviderKind parserProvider,
            PolicyStatus status,
            long policyVersion,
            String reason,
            Instant updatedAt
    ) {
        public boolean enabled() {
            return status == PolicyStatus.ENABLED;
        }
    }

    public record Snapshot(
            Map<String, Policy> policies,
            Map<String, Long> runningJobs
    ) {
        public Policy format(DocumentFormat format) {
            return policies.get(formatKey(format));
        }

        public Policy parser(DocumentFormat format, ParserProviderKind provider) {
            return policies.get(parserKey(format, provider));
        }

        public long running(DocumentFormat format) {
            return runningJobs.getOrDefault(formatKey(format), 0L);
        }

        public long running(DocumentFormat format, ParserProviderKind provider) {
            return runningJobs.getOrDefault(parserKey(format, provider), 0L);
        }
    }

    public record PolicyEvent(
            long id,
            String policyKey,
            PolicyScope scope,
            DocumentFormat documentFormat,
            ParserProviderKind parserProvider,
            PolicyAction action,
            PolicyStatus previousStatus,
            PolicyStatus newStatus,
            long policyVersion,
            String reason,
            String actorUsername,
            Instant createdAt
    ) {
    }
}
