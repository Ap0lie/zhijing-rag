package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphRetrievalContracts.CreateProfileRequest;
import com.example.rag.graph.GraphRetrievalContracts.ReleaseProfileRequest;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = {
        "rag.graph.enabled=true",
        "rag.graph.worker-enabled=false",
        "rag.graph.extraction.enabled=false"
})
class GraphPhase9IntegrationTests {

    private static final String HASH = "a".repeat(64);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private LocalGraphRetrievalService retrieval;
    @Autowired private GraphRetrievalConfigurationService configurations;
    @Autowired private ObjectMapper json;

    @Test
    @Transactional
    void v15CreatesImmutablePublishedRetrievalProfile() {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '15' AND success
                """,
                Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT profile_version
                FROM graph_retrieval_publications
                WHERE singleton_id = 1
                """,
                String.class
        )).isEqualTo("phase9-local-v1");

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE graph_retrieval_profiles
                SET edge_limit = 39
                WHERE version = 'phase9-local-v1'
                """
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void goldenDatasetContractIsVersionedWithoutFabricatedQualityCases()
            throws Exception {
        JsonNode contract;
        JsonNode manifest;
        try (var input = getClass().getResourceAsStream(
                "/graph-local-golden-v1.contract.json"
        )) {
            assumeTrue(
                    input != null,
                    "External graph evaluation resources are intentionally not checked in"
            );
            contract = json.readTree(input);
        }
        try (var input = getClass().getResourceAsStream(
                "/graph-local-golden/v1/manifest.json"
        )) {
            assumeTrue(
                    input != null,
                    "External graph evaluation resources are intentionally not checked in"
            );
            manifest = json.readTree(input);
        }
        assertThat(contract.path("datasetVersion").asText())
                .isEqualTo("graph-local-golden-v1");
        assertThat(contract.path("status").asText())
                .isEqualTo("PUBLIC_CANDIDATES_SELECTED");
        assertThat(contract.path("minimumConfirmedCases").asInt())
                .isEqualTo(50);
        assertThat(contract.path("candidateSets").size()).isEqualTo(3);
        assertThat(contract.path("hardGates").size()).isPositive();

        assertThat(manifest.path("suiteVersion").asText())
                .isEqualTo("graph-local-golden-v1");
        assertThat(manifest.path("status").asText())
                .isEqualTo("PUBLIC_CANDIDATES_SELECTED");
        var resources = new HashSet<String>();
        int caseCount = 0;
        for (JsonNode candidate : contract.path("candidateSets")) {
            String resource = candidate.path("resource").asText();
            assertThat(resources.add(resource)).isTrue();
            try (var input = getClass().getResourceAsStream(resource)) {
                JsonNode dataset = json.readTree(input);
                assertThat(dataset.path("status").asText())
                        .isEqualTo("PUBLIC_SOURCE_SELECTED");
                assertThat(dataset.path("cases").size())
                        .isEqualTo(candidate.path("caseCount").asInt());
                caseCount += dataset.path("cases").size();
            }
        }
        assertThat(caseCount).isEqualTo(100);
    }

    @Test
    @Transactional
    void v16KeepsPublicationProfileConsistentWithItsEvent() {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '16' AND success
                """,
                Integer.class
        )).isOne();
        String unpublished = "phase9-unpublished-" + UUID.randomUUID();
        insertProfile(unpublished);
        Long mismatchedEvent = jdbc.queryForObject(
                """
                INSERT INTO graph_retrieval_publication_events (
                    event_type, profile_version, reason
                ) VALUES ('PUBLISH', ?, 'constraint verification')
                RETURNING id
                """,
                Long.class,
                unpublished
        );

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE graph_retrieval_publications
                SET publication_event_id = ?
                WHERE singleton_id = 1
                """,
                mismatchedEvent
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void rollbackRejectsNeverPublishedProfileAndAcceptsPriorPublication() {
        UserEntity actor = users.saveAndFlush(new UserEntity(
                "graph-release-" + UUID.randomUUID(),
                "test-password-hash",
                UserRole.ADMIN
        ));
        String first = "phase9-first-" + UUID.randomUUID();
        String second = "phase9-second-" + UUID.randomUUID();
        createProfile(first, actor.getId());
        createProfile(second, actor.getId());

        assertThatThrownBy(() -> configurations.publish(
                release(first, "ROLLBACK"),
                actor.getId(),
                "ROLLBACK"
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo(
                        "GRAPH_RETRIEVAL_ROLLBACK_TARGET_INVALID"
                )
        );

        configurations.publish(
                release(first, "PUBLISH"),
                actor.getId(),
                "PUBLISH"
        );
        assertThatThrownBy(() -> configurations.publish(
                release(first, "ROLLBACK"),
                actor.getId(),
                "ROLLBACK"
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo(
                        "GRAPH_RETRIEVAL_ROLLBACK_TARGET_INVALID"
                )
        );
        configurations.publish(
                release(second, "PUBLISH"),
                actor.getId(),
                "PUBLISH"
        );

        assertThat(configurations.publish(
                release(first, "ROLLBACK"),
                actor.getId(),
                "ROLLBACK"
        ).profileVersion()).isEqualTo(first);
    }

    @Test
    @Transactional
    void localTraversalReturnsOnlyCurrentAuthorizedChildEvidence() throws Exception {
        Fixture fixture = fixture();

        LocalGraphRetrievalService.Expansion expansion = retrieval.expand(
                "目标实体",
                List.of(fixture.seedChildId()),
                List.of(fixture.documentId())
        );

        assertThat(expansion.used()).isTrue();
        assertThat(expansion.seedCount()).isOne();
        assertThat(expansion.seedDocumentIds())
                .containsExactly(fixture.documentId());
        assertThat(expansion.edgeCount()).isOne();
        assertThat(expansion.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.childId()).isEqualTo(fixture.evidenceChildId());
            assertThat(candidate.paths()).singleElement().satisfies(path -> {
                assertThat(path.sourceSpanId()).isEqualTo(fixture.evidenceSpanId());
                assertThat(path.relationshipType()).isEqualTo("DEPENDS_ON");
                assertThat(path.evidenceText()).isEqualTo("种子实体依赖目标实体。");
            });
        });

        jdbc.update(
                """
                UPDATE graph_projection_states
                SET state = 'FAILED'
                WHERE graph_generation = ? AND document_id = ?
                """,
                fixture.graphGeneration(),
                fixture.documentId()
        );
        LocalGraphRetrievalService.Expansion failedProjection = retrieval.expand(
                "目标实体",
                List.of(fixture.seedChildId()),
                List.of(fixture.documentId())
        );
        assertThat(failedProjection.used()).isFalse();
        assertThat(failedProjection.degradationCode())
                .isEqualTo("GRAPH_NO_SEED");
        jdbc.update(
                """
                UPDATE graph_projection_states
                SET state = 'PROJECTED'
                WHERE graph_generation = ? AND document_id = ?
                """,
                fixture.graphGeneration(),
                fixture.documentId()
        );

        jdbc.update(
                "UPDATE documents SET acl_version = 2 WHERE id = ?",
                fixture.documentId()
        );
        LocalGraphRetrievalService.Expansion stale = retrieval.expand(
                "目标实体",
                List.of(fixture.seedChildId()),
                List.of(fixture.documentId())
        );
        assertThat(stale.used()).isFalse();
        assertThat(stale.degradationCode()).isEqualTo("GRAPH_NO_SEED");

        writeReport(expansion, stale);
    }

    @Test
    @Transactional
    void twoHopTraversalKeepsItsPredecessorEdge() {
        Fixture fixture = fixture();
        UUID secondTarget = UUID.randomUUID();
        UUID secondRelationship = UUID.randomUUID();
        UUID secondChild = UUID.randomUUID();
        UUID secondSpan = UUID.randomUUID();
        insertChunk(
                secondChild,
                fixture.documentId(),
                fixture.revisionId(),
                fixture.parentId(),
                "CHILD",
                2,
                "目标实体关联第二目标。",
                true
        );
        insertSpan(
                secondSpan,
                secondChild,
                fixture.documentId(),
                fixture.revisionId(),
                fixture.sourceUnitId(),
                0,
                10
        );
        jdbc.update(
                """
                INSERT INTO graph_entities (
                    id, graph_generation, canonical_name,
                    normalized_name, entity_type
                ) VALUES (?, ?, '第二目标', '第二目标', 'CONCEPT')
                """,
                secondTarget,
                fixture.graphGeneration()
        );
        jdbc.update(
                """
                INSERT INTO graph_relationships (
                    id, graph_generation, source_entity_id,
                    target_entity_id, relationship_type
                ) VALUES (?, ?, ?, ?, 'RELATED_TO')
                """,
                secondRelationship,
                fixture.graphGeneration(),
                fixture.targetEntityId(),
                secondTarget
        );
        jdbc.update(
                """
                INSERT INTO graph_relationship_evidence (
                    id, graph_generation, relationship_id, document_id,
                    revision_id, parent_chunk_id, child_chunk_id,
                    source_span_id, evidence_text, evidence_text_hash,
                    start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                          '目标实体关联第二目标。', ?, 0, 10)
                """,
                UUID.randomUUID(),
                fixture.graphGeneration(),
                secondRelationship,
                fixture.documentId(),
                fixture.revisionId(),
                fixture.parentId(),
                secondChild,
                secondSpan,
                HASH
        );
        jdbc.update(
                """
                INSERT INTO graph_adjacency (
                    graph_generation, source_entity_id, target_entity_id,
                    relationship_id, direction
                ) VALUES (?, ?, ?, ?, 'OUT')
                """,
                fixture.graphGeneration(),
                fixture.targetEntityId(),
                secondTarget,
                secondRelationship
        );

        LocalGraphRetrievalService.Expansion expansion = retrieval.expand(
                "种子实体",
                List.of(fixture.seedChildId()),
                List.of(fixture.documentId())
        );

        assertThat(expansion.edgeCount()).isEqualTo(2);
        assertThat(expansion.candidates().stream()
                .flatMap(candidate -> candidate.paths().stream())
                .filter(path -> path.depth() == 2))
                .singleElement()
                .satisfies(path ->
                        assertThat(path.parentRelationshipId())
                                .isEqualTo(fixture.relationshipId())
                );
    }

    @Test
    @Transactional
    void latinAliasRequiresTokenBoundaryAndAuthorizedProvenance() {
        Fixture fixture = fixture();
        jdbc.update(
                """
                INSERT INTO graph_entity_aliases (
                    graph_generation, entity_id, alias, normalized_alias
                ) VALUES (?, ?, 'AI', 'ai')
                """,
                fixture.graphGeneration(),
                fixture.seedEntityId()
        );
        jdbc.update(
                """
                INSERT INTO graph_entity_alias_evidence (
                    graph_generation, entity_id, normalized_alias, mention_id
                ) VALUES (?, ?, 'ai', ?)
                """,
                fixture.graphGeneration(),
                fixture.seedEntityId(),
                fixture.mentionId()
        );

        LocalGraphRetrievalService.Expansion embedded = retrieval.expand(
                "training",
                List.of(),
                List.of(fixture.documentId())
        );
        LocalGraphRetrievalService.Expansion exact = retrieval.expand(
                "how does AI work?",
                List.of(),
                List.of(fixture.documentId())
        );

        assertThat(embedded.degradationCode()).isEqualTo("GRAPH_NO_SEED");
        assertThat(exact.used()).isTrue();
    }

    private void writeReport(
            LocalGraphRetrievalService.Expansion expansion,
            LocalGraphRetrievalService.Expansion stale
    ) throws Exception {
        Path output = Path.of(
                "target",
                "phase9-reports",
                "local-graph-integration.json"
        );
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(
                output.toFile(),
                Map.of(
                        "phase", "9",
                        "datasetVersion", "graph-local-golden-v1",
                        "datasetStatus", "PUBLIC_CANDIDATES_SELECTED",
                        "generatedAt", Instant.now().toString(),
                        "graphProfileVersion", expansion.profile().version(),
                        "checks", Map.of(
                                "authorizedPathResolved", expansion.used(),
                                "pathCount", expansion.edgeCount(),
                                "supportingChildCount",
                                expansion.candidates().size(),
                                "staleAclRejected",
                                "GRAPH_NO_SEED".equals(
                                        stale.degradationCode()
                                )
                        ),
                        "qualityMetrics", "NOT_MEASURED",
                        "performanceMetrics", "NOT_MEASURED"
                )
        );
    }

    private Fixture fixture() {
        UserEntity owner = users.saveAndFlush(new UserEntity(
                "graph-phase9-" + UUID.randomUUID(),
                "test-password-hash",
                UserRole.ADMIN
        ));
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID seedChildId = UUID.randomUUID();
        UUID evidenceChildId = UUID.randomUUID();
        UUID seedSpanId = UUID.randomUUID();
        UUID evidenceSpanId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        UUID seedEntityId = UUID.randomUUID();
        UUID targetEntityId = UUID.randomUUID();
        UUID mentionId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO documents (
                    id, owner_user_id, title, visibility
                ) VALUES (?, ?, 'Phase 9 Graph fixture', 'ALL_USERS')
                """,
                documentId,
                owner.getId()
        );
        jdbc.update(
                """
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash,
                    source_object_key, status, original_filename,
                    file_size_bytes, media_type
                ) VALUES (?, ?, 1, ?, ?, 'READY', 'phase9.pdf',
                          100, 'application/pdf')
                """,
                revisionId,
                documentId,
                HASH,
                "phase9/" + revisionId + ".pdf"
        );
        jdbc.update(
                "UPDATE documents SET current_revision_id = ? WHERE id = ?",
                revisionId,
                documentId
        );
        insertChunk(parentId, documentId, revisionId, null, "PARENT", 0,
                "种子实体依赖目标实体。", false);
        insertChunk(seedChildId, documentId, revisionId, parentId, "CHILD", 0,
                "种子实体", true);
        insertChunk(evidenceChildId, documentId, revisionId, parentId, "CHILD", 1,
                "种子实体依赖目标实体。", true);
        jdbc.update(
                """
                INSERT INTO source_units (
                    id, document_id, revision_id, unit_order, unit_kind,
                    stable_address, canonical_text, canonical_text_hash,
                    normalization_version, label_metadata
                ) VALUES (
                    ?, ?, ?, 1, 'PAGE', 'page:1',
                    '种子实体依赖目标实体。目标实体关联第二目标。', ?,
                    'utf16-v1',
                    '{"pageNumber":1,"sourceLabel":"第 1 页"}'::jsonb
                )
                """,
                sourceUnitId,
                documentId,
                revisionId,
                HASH
        );
        insertSpan(
                seedSpanId, seedChildId, documentId, revisionId,
                sourceUnitId, 0, 4
        );
        insertSpan(
                evidenceSpanId, evidenceChildId, documentId, revisionId,
                sourceUnitId, 0, 11
        );

        String configVersion = "phase9-integration-fixture-v1";
        jdbc.update(
                """
                INSERT INTO graph_configs (
                    version, extraction_model, extraction_revision,
                    prompt_version, schema_version, normalization_version,
                    resolution_rule_set_version, community_algorithm,
                    community_algorithm_version, community_seed,
                    community_resolution, reason
                ) VALUES (
                    ?, 'fixture-model', 'fixture-revision',
                    'phase8-extraction-prompt-v1', 'phase8-graph-schema-v1',
                    'phase8-normalization-v1', 'phase8-baseline-rules-v1',
                    'leiden', 'fixture-v1', 42, 1,
                    'Phase 9 integration fixture'
                )
                ON CONFLICT (version) DO NOTHING
                """,
                configVersion
        );
        Long generation = jdbc.queryForObject(
                """
                INSERT INTO graph_manifests (
                    id, graph_config_version, status,
                    expected_document_count, projected_document_count,
                    entity_count, mention_count, relationship_count,
                    relationship_evidence_count, build_reason
                ) VALUES (?, ?, 'ACTIVE', 1, 1, 2, 2, 1, 1,
                          'Phase 9 integration fixture')
                RETURNING graph_generation
                """,
                Long.class,
                UUID.randomUUID(),
                configVersion
        );
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO graph_publication_events (
                    graph_generation, action, reason
                ) VALUES (?, 'PUBLISH', 'Phase 9 integration fixture')
                RETURNING id
                """,
                Long.class,
                generation
        );
        jdbc.update(
                """
                INSERT INTO graph_publications (
                    singleton_id, graph_generation, publication_event_id
                ) VALUES (1, ?, ?)
                """,
                generation,
                eventId
        );
        jdbc.update(
                """
                INSERT INTO graph_generation_sources (
                    graph_generation, document_id, revision_id,
                    acl_version, document_title
                ) VALUES (?, ?, ?, 1, 'Phase 9 Graph fixture')
                """,
                generation,
                documentId,
                revisionId
        );
        jdbc.update(
                """
                INSERT INTO graph_projection_states (
                    graph_generation, document_id, revision_id, acl_version,
                    state, input_hash, artifact_ids
                ) VALUES (?, ?, ?, 1, 'PROJECTED', ?, '[]'::jsonb)
                """,
                generation,
                documentId,
                revisionId,
                HASH
        );
        jdbc.update(
                """
                INSERT INTO graph_entities (
                    id, graph_generation, canonical_name,
                    normalized_name, entity_type
                ) VALUES
                    (?, ?, '种子实体', '种子实体', 'CONCEPT'),
                    (?, ?, '目标实体', '目标实体', 'CONCEPT')
                """,
                seedEntityId,
                generation,
                targetEntityId,
                generation
        );
        jdbc.update(
                """
                INSERT INTO graph_entity_mentions (
                    id, graph_generation, entity_id, document_id, revision_id,
                    parent_chunk_id, child_chunk_id, source_span_id,
                    surface_text, start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '种子实体', 0, 4)
                """,
                mentionId,
                generation,
                seedEntityId,
                documentId,
                revisionId,
                parentId,
                seedChildId,
                seedSpanId
        );
        jdbc.update(
                """
                INSERT INTO graph_relationships (
                    id, graph_generation, source_entity_id,
                    target_entity_id, relationship_type
                ) VALUES (?, ?, ?, ?, 'DEPENDS_ON')
                """,
                relationshipId,
                generation,
                seedEntityId,
                targetEntityId
        );
        jdbc.update(
                """
                INSERT INTO graph_relationship_evidence (
                    id, graph_generation, relationship_id, document_id,
                    revision_id, parent_chunk_id, child_chunk_id,
                    source_span_id, evidence_text, evidence_text_hash,
                    start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                          '种子实体依赖目标实体。', ?, 0, 11)
                """,
                UUID.randomUUID(),
                generation,
                relationshipId,
                documentId,
                revisionId,
                parentId,
                evidenceChildId,
                evidenceSpanId,
                HASH
        );
        jdbc.update(
                """
                INSERT INTO graph_adjacency (
                    graph_generation, source_entity_id, target_entity_id,
                    relationship_id, direction
                ) VALUES (?, ?, ?, ?, 'OUT')
                """,
                generation,
                seedEntityId,
                targetEntityId,
                relationshipId
        );
        return new Fixture(
                documentId,
                revisionId,
                sourceUnitId,
                parentId,
                seedChildId,
                evidenceChildId,
                evidenceSpanId,
                generation,
                seedEntityId,
                targetEntityId,
                relationshipId,
                mentionId
        );
    }

    private void insertChunk(
            UUID id,
            UUID documentId,
            UUID revisionId,
            UUID parentId,
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
                id,
                documentId,
                revisionId,
                parentId,
                type,
                order,
                text,
                text.length(),
                text.length(),
                HASH,
                searchable
        );
    }

    private void insertSpan(
            UUID id,
            UUID childId,
            UUID documentId,
            UUID revisionId,
            UUID sourceUnitId,
            int startOffset,
            int endOffset
    ) {
        jdbc.update(
                """
                INSERT INTO source_spans (
                    id, chunk_id, document_id, revision_id, span_order,
                    locator_kind, start_source_unit_id, end_source_unit_id,
                    start_offset, end_offset, source_text_hash,
                    chunk_start_offset, chunk_end_offset, locator_address,
                    normalization_version
                ) VALUES (
                    ?, ?, ?, ?, 0, 'PAGE', ?, ?, ?, ?, ?, ?, ?,
                    '{"kind":"PAGE","startPage":1,"endPage":1}'::jsonb,
                    'utf16-v1'
                )
                """,
                id,
                childId,
                documentId,
                revisionId,
                sourceUnitId,
                sourceUnitId,
                startOffset,
                endOffset,
                HASH,
                startOffset,
                endOffset
        );
    }

    private void createProfile(String version, UUID actorId) {
        configurations.create(new CreateProfileRequest(
                version,
                5,
                2,
                20,
                40,
                30,
                1.0,
                900,
                15,
                500,
                "Phase 9 publication test",
                "CREATE"
        ), actorId);
    }

    private void insertProfile(String version) {
        jdbc.update(
                """
                INSERT INTO graph_retrieval_profiles (
                    version, seed_limit, max_hops, entity_limit,
                    edge_limit, graph_child_limit, graph_weight,
                    graph_context_token_budget, graph_context_percent,
                    statement_timeout_ms, reason
                ) VALUES (?, 5, 2, 20, 40, 30, 1, 900, 15, 500,
                          'Phase 9 constraint test')
                """,
                version
        );
    }

    private ReleaseProfileRequest release(
            String version,
            String confirmation
    ) {
        return new ReleaseProfileRequest(
                version,
                "Phase 9 publication test",
                confirmation
        );
    }

    private record Fixture(
            UUID documentId,
            UUID revisionId,
            UUID sourceUnitId,
            UUID parentId,
            UUID seedChildId,
            UUID evidenceChildId,
            UUID evidenceSpanId,
            long graphGeneration,
            UUID seedEntityId,
            UUID targetEntityId,
            UUID relationshipId,
            UUID mentionId
    ) {
    }
}
