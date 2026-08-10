package com.example.rag.memory;

import com.example.rag.common.ApiException;
import com.example.rag.security.PlatformUserPrincipal;
import com.example.rag.persistence.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryPackService {

    public static final int MAX_ITEMS = 5;
    public static final int MAX_TOKENS = 512;
    public static final int CONTEXT_PERCENT = 10;
    public static final String TOKEN_COUNTER_VERSION =
            "conservative-codepoint-json-v1";
    private static final int MAX_AUDIT_ITEMS = 20;
    private static final int DEFAULT_CONTEXT_TOKENS = 8_192;
    private static final double MIN_PERSONAL_RELEVANCE = 0.10;
    private static final double MIN_DOCUMENT_RELEVANCE = 0.20;
    private static final Pattern WORD = Pattern.compile(
            "[\\p{L}\\p{N}]+"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MemoryPackService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MemoryPack recall(
            PlatformUserPrincipal user,
            String standaloneQuery,
            boolean personalMemoryAllowed
    ) {
        return recall(
                user,
                standaloneQuery,
                personalMemoryAllowed,
                DEFAULT_CONTEXT_TOKENS
        );
    }

    @Transactional(readOnly = true)
    public MemoryPack recall(
            PlatformUserPrincipal user,
            String standaloneQuery,
            boolean personalMemoryAllowed,
            int totalContextTokens
    ) {
        int tokenBudget = Math.min(
                MAX_TOKENS,
                (int) Math.min(
                        Integer.MAX_VALUE,
                        (long) Math.max(0, totalContextTokens)
                                * CONTEXT_PERCENT / 100
                )
        );
        try {
            Boolean enabled = jdbc.query(
                    """
                    SELECT enabled
                    FROM user_memory_settings
                    WHERE user_id = ?
                    """,
                    rs -> rs.next() && rs.getBoolean(1),
                    user.id()
            );
            if (!Boolean.TRUE.equals(enabled)) {
                return MemoryPack.off();
            }
            List<Candidate> candidates = candidates(user).stream()
                    .map(candidate -> candidate.withRelevance(
                            relevance(standaloneQuery, candidate)
                    ))
                    .sorted(Comparator
                            .comparingDouble(Candidate::score).reversed()
                            .thenComparing(
                                    Candidate::updatedAt,
                                    Comparator.reverseOrder()
                            )
                            .thenComparing(Candidate::id))
                    .limit(MAX_AUDIT_ITEMS)
                    .toList();
            List<Selection> selections = new ArrayList<>();
            int selected = 0;
            int tokens = 0;
            for (Candidate candidate : candidates) {
                String status;
                String trimReason = null;
                int itemTokens = "DOCUMENT_FACT".equals(candidate.memoryType())
                        ? 0
                        : conservativeTokens(candidate);
                if (!relevant(candidate)) {
                    status = "TRIMMED";
                    trimReason = "MEMORY_RELEVANCE_LOW";
                } else if (!"DOCUMENT_FACT".equals(candidate.memoryType())
                        && !personalMemoryAllowed) {
                    status = "REMOTE_BLOCKED";
                    trimReason = "REMOTE_MEMORY_NOT_ALLOWED";
                } else if (selected >= MAX_ITEMS) {
                    status = "TRIMMED";
                    trimReason = "MEMORY_ITEM_LIMIT";
                } else if (tokens + itemTokens > tokenBudget) {
                    status = "TRIMMED";
                    trimReason = "MEMORY_TOKEN_BUDGET";
                } else {
                    status = "DOCUMENT_FACT".equals(candidate.memoryType())
                            ? "DOCUMENT_EVIDENCE"
                            : "INJECTED";
                    selected++;
                    tokens += itemTokens;
                }
                selections.add(new Selection(
                        candidate.id(),
                        candidate.memoryType(),
                        candidate.memoryKey(),
                        candidate.content(),
                        candidate.score(),
                        itemTokens,
                        candidate.sourceTypes(),
                        candidate.childChunkIds(),
                        status,
                        trimReason,
                        sha256(candidate.content())
                ));
            }
            return new MemoryPack(
                    true,
                    List.copyOf(selections),
                    tokens,
                    tokenBudget,
                    TOKEN_COUNTER_VERSION,
                    null
            );
        } catch (DataAccessException exception) {
            return new MemoryPack(
                    false,
                    List.of(),
                    0,
                    tokenBudget,
                    TOKEN_COUNTER_VERSION,
                    "MEMORY_SERVICE_UNAVAILABLE"
            );
        }
    }

    @Transactional
    public void saveRunUsages(
            UUID ownerUserId,
            UUID runId,
            MemoryPack pack,
            Set<UUID> usedMemoryIds,
            Set<UUID> usedDocumentChildIds
    ) {
        if (pack.selections().isEmpty()) {
            return;
        }
        requireOwnedRun(ownerUserId, runId);
        int order = 1;
        for (Selection selection : pack.selections()) {
            String status = "INJECTED".equals(selection.status())
                    && usedMemoryIds.contains(selection.memoryId())
                    ? "USED"
                    : selection.status();
            String trimReason = selection.trimReason();
            if ("DOCUMENT_EVIDENCE".equals(status)
                    && selection.childChunkIds().stream().noneMatch(
                    usedDocumentChildIds::contains)) {
                status = "TRIMMED";
                trimReason = "MEMORY_DOCUMENT_NOT_SELECTED";
            }
            jdbc.update(
                    """
                    INSERT INTO chat_run_memory_usages (
                        run_id, owner_user_id, memory_item_id, usage_order,
                        memory_type, usage_status, relevance_score,
                        token_count, token_limit, token_counter_version,
                        token_count_exact, source_types, content_hash,
                        trim_reason
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?::jsonb, ?, ?
                    )
                    ON CONFLICT (run_id, memory_item_id) DO NOTHING
                    """,
                    runId,
                    ownerUserId,
                    selection.memoryId(),
                    order++,
                    selection.memoryType(),
                    status,
                    selection.score(),
                    selection.tokenCount(),
                    pack.tokenBudget(),
                    pack.tokenCounterVersion(),
                    json(selection.sourceTypes()),
                    selection.contentHash(),
                    trimReason
            );
        }
    }

    @Transactional(readOnly = true)
    public List<RunMemoryUsageView> runUsages(
            PlatformUserPrincipal user,
            UUID runId
    ) {
        requireOwnedRun(user.id(), runId);
        return runUsages(user, List.of(runId));
    }

    @Transactional(readOnly = true)
    public List<RunMemoryUsageView> runUsages(
            PlatformUserPrincipal user,
            List<UUID> runIds
    ) {
        List<UUID> ids = runIds == null
                ? List.of()
                : runIds.stream().distinct().limit(100).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(ids.size(), "?")
        );
        List<Object> arguments = new ArrayList<>();
        arguments.add(user.role() == UserRole.ADMIN);
        arguments.add(user.id());
        arguments.add(user.id());
        arguments.add(user.id());
        arguments.addAll(ids);
        return jdbc.query(
                """
                SELECT usage.run_id, usage.memory_item_id, usage.memory_type,
                       usage.usage_status, usage.relevance_score,
                       usage.token_count, usage.token_limit,
                       usage.token_counter_version, usage.token_count_exact,
                       usage.source_types::text,
                       usage.trim_reason, usage.created_at,
                       CASE WHEN item.status = 'ACTIVE'
                                  AND (item.expires_at IS NULL
                                      OR item.expires_at > CURRENT_TIMESTAMP)
                                  AND NOT EXISTS (
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
                                                OR document.visibility =
                                                    'ALL_USERS'
                                                OR document.owner_user_id = ?
                                                OR EXISTS (
                                                    SELECT 1
                                                    FROM document_acl_entries acl
                                                    WHERE acl.document_id =
                                                        document.id
                                                      AND acl.user_id = ?
                                                )
                                            )
                                        )
                                  )
                            THEN TRUE ELSE FALSE END AS available,
                       item.memory_key, item.content
                FROM chat_run_memory_usages usage
                JOIN memory_items item
                  ON item.id = usage.memory_item_id
                 AND item.owner_user_id = usage.owner_user_id
                WHERE usage.owner_user_id = ?
                  AND usage.run_id IN (%s)
                ORDER BY usage.run_id, usage.usage_order
                """.formatted(placeholders),
                (rs, row) -> usageView(rs),
                arguments.toArray()
        );
    }

    @Transactional(readOnly = true)
    public boolean allUsedCurrent(
            PlatformUserPrincipal user,
            UUID runId
    ) {
        List<RunMemoryUsageView> used = runUsages(user, runId).stream()
                .filter(usage -> "USED".equals(usage.usageStatus()))
                .toList();
        return !used.isEmpty() && used.stream()
                .allMatch(RunMemoryUsageView::available);
    }

    @Transactional(readOnly = true)
    public Set<UUID> currentInjectedIds(
            PlatformUserPrincipal user,
            MemoryPack pack
    ) {
        Set<UUID> current = new LinkedHashSet<>();
        for (Selection selection : pack.injected()) {
            if (current(user, selection.memoryId())) {
                current.add(selection.memoryId());
            }
        }
        return Set.copyOf(current);
    }

    private boolean current(
            PlatformUserPrincipal user,
            UUID memoryId
    ) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM memory_items item
                WHERE item.id = ?
                  AND item.owner_user_id = ?
                  AND item.status = 'ACTIVE'
                  AND (item.expires_at IS NULL
                       OR item.expires_at > CURRENT_TIMESTAMP)
                  AND NOT EXISTS (
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
                  )
                """,
                Integer.class,
                memoryId,
                user.id(),
                user.role() == UserRole.ADMIN,
                user.id(),
                user.id()
        );
        return count != null && count == 1;
    }

    private List<Candidate> candidates(PlatformUserPrincipal user) {
        return jdbc.query(
                """
                SELECT item.id, item.memory_type, item.memory_key,
                       item.content, item.updated_at,
                       COALESCE((
                           SELECT jsonb_agg(DISTINCT source.source_type)
                           FROM memory_sources source
                           WHERE source.memory_item_id = item.id
                       ), '[]'::jsonb)::text AS source_types,
                       COALESCE((
                           SELECT jsonb_agg(DISTINCT source.child_chunk_id)
                           FROM memory_sources source
                           WHERE source.memory_item_id = item.id
                             AND source.source_type = 'DOCUMENT_SPAN'
                       ), '[]'::jsonb)::text AS child_chunk_ids
                FROM memory_items item
                WHERE item.owner_user_id = ?
                  AND item.status = 'ACTIVE'
                  AND item.memory_type IN (
                      'USER_PREFERENCE', 'USER_FACT', 'DOCUMENT_FACT'
                  )
                  AND (item.expires_at IS NULL
                       OR item.expires_at > CURRENT_TIMESTAMP)
                  AND NOT EXISTS (
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
                  )
                  AND (
                      item.memory_type <> 'DOCUMENT_FACT'
                      OR EXISTS (
                          SELECT 1
                          FROM memory_sources source
                          WHERE source.memory_item_id = item.id
                            AND source.source_type = 'DOCUMENT_SPAN'
                      )
                  )
                ORDER BY item.updated_at DESC, item.id
                LIMIT 200
                """,
                (rs, row) -> candidate(rs),
                user.id(),
                user.role() == UserRole.ADMIN,
                user.id(),
                user.id()
        );
    }

    private Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(
                rs.getObject("id", UUID.class),
                rs.getString("memory_type"),
                rs.getString("memory_key"),
                rs.getString("content"),
                readList(
                        rs.getString("source_types"),
                        new TypeReference<>() {
                        }
                ),
                readList(
                        rs.getString("child_chunk_ids"),
                        new TypeReference<>() {
                        }
                ),
                rs.getTimestamp("updated_at").toInstant(),
                0.0,
                0
        );
    }

    private RunMemoryUsageView usageView(ResultSet rs) throws SQLException {
        boolean available = rs.getBoolean("available");
        return new RunMemoryUsageView(
                rs.getObject("run_id", UUID.class),
                rs.getObject("memory_item_id", UUID.class),
                rs.getString("memory_type"),
                rs.getString("usage_status"),
                rs.getDouble("relevance_score"),
                rs.getInt("token_count"),
                rs.getInt("token_limit"),
                rs.getString("token_counter_version"),
                rs.getBoolean("token_count_exact"),
                readList(
                        rs.getString("source_types"),
                        new TypeReference<>() {
                        }
                ),
                available,
                available ? rs.getString("memory_key") : null,
                available ? rs.getString("content") : null,
                rs.getString("trim_reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private void requireOwnedRun(UUID ownerUserId, UUID runId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chat_runs
                WHERE id = ? AND owner_user_id = ?
                """,
                Integer.class,
                runId,
                ownerUserId
        );
        if (count == null || count != 1) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "CHAT_RUN_NOT_FOUND",
                    "问答任务不存在"
            );
        }
    }

    private Relevance relevance(String query, Candidate candidate) {
        Set<String> queryFeatures = features(query);
        Set<String> memoryFeatures = features(
                candidate.memoryKey() + " " + candidate.content()
        );
        long overlap = queryFeatures.stream()
                .filter(memoryFeatures::contains)
                .count();
        double overlapScore = queryFeatures.isEmpty()
                ? 0.0
                : (double) overlap / queryFeatures.size();
        return new Relevance(Math.min(1.0, overlapScore), (int) overlap);
    }

    private static boolean relevant(Candidate candidate) {
        if (candidate.matchCount() == 0) {
            return false;
        }
        if ("DOCUMENT_FACT".equals(candidate.memoryType())) {
            return candidate.matchCount() >= 2
                    && candidate.score() >= MIN_DOCUMENT_RELEVANCE;
        }
        return candidate.score() >= MIN_PERSONAL_RELEVANCE;
    }

    private static Set<String> features(String value) {
        String normalized = value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).strip();
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            int[] codePoints = token.codePoints().toArray();
            boolean containsHan = java.util.Arrays.stream(codePoints)
                    .anyMatch(codePoint ->
                            Character.UnicodeScript.of(codePoint)
                                    == Character.UnicodeScript.HAN);
            if (!containsHan || codePoints.length >= 2) {
                result.add(token);
            }
            for (int index = 0; index < codePoints.length; index++) {
                if (Character.UnicodeScript.of(codePoints[index])
                        == Character.UnicodeScript.HAN) {
                    if (index + 1 < codePoints.length
                            && Character.UnicodeScript.of(codePoints[index + 1])
                            == Character.UnicodeScript.HAN) {
                        result.add(new String(codePoints, index, 2));
                    }
                }
            }
        }
        return result;
    }

    private int conservativeTokens(Candidate candidate) {
        try {
            String serialized = objectMapper.writeValueAsString(java.util.Map.of(
                    "id", candidate.id().toString(),
                    "type", candidate.memoryType(),
                    "key", candidate.memoryKey(),
                    "content", candidate.content()
            ));
            int tokens = 8;
            int asciiRun = 0;
            int[] codePoints = serialized.codePoints().toArray();
            for (int codePoint : codePoints) {
                if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                    asciiRun++;
                    continue;
                }
                if (asciiRun > 0) {
                    tokens += (asciiRun + 2) / 3;
                    asciiRun = 0;
                }
                if (!Character.isWhitespace(codePoint)) {
                    tokens++;
                }
            }
            if (asciiRun > 0) {
                tokens += (asciiRun + 2) / 3;
            }
            return Math.min(4_096, tokens);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to count serialized memory", exception
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize memory usage", exception
            );
        }
    }

    private <T> List<T> readList(
            String value,
            TypeReference<List<T>> type
    ) {
        try {
            return List.copyOf(objectMapper.readValue(value, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored memory source list is invalid", exception
            );
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record MemoryPack(
            boolean enabled,
            List<Selection> selections,
            int tokenCount,
            int tokenBudget,
            String tokenCounterVersion,
            String degradationCode
    ) {
        public static MemoryPack off() {
            return new MemoryPack(
                    false,
                    List.of(),
                    0,
                    MAX_TOKENS,
                    TOKEN_COUNTER_VERSION,
                    null
            );
        }

        public List<Selection> injected() {
            return selections.stream()
                    .filter(item -> "INJECTED".equals(item.status()))
                    .toList();
        }

        public List<UUID> documentChildIds() {
            return selections.stream()
                    .filter(item -> "DOCUMENT_EVIDENCE".equals(item.status()))
                    .flatMap(item -> item.childChunkIds().stream())
                    .distinct()
                    .toList();
        }
    }

    public record Selection(
            UUID memoryId,
            String memoryType,
            String memoryKey,
            String content,
            double score,
            int tokenCount,
            List<String> sourceTypes,
            List<UUID> childChunkIds,
            String status,
            String trimReason,
            String contentHash
    ) {
    }

    public record RunMemoryUsageView(
            UUID runId,
            UUID memoryId,
            String memoryType,
            String usageStatus,
            double relevanceScore,
            int tokenCount,
            int tokenLimit,
            String tokenCounterVersion,
            boolean tokenCountExact,
            List<String> sourceTypes,
            boolean available,
            String memoryKey,
            String content,
            String trimReason,
            Instant createdAt
    ) {
    }

    private record Candidate(
            UUID id,
            String memoryType,
            String memoryKey,
            String content,
            List<String> sourceTypes,
            List<UUID> childChunkIds,
            Instant updatedAt,
            double score,
            int matchCount
    ) {
        Candidate withRelevance(Relevance value) {
            return new Candidate(
                    id, memoryType, memoryKey, content, sourceTypes,
                    childChunkIds, updatedAt, value.score(), value.matchCount()
            );
        }
    }

    private record Relevance(double score, int matchCount) {
    }
}
