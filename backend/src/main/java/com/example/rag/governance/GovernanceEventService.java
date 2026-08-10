package com.example.rag.governance;

import com.example.rag.common.ApiException;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Service
public class GovernanceEventService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GovernanceEventService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(
            String module,
            String action,
            PlatformUserPrincipal actor,
            String objectType,
            String objectId,
            String objectLabel,
            Map<String, ?> before,
            Map<String, ?> after,
            String reason
    ) {
        append(module, action, actor, objectType, objectId, objectLabel,
                before, after, reason, null, null);
    }

    public void append(
            String module,
            String action,
            PlatformUserPrincipal actor,
            String objectType,
            String objectId,
            String objectLabel,
            Map<String, ?> before,
            Map<String, ?> after,
            String reason,
            String idempotencyKey,
            String requestHash
    ) {
        jdbc.update(
                """
                INSERT INTO governance_events (
                    module, action, actor_user_id, actor_snapshot,
                    object_type, object_id, object_label,
                    before_summary, after_summary, reason, source_event,
                    idempotency_key, request_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?, ?)
                """,
                module, action, actor.id(), actor.getUsername(), objectType, objectId,
                objectLabel, json(before), json(after), normalizeReason(reason),
                module + ":" + action, idempotencyKey, requestHash
        );
    }

    public String requestHash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String existingRequestHash(
            PlatformUserPrincipal actor,
            String action,
            String idempotencyKey
    ) {
        return jdbc.query(
                """
                SELECT request_hash
                FROM governance_events
                WHERE actor_user_id = ? AND action = ? AND idempotency_key = ?
                """,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                actor.id(), action, idempotencyKey
        );
    }

    public String existingObjectId(
            PlatformUserPrincipal actor,
            String action,
            String idempotencyKey
    ) {
        return jdbc.query(
                """
                SELECT object_id
                FROM governance_events
                WHERE actor_user_id = ? AND action = ? AND idempotency_key = ?
                """,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                actor.id(), action, idempotencyKey
        );
    }

    public void lockIdempotency(PlatformUserPrincipal actor, String operation, String key) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) resultSet -> null,
                actor.id() + ":" + operation + ":" + key
        );
    }

    public static String normalizeReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.length() < 8 || value.length() > 500) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AUDIT_REASON_INVALID",
                    "审计理由需为 8-500 个字符"
            );
        }
        return value;
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit summary cannot be serialized", exception);
        }
    }
}
