package com.example.rag.governance;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceContracts.AuditEventPage;
import com.example.rag.governance.GovernanceContracts.AuditEventView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminAuditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AuditEventPage page(
            String module,
            String action,
            String actor,
            String object,
            Instant from,
            Instant to,
            String cursor,
            int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        Cursor decoded = decode(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT source_event, module, action, actor_id, actor_snapshot,
                       object_type, object_id, object_label,
                       before_summary::text, after_summary::text,
                       reason, occurred_at
                FROM admin_audit_events
                WHERE 1 = 1
                """);
        List<Object> arguments = new ArrayList<>();
        addExact(sql, arguments, "module", module);
        addExact(sql, arguments, "action", action);
        addLike(sql, arguments, "actor_snapshot", actor);
        if (object != null && !object.isBlank()) {
            sql.append(" AND (lower(object_id) LIKE ? OR lower(object_label) LIKE ?)");
            String pattern = "%" + object.trim().toLowerCase() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            arguments.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at <= ?");
            arguments.add(Timestamp.from(to));
        }
        if (decoded != null) {
            sql.append(" AND (occurred_at, source_event) < (?, ?)");
            arguments.add(Timestamp.from(decoded.occurredAt()));
            arguments.add(decoded.sourceEvent());
        }
        sql.append(" ORDER BY occurred_at DESC, source_event DESC LIMIT ?");
        arguments.add(limit + 1);

        List<AuditEventView> rows = jdbc.query(
                sql.toString(),
                (resultSet, rowNumber) -> new AuditEventView(
                        resultSet.getString("source_event"),
                        resultSet.getString("module"),
                        resultSet.getString("action"),
                        resultSet.getObject("actor_id", UUID.class),
                        resultSet.getString("actor_snapshot"),
                        resultSet.getString("object_type"),
                        resultSet.getString("object_id"),
                        resultSet.getString("object_label"),
                        json(resultSet.getString("before_summary")),
                        json(resultSet.getString("after_summary")),
                        resultSet.getString("reason"),
                        resultSet.getTimestamp("occurred_at").toInstant()
                ),
                arguments.toArray()
        );
        boolean hasNext = rows.size() > limit;
        List<AuditEventView> items = hasNext ? rows.subList(0, limit) : rows;
        String next = hasNext ? encode(items.get(items.size() - 1)) : null;
        return new AuditEventPage(List.copyOf(items), next);
    }

    private static void addExact(
            StringBuilder sql,
            List<Object> arguments,
            String column,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            arguments.add(value.trim().toUpperCase());
        }
    }

    private static void addLike(
            StringBuilder sql,
            List<Object> arguments,
            String column,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND lower(").append(column).append(") LIKE ?");
            arguments.add("%" + value.trim().toLowerCase() + "%");
        }
    }

    private Map<String, Object> json(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored audit summary is invalid", exception);
        }
    }

    private static String encode(AuditEventView event) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (event.occurredAt() + "|" + event.sourceEvent()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Instant.parse(decoded.substring(0, separator)), decoded.substring(separator + 1));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_CURSOR_INVALID", "操作日志游标无效");
        }
    }

    private record Cursor(Instant occurredAt, String sourceEvent) {
    }
}
