package com.example.rag.search;

import com.example.rag.search.IndexGenerationContracts.StartIndexBuildRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rag.search.enabled=true",
                "rag.search.projection-delay-ms=3600000"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_PHASE7A_BENCHMARK", matches = "true")
class Phase7aEmbeddingCacheBenchmarkTests {

    private static final int DOCUMENTS = 10;
    private static final int CHILDREN_PER_DOCUMENT = 1_000;
    private static final long CHILDREN = (long) DOCUMENTS * CHILDREN_PER_DOCUMENT;
    private static final long PHASE6B_CAPACITY_CHUNKS = 50_000;
    private static final long PHASE6B_CAPACITY_BASELINE_MS = 752_685;
    private static final String HASH = "b".repeat(64);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private SearchProperties properties;
    @Autowired private SearchIndexService indexes;
    @Autowired private IndexGenerationService generations;
    @Autowired private OpenSearchGateway openSearch;
    @Autowired private EmbeddingCacheService embeddingCache;

    @AfterEach
    void cleanUp() {
        reset();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void coldAndWarmBuildsKeepThePreviousGenerationActiveAndMeetCacheGates()
            throws Exception {
        reset();
        seedCorpus();
        SearchIndexService.Manifest previous = indexes.bootstrapActiveIndex();
        properties.setGenerationWorkerEnabled(true);

        var before = embeddingCache.stats();
        BuiltGeneration cold = build("10,000 Child cold Embedding benchmark");
        var afterCold = embeddingCache.stats();
        BuiltGeneration warm = build("10,000 Child warm Embedding cache benchmark");
        var afterWarm = embeddingCache.stats();

        assertReady(cold.ready());
        assertReady(warm.ready());
        assertThat(generations.generations().activeGeneration())
                .isEqualTo(previous.generation());
        assertThat(openSearch.aliasPointsTo(
                properties.getIndexAlias(), previous.indexName()
        )).isTrue();
        long warmHits = afterWarm.artifacts().hits() - afterCold.artifacts().hits();
        long warmMisses = afterWarm.artifacts().misses() - afterCold.artifacts().misses();
        long warmModelCalls =
                afterWarm.artifacts().modelCalls() - afterCold.artifacts().modelCalls();
        long coldModelCalls =
                afterCold.artifacts().modelCalls() - before.artifacts().modelCalls();
        double warmHitRate = (double) warmHits / (warmHits + warmMisses);
        double speedup = 1.0 - (double) warm.elapsed().toMillis() / cold.elapsed().toMillis();

        assertThat(coldModelCalls).isPositive();
        assertThat(warmModelCalls).isZero();
        assertThat(warmHitRate).isGreaterThanOrEqualTo(0.99);
        assertThat(speedup).isGreaterThanOrEqualTo(0.70);

        writeReport(
                previous,
                cold,
                warm,
                coldModelCalls,
                warmModelCalls,
                warmHits,
                warmMisses,
                warmHitRate,
                speedup
        );
    }

    private BuiltGeneration build(String reason) {
        var requested = generations.start(
                new StartIndexBuildRequest(
                        "phase6-hybrid-qwen3-v1",
                        "BUILD",
                        reason
                ),
                null
        );
        long started = System.nanoTime();
        IndexGenerationService.ClaimedGeneration claim =
                generations.claim().orElseThrow();
        generations.build(claim);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        var ready = generations.generations().generations().stream()
                .filter(item -> item.id().equals(requested.id()))
                .findFirst()
                .orElseThrow();
        return new BuiltGeneration(ready, elapsed);
    }

    private static void assertReady(
            IndexGenerationContracts.IndexGenerationView ready
    ) {
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.expectedChunkCount()).isEqualTo(CHILDREN);
        assertThat(ready.indexedChunkCount()).isEqualTo(CHILDREN);
        assertThat(ready.validVectorCount()).isEqualTo(CHILDREN);
        assertThat(ready.vectorCoverage()).isEqualTo(1.0);
    }

    private void seedCorpus() {
        UUID owner = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO users (id, username, password_hash, role)
                VALUES (?, 'phase6b-benchmark', 'benchmark-only', 'ADMIN')
                """,
                owner
        );
        for (int documentNumber = 0; documentNumber < DOCUMENTS; documentNumber++) {
            UUID documentId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID parentId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO documents (
                        id, owner_user_id, title, visibility, acl_version
                    ) VALUES (?, ?, ?, 'ALL_USERS', 1)
                    """,
                    documentId,
                    owner,
                    "Phase 6B benchmark document " + documentNumber
            );
            jdbc.update(
                    """
                    INSERT INTO document_revisions (
                        id, document_id, revision_number, content_hash,
                        source_object_key, status, parser_version,
                        original_filename, file_size_bytes, media_type
                    ) VALUES (?, ?, 1, ?, ?, 'READY', 'benchmark-parser',
                              ?, 128, 'application/pdf')
                    """,
                    revisionId,
                    documentId,
                    HASH,
                    "benchmark/" + revisionId + ".pdf",
                    "benchmark-" + documentNumber + ".pdf"
            );
            String parentText = "Phase 6B benchmark parent " + documentNumber;
            jdbc.update(
                    """
                    INSERT INTO chunks (
                        id, document_id, revision_id, parent_chunk_id,
                        chunk_type, chunk_order, text, heading_path,
                        start_block_order, end_block_order, character_count,
                        token_count, token_counter_version,
                        chunking_profile_version, parser_version,
                        chunker_version, content_hash, searchable
                    ) VALUES (?, ?, ?, NULL, 'PARENT', 0, ?, 'Benchmark',
                              0, 0, char_length(?), 8, 'unicode-codepoint-v1',
                              'phase4-v1', 'benchmark-parser',
                              'benchmark-chunker', ?, FALSE)
                    """,
                    parentId,
                    documentId,
                    revisionId,
                    parentText,
                    parentText,
                    HASH
            );
            jdbc.update(
                    """
                    INSERT INTO chunks (
                        id, document_id, revision_id, parent_chunk_id,
                        chunk_type, chunk_order, text, heading_path,
                        start_block_order, end_block_order, character_count,
                        token_count, token_counter_version,
                        chunking_profile_version, parser_version,
                        chunker_version, content_hash, searchable
                    )
                    SELECT gen_random_uuid(), ?, ?, ?, 'CHILD', item,
                           format(
                               '企业级检索 benchmark document %s passage %s '
                               'hybrid semantic retrieval access control',
                               ?, item
                           ),
                           'Benchmark', 0, 0,
                           char_length(format(
                               '企业级检索 benchmark document %s passage %s '
                               'hybrid semantic retrieval access control',
                               ?, item
                           )),
                           24, 'unicode-codepoint-v1', 'phase4-v1',
                           'benchmark-parser', 'benchmark-chunker', ?, TRUE
                    FROM generate_series(0, ?) AS item
                    """,
                    documentId,
                    revisionId,
                    parentId,
                    documentNumber,
                    documentNumber,
                    HASH,
                    CHILDREN_PER_DOCUMENT - 1
            );
            jdbc.update(
                    """
                    UPDATE documents
                    SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    revisionId,
                    documentId
            );
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE chunk_type = 'CHILD'",
                Long.class
        )).isEqualTo(CHILDREN);
    }

    private void writeReport(
            SearchIndexService.Manifest previous,
            BuiltGeneration cold,
            BuiltGeneration warm,
            long coldModelCalls,
            long warmModelCalls,
            long warmHits,
            long warmMisses,
            double warmHitRate,
            double speedup
    ) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("documents", DOCUMENTS);
        report.put("childChunks", CHILDREN);
        report.put("embeddingModel", "Qwen/Qwen3-Embedding-0.6B");
        report.put(
                "embeddingRevision",
                "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"
        );
        report.put("dimensions", 1024);
        report.put("indexConfigVersion", cold.ready().indexConfigVersion());
        report.put("previousActiveGeneration", previous.generation());
        report.put("coldReadyGeneration", cold.ready().indexGeneration());
        report.put("warmReadyGeneration", warm.ready().indexGeneration());
        report.put("coldElapsedMs", cold.elapsed().toMillis());
        report.put("warmElapsedMs", warm.elapsed().toMillis());
        report.put("phase6bCapacityChunks", PHASE6B_CAPACITY_CHUNKS);
        report.put("phase6bCapacityBaselineMs", PHASE6B_CAPACITY_BASELINE_MS);
        long normalizedBaseline = Math.round(
                (double) PHASE6B_CAPACITY_BASELINE_MS
                        * CHILDREN / PHASE6B_CAPACITY_CHUNKS
        );
        report.put("normalizedColdBaselineMs", normalizedBaseline);
        report.put(
                "coldDeltaPercentInformational",
                (double) (cold.elapsed().toMillis() - normalizedBaseline)
                        / normalizedBaseline * 100.0
        );
        report.put("coldModelCalls", coldModelCalls);
        report.put("warmModelCalls", warmModelCalls);
        report.put("warmArtifactHits", warmHits);
        report.put("warmArtifactMisses", warmMisses);
        report.put("warmHitRate", warmHitRate);
        report.put("speedup", speedup);
        report.put("validVectorCoverage", warm.ready().vectorCoverage());
        report.put("aliasStayedOnPreviousGeneration", true);
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());

        Path output = Path.of(
                "target", "phase7a-reports", "embedding-cache-10000.json"
        );
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private void reset() {
        properties.setGenerationWorkerEnabled(false);
        jdbc.queryForList("SELECT index_name FROM index_manifests", String.class)
                .forEach(openSearch::deleteIndex);
        jdbc.execute(
                """
                TRUNCATE TABLE embedding_cache_events, embedding_artifacts,
                    search_projection_states, index_manifests,
                    source_spans, chunks, content_blocks, parsed_documents,
                    pipeline_jobs, document_acl_entries, document_revisions,
                    documents
                CASCADE
                """
        );
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO retrieval_publication_events (
                    profile_version, action, reason
                ) VALUES (
                    'phase5-bm25-v1', 'MIGRATION',
                    'Phase 6B benchmark reset'
                )
                RETURNING id
                """,
                Long.class
        );
        jdbc.update(
                """
                INSERT INTO retrieval_publications (
                    singleton_id, profile_version, publication_event_id
                ) VALUES (1, 'phase5-bm25-v1', ?)
                """,
                eventId
        );
    }

    private record BuiltGeneration(
            IndexGenerationContracts.IndexGenerationView ready,
            Duration elapsed
    ) {
    }
}
