package com.example.rag.persistence;

import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class Phase4PersistenceIntegrationTests {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentRevisionRepository revisions;

    @Autowired
    private ChunkingProfileRepository profiles;

    @Autowired
    private ParsedDocumentRepository parsedDocuments;

    @Autowired
    private SourceUnitRepository sourceUnits;

    @Autowired
    private ContentBlockRepository blocks;

    @Autowired
    private ChunkRepository chunks;

    @Autowired
    private SourceSpanRepository spans;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void repositoriesPersistTraceableParentAndChildArtifacts() {
        var revision = createRevision("artifact-owner", "Artifacts");
        var profile = profiles.findById("phase4-v1").orElseThrow();
        UUID sourceUnitId = UUID.randomUUID();
        sourceUnits.saveAndFlush(new SourceUnitEntity(
                sourceUnitId,
                revision,
                1,
                SourceUnitKind.PAGE,
                "page:1",
                "可追溯正文",
                HASH_A,
                "legacy-page-offset-v1",
                "{\"sourceLabel\":\"第 1 页\"}"
        ));
        var block = blocks.saveAndFlush(new ContentBlockEntity(
                UUID.randomUUID(),
                revision,
                0,
                ContentBlockType.PARAGRAPH,
                "可追溯正文",
                "测试章节",
                SourceLocatorKind.PAGE,
                sourceUnitId,
                sourceUnitId,
                "{\"kind\":\"PAGE\",\"startPage\":1,\"endPage\":1}",
                0,
                5,
                5,
                "unicode-codepoint-v1",
                HASH_A,
                "legacy-page-offset-v1",
                "pdfbox-v1",
                null
        ));
        parsedDocuments.saveAndFlush(new ParsedDocumentEntity(
                revision,
                "# 测试章节\n\n可追溯正文",
                "pdfbox-v1",
                null,
                null,
                null,
                "legacy-v1",
                "{}",
                1,
                11,
                8
        ));

        var parent = chunks.saveAndFlush(new ChunkEntity(
                UUID.randomUUID(), revision, null, ChunkType.PARENT, 0,
                block.getText(), block.getHeadingPath(), 0, 0, 5,
                "unicode-codepoint-v1", profile, "pdfbox-v1", "chunker-v1", HASH_B
        ));
        var child = chunks.saveAndFlush(new ChunkEntity(
                UUID.randomUUID(), revision, parent.getId(), ChunkType.CHILD, 0,
                block.getText(), block.getHeadingPath(), 0, 0, 5,
                "unicode-codepoint-v1", profile, "pdfbox-v1", "chunker-v1", HASH_C
        ));
        spans.saveAndFlush(new SourceSpanEntity(
                UUID.randomUUID(), parent, 0, SourceLocatorKind.PAGE,
                sourceUnitId, sourceUnitId,
                "{\"kind\":\"PAGE\",\"startPage\":1,\"endPage\":1}",
                0, 5, 0, 5, HASH_A, "legacy-page-offset-v1", null
        ));
        spans.saveAndFlush(new SourceSpanEntity(
                UUID.randomUUID(), child, 0, SourceLocatorKind.PAGE,
                sourceUnitId, sourceUnitId,
                "{\"kind\":\"PAGE\",\"startPage\":1,\"endPage\":1}",
                0, 5, 0, 5, HASH_A, "legacy-page-offset-v1", null
        ));

        entityManager.clear();

        assertThat(parsedDocuments.findByRevisionId(revision.getId()))
                .get()
                .extracting(ParsedDocumentEntity::getMarkdown)
                .isEqualTo("# 测试章节\n\n可追溯正文");
        assertThat(blocks.findAllByRevisionIdOrderByBlockOrder(revision.getId()))
                .extracting(ContentBlockEntity::getSourceTextHash)
                .containsExactly(HASH_A);
        assertThat(chunks.findAllByRevisionIdAndChunkTypeOrderByChunkOrder(revision.getId(), ChunkType.CHILD))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getParentChunkId()).isEqualTo(parent.getId());
                    assertThat(saved.isSearchable()).isTrue();
                });
        assertThat(spans.findAllByChunkIdInOrderByChunkIdAscSpanOrderAsc(java.util.List.of(child.getId())))
                .singleElement()
                .satisfies(span -> {
                    assertThat(span.getRevisionId()).isEqualTo(revision.getId());
                    assertThat(span.getStartPage()).isEqualTo(1);
                });
    }

    @Test
    void databaseRejectsAChildAsAnotherChildsParent() {
        var revision = createRevision("parent-type-owner", "Parent type");
        var profile = profiles.findById("phase4-v1").orElseThrow();
        var parent = chunks.saveAndFlush(chunk(revision, profile, null, ChunkType.PARENT, 0));
        var child = chunks.saveAndFlush(chunk(revision, profile, parent.getId(), ChunkType.CHILD, 0));

        assertThatThrownBy(() -> chunks.saveAndFlush(
                chunk(revision, profile, child.getId(), ChunkType.CHILD, 1)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsRunningJobsWithoutALease() {
        var revision = createRevision("lease-owner", "Lease");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, stage, status, attempt, max_attempts, pipeline_version,
                    document_format, started_at
                ) VALUES (?, ?, 'PARSE', 'RUNNING', 1, 3, 'lease-test', ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(), revision.getId(), revision.getDocumentFormat().name()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validRunningJobStoresLeaseAndHeartbeat() {
        var revision = createRevision("valid-lease-owner", "Valid lease");
        var jobId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, stage, status, attempt, max_attempts, pipeline_version,
                    document_format, lease_owner, lease_expires_at, heartbeat_at, started_at
                ) VALUES (?, ?, 'PARSE', 'RUNNING', 1, 3, 'lease-test', ?, ?, ?, ?, ?)
                """,
                jobId,
                revision.getId(),
                revision.getDocumentFormat().name(),
                "parser-worker-1",
                now.plusSeconds(30),
                now,
                now
        );

        assertThat(jdbc.queryForObject(
                "SELECT lease_owner FROM pipeline_jobs WHERE id = ?",
                String.class,
                jobId
        )).isEqualTo("parser-worker-1");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migrationKeepsOneClaimableParseJobForEachUploadedRevision() {
        String schema = "phase4_migration_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate isolated = new JdbcTemplate(dataSource);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(schema)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("4"))
                    .load()
                    .migrate();

            UUID ownerId = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID runningJobId = UUID.randomUUID();
            isolated.update(
                    "INSERT INTO " + schema
                            + ".users (id, username, password_hash, role) VALUES (?, ?, ?, 'USER')",
                    ownerId, "phase4-migration-owner", "test-hash"
            );
            isolated.update(
                    "INSERT INTO " + schema
                            + ".documents (id, owner_user_id, title, visibility) VALUES (?, ?, ?, 'RESTRICTED')",
                    documentId, ownerId, "Migration"
            );
            isolated.update(
                    """
                    INSERT INTO %s.document_revisions (
                        id, document_id, revision_number, content_hash, source_object_key, status,
                        original_filename, file_size_bytes, media_type
                    ) VALUES (?, ?, 1, ?, ?, 'UPLOADED', 'migration.pdf', 128, 'application/pdf')
                    """.formatted(schema),
                    revisionId, documentId, HASH_A, "migration/" + revisionId + ".pdf"
            );
            isolated.update(
                    """
                    INSERT INTO %s.pipeline_jobs (
                        id, revision_id, stage, status, attempt, pipeline_version
                    ) VALUES (?, ?, 'PARSE', 'RUNNING', 5, 'legacy-v1')
                    """.formatted(schema),
                    runningJobId,
                    revisionId
            );

            UUID deletedDocumentId = UUID.randomUUID();
            UUID deletedRevisionId = UUID.randomUUID();
            isolated.update(
                    "INSERT INTO " + schema
                            + ".documents (id, owner_user_id, title, visibility, deleted_at) "
                            + "VALUES (?, ?, ?, 'RESTRICTED', CURRENT_TIMESTAMP)",
                    deletedDocumentId,
                    ownerId,
                    "Deleted migration"
            );
            isolated.update(
                    """
                    INSERT INTO %s.document_revisions (
                        id, document_id, revision_number, content_hash, source_object_key, status,
                        original_filename, file_size_bytes, media_type
                    ) VALUES (?, ?, 1, ?, ?, 'UPLOADED', 'deleted.pdf', 128, 'application/pdf')
                    """.formatted(schema),
                    deletedRevisionId,
                    deletedDocumentId,
                    HASH_B,
                    "migration/" + deletedRevisionId + ".pdf"
            );

            Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(schema)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertThat(isolated.queryForObject(
                    """
                    SELECT count(*) FROM %s.pipeline_jobs
                    WHERE revision_id = ? AND stage = 'PARSE'
                      AND status = 'PENDING' AND pipeline_version = 'phase4-v1'
                    """.formatted(schema),
                    Integer.class,
                    revisionId
            )).isEqualTo(1);
            assertThat(isolated.queryForMap(
                    "SELECT status, attempt, max_attempts, error_code FROM "
                            + schema + ".pipeline_jobs WHERE id = ?",
                    runningJobId
            )).containsEntry("status", "FAILED")
                    .containsEntry("attempt", 5)
                    .containsEntry("max_attempts", 6)
                    .containsEntry("error_code", "MIGRATION_DUPLICATE_PARSE");
            assertThat(isolated.queryForObject(
                    """
                    SELECT count(*) FROM %s.pipeline_jobs
                    WHERE revision_id = ? AND stage = 'PARSE'
                      AND status IN ('PENDING', 'RUNNING')
                    """.formatted(schema),
                    Integer.class,
                    revisionId
            )).isEqualTo(1);
            assertThat(isolated.queryForObject(
                    "SELECT count(*) FROM " + schema + ".pipeline_jobs WHERE revision_id = ?",
                    Integer.class,
                    deletedRevisionId
            )).isZero();
        } finally {
            isolated.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private DocumentRevisionEntity createRevision(String username, String title) {
        var user = users.saveAndFlush(new UserEntity(username, "test-hash", UserRole.USER));
        var document = documents.saveAndFlush(
                new DocumentEntity(user, title, DocumentVisibility.RESTRICTED)
        );
        return revisions.saveAndFlush(new DocumentRevisionEntity(
                document,
                1,
                HASH_A,
                "test/" + UUID.randomUUID() + ".pdf",
                RevisionStatus.UPLOADED,
                "test.pdf",
                128,
                "application/pdf",
                null
        ));
    }

    private ChunkEntity chunk(
            DocumentRevisionEntity revision,
            ChunkingProfileEntity profile,
            UUID parentId,
            ChunkType type,
            int order
    ) {
        return new ChunkEntity(
                UUID.randomUUID(), revision, parentId, type, order,
                "chunk-" + order, "heading", 0, 0, 2,
                "unicode-codepoint-v1", profile, "pdfbox-v1", "chunker-v1", HASH_B
        );
    }
}
