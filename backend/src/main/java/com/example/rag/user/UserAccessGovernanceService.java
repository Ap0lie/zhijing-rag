package com.example.rag.user;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.graph.GraphRebuildRequestService;
import com.example.rag.persistence.DocumentAclEntryEntity;
import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import com.example.rag.user.UserAccessContracts.AccessSummary;
import com.example.rag.user.UserAccessContracts.DocumentGrantChange;
import com.example.rag.user.UserAccessContracts.DocumentGrantPage;
import com.example.rag.user.UserAccessContracts.DocumentGrantUpdateRequest;
import com.example.rag.user.UserAccessContracts.DocumentGrantUpdateResult;
import com.example.rag.user.UserAccessContracts.DocumentGrantView;
import com.example.rag.user.UserAccessContracts.UserAccessView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserAccessGovernanceService {

    private static final String GRANT_ACTION = "DOCUMENT_GRANTS_CHANGED";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final DocumentRepository documents;
    private final DocumentAclEntryRepository aclEntries;
    private final GraphRebuildRequestService graphRebuilds;
    private final GovernanceEventService events;

    public UserAccessGovernanceService(
            JdbcTemplate jdbc,
            UserRepository users,
            DocumentRepository documents,
            DocumentAclEntryRepository aclEntries,
            GraphRebuildRequestService graphRebuilds,
            GovernanceEventService events
    ) {
        this.jdbc = jdbc;
        this.users = users;
        this.documents = documents;
        this.aclEntries = aclEntries;
        this.graphRebuilds = graphRebuilds;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Map<UUID, AccessSummary> summaries(List<UserEntity> userList) {
        if (userList.isEmpty()) {
            return Map.of();
        }
        long total = count("SELECT count(*) FROM documents WHERE deleted_at IS NULL");
        long publicCount = count("SELECT count(*) FROM documents WHERE deleted_at IS NULL AND visibility = 'ALL_USERS'");
        Map<UUID, Long> owned = countsByUser("""
                SELECT owner_user_id, count(*)
                FROM documents
                WHERE deleted_at IS NULL AND visibility = 'RESTRICTED'
                GROUP BY owner_user_id
                """);
        Map<UUID, Long> grants = countsByUser("""
                SELECT acl.user_id, count(*)
                FROM document_acl_entries acl
                JOIN documents document ON document.id = acl.document_id
                WHERE document.deleted_at IS NULL AND document.visibility = 'RESTRICTED'
                GROUP BY acl.user_id
                """);
        Map<UUID, AccessSummary> result = new HashMap<>();
        for (UserEntity user : userList) {
            long ownerCount = owned.getOrDefault(user.getId(), 0L);
            long grantCount = grants.getOrDefault(user.getId(), 0L);
            boolean platform = user.getRole() == UserRole.ADMIN;
            result.put(user.getId(), new AccessSummary(
                    platform,
                    platform ? 0 : publicCount,
                    platform ? 0 : ownerCount,
                    platform ? 0 : grantCount,
                    platform ? total : publicCount + ownerCount + grantCount
            ));
        }
        return Map.copyOf(result);
    }

    @Transactional(readOnly = true)
    public UserAccessView user(UUID userId) {
        UserEntity user = requireUser(userId);
        AccessSummary summary = summaries(List.of(user)).getOrDefault(userId, AccessSummary.empty());
        return new UserAccessView(UserResponse.from(user, summary), summary);
    }

    @Transactional(readOnly = true)
    public DocumentGrantPage grants(UUID userId, String query, int page, int size) {
        UserEntity user = requireUser(userId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String normalized = query == null ? "" : query.trim();
        String pattern = "%" + normalized.toLowerCase() + "%";
        long total = jdbc.queryForObject(
                """
                SELECT count(*) FROM documents document
                WHERE document.deleted_at IS NULL
                  AND (? = '' OR lower(document.title) LIKE ?)
                """,
                Long.class,
                normalized,
                pattern
        );
        List<DocumentGrantView> items = jdbc.query(
                """
                SELECT document.id, document.title, document.visibility,
                       document.owner_user_id, owner.username AS owner_username,
                       document.acl_version,
                       EXISTS (
                           SELECT 1 FROM document_acl_entries acl
                           WHERE acl.document_id = document.id AND acl.user_id = ?
                       ) AS explicit_grant
                FROM documents document
                JOIN users owner ON owner.id = document.owner_user_id
                WHERE document.deleted_at IS NULL
                  AND (? = '' OR lower(document.title) LIKE ?)
                ORDER BY lower(document.title), document.id
                LIMIT ? OFFSET ?
                """,
                (resultSet, rowNumber) -> {
                    UUID ownerId = resultSet.getObject("owner_user_id", UUID.class);
                    String visibility = resultSet.getString("visibility");
                    boolean explicit = resultSet.getBoolean("explicit_grant");
                    String source = "ALL_USERS".equals(visibility)
                            ? "PUBLIC"
                            : ownerId.equals(userId) ? "OWNER" : explicit ? "EXPLICIT" : "NONE";
                    boolean editable = user.getRole() == UserRole.USER
                            && user.isEnabled()
                            && "RESTRICTED".equals(visibility)
                            && !ownerId.equals(userId);
                    return new DocumentGrantView(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("title"),
                            visibility,
                            ownerId,
                            resultSet.getString("owner_username"),
                            resultSet.getLong("acl_version"),
                            source,
                            explicit,
                            editable
                    );
                },
                userId,
                normalized,
                pattern,
                safeSize,
                safePage * safeSize
        );
        return new DocumentGrantPage(userId, safePage, safeSize, total, items);
    }

    @Transactional
    public DocumentGrantUpdateResult updateGrants(
            UUID userId,
            DocumentGrantUpdateRequest request,
            PlatformUserPrincipal actor
    ) {
        if (!"UPDATE_DOCUMENT_GRANTS".equals(request.confirmation())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION_INVALID", "确认字段不正确");
        }
        String reason = GovernanceEventService.normalizeReason(request.reason());
        List<DocumentGrantChange> changes = normalize(request.changes());
        String requestHash = events.requestHash(userId + ":" + request.expectedUserSecurityVersion() + ":" + changes);
        events.lockIdempotency(actor, GRANT_ACTION, request.idempotencyKey());
        String existingHash = events.existingRequestHash(actor, GRANT_ACTION, request.idempotencyKey());
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "幂等键已用于不同的权限变更请求"
                );
            }
            return new DocumentGrantUpdateResult(user(userId), List.of(), true);
        }

        UserEntity target = requireUser(userId);
        if (!target.isEnabled() || target.getRole() != UserRole.USER) {
            throw new ApiException(HttpStatus.CONFLICT, "ACL_USER_INVALID", "只有启用的普通用户可以编辑明确授权");
        }
        if (target.getSecurityVersion() != request.expectedUserSecurityVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_SECURITY_VERSION_CONFLICT", "用户安全状态已变化，请刷新后重试");
        }
        AccessSummary before = summaries(List.of(target)).get(userId);
        List<UUID> ids = changes.stream().map(DocumentGrantChange::documentId).toList();
        Map<UUID, DocumentEntity> locked = new LinkedHashMap<>();
        documents.findAllByIdForUpdate(ids).forEach(document -> locked.put(document.getId(), document));
        if (locked.size() != ids.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "部分文档不存在或已删除");
        }

        List<UUID> changed = new ArrayList<>();
        for (DocumentGrantChange change : changes) {
            DocumentEntity document = locked.get(change.documentId());
            if (document.getVisibility() != DocumentVisibility.RESTRICTED
                    || document.getOwner().getId().equals(userId)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ACL_DERIVED_SCOPE_READ_ONLY",
                        "公共或所有者权限只能查看，不能编辑"
                );
            }
            if (document.getAclVersion() != change.expectedAclVersion()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ACL_VERSION_CONFLICT",
                        "文档权限已被其他请求更新，请刷新后重试"
                );
            }
            boolean current = aclEntries.existsByDocumentIdAndUserId(document.getId(), userId);
            if (current == change.granted()) {
                continue;
            }
            if (change.granted()) {
                aclEntries.save(new DocumentAclEntryEntity(document, target));
            } else {
                aclEntries.deleteByDocumentIdAndUserId(document.getId(), userId);
            }
            document.markAclChanged();
            changed.add(document.getId());
        }
        aclEntries.flush();
        documents.flush();
        changed.forEach(graphRebuilds::aclChanged);
        AccessSummary after = summaries(List.of(target)).get(userId);
        events.append(
                "ACCESS", GRANT_ACTION, actor, "USER", userId.toString(), target.getUsername(),
                summary(before), summary(after), reason, request.idempotencyKey(), requestHash
        );
        return new DocumentGrantUpdateResult(user(userId), List.copyOf(changed), false);
    }

    private UserEntity requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
    }

    private static List<DocumentGrantChange> normalize(List<DocumentGrantChange> input) {
        Map<UUID, DocumentGrantChange> unique = new LinkedHashMap<>();
        input.stream()
                .sorted(Comparator.comparing(DocumentGrantChange::documentId))
                .forEach(change -> {
                    if (unique.put(change.documentId(), change) != null) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "ACL_CHANGE_DUPLICATE", "同一文档不能重复提交");
                    }
                });
        return List.copyOf(unique.values());
    }

    private static Map<String, Long> summary(AccessSummary value) {
        return Map.of(
                "publicDocuments", value.publicDocuments(),
                "ownedDocuments", value.ownedDocuments(),
                "explicitGrants", value.explicitGrants(),
                "totalDocuments", value.totalDocuments()
        );
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private Map<UUID, Long> countsByUser(String sql) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(sql, (resultSet, rowNumber) -> Map.entry(
                resultSet.getObject(1, UUID.class),
                resultSet.getLong(2)
        )).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
