package com.example.rag.search;

import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class SearchAccessService {

    private final NamedParameterJdbcTemplate jdbc;

    SearchAccessService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<AuthorizedRevision> authorized(
            PlatformUserPrincipal user,
            UUID documentId,
            DocumentVisibility visibility
    ) {
        var parameters = new MapSqlParameterSource()
                .addValue("userId", user.id())
                .addValue("sessionRole", user.role().name())
                .addValue("sessionPasswordHash", user.getPassword())
                .addValue("documentId", documentId)
                .addValue("visibility", visibility == null ? null : visibility.name());
        return jdbc.query(
                """
                SELECT document.id AS document_id,
                       document.title,
                       document.current_revision_id AS revision_id,
                       revision.revision_number,
                       document.acl_version
                FROM documents document
                JOIN users request_user
                  ON request_user.id = :userId
                 AND request_user.enabled = TRUE
                 AND request_user.role = :sessionRole
                 AND request_user.password_hash = :sessionPasswordHash
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                 AND revision.document_id = document.id
                WHERE document.deleted_at IS NULL
                  AND revision.status = 'READY'
                  AND (
                    CAST(:documentId AS UUID) IS NULL
                    OR document.id = CAST(:documentId AS UUID)
                  )
                  AND (
                    CAST(:visibility AS VARCHAR) IS NULL
                    OR document.visibility = CAST(:visibility AS VARCHAR)
                  )
                  AND (
                    request_user.role = 'ADMIN'
                    OR document.visibility = 'ALL_USERS'
                    OR document.owner_user_id = :userId
                    OR EXISTS (
                        SELECT 1
                        FROM document_acl_entries acl
                        WHERE acl.document_id = document.id
                          AND acl.user_id = :userId
                    )
                  )
                ORDER BY document.id
                """,
                parameters,
                (resultSet, rowNumber) -> new AuthorizedRevision(
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getInt("revision_number"),
                        resultSet.getLong("acl_version")
                )
        );
    }

    @Transactional(readOnly = true)
    Map<UUID, AuthorizedRevision> authorizedByDocument(
            PlatformUserPrincipal user,
            UUID documentId,
            DocumentVisibility visibility
    ) {
        Map<UUID, AuthorizedRevision> result = new LinkedHashMap<>();
        authorized(user, documentId, visibility).forEach(item -> result.put(item.documentId(), item));
        return result;
    }

    static String projectionKey(UUID documentId, UUID revisionId, long aclVersion) {
        return documentId + ":" + revisionId + ":" + aclVersion;
    }

    record AuthorizedRevision(
            UUID documentId,
            String title,
            UUID revisionId,
            int revisionNumber,
            long aclVersion
    ) {
        String projectionKey() {
            return SearchAccessService.projectionKey(documentId, revisionId, aclVersion);
        }
    }
}
