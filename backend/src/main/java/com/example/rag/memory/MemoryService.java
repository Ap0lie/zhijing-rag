package com.example.rag.memory;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.memory.MemoryContracts.AdminMemorySummaryView;
import com.example.rag.memory.MemoryContracts.CreateMemoryRequest;
import com.example.rag.memory.MemoryContracts.MemoryEventView;
import com.example.rag.memory.MemoryContracts.MemoryItemView;
import com.example.rag.memory.MemoryContracts.MemorySettingsView;
import com.example.rag.memory.MemoryContracts.MemorySourceInput;
import com.example.rag.memory.MemoryContracts.MemorySourceView;
import com.example.rag.memory.MemoryContracts.ReplaceMemoryRequest;
import com.example.rag.memory.MemoryContracts.UpdateMemorySettingsRequest;
import com.example.rag.memory.MemoryContracts.UserProfileEntry;
import com.example.rag.memory.MemoryContracts.UserProfileView;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MemoryService {

    private static final int MAX_CONTENT_LENGTH = 1200;
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}");
    private static final List<Pattern> CREDENTIAL_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----"),
            Pattern.compile(
                    "(?i)\\b(?:password|passwd|pwd|cookie|token|api[ _-]?key"
                            + "|secret|authorization)\\b\\s*[:=]\\s*\\S{4,}"
            ),
            Pattern.compile("(?:密码|令牌|密钥)\\s*[:：=]\\s*\\S{4,}"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                            + "\\.[A-Za-z0-9_-]{8,}\\b"
            )
    );

    private final JdbcTemplate jdbc;

    public MemoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public MemorySettingsView settings(PlatformUserPrincipal user) {
        ensureSettings(user.id());
        return readSettings(user.id());
    }

    @Transactional
    public MemorySettingsView updateSettings(
            PlatformUserPrincipal user,
            UpdateMemorySettingsRequest request
    ) {
        ensureSettings(user.id());
        int changed = jdbc.update(
                """
                UPDATE user_memory_settings
                SET enabled = ?,
                    suggestion_enabled = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND version = ?
                """,
                request.enabled(),
                request.suggestionEnabled(),
                user.id(),
                request.expectedVersion()
        );
        if (changed == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MEMORY_SETTINGS_CONFLICT",
                    "记忆设置已被其他请求修改，请刷新后重试"
            );
        }
        return readSettings(user.id());
    }

    @Transactional
    public List<MemoryItemView> list(
            PlatformUserPrincipal user,
            String type,
            String status
    ) {
        expireOwned(user.id());
        String safeType = filter(type, Set.of(
                "ALL",
                "USER_PREFERENCE",
                "USER_FACT",
                "SESSION_SUMMARY",
                "DOCUMENT_FACT"
        ), "MEMORY_TYPE_INVALID");
        String safeStatus = filter(status, Set.of(
                "ALL",
                "CANDIDATE",
                "ACTIVE",
                "REJECTED",
                "REVOKED",
                "EXPIRED",
                "FORGOTTEN"
        ), "MEMORY_STATUS_INVALID");
        return rows(user).stream()
                .filter(ItemRow::sourcesValid)
                .map(ItemRow::view)
                .filter(item -> "ALL".equals(safeType)
                        || item.memoryType().equals(safeType))
                .filter(item -> "ALL".equals(safeStatus)
                        || item.status().equals(safeStatus))
                .limit(200)
                .toList();
    }

    @Transactional
    public MemoryItemView detail(
            PlatformUserPrincipal user,
            UUID memoryId
    ) {
        expireOwned(user.id());
        return requireVisible(user, memoryId).view();
    }

    @Transactional
    public List<MemorySourceView> sources(
            PlatformUserPrincipal user,
            UUID memoryId
    ) {
        expireOwned(user.id());
        requireVisible(user, memoryId);
        return jdbc.query(
                """
                SELECT source.id, source.source_type,
                       source.chat_session_id, source.chat_message_id,
                       source.document_id, source.revision_id,
                       source.child_chunk_id, source.source_span_id,
                       source.source_deleted_at, source.created_at,
                       revision.document_format,
                       location.locator_kind,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.start_offset,
                       location.end_offset,
                       location.address::text AS locator_address,
                       location.source_text_hash,
                       location.normalization_version,
                       location.start_page,
                       location.end_page,
                       location.source_label
                FROM memory_sources source
                LEFT JOIN document_revisions revision
                  ON revision.id = source.revision_id
                 AND revision.document_id = source.document_id
                LEFT JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = source.source_span_id
                WHERE source.memory_item_id = ?
                  AND source.owner_user_id = ?
                ORDER BY source.created_at, source.id
                """,
                (rs, row) -> {
                    SourceLocatorResponse locator =
                            locatorOrNull(rs);
                    return new MemorySourceView(
                            rs.getObject("id", UUID.class),
                            rs.getString("source_type"),
                            rs.getObject(
                                    "chat_session_id", UUID.class
                            ),
                            rs.getObject(
                                    "chat_message_id", UUID.class
                            ),
                            rs.getObject("document_id", UUID.class),
                            rs.getObject("revision_id", UUID.class),
                            rs.getObject("child_chunk_id", UUID.class),
                            rs.getObject("source_span_id", UUID.class),
                            rs.getTimestamp("source_deleted_at") == null
                                    ? null
                                    : rs.getTimestamp(
                                            "source_deleted_at"
                                    ).toInstant(),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("document_format"),
                            locator,
                            locator == null
                                    ? null : locator.sourceLabel()
                    );
                },
                memoryId,
                user.id()
        );
    }

    private static SourceLocatorResponse locatorOrNull(
            ResultSet resultSet
    ) throws SQLException {
        if (resultSet.getObject(
                "start_source_unit_id", UUID.class
        ) == null) {
            return null;
        }
        return new SourceLocatorResponse(
                resultSet.getString("locator_kind"),
                resultSet.getObject(
                        "start_source_unit_id", UUID.class
                ),
                resultSet.getObject(
                        "end_source_unit_id", UUID.class
                ),
                resultSet.getInt("start_offset"),
                resultSet.getInt("end_offset"),
                resultSet.getString("locator_address"),
                resultSet.getString("source_text_hash"),
                resultSet.getString("normalization_version"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("source_label")
        );
    }

    @Transactional
    public List<MemoryEventView> events(
            PlatformUserPrincipal user,
            UUID memoryId
    ) {
        expireOwned(user.id());
        requireVisible(user, memoryId);
        return jdbc.query(
                """
                SELECT id, event_type, related_memory_id, reason, created_at
                FROM memory_events
                WHERE memory_item_id = ? AND owner_user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 200
                """,
                (rs, row) -> new MemoryEventView(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getObject("related_memory_id", UUID.class),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                memoryId,
                user.id()
        );
    }

    @Transactional
    public UserProfileView profile(PlatformUserPrincipal user) {
        MemorySettingsView settings = settings(user);
        expireOwned(user.id());
        List<UserProfileEntry> preferences = rows(user).stream()
                .filter(ItemRow::sourcesValid)
                .map(ItemRow::view)
                .filter(item -> "USER_PREFERENCE".equals(item.memoryType()))
                .filter(item -> "ACTIVE".equals(item.status()))
                .map(item -> new UserProfileEntry(
                        item.id(),
                        item.memoryKey(),
                        item.content(),
                        item.versionNumber()
                ))
                .toList();
        return new UserProfileView(settings.enabled(), preferences);
    }

    @Transactional
    public MemoryItemView create(
            PlatformUserPrincipal user,
            String idempotencyKey,
            CreateMemoryRequest request
    ) {
        String safeIdempotencyKey = idempotencyKey(idempotencyKey);
        UUID existing = findByIdempotency(user.id(), safeIdempotencyKey);
        if (existing != null) {
            return requireVisible(user, existing).view();
        }

        String memoryKey = clean(request.memoryKey());
        String normalizedKey = normalizedKey(memoryKey);
        String content = memoryContent(request.content());
        String memoryType = request.memoryType();
        validateExpiry(request.expiresAt());
        SourceValidation validation = validateSources(
                memoryType, content, request.sources(), user
        );

        acquireKeyLock(user.id(), memoryType, normalizedKey);
        existing = findByIdempotency(user.id(), safeIdempotencyKey);
        if (existing != null) {
            return requireVisible(user, existing).view();
        }
        expireOwned(user.id());
        if (!request.candidate()
                && findActive(user.id(), memoryType, normalizedKey) != null) {
            throw activeConflict();
        }

        UUID id = UUID.randomUUID();
        String status = request.candidate() ? "CANDIDATE" : "ACTIVE";
        int version = nextVersion(user.id(), memoryType, normalizedKey);
        insertItem(
                id,
                user.id(),
                memoryType,
                memoryKey,
                normalizedKey,
                content,
                status,
                version,
                "USER",
                null,
                safeIdempotencyKey,
                request.expiresAt()
        );
        insertSources(id, user.id(), validation.sources());
        event(id, user.id(), "CREATED", user.id(), null, null);
        return requireVisible(user, id).view();
    }

    @Transactional
    List<MemoryItemView> createSuggestions(
            UUID owner,
            UUID sessionId,
            UUID sourceMessageId,
            UUID jobId,
            String extractorVersion,
            String promptVersion,
            String inputHash,
            List<MemorySuggestionProvider.Suggestion> suggestions
    ) {
        ensureSettings(owner);
        Boolean enabled = jdbc.queryForObject(
                """
                SELECT suggestion_enabled
                FROM user_memory_settings
                WHERE user_id = ?
                """,
                Boolean.class,
                owner
        );
        if (!Boolean.TRUE.equals(enabled)) {
            return List.of();
        }
        requireOwnedMessage(owner, sessionId, sourceMessageId);
        List<MemoryItemView> created = new ArrayList<>();
        int position = 0;
        for (MemorySuggestionProvider.Suggestion suggestion : suggestions) {
            position++;
            String memoryType = suggestion.memoryType();
            if (!Set.of("USER_PREFERENCE", "USER_FACT")
                    .contains(memoryType)) {
                continue;
            }
            String memoryKey = clean(suggestion.memoryKey());
            String normalizedKey = normalizedKey(memoryKey);
            String content = memoryContent(suggestion.content());
            String key = idempotencyKey(
                    "suggestion:" + jobId + ":" + position
            );
            UUID existing = findByIdempotency(owner, key);
            if (existing != null) {
                created.add(requireOwned(owner, existing).view());
                continue;
            }

            acquireKeyLock(owner, memoryType, normalizedKey);
            existing = findByIdempotency(owner, key);
            if (existing != null) {
                created.add(requireOwned(owner, existing).view());
                continue;
            }
            UUID id = UUID.randomUUID();
            insertItem(
                    id,
                    owner,
                    memoryType,
                    memoryKey,
                    normalizedKey,
                    content,
                    "CANDIDATE",
                    nextVersion(owner, memoryType, normalizedKey),
                    "SUGGESTION",
                    null,
                    key,
                    null
            );
            insertSources(
                    id,
                    owner,
                    List.of(new MemorySourceInput(
                            "CHAT_MESSAGE",
                            sessionId,
                            sourceMessageId,
                            null,
                            null,
                            null,
                            null
                    ))
            );
            event(
                    id,
                    owner,
                    "SUGGESTED",
                    null,
                    null,
                    "extractor=" + extractorVersion
                            + "; prompt=" + promptVersion
                            + "; input=" + inputHash.substring(0, 12)
            );
            created.add(requireOwned(owner, id).view());
        }
        return List.copyOf(created);
    }

    @Transactional
    public MemoryItemView confirm(
            PlatformUserPrincipal user,
            UUID memoryId,
            String reason
    ) {
        safeReason(reason);
        expireOwned(user.id());
        requireVisible(user, memoryId);
        LockedItem item = lock(user.id(), memoryId);
        if ("ACTIVE".equals(item.status())) {
            return requireVisible(user, memoryId).view();
        }
        if (!"CANDIDATE".equals(item.status())) {
            throw invalidTransition("只有候选记忆可以确认");
        }
        Integer deletedSources = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM memory_sources
                WHERE memory_item_id = ?
                  AND owner_user_id = ?
                  AND source_deleted_at IS NOT NULL
                """,
                Integer.class,
                memoryId,
                user.id()
        );
        if (deletedSources != null && deletedSources > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MEMORY_SOURCE_DELETED",
                    "候选来源已删除，不能再确认"
            );
        }
        acquireKeyLock(user.id(), item.memoryType(), item.normalizedKey());
        UUID active = findActive(
                user.id(), item.memoryType(), item.normalizedKey()
        );
        if (active != null && !active.equals(memoryId)) {
            throw activeConflict();
        }
        jdbc.update(
                """
                UPDATE memory_items
                SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ? AND status = 'CANDIDATE'
                """,
                memoryId,
                user.id()
        );
        event(
                memoryId, user.id(), "CONFIRMED", user.id(), null,
                safeReason(reason)
        );
        return requireVisible(user, memoryId).view();
    }

    @Transactional
    public MemoryItemView reject(
            PlatformUserPrincipal user,
            UUID memoryId,
            String reason
    ) {
        String safeReason = safeReason(reason);
        expireOwned(user.id());
        requireVisible(user, memoryId);
        LockedItem item = lock(user.id(), memoryId);
        if ("REJECTED".equals(item.status())) {
            return requireVisible(user, memoryId).view();
        }
        if (!"CANDIDATE".equals(item.status())) {
            throw invalidTransition("只有候选记忆可以拒绝");
        }
        updateStatus(memoryId, user.id(), "REJECTED");
        event(
                memoryId, user.id(), "REJECTED", user.id(), null, safeReason
        );
        return requireVisible(user, memoryId).view();
    }

    @Transactional
    public MemoryItemView replace(
            PlatformUserPrincipal user,
            UUID memoryId,
            String idempotencyKey,
            ReplaceMemoryRequest request
    ) {
        String safeIdempotencyKey = idempotencyKey(idempotencyKey);
        UUID repeated = findByIdempotency(user.id(), safeIdempotencyKey);
        if (repeated != null) {
            return requireVisible(user, repeated).view();
        }
        expireOwned(user.id());
        requireVisible(user, memoryId);
        LockedItem target = lock(user.id(), memoryId);
        if (!Set.of("ACTIVE", "CANDIDATE").contains(target.status())) {
            throw invalidTransition("只能替换 ACTIVE 或 CANDIDATE 记忆");
        }

        String memoryKey = clean(request.memoryKey());
        String normalizedKey = normalizedKey(memoryKey);
        String content = memoryContent(request.content());
        String reason = safeReason(request.reason());
        validateExpiry(request.expiresAt());
        SourceValidation validation = validateSources(
                target.memoryType(), content, request.sources(), user
        );
        acquireKeyLock(user.id(), target.memoryType(), normalizedKey);
        UUID active = findActive(
                user.id(), target.memoryType(), normalizedKey
        );
        if ("ACTIVE".equals(target.status())
                && active != null
                && !active.equals(memoryId)) {
            throw activeConflict();
        }

        UUID superseded;
        if ("ACTIVE".equals(target.status())) {
            superseded = memoryId;
            updateStatus(memoryId, user.id(), "REVOKED");
            event(
                    memoryId, user.id(), "SUPERSEDED", user.id(), null, reason
            );
        } else {
            superseded = active == null ? memoryId : active;
            if (active != null) {
                updateStatus(active, user.id(), "REVOKED");
                event(
                        active, user.id(), "SUPERSEDED",
                        user.id(), null, reason
                );
            }
            updateStatus(memoryId, user.id(), "REJECTED");
            event(
                    memoryId, user.id(), "REJECTED", user.id(), null, reason
            );
        }

        UUID replacementId = UUID.randomUUID();
        int version = nextVersion(
                user.id(), target.memoryType(), normalizedKey
        );
        insertItem(
                replacementId,
                user.id(),
                target.memoryType(),
                memoryKey,
                normalizedKey,
                content,
                "ACTIVE",
                version,
                "USER",
                superseded,
                safeIdempotencyKey,
                request.expiresAt()
        );
        insertSources(replacementId, user.id(), validation.sources());
        event(
                replacementId,
                user.id(),
                "REPLACED",
                user.id(),
                superseded,
                reason
        );
        return requireVisible(user, replacementId).view();
    }

    @Transactional
    public MemoryItemView revoke(
            PlatformUserPrincipal user,
            UUID memoryId,
            String reason
    ) {
        String safeReason = safeReason(reason);
        expireOwned(user.id());
        requireVisible(user, memoryId);
        LockedItem item = lock(user.id(), memoryId);
        if ("REVOKED".equals(item.status())
                || "REJECTED".equals(item.status())
                || "EXPIRED".equals(item.status())) {
            return requireVisible(user, memoryId).view();
        }
        if (!"ACTIVE".equals(item.status())) {
            throw invalidTransition("只有 ACTIVE 记忆可以撤销");
        }
        updateStatus(memoryId, user.id(), "REVOKED");
        event(
                memoryId, user.id(), "REVOKED", user.id(), null, safeReason
        );
        return requireVisible(user, memoryId).view();
    }

    @Transactional
    public MemoryItemView forget(
            PlatformUserPrincipal user,
            UUID memoryId,
            String reason
    ) {
        String safeReason = safeReason(reason);
        expireOwned(user.id());
        LockedItem item = lock(user.id(), memoryId);
        if ("FORGOTTEN".equals(item.status())) {
            return requireVisible(user, memoryId).view();
        }
        jdbc.update(
                "DELETE FROM memory_sources "
                        + "WHERE memory_item_id = ? AND owner_user_id = ?",
                memoryId,
                user.id()
        );
        jdbc.update(
                """
                UPDATE memory_items
                SET content = NULL,
                    status = 'FORGOTTEN',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ?
                """,
                memoryId,
                user.id()
        );
        event(
                memoryId, user.id(), "FORGOTTEN", user.id(), null, safeReason
        );
        return requireVisible(user, memoryId).view();
    }

    @Transactional(readOnly = true)
    public AdminMemorySummaryView adminSummary() {
        Map<String, Long> byType = counts(
                "SELECT memory_type, count(*) FROM memory_items "
                        + "GROUP BY memory_type ORDER BY memory_type"
        );
        Map<String, Long> byStatus = counts(
                """
                SELECT effective_status, count(*)
                FROM (
                    SELECT CASE
                        WHEN status IN ('ACTIVE', 'CANDIDATE')
                             AND expires_at <= CURRENT_TIMESTAMP
                        THEN 'EXPIRED'
                        ELSE status
                    END AS effective_status
                    FROM memory_items
                ) item
                GROUP BY effective_status
                ORDER BY effective_status
                """
        );
        return new AdminMemorySummaryView(
                count("SELECT count(*) FROM user_memory_settings"),
                count("""
                        SELECT count(*) FROM user_memory_settings
                        WHERE enabled
                        """),
                count("""
                        SELECT count(*) FROM user_memory_settings
                        WHERE suggestion_enabled
                        """),
                byType,
                byStatus
        );
    }

    private List<ItemRow> rows(PlatformUserPrincipal user) {
        return jdbc.query(
                itemSelect() + """
                        WHERE item.owner_user_id = ?
                        ORDER BY item.updated_at DESC, item.id
                        """,
                this::itemRow,
                user.role() == UserRole.ADMIN,
                user.id(),
                user.id(),
                user.id()
        );
    }

    private ItemRow requireVisible(
            PlatformUserPrincipal user,
            UUID memoryId
    ) {
        List<ItemRow> found = jdbc.query(
                itemSelect() + """
                        WHERE item.owner_user_id = ? AND item.id = ?
                        """,
                this::itemRow,
                user.role() == UserRole.ADMIN,
                user.id(),
                user.id(),
                user.id(),
                memoryId
        );
        if (found.isEmpty() || !found.getFirst().sourcesValid()) {
            throw notFound();
        }
        return found.getFirst();
    }

    private ItemRow requireOwned(UUID owner, UUID memoryId) {
        List<ItemRow> found = jdbc.query(
                itemSelect() + """
                        WHERE item.owner_user_id = ? AND item.id = ?
                        """,
                this::itemRow,
                false,
                owner,
                owner,
                owner,
                memoryId
        );
        if (found.isEmpty() || !found.getFirst().sourcesValid()) {
            throw notFound();
        }
        return found.getFirst();
    }

    private static String itemSelect() {
        return """
                SELECT item.id, item.memory_type, item.memory_key,
                       item.normalized_key, item.content, item.status,
                       item.version_number, item.origin,
                       item.supersedes_memory_id, item.expires_at,
                       item.created_at, item.updated_at,
                       (
                           SELECT count(*)::integer
                           FROM memory_sources source
                           WHERE source.memory_item_id = item.id
                       ) AS source_count,
                       NOT EXISTS (
                           SELECT 1
                           FROM memory_sources source
                           JOIN documents document
                             ON document.id = source.document_id
                           WHERE source.memory_item_id = item.id
                             AND source.source_type = 'DOCUMENT_SPAN'
                             AND NOT (
                                 document.deleted_at IS NULL
                                 AND document.current_revision_id =
                                     source.revision_id
                                 AND (
                                     ?
                                     OR document.visibility = 'ALL_USERS'
                                     OR document.owner_user_id = ?
                                     OR EXISTS (
                                         SELECT 1
                                         FROM document_acl_entries acl
                                         WHERE acl.document_id = document.id
                                           AND acl.user_id = ?
                                     )
                                 )
                             )
                       ) AS sources_valid
                FROM memory_items item
                """;
    }

    private ItemRow itemRow(ResultSet rs, int row) throws SQLException {
        var expiresAt = rs.getTimestamp("expires_at");
        return new ItemRow(
                new MemoryItemView(
                        rs.getObject("id", UUID.class),
                        rs.getString("memory_type"),
                        rs.getString("memory_key"),
                        rs.getString("content"),
                        rs.getString("status"),
                        rs.getInt("version_number"),
                        rs.getString("origin"),
                        rs.getObject("supersedes_memory_id", UUID.class),
                        rs.getInt("source_count"),
                        expiresAt == null ? null : expiresAt.toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                rs.getString("normalized_key"),
                rs.getBoolean("sources_valid")
        );
    }

    private MemorySettingsView readSettings(UUID userId) {
        return jdbc.queryForObject(
                """
                SELECT enabled, suggestion_enabled, version, updated_at
                FROM user_memory_settings
                WHERE user_id = ?
                """,
                (rs, row) -> new MemorySettingsView(
                        rs.getBoolean("enabled"),
                        rs.getBoolean("suggestion_enabled"),
                        rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                userId
        );
    }

    private void ensureSettings(UUID userId) {
        jdbc.update(
                """
                INSERT INTO user_memory_settings (user_id)
                VALUES (?)
                ON CONFLICT (user_id) DO NOTHING
                """,
                userId
        );
    }

    private SourceValidation validateSources(
            String memoryType,
            String content,
            List<MemorySourceInput> sources,
            PlatformUserPrincipal user
    ) {
        List<MemorySourceInput> safeSources =
                sources == null ? List.of() : List.copyOf(sources);
        Set<String> identities = new LinkedHashSet<>();
        List<String> documentTexts = new ArrayList<>();
        boolean hasDocument = false;
        boolean hasChat = false;

        for (MemorySourceInput source : safeSources) {
            switch (source.sourceType()) {
                case "CHAT_SESSION" -> {
                    requireShape(
                            source.chatSessionId() != null
                                    && source.chatMessageId() == null
                                    && noDocumentFields(source),
                            "CHAT_SESSION 来源字段不完整"
                    );
                    requireOwnedSession(user.id(), source.chatSessionId());
                    duplicate(identities, "session:" + source.chatSessionId());
                    hasChat = true;
                }
                case "CHAT_MESSAGE" -> {
                    requireShape(
                            source.chatSessionId() != null
                                    && source.chatMessageId() != null
                                    && noDocumentFields(source),
                            "CHAT_MESSAGE 来源字段不完整"
                    );
                    requireOwnedMessage(
                            user.id(),
                            source.chatSessionId(),
                            source.chatMessageId()
                    );
                    duplicate(identities, "message:" + source.chatMessageId());
                    hasChat = true;
                }
                case "DOCUMENT_SPAN" -> {
                    requireShape(
                            source.chatSessionId() == null
                                    && source.chatMessageId() == null
                                    && source.documentId() != null
                                    && source.revisionId() != null
                                    && source.childChunkId() != null
                                    && source.sourceSpanId() != null,
                            "DOCUMENT_SPAN 来源字段不完整"
                    );
                    if (!"DOCUMENT_FACT".equals(memoryType)) {
                        throw new ApiException(
                                HttpStatus.BAD_REQUEST,
                                "MEMORY_SOURCE_TYPE_INVALID",
                                "文档来源只能用于 DOCUMENT_FACT"
                        );
                    }
                    documentTexts.add(requireDocumentSource(user, source));
                    duplicate(
                            identities, "span:" + source.sourceSpanId()
                    );
                    hasDocument = true;
                }
                default -> throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MEMORY_SOURCE_TYPE_INVALID",
                        "不支持的记忆来源类型"
                );
            }
        }
        if ("DOCUMENT_FACT".equals(memoryType) && !hasDocument) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_DOCUMENT_SOURCE_REQUIRED",
                    "DOCUMENT_FACT 至少需要一个当前可访问的 Child SourceSpan"
            );
        }
        if ("SESSION_SUMMARY".equals(memoryType) && !hasChat) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_CHAT_SOURCE_REQUIRED",
                    "SESSION_SUMMARY 至少需要一个当前用户的会话来源"
            );
        }
        String normalizedContent = comparable(content);
        if (documentTexts.stream()
                .map(MemoryService::comparable)
                .anyMatch(normalizedContent::equals)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_DOCUMENT_BODY_REJECTED",
                    "长期记忆只保存简短事实，不能复制完整 Child 或文档正文"
            );
        }
        return new SourceValidation(safeSources);
    }

    private void requireOwnedSession(UUID owner, UUID sessionId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chat_sessions
                WHERE id = ? AND owner_user_id = ?
                """,
                Integer.class,
                sessionId,
                owner
        );
        if (count == null || count != 1) {
            throw sourceUnavailable();
        }
    }

    private void requireOwnedMessage(
            UUID owner,
            UUID sessionId,
            UUID messageId
    ) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chat_messages
                WHERE id = ? AND session_id = ? AND owner_user_id = ?
                  AND status = 'COMPLETED'
                """,
                Integer.class,
                messageId,
                sessionId,
                owner
        );
        if (count == null || count != 1) {
            throw sourceUnavailable();
        }
    }

    private String requireDocumentSource(
            PlatformUserPrincipal user,
            MemorySourceInput source
    ) {
        List<String> texts = jdbc.query(
                """
                SELECT chunk.text
                FROM source_spans span
                JOIN chunks chunk
                  ON chunk.id = span.chunk_id
                 AND chunk.document_id = span.document_id
                 AND chunk.revision_id = span.revision_id
                JOIN documents document ON document.id = span.document_id
                JOIN document_revisions revision
                  ON revision.id = span.revision_id
                 AND revision.document_id = span.document_id
                WHERE span.id = ?
                  AND span.chunk_id = ?
                  AND span.document_id = ?
                  AND span.revision_id = ?
                  AND chunk.chunk_type = 'CHILD'
                  AND chunk.searchable
                  AND revision.status = 'READY'
                  AND document.deleted_at IS NULL
                  AND document.current_revision_id = span.revision_id
                  AND (
                      ?
                      OR document.visibility = 'ALL_USERS'
                      OR document.owner_user_id = ?
                      OR EXISTS (
                          SELECT 1
                          FROM document_acl_entries acl
                          WHERE acl.document_id = document.id
                            AND acl.user_id = ?
                      )
                  )
                """,
                (rs, row) -> rs.getString(1),
                source.sourceSpanId(),
                source.childChunkId(),
                source.documentId(),
                source.revisionId(),
                user.role() == UserRole.ADMIN,
                user.id(),
                user.id()
        );
        if (texts.size() != 1) {
            throw sourceUnavailable();
        }
        return texts.getFirst();
    }

    private void insertSources(
            UUID memoryId,
            UUID owner,
            List<MemorySourceInput> sources
    ) {
        for (MemorySourceInput source : sources) {
            jdbc.update(
                    """
                    INSERT INTO memory_sources (
                        id, memory_item_id, owner_user_id, source_type,
                        chat_session_id, chat_message_id, document_id,
                        revision_id, child_chunk_id, source_span_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    memoryId,
                    owner,
                    source.sourceType(),
                    source.chatSessionId(),
                    source.chatMessageId(),
                    source.documentId(),
                    source.revisionId(),
                    source.childChunkId(),
                    source.sourceSpanId()
            );
        }
    }

    private void insertItem(
            UUID id,
            UUID owner,
            String type,
            String key,
            String normalizedKey,
            String content,
            String status,
            int version,
            String origin,
            UUID supersedes,
            String idempotencyKey,
            Instant expiresAt
    ) {
        try {
            jdbc.update(
                    """
                    INSERT INTO memory_items (
                        id, owner_user_id, memory_type, memory_key,
                        normalized_key, content, status, version_number,
                        origin, supersedes_memory_id, idempotency_key,
                        expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    owner,
                    type,
                    key,
                    normalizedKey,
                    content,
                    status,
                    version,
                    origin,
                    supersedes,
                    idempotencyKey,
                    expiresAt
            );
        } catch (DuplicateKeyException duplicate) {
            throw activeConflict();
        }
    }

    private void event(
            UUID memoryId,
            UUID owner,
            String eventType,
            UUID actor,
            UUID relatedMemory,
            String reason
    ) {
        jdbc.update(
                """
                INSERT INTO memory_events (
                    memory_item_id, owner_user_id, event_type,
                    actor_user_id, related_memory_id, reason
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                memoryId,
                owner,
                eventType,
                actor,
                relatedMemory,
                reason
        );
    }

    private void expireOwned(UUID owner) {
        jdbc.update(
                """
                WITH expired AS (
                    UPDATE memory_items
                    SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
                    WHERE owner_user_id = ?
                      AND status IN ('ACTIVE', 'CANDIDATE')
                      AND expires_at <= CURRENT_TIMESTAMP
                    RETURNING id, owner_user_id
                )
                INSERT INTO memory_events (
                    memory_item_id, owner_user_id, event_type
                )
                SELECT id, owner_user_id, 'EXPIRED'
                FROM expired
                """,
                owner
        );
    }

    private LockedItem lock(UUID owner, UUID memoryId) {
        List<LockedItem> items = jdbc.query(
                """
                SELECT id, memory_type, normalized_key, status
                FROM memory_items
                WHERE id = ? AND owner_user_id = ?
                FOR UPDATE
                """,
                (rs, row) -> new LockedItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("memory_type"),
                        rs.getString("normalized_key"),
                        rs.getString("status")
                ),
                memoryId,
                owner
        );
        if (items.isEmpty()) {
            throw notFound();
        }
        return items.getFirst();
    }

    private void updateStatus(UUID memoryId, UUID owner, String status) {
        jdbc.update(
                """
                UPDATE memory_items
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ?
                """,
                status,
                memoryId,
                owner
        );
    }

    private UUID findActive(UUID owner, String type, String normalizedKey) {
        List<UUID> ids = jdbc.query(
                """
                SELECT id
                FROM memory_items
                WHERE owner_user_id = ?
                  AND memory_type = ?
                  AND normalized_key = ?
                  AND status = 'ACTIVE'
                """,
                (rs, row) -> rs.getObject(1, UUID.class),
                owner,
                type,
                normalizedKey
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private UUID findByIdempotency(UUID owner, String key) {
        List<UUID> ids = jdbc.query(
                """
                SELECT id
                FROM memory_items
                WHERE owner_user_id = ? AND idempotency_key = ?
                """,
                (rs, row) -> rs.getObject(1, UUID.class),
                owner,
                key
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private int nextVersion(UUID owner, String type, String normalizedKey) {
        Integer version = jdbc.queryForObject(
                """
                SELECT COALESCE(max(version_number), 0) + 1
                FROM memory_items
                WHERE owner_user_id = ?
                  AND memory_type = ?
                  AND normalized_key = ?
                """,
                Integer.class,
                owner,
                type,
                normalizedKey
        );
        return version == null ? 1 : version;
    }

    private void acquireKeyLock(
            UUID owner,
            String type,
            String normalizedKey
    ) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) rs -> null,
                owner + ":" + type + ":" + normalizedKey
        );
    }

    private long count(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(
                sql,
                (rs, row) -> Map.entry(rs.getString(1), rs.getLong(2))
        ).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static String filter(
            String value,
            Set<String> allowed,
            String errorCode
    ) {
        String result = value == null || value.isBlank()
                ? "ALL"
                : value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    errorCode,
                    "不支持的记忆筛选条件"
            );
        }
        return result;
    }

    private static String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 必须为 8–128 位安全字符"
            );
        }
        return value;
    }

    private static String memoryContent(String value) {
        String content = clean(value);
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_DOCUMENT_BODY_REJECTED",
                    "长期记忆只保存不超过 1200 字符的简短事实"
            );
        }
        rejectCredentials(content);
        return content;
    }

    private static String normalizedKey(String value) {
        String normalized = Normalizer.normalize(
                value, Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        rejectCredentials(normalized);
        return normalized;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_CONTENT_REQUIRED",
                    "记忆名称和正文不能为空"
            );
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String reason = value.strip();
        rejectCredentials(reason);
        return reason;
    }

    private static void rejectCredentials(String value) {
        if (containsCredentials(value)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_CREDENTIAL_REJECTED",
                    "长期记忆不能保存密码、Cookie、Token、API Key 或私钥"
            );
        }
    }

    static boolean containsCredentials(String value) {
        return value != null && CREDENTIAL_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(value).find());
    }

    private static void validateExpiry(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_EXPIRY_INVALID",
                    "过期时间必须晚于当前时间"
            );
        }
    }

    private static boolean noDocumentFields(MemorySourceInput source) {
        return source.documentId() == null
                && source.revisionId() == null
                && source.childChunkId() == null
                && source.sourceSpanId() == null;
    }

    private static void requireShape(boolean valid, String message) {
        if (!valid) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_SOURCE_SHAPE_INVALID",
                    message
            );
        }
    }

    private static void duplicate(Set<String> identities, String identity) {
        if (!identities.add(identity)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MEMORY_SOURCE_DUPLICATE",
                    "记忆来源不能重复"
            );
        }
    }

    private static String comparable(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static ApiException sourceUnavailable() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "MEMORY_SOURCE_NOT_FOUND",
                "记忆来源不存在或当前无权访问"
        );
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "MEMORY_NOT_FOUND",
                "记忆不存在或当前无权访问"
        );
    }

    private static ApiException activeConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "MEMORY_ACTIVE_CONFLICT",
                "同类型和名称已有 ACTIVE 记忆，请使用替换操作"
        );
    }

    private static ApiException invalidTransition(String message) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "MEMORY_STATE_CONFLICT",
                message
        );
    }

    private record ItemRow(
            MemoryItemView view,
            String normalizedKey,
            boolean sourcesValid
    ) {
    }

    private record LockedItem(
            UUID id,
            String memoryType,
            String normalizedKey,
            String status
    ) {
    }

    private record SourceValidation(List<MemorySourceInput> sources) {
    }
}
