package com.example.rag.graph;

import com.example.rag.graph.GraphBuildContracts.ClaimedGeneration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
        "spring.main.lazy-initialization=true",
        "rag.graph.worker-enabled=false",
        "rag.graph.global-worker-enabled=false"
})
@Transactional
class GenerationRecoveryIntegrationTests {

    private static final String GRAPH_CONFIG = "phase18c-recovery-graph";
    private static final String GLOBAL_CONFIG = "phase18c-recovery-global";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GraphGenerationRepository graphs;
    @Autowired private GlobalGraphRepository globals;

    @Test
    void graphAndGlobalInterruptedWorkersAreFencedAfterLeaseTakeover() {
        jdbc.update("DELETE FROM global_graph_manifests WHERE status = 'BUILDING'");
        jdbc.update("DELETE FROM graph_manifests WHERE status = 'BUILDING'");
        insertConfigs();

        var graph = graphs.start(
                GRAPH_CONFIG,
                "Phase 18C Graph interruption recovery",
                null
        );
        ClaimedGeneration firstGraph = graphs.claim().orElseThrow();
        expire("graph_manifests", "graph_generation", graph.generation());
        ClaimedGeneration reclaimedGraph = graphs.claim().orElseThrow();

        assertThat(reclaimedGraph.attempt()).isEqualTo(firstGraph.attempt() + 1);
        graphs.fail(firstGraph, "STALE_WORKER", "must not commit");
        var graphRow = graphs.manifest(graph.generation());
        assertThat(graphRow.status()).isEqualTo("BUILDING");
        assertThat(graphRow.attempt()).isEqualTo(reclaimedGraph.attempt());
        assertThat(graphRow.heartbeatAt()).isNotNull();
        assertThat(graphRow.leaseExpiresAt()).isNotNull();

        long globalGeneration = insertGlobalBuild(graph.generation());
        GlobalGraphContracts.ClaimedGeneration firstGlobal =
                globals.claim().orElseThrow();
        expire(
                "global_graph_manifests",
                "global_generation",
                globalGeneration
        );
        GlobalGraphContracts.ClaimedGeneration reclaimedGlobal =
                globals.claim().orElseThrow();

        assertThat(reclaimedGlobal.attempt())
                .isEqualTo(firstGlobal.attempt() + 1);
        assertThat(reclaimedGlobal.leaseOwner())
                .isNotEqualTo(firstGlobal.leaseOwner());
        globals.fail(firstGlobal, "STALE_WORKER", "must not commit");
        var globalRow = globals.manifest(globalGeneration);
        assertThat(globalRow.status()).isEqualTo("BUILDING");
        assertThat(globalRow.attempt()).isEqualTo(reclaimedGlobal.attempt());
        assertThat(globalRow.heartbeatAt()).isNotNull();
        assertThat(globalRow.leaseExpiresAt()).isNotNull();
    }

    @Test
    void deletedGlobalManifestStillPinsItsSourceGraphGeneration() {
        insertConfigs();

        long pinned = insertGraphManifest("RETIRED", true);
        long removable = insertGraphManifest("RETIRED", true);
        insertGraphManifest("READY", false);
        insertGraphManifest("READY", false);
        insertGlobalManifest(pinned, "DELETED");

        graphs.cleanupExpired();

        assertThat(graphs.manifest(pinned).status()).isEqualTo("RETIRED");
        assertThat(graphs.manifest(removable).status()).isEqualTo("DELETED");
    }

    @Test
    void graphPublicationRunsItsFinalClosureInsideTheLockedBoundary() {
        insertConfigs();
        long graphGeneration = insertGraphManifest("READY", false);

        assertThat(graphs.release(
                graphGeneration,
                "READY",
                "PUBLISH",
                "Phase 18 P1 final Graph closure",
                null
        ).status()).isEqualTo("ACTIVE");

    }

    private long insertGlobalBuild(long sourceGraphGeneration) {
        Long generation = jdbc.queryForObject(
                """
                INSERT INTO global_graph_manifests (
                    id, global_config_version, source_graph_generation,
                    index_name, status, source_set_hash,
                    expected_source_count, build_reason
                ) VALUES (?, ?, ?, ?,
                          'BUILDING', ?, 0, ?)
                RETURNING global_generation
                """,
                Long.class,
                UUID.randomUUID(),
                GLOBAL_CONFIG,
                sourceGraphGeneration,
                "phase18c-recovery-" + UUID.randomUUID(),
                "a".repeat(64),
                "Phase 18C Global interruption recovery"
        );
        return generation == null ? 0 : generation;
    }

    private long insertGraphManifest(String status, boolean expired) {
        Long generation = jdbc.queryForObject(
                """
                INSERT INTO graph_manifests (
                    id, graph_config_version, status,
                    expected_document_count, build_reason,
                    retention_until
                ) VALUES (?, ?, ?, 0, ?,
                          CASE WHEN ? THEN CURRENT_TIMESTAMP - INTERVAL '1 second'
                               ELSE NULL END)
                RETURNING graph_generation
                """,
                Long.class,
                UUID.randomUUID(),
                GRAPH_CONFIG,
                status,
                "Phase 18C dependency-aware retention fixture",
                expired
        );
        return generation == null ? 0 : generation;
    }

    private void insertGlobalManifest(long sourceGraphGeneration, String status) {
        jdbc.update(
                """
                INSERT INTO global_graph_manifests (
                    id, global_config_version, source_graph_generation,
                    index_name, status, source_set_hash,
                    expected_source_count, build_reason
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                """,
                UUID.randomUUID(),
                GLOBAL_CONFIG,
                sourceGraphGeneration,
                "phase18c-retention-" + UUID.randomUUID(),
                status,
                "b".repeat(64),
                "Phase 18C dependency-aware retention fixture"
        );
    }

    private void insertConfigs() {
        jdbc.update(
                """
                INSERT INTO graph_configs (
                    version, extraction_model, extraction_revision,
                    prompt_version, schema_version, normalization_version,
                    resolution_rule_set_version, community_algorithm,
                    community_algorithm_version, community_seed,
                    community_resolution, reason
                ) VALUES (?, 'recovery-model', 'recovery-revision',
                          'recovery-prompt', 'recovery-schema',
                          'source-locator-v1', 'phase8-baseline-rules-v1',
                          'LEIDEN', '0.10.2', 42, 1.0, ?)
                """,
                GRAPH_CONFIG,
                "Phase 18C Graph recovery fixture"
        );
        jdbc.update(
                """
                INSERT INTO global_graph_configs (
                    version, report_model, report_revision,
                    prompt_version, schema_version,
                    community_algorithm, community_algorithm_version,
                    community_seed, community_resolution,
                    index_config_version, bm25_top_k, vector_top_k,
                    rrf_rank_constant, report_limit,
                    context_token_budget, map_call_limit,
                    model_call_limit, hard_timeout_ms,
                    statement_timeout_ms, reason
                ) VALUES (?, 'recovery-model', 'recovery-revision',
                          'recovery-prompt', 'recovery-schema',
                          'LEIDEN', '0.10.2', 42, 1.0,
                          'phase15a-hybrid-qwen3-source-locator-v1',
                          10, 10, 60, 8, 900, 1, 2, 30000, 500, ?)
                """,
                GLOBAL_CONFIG,
                "Phase 18C Global recovery fixture"
        );
    }

    private void expire(String table, String generationColumn, long generation) {
        jdbc.update(
                "UPDATE %s SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE %s = ?"
                        .formatted(table, generationColumn),
                generation
        );
    }
}
