package com.example.rag.governance;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceContracts.OperationImpact;
import com.example.rag.governance.GovernanceContracts.OperationImpactRequest;
import com.example.rag.persistence.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OperationImpactService {

    private static final Map<String, String> GENERIC_CONFIRMATIONS = Map.ofEntries(
            Map.entry("DOCUMENT_DELETE", "DELETE_DOCUMENT"),
            Map.entry("DOCUMENT_REPARSE", "REPARSE_DOCUMENT"),
            Map.entry("FORMAT_DISABLE", "DISABLE_DOCUMENT_FORMAT"),
            Map.entry("RETRIEVAL_PUBLISH", "PUBLISH_RETRIEVAL"),
            Map.entry("RETRIEVAL_ROLLBACK", "ROLLBACK_RETRIEVAL"),
            Map.entry("INDEX_PUBLISH", "PUBLISH_INDEX"),
            Map.entry("INDEX_ROLLBACK", "ROLLBACK_INDEX"),
            Map.entry("GRAPH_PUBLISH", "PUBLISH_GRAPH"),
            Map.entry("GRAPH_ROLLBACK", "ROLLBACK_GRAPH"),
            Map.entry("GLOBAL_PUBLISH", "PUBLISH_GLOBAL"),
            Map.entry("GLOBAL_ROLLBACK", "ROLLBACK_GLOBAL"),
            Map.entry("QUERY_PUBLISH", "PUBLISH_QUERY_PROFILE"),
            Map.entry("QUERY_ROLLBACK", "ROLLBACK_QUERY_PROFILE"),
            Map.entry("ANSWER_PUBLISH", "PUBLISH_ANSWER_PROFILE"),
            Map.entry("ANSWER_ROLLBACK", "ROLLBACK_ANSWER_PROFILE"),
            Map.entry("BASELINE_PUBLISH", "PUBLISH_BASELINE"),
            Map.entry("BASELINE_ROLLBACK", "ROLLBACK_BASELINE")
    );

    private final JdbcTemplate jdbc;
    private final SessionRegistry sessions;

    public OperationImpactService(JdbcTemplate jdbc, SessionRegistry sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public OperationImpact preflight(OperationImpactRequest request) {
        String operation = request.operation().trim().toUpperCase();
        return switch (operation) {
            case "USER_UPDATE" -> userUpdate(request);
            case "USER_PASSWORD_RESET" -> passwordReset(request);
            case "DOCUMENT_GRANT_BATCH" -> grantBatch(request);
            case "PIPELINE_RECOVER" -> pipelineRecover(request);
            default -> generic(operation, request.objectId());
        };
    }

    @Transactional(readOnly = true)
    public OperationImpact replayedPipelineRecovery(UUID jobId) {
        return pipelineRecover(new OperationImpactRequest(
                "PIPELINE_RECOVER", jobId.toString(), Map.of()
        ), false);
    }

    private OperationImpact pipelineRecover(OperationImpactRequest request) {
        return pipelineRecover(request, true);
    }

    private OperationImpact pipelineRecover(OperationImpactRequest request, boolean enforceCurrentStatus) {
        UUID jobId;
        try {
            jobId = UUID.fromString(request.objectId());
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PIPELINE_JOB_ID_INVALID", "Pipeline 任务 ID 无效");
        }
        PipelineFacts job = jdbc.query(
                """
                SELECT job.id, job.stage, job.status, job.attempt, job.max_attempts,
                       job.error_code, revision.id AS revision_id, document.id AS document_id,
                       document.title
                FROM pipeline_jobs job
                JOIN document_revisions revision ON revision.id = job.revision_id
                JOIN documents document ON document.id = revision.document_id
                WHERE job.id = ? AND document.deleted_at IS NULL
                """,
                rs -> rs.next() ? new PipelineFacts(
                        rs.getObject("id", UUID.class), rs.getString("stage"),
                        rs.getString("status"), rs.getInt("attempt"),
                        rs.getInt("max_attempts"), rs.getString("error_code"),
                        rs.getObject("revision_id", UUID.class),
                        rs.getObject("document_id", UUID.class), rs.getString("title")
                ) : null,
                jobId
        );
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PIPELINE_JOB_NOT_FOUND", "Pipeline 任务不存在");
        }
        List<String> blockers = !enforceCurrentStatus
                ? List.of()
                : "QUARANTINED".equals(job.status())
                ? List.of("隔离任务必须按原因切换 Parser、检查 Provider 或创建重解析 Revision，不能无条件重新排队")
                : !List.of("FAILED", "CANCELLED").contains(job.status())
                ? List.of("只有失败或已取消的任务可以人工重新排队")
                : List.of();
        return new OperationImpact(
                "PIPELINE_RECOVER", "PIPELINE_JOB", job.id().toString(), "RECOVER_PIPELINE_JOB",
                Math.max(job.attempt(), 0),
                List.of("创建一次有审计记录的人工重新排队；保留原失败事实和自动尝试次数快照"),
                List.of("Worker 将重新领取任务；成功后后续 Chunk、Index 与下游投影按既有主链收敛"),
                List.of("不会删除历史尝试，不会自动发布 Index、Graph、Global 或 Baseline"),
                blockers,
                Map.of("currentAttempt", (long) job.attempt(), "automaticAttemptLimit", (long) job.maxAttempts()),
                "如恢复后仍失败，任务会再次停止并保留新的失败原因"
        );
    }

    private OperationImpact userUpdate(OperationImpactRequest request) {
        UserFacts user = user(request.objectId());
        Map<String, Object> parameters = request.safeParameters();
        String nextRole = String.valueOf(parameters.getOrDefault("role", user.role()));
        boolean nextEnabled = Boolean.parseBoolean(
                String.valueOf(parameters.getOrDefault("enabled", user.enabled()))
        );
        long grants = explicitGrants(user.id());
        long activeSessions = activeSessions(user.id());
        List<String> blockers = user.role().equals("ADMIN") && user.enabled()
                && (!"ADMIN".equals(nextRole) || !nextEnabled)
                && enabledAdminCount() <= 1
                ? List.of("不能禁用或降级最后一个启用的管理员")
                : List.of();
        boolean losesGrantEligibility = user.enabled() && user.role().equals("USER")
                && (!nextEnabled || !"USER".equals(nextRole));
        return new OperationImpact(
                "USER_UPDATE", "USER", user.id().toString(), "UPDATE_USER",
                user.securityVersion(),
                List.of("角色或登录状态将在事务提交后立即生效", "该用户的现有 Session 将立即失效"),
                losesGrantEligibility
                        ? List.of("移除明确文档授权并触发相关 Graph/Global stale 与重建申请")
                        : List.of(),
                List.of("不会修改公共文档、用户拥有的文档或其他用户权限"),
                blockers,
                Map.of("activeSessions", activeSessions, "explicitGrants", grants),
                "可通过新的用户修改操作恢复角色或启用状态；已撤销的明确授权需重新授予"
        );
    }

    private OperationImpact passwordReset(OperationImpactRequest request) {
        UserFacts user = user(request.objectId());
        return new OperationImpact(
                "USER_PASSWORD_RESET", "USER", user.id().toString(), "RESET_USER_PASSWORD",
                user.securityVersion(),
                List.of("密码 Hash 将被替换", "该用户的现有 Session 将立即失效"),
                List.of(),
                List.of("不会改变角色、文档权限、历史问答或记忆"),
                List.of(),
                Map.of("activeSessions", activeSessions(user.id())),
                "密码不可恢复；如需撤销只能再次设置新密码"
        );
    }

    private OperationImpact grantBatch(OperationImpactRequest request) {
        UserFacts user = user(request.objectId());
        long requested = longParameter(request.safeParameters(), "changeCount");
        List<String> blockers = !user.enabled() || !user.role().equals("USER")
                ? List.of("只有启用的普通用户可以接收明确文档授权")
                : List.of();
        return new OperationImpact(
                "DOCUMENT_GRANT_BATCH", "USER", user.id().toString(), "UPDATE_DOCUMENT_GRANTS",
                user.securityVersion(),
                List.of("新增或撤销的明确授权对下一次检索、问答、Chunk、Citation 与下载立即生效"),
                List.of("受影响文档进入 Graph/Global stale 与候选重建主链"),
                List.of("公共文档和该用户拥有的文档权限不会被修改"),
                blockers,
                Map.of("requestedChanges", requested, "currentExplicitGrants", explicitGrants(user.id())),
                "可提交反向差量授权；撤权期间不会继续暴露旧 Evidence"
        );
    }

    private OperationImpact generic(String operation, String objectId) {
        String confirmation = GENERIC_CONFIRMATIONS.get(operation);
        if (confirmation == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPERATION_IMPACT_UNKNOWN", "不支持的影响预检操作");
        }
        return new OperationImpact(
                operation, genericObjectType(operation), objectId, confirmation,
                currentFactVersion(operation),
                List.of("执行成功后将改变该模块的当前生效事实"),
                operation.contains("DELETE") || operation.contains("REPARSE") || operation.contains("FORMAT")
                        ? List.of("相关索引或图投影将异步收敛")
                        : List.of(),
                List.of("不会绕过当前 ACL、Revision、Locator 或发布闭包检查"),
                List.of(),
                Map.of(),
                operation.contains("DELETE")
                        ? "删除事实不可直接恢复"
                        : "只有存在兼容且已追平的历史候选时才能显式回滚"
        );
    }

    private UserFacts user(String value) {
        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USER_ID_INVALID", "用户 ID 无效");
        }
        UserFacts result = jdbc.query(
                "SELECT id, username, role, enabled, security_version FROM users WHERE id = ?",
                rs -> rs.next() ? new UserFacts(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("role"), rs.getBoolean("enabled"),
                        rs.getLong("security_version")
                ) : null,
                id
        );
        if (result == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        return result;
    }

    private long explicitGrants(UUID userId) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM document_acl_entries WHERE user_id = ?", Long.class, userId
        );
        return value == null ? 0 : value;
    }

    private long enabledAdminCount() {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'ADMIN' AND enabled", Long.class
        );
        return value == null ? 0 : value;
    }

    private long activeSessions(UUID userId) {
        return sessions.getAllPrincipals().stream()
                .filter(com.example.rag.security.PlatformUserPrincipal.class::isInstance)
                .map(com.example.rag.security.PlatformUserPrincipal.class::cast)
                .filter(principal -> principal.id().equals(userId))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .count();
    }

    private long currentFactVersion(String operation) {
        if (operation.startsWith("RETRIEVAL") || operation.startsWith("INDEX")) {
            return nullableLong("SELECT publication_event_id FROM retrieval_publications WHERE singleton_id = 1");
        }
        if (operation.startsWith("GRAPH_")) {
            return nullableLong("SELECT publication_event_id FROM graph_publications WHERE singleton_id = 1");
        }
        if (operation.startsWith("GLOBAL_")) {
            return nullableLong("SELECT publication_event_id FROM global_graph_publications WHERE singleton_id = 1");
        }
        if (operation.startsWith("QUERY_")) {
            return nullableLong("SELECT publication_event_id FROM query_intelligence_profile_publications WHERE singleton_id = 1");
        }
        if (operation.startsWith("ANSWER_")) {
            return nullableLong("SELECT publication_event_id FROM answer_profile_publications WHERE singleton_id = 1");
        }
        return 0;
    }

    private long nullableLong(String sql) {
        List<Long> values = jdbc.query(sql, (rs, row) -> rs.getLong(1));
        return values.isEmpty() ? 0 : values.getFirst();
    }

    private static long longParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return Math.max(number.longValue(), 0);
        }
        try {
            return Math.max(Long.parseLong(String.valueOf(value)), 0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String genericObjectType(String operation) {
        int separator = operation.indexOf('_');
        return separator < 0 ? operation : operation.substring(0, separator);
    }

    private record UserFacts(UUID id, String username, String role, boolean enabled, long securityVersion) {
    }

    private record PipelineFacts(
            UUID id,
            String stage,
            String status,
            int attempt,
            int maxAttempts,
            String errorCode,
            UUID revisionId,
            UUID documentId,
            String title
    ) {
    }
}
