package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphApiContracts.CreateResolutionRuleRequest;
import com.example.rag.graph.GraphApiContracts.CreateResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.MaterializeResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.ReviseResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.WithdrawResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewRequest;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.graph.enabled=true",
        "rag.graph.worker-enabled=false",
        "rag.graph.extraction.enabled=false"
})
@AutoConfigureMockMvc
class GraphPhase21AIntegrationTests {

    private static final String HASH = "d".repeat(64);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private GraphGenerationRepository generations;
    @Autowired private GraphQueryService queries;
    @Autowired private GraphResolutionService resolutions;
    @Autowired private GraphResolutionProposalService proposals;
    @Autowired private MockMvc mockMvc;

    @Test
    @Transactional
    void aliasSearchPreviewAndIdempotentCreateKeepActiveGraphUnchanged() {
        Fixture fixture = fixture();

        var page = queries.entities(
                fixture.admin(), fixture.generation(),
                "alpha alias", "CONCEPT", null, 0, 10
        );
        assertThat(page.items()).singleElement().satisfies(entity -> {
            assertThat(entity.id()).isEqualTo(fixture.alpha());
            assertThat(entity.matchSource()).isEqualTo("ALIAS");
            assertThat(entity.matchedAlias()).isEqualTo("Alpha Alias");
            assertThat(entity.aliases()).contains("Alpha Alias");
        });

        var preview = resolutions.preview(
                new ResolutionRulePreviewRequest(
                        fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alpha Beta", "CONCEPT"
                ),
                fixture.admin()
        );
        assertThat(preview.previewToken()).isNotNull();
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.impact().mentionCount()).isEqualTo(2);
        assertThat(preview.impact().sourceSpanCount()).isEqualTo(2);
        assertThat(preview.impact().documentCount()).isOne();
        assertThat(preview.impact().queryImpactState())
                .isEqualTo("NOT_AVAILABLE");

        String newVersion = "phase21a-rule-" + UUID.randomUUID();
        String idempotencyKey = "phase21a-" + UUID.randomUUID();
        var request = new CreateResolutionRuleRequest(
                preview.previewToken(), newVersion, "APPLY_NEXT_BUILD",
                "Merge aliases after reviewing current source evidence",
                idempotencyKey
        );
        var created = resolutions.create(request, fixture.admin(), newVersion);
        var replayed = resolutions.create(request, fixture.admin(), newVersion);

        assertThat(created.version()).isEqualTo(newVersion);
        assertThat(replayed.version()).isEqualTo(newVersion);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_resolution_rules WHERE rule_set_version = ?",
                Integer.class,
                newVersion
        )).isOne();
        assertThat(jdbc.queryForMap(
                "SELECT status, graph_config_version FROM graph_manifests WHERE graph_generation = ?",
                fixture.generation()
        )).containsEntry("status", "READY")
                .containsEntry("graph_config_version", fixture.config());
    }

    @Test
    @Transactional
    void aclDriftInvalidatesPreviewAndWritesNothing() {
        Fixture fixture = fixture();
        var preview = resolutions.preview(
                new ResolutionRulePreviewRequest(
                        fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alpha Beta", "CONCEPT"
                ),
                fixture.admin()
        );
        jdbc.update(
                "UPDATE documents SET acl_version = acl_version + 1 WHERE id = ?",
                fixture.document()
        );
        String newVersion = "phase21a-stale-" + UUID.randomUUID();

        assertThatThrownBy(() -> resolutions.create(
                new CreateResolutionRuleRequest(
                        preview.previewToken(), newVersion,
                        "APPLY_NEXT_BUILD",
                        "This stale preview must not create any graph config",
                        "phase21a-stale-" + UUID.randomUUID()
                ),
                fixture.admin(),
                newVersion
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("重新预检");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_configs WHERE version = ?",
                Integer.class,
                newVersion
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM graph_resolution_previews WHERE token = ?",
                Boolean.class,
                preview.previewToken()
        )).isTrue();
    }

    @Test
    @Transactional
    void ordinaryUserCannotCreateResolutionPreview() throws Exception {
        UserEntity ordinary = users.saveAndFlush(new UserEntity(
                "phase21a-user-" + UUID.randomUUID(),
                "phase21a-user-hash", UserRole.USER
        ));
        mockMvc.perform(post("/api/v1/admin/graph/resolution-rules/previews")
                        .with(user(PlatformUserPrincipal.from(ordinary)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "graphGeneration": 1,
                                  "baseConfigVersion": "not-visible",
                                  "action": "MERGE",
                                  "sourceEntityIds": [
                                    "00000000-0000-0000-0000-000000000001",
                                    "00000000-0000-0000-0000-000000000002"
                                  ],
                                  "matchAliases": [],
                                  "targetCanonicalName": "Blocked",
                                  "targetEntityType": "CONCEPT"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @Transactional
    void proposalRevisionsRemainImmutableAndMaterializationDoesNotPublish() {
        Fixture fixture = fixture();
        var created = proposals.create(
                new CreateResolutionProposalRequest(
                        null, fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alpha Beta", "CONCEPT",
                        "Create a reviewed entity governance proposal",
                        "phase21c-create-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        assertThat(created.proposal().status()).isEqualTo("READY");
        assertThat(created.proposal().currentRevision()).isOne();

        var revised = proposals.revise(
                created.proposal().id(),
                new ReviseResolutionProposalRequest(
                        1, created.proposal().version(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alpha and Beta", "CONCEPT",
                        "Clarify the target name after reviewing evidence",
                        "phase21c-revise-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        assertThat(revised.proposal().currentRevision()).isEqualTo(2);
        assertThat(revised.revisions()).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_resolution_proposal_revisions WHERE proposal_id = ?",
                Integer.class, created.proposal().id()
        )).isEqualTo(2);

        var preview = resolutions.preview(
                new ResolutionRulePreviewRequest(
                        fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()).stream().sorted().toList(),
                        List.of(), "Alpha and Beta", "CONCEPT"
                ),
                fixture.admin()
        );
        String configVersion = "phase21c-config-" + UUID.randomUUID();
        var materialized = proposals.materialize(
                created.proposal().id(),
                new MaterializeResolutionProposalRequest(
                        2, revised.proposal().version(), preview.previewToken(),
                        configVersion, "MATERIALIZE_RESOLUTION_PROPOSAL",
                        "Materialize the reviewed proposal for a future build",
                        "phase21c-materialize-" + UUID.randomUUID()
                ),
                fixture.admin()
        );
        assertThat(materialized.proposal().status()).isEqualTo("MATERIALIZED");
        assertThat(materialized.proposal().materializedConfigVersion())
                .isEqualTo(configVersion);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_manifests WHERE graph_config_version = ?",
                Integer.class, configVersion
        )).isZero();
        assertThat(jdbc.queryForMap(
                "SELECT status, graph_config_version FROM graph_manifests WHERE graph_generation = ?",
                fixture.generation()
        )).containsEntry("status", "READY")
                .containsEntry("graph_config_version", fixture.config());
    }

    @Test
    @Transactional
    void overlappingProposalsConflictAndCannotBeMaterializedOrSilentlyOverwritten() {
        Fixture fixture = fixture();
        var first = proposals.create(
                new CreateResolutionProposalRequest(
                        null, fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alpha Beta", "CONCEPT",
                        "Create the first overlapping governance proposal",
                        "phase21c-first-" + UUID.randomUUID()
                ), fixture.admin()
        );
        var second = proposals.create(
                new CreateResolutionProposalRequest(
                        null, fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alternative Alpha Beta", "CONCEPT",
                        "Create a conflicting proposal to test safe blocking",
                        "phase21c-second-" + UUID.randomUUID()
                ), fixture.admin()
        );
        var refreshedFirst = proposals.detail(
                first.proposal().id(), fixture.admin(), false
        );
        assertThat(refreshedFirst.proposal().status()).isEqualTo("CONFLICTED");
        assertThat(second.proposal().status()).isEqualTo("CONFLICTED");
        assertThat(refreshedFirst.proposal().conflicts()).isNotEmpty();

        assertThatThrownBy(() -> proposals.materialize(
                first.proposal().id(),
                new MaterializeResolutionProposalRequest(
                        1, refreshedFirst.proposal().version(), UUID.randomUUID(),
                        "phase21c-blocked-" + UUID.randomUUID(),
                        "MATERIALIZE_RESOLUTION_PROPOSAL",
                        "Conflicted proposal materialization must be rejected",
                        "phase21c-blocked-" + UUID.randomUUID()
                ), fixture.admin()
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("不能物化");
    }

    @Test
    @Transactional
    void withdrawnProposalKeepsRevisionHistoryAndOrdinaryUserIsForbidden() throws Exception {
        Fixture fixture = fixture();
        var created = proposals.create(
                new CreateResolutionProposalRequest(
                        null, fixture.generation(), fixture.config(), "MERGE",
                        List.of(fixture.alpha(), fixture.beta()), List.of(),
                        "Alpha Beta", "CONCEPT",
                        "Create a proposal that will be safely withdrawn",
                        "phase21c-withdraw-create-" + UUID.randomUUID()
                ), fixture.admin()
        );
        var withdrawn = proposals.withdraw(
                created.proposal().id(),
                new WithdrawResolutionProposalRequest(
                        1, created.proposal().version(),
                        "WITHDRAW_RESOLUTION_PROPOSAL",
                        "Withdraw after administrator evidence review",
                        "phase21c-withdraw-" + UUID.randomUUID()
                ), fixture.admin()
        );
        assertThat(withdrawn.proposal().status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.revisions()).hasSize(1);

        UserEntity ordinary = users.saveAndFlush(new UserEntity(
                "phase21c-user-" + UUID.randomUUID(),
                "phase21c-user-hash", UserRole.USER
        ));
        mockMvc.perform(post("/api/v1/admin/graph/resolution-proposals")
                        .with(user(PlatformUserPrincipal.from(ordinary)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private Fixture fixture() {
        UserEntity admin = users.saveAndFlush(new UserEntity(
                "phase21a-admin-" + UUID.randomUUID(),
                "phase21a-admin-hash", UserRole.ADMIN
        ));
        UUID document = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID alphaChild = UUID.randomUUID();
        UUID betaChild = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID alphaSpan = UUID.randomUUID();
        UUID betaSpan = UUID.randomUUID();
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO documents (id, owner_user_id, title, visibility) VALUES (?, ?, 'Phase 21A fixture', 'ALL_USERS')",
                document, admin.getId()
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash,
                    source_object_key, status, original_filename,
                    file_size_bytes, media_type
                ) VALUES (?, ?, 1, ?, ?, 'READY', 'phase21a.pdf',
                          100, 'application/pdf')
                """,
                revision, document, HASH, "phase21a/" + revision + ".pdf"
        );
        jdbc.update(
                "UPDATE documents SET current_revision_id = ? WHERE id = ?",
                revision, document
        );
        insertChunk(parent, document, revision, null, "PARENT", 0,
                "Alpha Alias and Beta are related.", false);
        insertChunk(alphaChild, document, revision, parent, "CHILD", 0,
                "Alpha Alias", true);
        insertChunk(betaChild, document, revision, parent, "CHILD", 1,
                "Beta", true);
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (?, ?, ?, 1, 'PAGE', 'page:1',
                          'Alpha Alias and Beta are related.', ?,
                          'utf16-v1',
                          '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb)
                """,
                unit, document, revision, HASH
        );
        insertSpan(alphaSpan, alphaChild, document, revision, unit, 0, 11);
        insertSpan(betaSpan, betaChild, document, revision, unit, 16, 20);

        String config = "phase21a-fixture-" + UUID.randomUUID();
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
                          'Phase 21A integration fixture')
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
                ) VALUES (?, ?, 'READY', ?, 1, 1, 2, 2,
                          'Phase 21A integration fixture')
                RETURNING graph_generation
                """,
                Long.class,
                UUID.randomUUID(), config, sourceSetHash
        );
        jdbc.update(
                """
                INSERT INTO graph_projection_states (
                    graph_generation, document_id, revision_id, acl_version,
                    state, input_hash, artifact_ids
                ) SELECT ?, id, current_revision_id, acl_version,
                         'PROJECTED', ?, '[]'::jsonb
                  FROM documents WHERE id = ?
                """,
                generation, HASH, document
        );
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
        UUID alphaMention = UUID.randomUUID();
        insertMention(alphaMention, generation, alpha, document, revision,
                parent, alphaChild, alphaSpan, "Alpha Alias", 0, 11);
        insertMention(UUID.randomUUID(), generation, beta, document, revision,
                parent, betaChild, betaSpan, "Beta", 16, 20);
        jdbc.update(
                """
                INSERT INTO graph_entity_aliases (
                    graph_generation, entity_id, normalized_alias, alias
                ) VALUES (?, ?, 'alpha alias', 'Alpha Alias')
                """,
                generation, alpha
        );
        jdbc.update(
                """
                INSERT INTO graph_entity_alias_evidence (
                    graph_generation, entity_id, normalized_alias, mention_id
                ) VALUES (?, ?, 'alpha alias', ?)
                """,
                generation, alpha, alphaMention
        );
        return new Fixture(
                PlatformUserPrincipal.from(admin), document, generation,
                config, alpha, beta
        );
    }

    private void insertChunk(
            UUID id, UUID document, UUID revision, UUID parent,
            String type, int order, String text, boolean searchable
    ) {
        jdbc.update(
                """
                INSERT INTO chunks (
                    id, document_id, revision_id, parent_chunk_id, chunk_type,
                    chunk_order, text, heading_path, start_block_order,
                    end_block_order, character_count, token_count,
                    token_counter_version, chunking_profile_version,
                    parser_version, chunker_version, content_hash, searchable
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '[]', 0, 0, ?, ?,
                          'unicode-codepoint-v1', 'phase4-v1',
                          'pdfbox-v1', 'phase4-v1', ?, ?)
                """,
                id, document, revision, parent, type, order, text,
                text.length(), text.length(), HASH, searchable
        );
    }

    private void insertSpan(
            UUID id, UUID child, UUID document, UUID revision,
            UUID unit, int start, int end
    ) {
        jdbc.update(
                """
                INSERT INTO source_spans (
                    id, chunk_id, document_id, revision_id, span_order,
                    locator_kind, start_source_unit_id, end_source_unit_id,
                    start_offset, end_offset, source_text_hash,
                    chunk_start_offset, chunk_end_offset, locator_address,
                    normalization_version
                ) VALUES (?, ?, ?, ?, 0, 'PAGE', ?, ?, ?, ?, ?, 0, ?,
                          '{"kind":"PAGE","startPage":1,"endPage":1}'::jsonb,
                          'utf16-v1')
                """,
                id, child, document, revision, unit, unit,
                start, end, HASH, Math.max(1, end - start)
        );
    }

    private void insertMention(
            UUID id, long generation, UUID entity, UUID document,
            UUID revision, UUID parent, UUID child, UUID span,
            String surface, int start, int end
    ) {
        jdbc.update(
                """
                INSERT INTO graph_entity_mentions (
                    id, graph_generation, entity_id, document_id, revision_id,
                    parent_chunk_id, child_chunk_id, source_span_id,
                    surface_text, start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, generation, entity, document, revision, parent, child,
                span, surface, start, end
        );
    }

    private record Fixture(
            PlatformUserPrincipal admin,
            UUID document,
            long generation,
            String config,
            UUID alpha,
            UUID beta
    ) {
    }
}
