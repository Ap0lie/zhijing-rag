package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphApiContracts.GraphRootType;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "rag.graph.enabled=true",
        "rag.graph.worker-enabled=false",
        "rag.graph.extraction.enabled=false"
})
class GraphPhase20IntegrationTests {

    private static final String HASH = "c".repeat(64);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private GraphVisualizationService visualization;

    @Test
    @Transactional
    void entityAndCommunitySubgraphsKeepEvidenceBackedClosure() {
        Fixture fixture = fixture();

        var oneHop = visualization.subgraph(
                fixture.admin(),
                fixture.generation(),
                GraphRootType.ENTITY,
                fixture.alpha(),
                1
        );
        assertThat(oneHop.nodes()).extracting("name")
                .containsExactly("Alpha", "Beta");
        assertThat(oneHop.edges()).singleElement()
                .satisfies(edge -> {
                    assertThat(edge.relationshipType()).isEqualTo("LINKS");
                    assertThat(edge.evidenceCount()).isOne();
                });

        var twoHop = visualization.subgraph(
                fixture.admin(),
                fixture.generation(),
                GraphRootType.ENTITY,
                fixture.alpha(),
                2
        );
        assertThat(twoHop.nodes()).extracting("name")
                .containsExactly("Alpha", "Beta", "Gamma");
        assertThat(twoHop.edges()).hasSize(2);
        assertThat(twoHop.nodes().stream()
                .filter(node -> node.name().equals("Gamma")))
                .singleElement()
                .satisfies(node -> assertThat(node.depth()).isEqualTo(2));

        var community = visualization.subgraph(
                fixture.admin(),
                fixture.generation(),
                GraphRootType.COMMUNITY,
                fixture.community(),
                2
        );
        assertThat(community.hops()).isOne();
        assertThat(community.nodes()).hasSize(3);
        assertThat(community.edges()).hasSize(2);

        var relationship = visualization.relationship(
                fixture.admin(),
                fixture.generation(),
                fixture.firstRelationship()
        );
        assertThat(relationship.evidence()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.childChunkId())
                            .isEqualTo(fixture.firstEvidenceChild());
                    assertThat(evidence.sourceLocator()).isNotNull();
                });
    }

    @Test
    @Transactional
    void staleProjectionAndUnbrowsableGenerationFailClosed() {
        Fixture fixture = fixture();
        jdbc.update(
                "UPDATE documents SET acl_version = acl_version + 1 WHERE id = ?",
                fixture.document()
        );

        assertThatThrownBy(() -> visualization.subgraph(
                fixture.admin(),
                fixture.generation(),
                GraphRootType.ENTITY,
                fixture.alpha(),
                1
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("找不到实体");

        jdbc.update(
                "UPDATE graph_manifests SET status = 'FAILED' WHERE graph_generation = ?",
                fixture.generation()
        );
        assertThatThrownBy(() -> visualization.subgraph(
                fixture.admin(),
                fixture.generation(),
                GraphRootType.ENTITY,
                fixture.alpha(),
                1
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("当前不可浏览");
    }

    @Test
    @Transactional
    void ordinaryUserCannotInspectGraphTopology() {
        Fixture fixture = fixture();
        UserEntity ordinary = users.saveAndFlush(new UserEntity(
                "graph-phase20-user-" + UUID.randomUUID(),
                "test-password-hash",
                UserRole.USER
        ));

        assertThatThrownBy(() -> visualization.subgraph(
                PlatformUserPrincipal.from(ordinary),
                fixture.generation(),
                GraphRootType.ENTITY,
                fixture.alpha(),
                1
        )).isInstanceOf(ApiException.class)
                .hasMessageContaining("管理员会话");
    }

    @Test
    @Transactional
    void localGraphCapsEdgesWithoutExposingHiddenCounts() {
        Fixture fixture = fixture();
        for (int index = 0; index < 45; index++) {
            UUID relationship = UUID.randomUUID();
            insertRelationship(
                    relationship,
                    fixture.generation(),
                    fixture.alpha(),
                    fixture.beta(),
                    "SUPPORTS_" + index
            );
            insertEvidence(
                    fixture.generation(),
                    relationship,
                    fixture.document(),
                    fixture.revision(),
                    fixture.parent(),
                    fixture.firstEvidenceChild(),
                    fixture.firstSpan(),
                    "Alpha links Beta."
            );
        }

        var graph = visualization.subgraph(
                fixture.admin(),
                fixture.generation(),
                GraphRootType.ENTITY,
                fixture.alpha(),
                1
        );

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(40);
        assertThat(graph.truncated()).isTrue();
        assertThat(graph.edges()).extracting(edge -> edge.id().toString())
                .isSorted();
    }

    private Fixture fixture() {
        UserEntity owner = users.saveAndFlush(new UserEntity(
                "graph-phase20-admin-" + UUID.randomUUID(),
                "test-password-hash",
                UserRole.ADMIN
        ));
        UUID document = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID alphaChild = UUID.randomUUID();
        UUID firstEvidenceChild = UUID.randomUUID();
        UUID secondEvidenceChild = UUID.randomUUID();
        UUID sourceUnit = UUID.randomUUID();
        UUID alphaSpan = UUID.randomUUID();
        UUID firstSpan = UUID.randomUUID();
        UUID secondSpan = UUID.randomUUID();
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        UUID gamma = UUID.randomUUID();
        UUID firstRelationship = UUID.randomUUID();
        UUID secondRelationship = UUID.randomUUID();
        UUID community = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO documents (id, owner_user_id, title, visibility)
                VALUES (?, ?, 'Phase 20 topology fixture', 'ALL_USERS')
                """,
                document,
                owner.getId()
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash,
                    source_object_key, status, original_filename,
                    file_size_bytes, media_type
                ) VALUES (?, ?, 1, ?, ?, 'READY', 'phase20.pdf',
                          100, 'application/pdf')
                """,
                revision,
                document,
                HASH,
                "phase20/" + revision + ".pdf"
        );
        jdbc.update(
                "UPDATE documents SET current_revision_id = ? WHERE id = ?",
                revision,
                document
        );
        insertChunk(parent, document, revision, null, "PARENT", 0,
                "Alpha links Beta. Beta links Gamma.", false);
        insertChunk(alphaChild, document, revision, parent, "CHILD", 0,
                "Alpha", true);
        insertChunk(firstEvidenceChild, document, revision, parent, "CHILD", 1,
                "Alpha links Beta.", true);
        insertChunk(secondEvidenceChild, document, revision, parent, "CHILD", 2,
                "Beta links Gamma.", true);
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (?, ?, ?, 1, 'PAGE', 'page:1',
                          'Alpha links Beta. Beta links Gamma.', ?,
                          'utf16-v1',
                          '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb)
                """,
                sourceUnit,
                document,
                revision,
                HASH
        );
        insertSpan(alphaSpan, alphaChild, document, revision, sourceUnit, 0, 5);
        insertSpan(firstSpan, firstEvidenceChild, document, revision, sourceUnit,
                0, 17);
        insertSpan(secondSpan, secondEvidenceChild, document, revision, sourceUnit,
                18, 35);

        String config = "phase20-topology-fixture-v1";
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
                          'Phase 20 topology fixture')
                ON CONFLICT (version) DO NOTHING
                """,
                config
        );
        Long generation = jdbc.queryForObject(
                """
                INSERT INTO graph_manifests (
                    id, graph_config_version, status,
                    expected_document_count, projected_document_count,
                    entity_count, mention_count, relationship_count,
                    relationship_evidence_count, community_count,
                    build_reason
                ) VALUES (?, ?, 'READY', 1, 1, 3, 3, 2, 2, 1,
                          'Phase 20 topology fixture')
                RETURNING graph_generation
                """,
                Long.class,
                UUID.randomUUID(),
                config
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
                generation,
                HASH,
                document
        );
        jdbc.update(
                """
                INSERT INTO graph_entities (
                    id, graph_generation, canonical_name,
                    normalized_name, entity_type
                ) VALUES
                    (?, ?, 'Alpha', 'alpha', 'CONCEPT'),
                    (?, ?, 'Beta', 'beta', 'CONCEPT'),
                    (?, ?, 'Gamma', 'gamma', 'CONCEPT')
                """,
                alpha, generation,
                beta, generation,
                gamma, generation
        );
        insertMention(UUID.randomUUID(), generation, alpha, document, revision,
                parent, alphaChild, alphaSpan, "Alpha", 0, 5);
        insertMention(UUID.randomUUID(), generation, beta, document, revision,
                parent, firstEvidenceChild, firstSpan, "Beta", 12, 16);
        insertMention(UUID.randomUUID(), generation, gamma, document, revision,
                parent, secondEvidenceChild, secondSpan, "Gamma", 29, 34);
        insertRelationship(firstRelationship, generation, alpha, beta, "LINKS");
        insertRelationship(secondRelationship, generation, beta, gamma, "LINKS");
        insertEvidence(generation, firstRelationship, document, revision, parent,
                firstEvidenceChild, firstSpan, "Alpha links Beta.");
        insertEvidence(generation, secondRelationship, document, revision, parent,
                secondEvidenceChild, secondSpan, "Beta links Gamma.");
        jdbc.update(
                """
                INSERT INTO graph_communities (
                    id, graph_generation, community_key, title,
                    summary, entity_count
                ) VALUES (?, ?, 1, 'Alpha network',
                          'Evidence-backed test community', 3)
                """,
                community,
                generation
        );
        jdbc.batchUpdate(
                """
                INSERT INTO graph_community_members (
                    graph_generation, community_id, entity_id, member_order
                ) VALUES (?, ?, ?, ?)
                """,
                java.util.List.of(
                        new Object[]{generation, community, alpha, 0},
                        new Object[]{generation, community, beta, 1},
                        new Object[]{generation, community, gamma, 2}
                )
        );
        return new Fixture(
                PlatformUserPrincipal.from(owner),
                document,
                revision,
                parent,
                generation,
                alpha,
                beta,
                community,
                firstRelationship,
                firstEvidenceChild,
                firstSpan
        );
    }

    private void insertChunk(
            UUID id,
            UUID document,
            UUID revision,
            UUID parent,
            String type,
            int order,
            String text,
            boolean searchable
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
            UUID id,
            UUID child,
            UUID document,
            UUID revision,
            UUID sourceUnit,
            int start,
            int end
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
                id, child, document, revision, sourceUnit, sourceUnit,
                start, end, HASH, Math.max(1, end - start)
        );
    }

    private void insertMention(
            UUID id,
            long generation,
            UUID entity,
            UUID document,
            UUID revision,
            UUID parent,
            UUID child,
            UUID span,
            String surface,
            int start,
            int end
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

    private void insertRelationship(
            UUID id,
            long generation,
            UUID source,
            UUID target,
            String type
    ) {
        jdbc.update(
                """
                INSERT INTO graph_relationships (
                    id, graph_generation, source_entity_id,
                    target_entity_id, relationship_type
                ) VALUES (?, ?, ?, ?, ?)
                """,
                id, generation, source, target, type
        );
    }

    private void insertEvidence(
            long generation,
            UUID relationship,
            UUID document,
            UUID revision,
            UUID parent,
            UUID child,
            UUID span,
            String text
    ) {
        jdbc.update(
                """
                INSERT INTO graph_relationship_evidence (
                    id, graph_generation, relationship_id, document_id,
                    revision_id, parent_chunk_id, child_chunk_id,
                    source_span_id, evidence_text, evidence_text_hash,
                    start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                UUID.randomUUID(), generation, relationship, document,
                revision, parent, child, span, text, HASH, text.length()
        );
    }

    private record Fixture(
            PlatformUserPrincipal admin,
            UUID document,
            UUID revision,
            UUID parent,
            long generation,
            UUID alpha,
            UUID beta,
            UUID community,
            UUID firstRelationship,
            UUID firstEvidenceChild,
            UUID firstSpan
    ) {
    }
}
