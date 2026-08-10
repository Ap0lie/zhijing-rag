package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphApiContracts.RefreshResolutionCandidatesRequest;
import com.example.rag.graph.GraphApiContracts.UpdateResolutionCandidateRequest;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.graph.enabled=true",
        "rag.graph.worker-enabled=false",
        "rag.graph.extraction.enabled=false"
})
@AutoConfigureMockMvc
class GraphPhase21BIntegrationTests {

    private static final String HASH = "e".repeat(64);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private GraphGenerationRepository generations;
    @Autowired private GraphResolutionCandidateService candidates;
    @Autowired private MockMvc mockMvc;

    @Test
    @Transactional
    void deterministicRefreshProducesExplainableDuplicateAndSplitCandidates() {
        Fixture fixture = fixture();
        var request = new RefreshResolutionCandidatesRequest(
                fixture.generation(), "REFRESH_RESOLUTION_CANDIDATES",
                "Refresh explainable graph resolution candidates",
                "phase21b-refresh-" + UUID.randomUUID()
        );
        var first = candidates.refresh(request, fixture.admin());
        var repeated = candidates.refresh(
                new RefreshResolutionCandidatesRequest(
                        fixture.generation(), "REFRESH_RESOLUTION_CANDIDATES",
                        "Refresh explainable graph resolution candidates",
                        "phase21b-repeat-" + UUID.randomUUID()
                ),
                fixture.admin()
        );

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.duplicateCandidateCount()).isGreaterThanOrEqualTo(1);
        assertThat(first.splitCandidateCount()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_resolution_candidate_snapshots WHERE graph_generation = ?",
                Integer.class,
                fixture.generation()
        )).isOne();

        var duplicatePage = candidates.candidates(
                fixture.generation(), "SUSPECTED_DUPLICATE",
                "ACTIVE", "ALIAS_OVERLAP", "", null, 20
        );
        assertThat(duplicatePage.items()).isNotEmpty();
        var duplicate = duplicatePage.items().getFirst();
        assertThat(duplicate.entities()).extracting(
                GraphApiContracts.ResolutionEntityView::canonicalName
        ).contains("Alpha", "Beta");
        assertThat(duplicate.signals()).extracting(
                GraphApiContracts.GraphResolutionCandidateSignalView::code
        ).contains("ALIAS_OVERLAP");
        var detail = candidates.detail(duplicate.id());
        assertThat(detail.evidence()).isNotEmpty();
        assertThat(detail.evidence()).allSatisfy(evidence -> {
            assertThat(evidence.childChunkId()).isNotNull();
            assertThat(evidence.sourceSpanId()).isNotNull();
        });

        var splitPage = candidates.candidates(
                fixture.generation(), "SUSPECTED_MERGE",
                "ACTIVE", "LOW_OVERLAP_SOURCE_CLUSTERS", "Alpha", null, 20
        );
        assertThat(splitPage.items()).isNotEmpty();
        assertThat(splitPage.items().getFirst().suggestedAction()).isEqualTo("SPLIT");
        assertThat(splitPage.items().getFirst().suggestedAliases()).isNotEmpty();
    }

    @Test
    @Transactional
    void ignoreRestoreUsesVersionCasAndNeverChangesGraphFacts() {
        Fixture fixture = fixture();
        candidates.refresh(
                new RefreshResolutionCandidatesRequest(
                        fixture.generation(), "REFRESH_RESOLUTION_CANDIDATES",
                        "Create candidates for state transition test",
                        "phase21b-state-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        var candidate = candidates.candidates(
                fixture.generation(), "SUSPECTED_DUPLICATE",
                "ACTIVE", "", "", null, 20
        ).items().getFirst();
        var ignored = candidates.changeState(
                candidate.id(), "IGNORE",
                new UpdateResolutionCandidateRequest(
                        candidate.version(), "IGNORE_RESOLUTION_CANDIDATE",
                        "Ignore after reviewing current candidate evidence",
                        "phase21b-ignore-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        assertThat(ignored.status()).isEqualTo("IGNORED");
        assertThat(ignored.version()).isEqualTo(candidate.version() + 1);
        assertThatThrownBy(() -> candidates.changeState(
                candidate.id(), "RESTORE",
                new UpdateResolutionCandidateRequest(
                        candidate.version(), "RESTORE_RESOLUTION_CANDIDATE",
                        "Stale version must not restore candidate state",
                        "phase21b-stale-state-" + UUID.randomUUID()
                ),
                fixture.admin()
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("已变化");
        var restored = candidates.changeState(
                candidate.id(), "RESTORE",
                new UpdateResolutionCandidateRequest(
                        ignored.version(), "RESTORE_RESOLUTION_CANDIDATE",
                        "Restore after reviewing the prior ignore decision",
                        "phase21b-restore-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        assertThat(restored.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap(
                "SELECT status, graph_config_version FROM graph_manifests WHERE graph_generation = ?",
                fixture.generation()
        )).containsEntry("status", "READY")
                .containsEntry("graph_config_version", fixture.config());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_configs WHERE version <> ? AND version LIKE 'phase21b%'",
                Integer.class,
                fixture.config()
        )).isZero();
    }

    @Test
    @Transactional
    void aclDriftMakesSnapshotStaleAndOrdinaryUserCannotBrowse() throws Exception {
        Fixture fixture = fixture();
        candidates.refresh(
                new RefreshResolutionCandidatesRequest(
                        fixture.generation(), "REFRESH_RESOLUTION_CANDIDATES",
                        "Create candidates before ACL drift test",
                        "phase21b-drift-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        var candidate = candidates.candidates(
                fixture.generation(), "SUSPECTED_DUPLICATE",
                "ACTIVE", "", "", null, 20
        ).items().getFirst();
        jdbc.update(
                "UPDATE documents SET acl_version = acl_version + 1 WHERE id = ?",
                fixture.firstDocument()
        );

        var stale = candidates.candidates(
                fixture.generation(), "", "STALE", "", "", null, 20
        );
        assertThat(stale.snapshot().status()).isEqualTo("STALE");
        assertThat(stale.items()).isNotEmpty();
        assertThat(stale.items().getFirst().entities()).isEmpty();
        assertThat(candidates.detail(candidate.id()).evidence()).isEmpty();
        assertThatThrownBy(() -> candidates.changeState(
                candidate.id(), "IGNORE",
                new UpdateResolutionCandidateRequest(
                        candidate.version(), "IGNORE_RESOLUTION_CANDIDATE",
                        "Stale candidate must not change governance state",
                        "phase21b-stale-ignore-" + UUID.randomUUID()
                ),
                fixture.admin()
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("已过期");

        UserEntity ordinary = users.saveAndFlush(new UserEntity(
                "phase21b-user-" + UUID.randomUUID(),
                "phase21b-user-hash", UserRole.USER
        ));
        mockMvc.perform(get("/api/v1/admin/graph/resolution-candidates")
                        .param("generation", String.valueOf(fixture.generation()))
                        .with(user(PlatformUserPrincipal.from(ordinary))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private Fixture fixture() {
        UserEntity admin = users.saveAndFlush(new UserEntity(
                "phase21b-admin-" + UUID.randomUUID(),
                "phase21b-admin-hash", UserRole.ADMIN
        ));
        DocumentBits first = document(admin, "First evidence", "Shared Alias");
        DocumentBits second = document(admin, "Second evidence", "Alpha Labs");
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        String config = "phase21b-fixture-" + UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO graph_configs (
                    version, extraction_model, extraction_revision,
                    prompt_version, schema_version, normalization_version,
                    resolution_rule_set_version, community_algorithm,
                    community_algorithm_version, community_seed,
                    community_resolution, reason
                ) VALUES (?, 'fixture-model', 'fixture-revision',
                          'phase8-extraction-prompt-v1',
                          'phase8-graph-schema-v1',
                          'phase8-normalization-v1',
                          'phase8-baseline-rules-v1',
                          'leiden', 'fixture-v1', 42, 1,
                          'Phase 21B integration fixture')
                """,
                config
        );
        String sourceSetHash = generations.currentSourceSetHash();
        Long generation = jdbc.queryForObject(
                """
                INSERT INTO graph_manifests (
                    id, graph_config_version, status, source_set_hash,
                    expected_document_count, projected_document_count,
                    entity_count, mention_count, build_reason
                ) VALUES (?, ?, 'READY', ?, 2, 2, 2, 3,
                          'Phase 21B integration fixture')
                RETURNING graph_generation
                """,
                Long.class,
                UUID.randomUUID(), config, sourceSetHash
        );
        for (DocumentBits bits : new DocumentBits[]{first, second}) {
            jdbc.update(
                    """
                    INSERT INTO graph_projection_states (
                        graph_generation, document_id, revision_id, acl_version,
                        state, input_hash, artifact_ids
                    ) SELECT ?, id, current_revision_id, acl_version,
                             'PROJECTED', ?, '[]'::jsonb
                      FROM documents WHERE id = ?
                    """,
                    generation, HASH, bits.document()
            );
        }
        jdbc.update(
                """
                INSERT INTO graph_entities (
                    id, graph_generation, canonical_name,
                    normalized_name, entity_type
                ) VALUES
                    (?, ?, 'Alpha', 'alpha', 'CONCEPT'),
                    (?, ?, 'Beta', 'beta', 'CONCEPT')
                """,
                alpha, generation, beta, generation
        );
        UUID alphaFirstMention = insertMention(generation, alpha, first, "Shared Alias");
        UUID alphaSecondMention = insertMention(generation, alpha, second, "Alpha Labs");
        UUID betaMention = insertMention(generation, beta, first, "Shared Alias");
        insertAlias(generation, alpha, "shared alias", "Shared Alias", alphaFirstMention);
        insertAlias(generation, alpha, "alpha labs", "Alpha Labs", alphaSecondMention);
        insertAlias(generation, beta, "shared alias", "Shared Alias", betaMention);
        return new Fixture(
                PlatformUserPrincipal.from(admin), generation, config,
                first.document()
        );
    }

    private DocumentBits document(UserEntity admin, String title, String text) {
        UUID document = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID span = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO documents (id, owner_user_id, title, visibility) VALUES (?, ?, ?, 'ALL_USERS')",
                document, admin.getId(), title
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash,
                    source_object_key, status, original_filename,
                    file_size_bytes, media_type
                ) VALUES (?, ?, 1, ?, ?, 'READY', 'phase21b.pdf',
                          100, 'application/pdf')
                """,
                revision, document, HASH, "phase21b/" + revision + ".pdf"
        );
        jdbc.update("UPDATE documents SET current_revision_id = ? WHERE id = ?", revision, document);
        insertChunk(parent, document, revision, null, "PARENT", false, text);
        insertChunk(child, document, revision, parent, "CHILD", true, text);
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (?, ?, ?, 1, 'PAGE', 'page:1', ?, ?,
                          'utf16-v1',
                          '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb)
                """,
                unit, document, revision, text, HASH
        );
        jdbc.update(
                """
                INSERT INTO source_spans (
                    id, chunk_id, document_id, revision_id, span_order,
                    locator_kind, start_source_unit_id, end_source_unit_id,
                    start_offset, end_offset, source_text_hash,
                    chunk_start_offset, chunk_end_offset, locator_address,
                    normalization_version
                ) VALUES (?, ?, ?, ?, 0, 'PAGE', ?, ?, 0, ?, ?, 0, ?,
                          '{"kind":"PAGE","startPage":1,"endPage":1}'::jsonb,
                          'utf16-v1')
                """,
                span, child, document, revision, unit, unit,
                text.length(), HASH, text.length()
        );
        return new DocumentBits(document, revision, parent, child, span);
    }

    private void insertChunk(
            UUID id, UUID document, UUID revision, UUID parent,
            String type, boolean searchable, String text
    ) {
        jdbc.update(
                """
                INSERT INTO chunks (
                    id, document_id, revision_id, parent_chunk_id, chunk_type,
                    chunk_order, text, heading_path, start_block_order,
                    end_block_order, character_count, token_count,
                    token_counter_version, chunking_profile_version,
                    parser_version, chunker_version, content_hash, searchable
                ) VALUES (?, ?, ?, ?, ?, 0, ?, '[]', 0, 0, ?, ?,
                          'unicode-codepoint-v1', 'phase4-v1',
                          'pdfbox-v1', 'phase4-v1', ?, ?)
                """,
                id, document, revision, parent, type, text,
                text.length(), text.length(), HASH, searchable
        );
    }

    private UUID insertMention(
            long generation, UUID entity, DocumentBits bits, String surface
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO graph_entity_mentions (
                    id, graph_generation, entity_id, document_id, revision_id,
                    parent_chunk_id, child_chunk_id, source_span_id,
                    surface_text, start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                id, generation, entity, bits.document(), bits.revision(),
                bits.parent(), bits.child(), bits.span(), surface, surface.length()
        );
        return id;
    }

    private void insertAlias(
            long generation, UUID entity, String normalized,
            String alias, UUID mention
    ) {
        jdbc.update(
                """
                INSERT INTO graph_entity_aliases (
                    graph_generation, entity_id, normalized_alias, alias
                ) VALUES (?, ?, ?, ?)
                """,
                generation, entity, normalized, alias
        );
        jdbc.update(
                """
                INSERT INTO graph_entity_alias_evidence (
                    graph_generation, entity_id, normalized_alias, mention_id
                ) VALUES (?, ?, ?, ?)
                """,
                generation, entity, normalized, mention
        );
    }

    private record Fixture(
            PlatformUserPrincipal admin,
            long generation,
            String config,
            UUID firstDocument
    ) { }

    private record DocumentBits(
            UUID document,
            UUID revision,
            UUID parent,
            UUID child,
            UUID span
    ) { }
}
