package com.example.rag.graph;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "rag.graph.worker-enabled=false",
        "rag.graph.global-worker-enabled=false"
})
class GraphRebuildLifecycleIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GraphRebuildRequestService rebuilds;

    @Test
    @Transactional
    void aclChangeSupersedesActiveRequestAndCreatesCurrentRequest() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID oldRequestId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO users (id, username, password_hash, role)
                VALUES (?, ?, ?, 'ADMIN')
                """,
                userId,
                "graph-rebuild-" + userId,
                "test-password-hash"
        );
        jdbc.update(
                """
                INSERT INTO documents (
                    id, owner_user_id, title, visibility, acl_version
                ) VALUES (?, ?, 'Lifecycle fixture', 'RESTRICTED', 1)
                """,
                documentId,
                userId
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash,
                    source_object_key, status, original_filename,
                    file_size_bytes, media_type
                ) VALUES (
                    ?, ?, 1, ?, ?, 'READY', 'fixture.pdf',
                    1, 'application/pdf'
                )
                """,
                revisionId,
                documentId,
                "a".repeat(64),
                "graph-rebuild/" + revisionId + ".pdf"
        );
        jdbc.update(
                "UPDATE documents SET current_revision_id = ? WHERE id = ?",
                revisionId,
                documentId
        );
        jdbc.update(
                """
                INSERT INTO graph_rebuild_requests (
                    id, document_id, target_revision_id,
                    target_acl_version, reason, state,
                    global_rebuild_required
                ) VALUES (?, ?, ?, 1, 'ACL_CHANGED', 'REQUESTED', FALSE)
                """,
                oldRequestId,
                documentId,
                revisionId
        );

        jdbc.update(
                "UPDATE documents SET acl_version = 2 WHERE id = ?",
                documentId
        );
        rebuilds.aclChanged(documentId);

        assertThat(jdbc.queryForObject(
                """
                SELECT state
                FROM graph_rebuild_requests
                WHERE id = ?
                """,
                String.class,
                oldRequestId
        )).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                """
                SELECT completed_at IS NOT NULL
                FROM graph_rebuild_requests
                WHERE id = ?
                """,
                Boolean.class,
                oldRequestId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM graph_rebuild_requests
                WHERE document_id = ?
                  AND target_revision_id = ?
                  AND target_acl_version = 2
                  AND state = 'REQUESTED'
                  AND NOT global_rebuild_required
                  AND candidate_graph_generation IS NULL
                  AND candidate_global_generation IS NULL
                """,
                Integer.class,
                documentId,
                revisionId
        )).isEqualTo(1);
    }
}
