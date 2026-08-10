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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceIntegrationTests {

    private static final String CONTENT_HASH = "a".repeat(64);

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentRevisionRepository revisions;

    @Autowired
    private PipelineJobRepository jobs;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void repositoriesPersistAndReloadTheAggregate() {
        var user = users.saveAndFlush(new UserEntity("repository-user", "test-hash", UserRole.USER));
        var document = documents.saveAndFlush(
                new DocumentEntity(user, "Repository test", DocumentVisibility.RESTRICTED)
        );
        var revision = revisions.saveAndFlush(new DocumentRevisionEntity(
                document,
                1,
                CONTENT_HASH,
                "test/revision-1.pdf",
                RevisionStatus.UPLOADED,
                "revision-1.pdf",
                128,
                "application/pdf",
                "repository-test-key"
        ));
        var job = jobs.saveAndFlush(
                new PipelineJobEntity(revision, PipelineStage.PARSE, PipelineJobStatus.PENDING, "v1")
        );

        entityManager.clear();

        assertThat(users.findByUsername("repository-user")).isPresent();
        assertThat(documents.findById(document.getId())).isPresent();
        assertThat(revisions.findById(revision.getId()))
                .get()
                .extracting(DocumentRevisionEntity::getRevisionNumber)
                .isEqualTo(1);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(PipelineJobEntity::getStatus)
                .isEqualTo(PipelineJobStatus.PENDING);
    }

    @Test
    void primaryKeyRejectsDuplicateIds() {
        var id = UUID.randomUUID();
        insertUser(id, "primary-key-a", "USER");

        assertThatThrownBy(() -> insertUser(id, "primary-key-b", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueKeyRejectsDuplicateUsernames() {
        insertUser(UUID.randomUUID(), "duplicate-user", "USER");

        assertThatThrownBy(() -> insertUser(UUID.randomUUID(), "duplicate-user", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeyRejectsUnknownDocumentOwner() {
        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO documents (id, owner_user_id, title, visibility)
                VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), "Orphan", "RESTRICTED"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revisionStatusConstraintRejectsUnsupportedValues() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        insertUser(userId, "revision-status-user", "USER");
        insertDocument(documentId, userId);

        assertThatThrownBy(() -> insertRevision(UUID.randomUUID(), documentId, "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jobStatusConstraintRejectsUnsupportedValues() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        insertUser(userId, "job-status-user", "USER");
        insertDocument(documentId, userId);
        insertRevision(revisionId, documentId, "UPLOADED");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, stage, status, pipeline_version, document_format
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), revisionId, "PARSE", "UNKNOWN", "v1", "PDF"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allFlywayMigrationsAreRecordedOnce() throws Exception {
        Integer successfulMigrations = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success",
                Integer.class
        );
        Integer distinctVersions = jdbc.queryForObject(
                "SELECT count(DISTINCT version) FROM flyway_schema_history WHERE success",
                Integer.class
        );
        long migrationResources = Arrays.stream(
                        new PathMatchingResourcePatternResolver()
                                .getResources("classpath*:db/migration/V*__*.sql")
                )
                .map(resource -> resource.getFilename())
                .filter(Objects::nonNull)
                .distinct()
                .count();

        assertThat(successfulMigrations).isEqualTo(distinctVersions);
        assertThat(successfulMigrations.longValue()).isEqualTo(migrationResources);
    }

    @Test
    void v33ClosesGraphAndGlobalRebuildLifecycle() {
        Set<String> columns = Set.copyOf(jdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'graph_rebuild_requests'
                """,
                String.class
        ));
        String stateConstraint = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'graph_rebuild_requests'::regclass
                  AND conname = 'ck_graph_rebuild_requests_state'
                """,
                String.class
        );
        String lifecycleConstraint = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'graph_rebuild_requests'::regclass
                  AND conname = 'ck_graph_rebuild_requests_lifecycle'
                """,
                String.class
        );

        assertThat(columns).contains(
                "global_rebuild_required",
                "candidate_global_generation",
                "graph_ready_at",
                "global_ready_at"
        );
        assertThat(stateConstraint)
                .contains("GRAPH_BUILDING")
                .contains("GRAPH_READY")
                .contains("GLOBAL_BUILDING");
        assertThat(lifecycleConstraint)
                .contains("candidate_global_generation")
                .contains("global_ready_at");
    }

    @Test
    void v32InstallsPhase13IntegrityGuards() {
        Set<String> revisionConstraints = Set.copyOf(jdbc.queryForList(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'document_revisions'::regclass
                """,
                String.class
        ));
        String imageBlockForeignKey = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'document_image_assets'::regclass
                  AND conname = 'fk_document_image_assets_block_revision'
                """,
                String.class
        );
        String tablePreviewForeignKey = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'document_tables'::regclass
                  AND conname = 'fk_document_tables_preview_revision'
                """,
                String.class
        );
        String activeParseIndex = jdbc.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'pipeline_jobs'
                  AND indexname = 'uq_pipeline_jobs_active_parse_revision'
                """,
                String.class
        );
        String offsetEncodingDefault = jdbc.queryForObject(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'parsed_documents'
                  AND column_name = 'offset_encoding'
                """,
                String.class
        );
        String offsetEncodingConstraint = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'parsed_documents'::regclass
                  AND conname = 'ck_parsed_documents_offset_encoding'
                """,
                String.class
        );
        Integer utf16Length = jdbc.queryForObject(
                "SELECT rag_utf16_code_unit_length(?)",
                Integer.class,
                "A😀B"
        );

        assertThat(revisionConstraints).contains("fk_document_revisions_reparse_requested_by");
        assertThat(imageBlockForeignKey)
                .contains("FOREIGN KEY (content_block_id, document_id, revision_id)");
        assertThat(tablePreviewForeignKey)
                .contains("FOREIGN KEY (preview_asset_id, document_id, revision_id)");
        assertThat(activeParseIndex)
                .contains("UNIQUE")
                .contains("stage")
                .contains("PENDING")
                .contains("RUNNING");
        assertThat(offsetEncodingDefault).contains("UTF16_CODE_UNIT");
        assertThat(offsetEncodingConstraint).contains("offset_encoding").contains("UTF16_CODE_UNIT");
        assertThat(utf16Length).isEqualTo(4);
    }

    @Test
    void v32UsesUtf16SourceSpanBounds() {
        var documentId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var parentChunkId = UUID.randomUUID();
        var childChunkId = UUID.randomUUID();
        insertChunkFixture(
                "utf16-source-span-user",
                documentId,
                revisionId,
                parentChunkId,
                childChunkId,
                "A😀B",
                3
        );
        insertSourceSpan(UUID.randomUUID(), childChunkId, documentId, revisionId, 0, 0, 4);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM source_spans WHERE chunk_id = ?",
                Integer.class,
                childChunkId
        )).isEqualTo(1);
        assertThatThrownBy(() -> insertSourceSpan(
                UUID.randomUUID(),
                childChunkId,
                documentId,
                revisionId,
                1,
                4,
                5
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v32RejectsChunkUpdatesThatInvalidateSourceSpans() {
        var documentId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var parentChunkId = UUID.randomUUID();
        var childChunkId = UUID.randomUUID();
        insertChunkFixture(
                "utf16-chunk-update-user",
                documentId,
                revisionId,
                parentChunkId,
                childChunkId,
                "A😀B",
                3
        );
        insertSourceSpan(UUID.randomUUID(), childChunkId, documentId, revisionId, 0, 0, 4);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE chunks SET text = ?, character_count = ? WHERE id = ?",
                "AB",
                2,
                childChunkId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v4LifecycleConstraintsAndStagingIndexAreInstalled() {
        Set<String> constraints = Set.copyOf(jdbc.queryForList(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'document_revisions'::regclass
                """,
                String.class
        ));
        Integer stagingIndex = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'document_revisions'
                  AND indexname = 'ix_document_revisions_staging_expiry'
                """,
                Integer.class
        );

        assertThat(constraints).contains(
                "ck_document_revisions_request_fingerprint",
                "ck_document_revisions_idempotency_fingerprint",
                "ck_document_revisions_staging_expiry"
        );
        assertThat(stagingIndex).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void v4MovesEveryLegacyProcessingStateBackToStaged() {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate isolated = new JdbcTemplate(dataSource);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(schema)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("3"))
                    .load()
                    .migrate();

            UUID ownerId = UUID.randomUUID();
            isolated.update(
                    "INSERT INTO " + schema + ".users (id, username, password_hash, role) VALUES (?, ?, ?, ?)",
                    ownerId, "migration-owner", "test-hash", "USER"
            );
            for (String legacyStatus : List.of("PROCESSING", "READY", "FAILED", "QUARANTINED")) {
                UUID documentId = UUID.randomUUID();
                UUID revisionId = UUID.randomUUID();
                isolated.update(
                        "INSERT INTO " + schema
                                + ".documents (id, owner_user_id, title, visibility) VALUES (?, ?, ?, ?)",
                        documentId, ownerId, "Legacy " + legacyStatus, "RESTRICTED"
                );
                isolated.update(
                        """
                        INSERT INTO %s.document_revisions (
                            id, document_id, revision_number, content_hash, source_object_key, status,
                            original_filename, file_size_bytes, media_type
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.formatted(schema),
                        revisionId,
                        documentId,
                        1,
                        CONTENT_HASH,
                        "legacy/" + revisionId + ".pdf",
                        legacyStatus,
                        "legacy.pdf",
                        128,
                        "application/pdf"
                );
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(schema)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var migrated = isolated.queryForList(
                    "SELECT status, staging_expires_at FROM " + schema + ".document_revisions"
            );
            assertThat(migrated).hasSize(4).allSatisfy(row -> {
                assertThat(row.get("status")).isEqualTo("STAGED");
                assertThat(row.get("staging_expires_at")).isNotNull();
            });
        } finally {
            isolated.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void aclVersionMustRemainPositive() {
        var userId = UUID.randomUUID();
        insertUser(userId, "acl-version-user", "USER");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO documents (id, owner_user_id, title, visibility, acl_version)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), userId, "Invalid ACL version", "RESTRICTED", 0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void currentRevisionMustBelongToTheSameDocument() {
        var userId = UUID.randomUUID();
        var firstDocumentId = UUID.randomUUID();
        var secondDocumentId = UUID.randomUUID();
        var secondRevisionId = UUID.randomUUID();
        insertUser(userId, "current-revision-user", "USER");
        insertDocument(firstDocumentId, userId);
        insertDocument(secondDocumentId, userId);
        insertRevision(secondRevisionId, secondDocumentId, "UPLOADED");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE documents SET current_revision_id = ? WHERE id = ?",
                secondRevisionId, firstDocumentId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyRequiresARequestFingerprint() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        insertUser(userId, "fingerprint-required-user", "USER");
        insertDocument(documentId, userId);

        assertThatThrownBy(() -> insertRevision(
                UUID.randomUUID(),
                documentId,
                "UPLOADED",
                "fingerprint-required-key",
                null,
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requestFingerprintMustBeSha256Length() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        insertUser(userId, "fingerprint-length-user", "USER");
        insertDocument(documentId, userId);

        assertThatThrownBy(() -> insertRevision(
                UUID.randomUUID(),
                documentId,
                "UPLOADED",
                "fingerprint-length-key",
                "too-short",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stagedRevisionRequiresAnExpiry() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        insertUser(userId, "staging-expiry-user", "USER");
        insertDocument(documentId, userId);

        assertThatThrownBy(() -> insertRevision(
                UUID.randomUUID(), documentId, "STAGED", null, null, null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stagedRevisionWithAnExpiryIsAccepted() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        insertUser(userId, "valid-staging-user", "USER");
        insertDocument(documentId, userId);

        insertRevision(
                revisionId,
                documentId,
                "STAGED",
                "valid-staging-key",
                CONTENT_HASH,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)
        );

        assertThat(revisions.findById(revisionId)).isPresent();
    }

    @Test
    void nonStagedRevisionRejectsAnExpiry() {
        var userId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        insertUser(userId, "non-staged-expiry-user", "USER");
        insertDocument(documentId, userId);

        assertThatThrownBy(() -> insertRevision(
                UUID.randomUUID(), documentId, "UPLOADED", null, null, OffsetDateTime.now(ZoneOffset.UTC)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aclUniqueKeyRejectsDuplicateDocumentUserPairs() {
        var user = users.saveAndFlush(new UserEntity("acl-unique-user", "test-hash", UserRole.USER));
        var document = documents.saveAndFlush(
                new DocumentEntity(user, "ACL unique", DocumentVisibility.RESTRICTED)
        );
        jdbc.update(
                "INSERT INTO document_acl_entries (id, document_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), document.getId(), user.getId()
        );

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO document_acl_entries (id, document_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), document.getId(), user.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aclForeignKeyRejectsUnknownDocuments() {
        var user = users.saveAndFlush(new UserEntity("acl-fk-user", "test-hash", UserRole.USER));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO document_acl_entries (id, document_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), user.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertUser(UUID id, String username, String role) {
        jdbc.update(
                """
                INSERT INTO users (id, username, password_hash, role)
                VALUES (?, ?, ?, ?)
                """,
                id, username, "test-hash", role
        );
    }

    private void insertDocument(UUID id, UUID ownerId) {
        jdbc.update(
                """
                INSERT INTO documents (id, owner_user_id, title, visibility)
                VALUES (?, ?, ?, ?)
                """,
                id, ownerId, "Constraint test", "RESTRICTED"
        );
    }

    private void insertRevision(UUID id, UUID documentId, String status) {
        insertRevision(id, documentId, status, null, null, null);
    }

    private void insertRevision(
            UUID id,
            UUID documentId,
            String status,
            String idempotencyKey,
            String requestFingerprint,
            OffsetDateTime stagingExpiresAt
    ) {
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash, source_object_key, status,
                    original_filename, file_size_bytes, media_type, idempotency_key,
                    request_fingerprint, staging_expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, documentId, 1, CONTENT_HASH, "test/" + id + ".pdf", status,
                "constraint.pdf", 128, "application/pdf", idempotencyKey,
                requestFingerprint, stagingExpiresAt
        );
    }

    private void insertChunkFixture(
            String username,
            UUID documentId,
            UUID revisionId,
            UUID parentChunkId,
            UUID childChunkId,
            String childText,
            int childCharacterCount
    ) {
        var ownerId = UUID.randomUUID();
        insertUser(ownerId, username, "USER");
        insertDocument(documentId, ownerId);
        insertRevision(revisionId, documentId, "UPLOADED");
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (
                    ?, ?, ?, 1, 'PAGE', 'page:1', ?, ?,
                    'utf16-v1',
                    '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb
                )
                """,
                UUID.randomUUID(),
                documentId,
                revisionId,
                childText,
                CONTENT_HASH
        );
        jdbc.update(
                """
                INSERT INTO chunks (
                    id, document_id, revision_id, parent_chunk_id, chunk_type, chunk_order,
                    text, heading_path, start_block_order, end_block_order, character_count,
                    token_count, token_counter_version, chunking_profile_version,
                    parser_version, chunker_version, content_hash, searchable
                )
                VALUES (?, ?, ?, NULL, 'PARENT', 0, ?, '', 0, 0, ?, 1, 'unicode-v1',
                        'phase4-v1', 'test-parser', 'test-chunker', ?, FALSE)
                """,
                parentChunkId,
                documentId,
                revisionId,
                "parent",
                6,
                CONTENT_HASH
        );
        jdbc.update(
                """
                INSERT INTO chunks (
                    id, document_id, revision_id, parent_chunk_id, chunk_type, chunk_order,
                    text, heading_path, start_block_order, end_block_order, character_count,
                    token_count, token_counter_version, chunking_profile_version,
                    parser_version, chunker_version, content_hash, searchable
                )
                VALUES (?, ?, ?, ?, 'CHILD', 0, ?, '', 0, 0, ?, 1, 'unicode-v1',
                        'phase4-v1', 'test-parser', 'test-chunker', ?, TRUE)
                """,
                childChunkId,
                documentId,
                revisionId,
                parentChunkId,
                childText,
                childCharacterCount,
                CONTENT_HASH
        );
    }

    private void insertSourceSpan(
            UUID spanId,
            UUID chunkId,
            UUID documentId,
            UUID revisionId,
            int spanOrder,
            int chunkStartOffset,
            int chunkEndOffset
    ) {
        UUID sourceUnitId = jdbc.queryForObject(
                """
                SELECT id
                FROM source_units
                WHERE revision_id = ? AND unit_order = 1
                """,
                UUID.class,
                revisionId
        );
        jdbc.update(
                """
                INSERT INTO source_spans (
                    id, chunk_id, document_id, revision_id, span_order,
                    locator_kind, start_source_unit_id, end_source_unit_id,
                    start_offset, end_offset, source_text_hash,
                    chunk_start_offset, chunk_end_offset, locator_address,
                    normalization_version
                )
                VALUES (
                    ?, ?, ?, ?, ?, 'PAGE', ?, ?, ?, ?, ?, ?, ?,
                    '{"kind":"PAGE","startPage":1,"endPage":1}'::jsonb,
                    'utf16-v1'
                )
                """,
                spanId,
                chunkId,
                documentId,
                revisionId,
                spanOrder,
                sourceUnitId,
                sourceUnitId,
                chunkStartOffset,
                chunkEndOffset,
                CONTENT_HASH,
                chunkStartOffset,
                chunkEndOffset
        );
    }
}
