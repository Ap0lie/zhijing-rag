package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.document.StorageProperties;
import com.example.rag.graph.GraphRetrievalContracts.ProfileView;
import com.example.rag.graph.LocalGraphRetrievalService;
import com.example.rag.graph.LocalGraphRetrievalService.Expansion;
import com.example.rag.graph.LocalGraphRetrievalService.ShadowSeedDiagnostics;
import com.example.rag.persistence.PipelineStage;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.pipeline.PipelineProperties;
import com.example.rag.projection.ProjectionClosureStatus;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rag.search.enabled=true",
        "rag.search.projection-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchIntegrationTests {

    private static final String HASH = "a".repeat(64);
    private static final String PROFILE = "phase4-v1";
    private static final long INDEX_COORDINATION_LOCK = 0x5241475F494E4458L;
    private static final int CANDIDATE_DEPTH = 50;
    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 1;
    private static final long QUERY_TIMEOUT_MS = 5_000;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SearchIndexService indexes;
    @Autowired private SearchDocumentProjector projector;
    @Autowired private SearchProperties searchProperties;
    @Autowired private IndexGenerationService generations;
    @Autowired private SearchService searchService;
    @Autowired private EmbeddingCacheService embeddingCache;
    @Autowired private StorageProperties storageProperties;
    @Autowired private PipelineProperties pipelineProperties;
    @Autowired private DataSource dataSource;
    @Autowired private ModelCircuitBreakers circuits;

    @MockitoSpyBean
    private OpenSearchGateway openSearch;

    @MockitoBean
    private EmbeddingProvider embeddings;

    @MockitoBean
    private RerankProvider reranker;

    @MockitoSpyBean
    private EvidenceContextService contexts;

    @MockitoSpyBean
    private SearchAccessService access;

    @MockitoSpyBean
    private LocalGraphRetrievalService graphs;

    private UserEntity admin;
    private UserEntity reader;
    private UserEntity outsider;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        resetDedicatedTestState();
        embeddingCache.clearQueryCache();
        when(embeddings.descriptor()).thenReturn(new ModelDescriptor(
                true,
                "Qwen/Qwen3-Embedding-0.6B",
                "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3",
                1024
        ));
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(SearchIntegrationTests::testVector).toList();
        });
        when(reranker.descriptor()).thenReturn(new ModelDescriptor(
                true,
                "Qwen/Qwen3-Reranker-0.6B",
                "e61197ed45024b0ed8a2d74b80b4d909f1255473",
                null
        ));
        when(reranker.rerank(anyString(), anyList())).thenAnswer(invocation ->
                rankedScores(invocation.getArgument(1))
        );
        circuits.reset();
        admin = createUser("search-admin", UserRole.ADMIN);
        reader = createUser("search-reader", UserRole.USER);
        outsider = createUser("search-outsider", UserRole.USER);
        fixture = createFixture();
    }

    @AfterEach
    void tearDown() {
        embeddingCache.clearQueryCache();
        reset(openSearch);
        reset(embeddings);
        reset(reranker);
        reset(contexts);
        reset(access);
        reset(graphs);
        circuits.reset();
        searchProperties.setGenerationWorkerEnabled(false);
        resetDedicatedTestState();
    }

    @Test
    void vectorGenerationRequiresCompleteCoverageAndPublishesOrRollsBackExplicitly()
            throws Exception {
        SearchIndexService.Manifest initial = indexes.bootstrapActiveIndex();
        searchProperties.setGenerationWorkerEnabled(true);

        var started = generations.start(
                new IndexGenerationContracts.StartIndexBuildRequest(
                        "phase15a-hybrid-qwen3-source-locator-v1",
                        "BUILD",
                        "Phase 6B integration verification"
                ),
                admin.getId()
        );
        assertThat(started.status()).isEqualTo("BUILDING");
        assertThat(generations.generations().activeGeneration())
                .isEqualTo(initial.generation());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE index_manifests SET status = 'READY' WHERE id = ?",
                started.id()
        )).isInstanceOf(DataAccessException.class);

        IndexGenerationService.ClaimedGeneration claim =
                generations.claim().orElseThrow();
        generations.build(claim);

        var ready = generations.generations().generations().stream()
                .filter(item -> item.id().equals(started.id()))
                .findFirst()
                .orElseThrow();
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.indexedChunkCount()).isEqualTo(3);
        assertThat(ready.validVectorCount()).isEqualTo(3);
        assertThat(ready.vectorCoverage()).isEqualTo(1.0);
        assertThat(ready.readyCheckPassed()).isTrue();
        assertThat(openSearch.aliasPointsTo(
                searchProperties.getIndexAlias(), initial.indexName()
        )).isTrue();

        var active = generations.publish(
                new IndexGenerationContracts.PublishGenerationRequest(
                        ready.indexGeneration(),
                        "phase6-hybrid-rrf-v1",
                        "PUBLISH",
                        "Hybrid retrieval integration verification"
                ),
                admin.getId()
        );
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(generations.generations().activeGeneration())
                .isEqualTo(ready.indexGeneration());
        assertThat(openSearch.aliasPointsTo(
                searchProperties.getIndexAlias(), ready.indexName()
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT profile_version FROM retrieval_publications WHERE singleton_id = 1",
                String.class
        )).isEqualTo("phase6-hybrid-rrf-v1");

        JsonNode hybrid = search(reader, "traceable evidence");
        assertThat(hybrid.path("modeRequested").asText()).isEqualTo("HYBRID");
        assertThat(hybrid.path("modeUsed").asText()).isEqualTo("HYBRID");
        assertThat(hybrid.path("degraded").asBoolean()).isFalse();
        assertThat(hybrid.path("items").isEmpty()).isFalse();
        JsonNode outsiderResults = search(outsider, "restrictedaccesssentinel");
        assertThat(outsiderResults.path("items"))
                .noneMatch(item -> fixture.restrictedDocumentId().toString()
                        .equals(item.path("documentId").asText()));
        assertThat(outsiderResults.path("items").toString())
                .doesNotContain("restrictedaccesssentinel");

        var rolledBack = generations.rollback(
                new IndexGenerationContracts.RollbackGenerationRequest(
                        initial.generation(),
                        "phase5-bm25-v1",
                        "ROLLBACK",
                        "Rollback integration verification"
                ),
                admin.getId()
        );
        assertThat(rolledBack.status()).isEqualTo("ACTIVE");
        assertThat(generations.generations().activeGeneration())
                .isEqualTo(initial.generation());
        assertThat(openSearch.aliasPointsTo(
                searchProperties.getIndexAlias(), initial.indexName()
        )).isTrue();
        assertThat(search(reader, "traceable evidence").path("modeUsed").asText())
                .isEqualTo("BM25");
    }

    @Test
    void interruptedIndexBuildIsReclaimedAndTheStaleAttemptCannotCommit() {
        indexes.bootstrapActiveIndex();
        searchProperties.setGenerationWorkerEnabled(true);
        var started = generations.start(
                new IndexGenerationContracts.StartIndexBuildRequest(
                        "phase15a-hybrid-qwen3-source-locator-v1",
                        "BUILD",
                        "Phase 18C Index interruption recovery"
                ),
                admin.getId()
        );

        IndexGenerationService.ClaimedGeneration first =
                generations.claim().orElseThrow();
        jdbc.update(
                """
                UPDATE index_manifests
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                started.id()
        );
        IndexGenerationService.ClaimedGeneration reclaimed =
                generations.claim().orElseThrow();

        assertThat(reclaimed.attempt()).isEqualTo(first.attempt() + 1);
        generations.fail(first, new IllegalStateException("stale worker"));
        var running = generation(started.id());
        assertThat(running.status()).isEqualTo("BUILDING");
        assertThat(running.buildAttempt()).isEqualTo(reclaimed.attempt());
        assertThat(running.recovery().state()).isEqualTo("RUNNING");

        generations.build(reclaimed);
        var ready = generation(started.id());
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.closure().ready()).isTrue();
        assertThat(ready.closure().formats())
                .extracting(
                        ProjectionClosureStatus.FormatCoverage::documentFormat
                )
                .containsExactly("PDF");
    }

    @Test
    void unchangedGenerationReusesEveryPersistedEmbeddingArtifact() {
        indexes.bootstrapActiveIndex();
        searchProperties.setGenerationWorkerEnabled(true);

        var cold = generations.start(
                new IndexGenerationContracts.StartIndexBuildRequest(
                        "phase15a-hybrid-qwen3-source-locator-v1",
                        "BUILD",
                        "Phase 7A cold generation"
                ),
                admin.getId()
        );
        generations.build(generations.claim().orElseThrow());
        assertThat(generation(cold.id()).status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM embedding_artifacts",
                Long.class
        )).isEqualTo(3);
        verify(embeddings, times(1)).embed(anyList());

        clearInvocations(embeddings);
        var warm = generations.start(
                new IndexGenerationContracts.StartIndexBuildRequest(
                        "phase15a-hybrid-qwen3-source-locator-v1",
                        "BUILD",
                        "Phase 7A warm generation"
                ),
                admin.getId()
        );
        generations.build(generations.claim().orElseThrow());

        var ready = generation(warm.id());
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.validVectorCount()).isEqualTo(3);
        assertThat(ready.vectorCoverage()).isEqualTo(1.0);
        verify(embeddings, times(0)).embed(anyList());
        assertThat(embeddingCache.stats().artifacts().hits()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void phase6cReranksAuthorizedChildrenAndReturnsBudgetedParentEvidence()
            throws Exception {
        publishPhase6c();
        clearInvocations(reranker);

        JsonNode response = search(outsider, "traceable evidence");

        assertThat(response.path("profileVersion").asText())
                .isEqualTo("phase6c-hybrid-rerank-v1");
        assertThat(response.path("modeUsed").asText()).isEqualTo("HYBRID");
        assertThat(response.path("items")).hasSizeBetween(1, 8);
        JsonNode first = response.path("items").get(0);
        assertThat(first.at("/evidence/rank").asInt()).isOne();
        assertThat(first.at("/evidence/childText").asText()).isNotBlank();
        assertThat(first.at("/evidence/parent/chunkId").asText()).isNotBlank();
        assertThat(first.at("/evidence/parent/contributedTokens").asInt())
                .isLessThanOrEqualTo(800);
        assertThat(first.path("chunkId").asText()).isNotEqualTo(
                first.at("/evidence/parent/chunkId").asText()
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(eq("traceable evidence"), documents.capture());
        assertThat(documents.getValue())
                .allSatisfy(text -> assertThat(text).doesNotContain("restrictedaccesssentinel"));

        MvcResult debugResult = mockMvc.perform(post("/api/v1/admin/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("traceable evidence"))
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode debug = json.readTree(debugResult.getResponse().getContentAsByteArray());
        assertThat(debug.path("stages"))
                .extracting(stage -> stage.path("name").asText())
                .containsExactly(
                        "BM25", "VECTOR", "ACL_REVISION_R1", "RRF",
                        "ACL_REVISION", "GLOBAL_REPORT", "GLOBAL_FUSION",
                        "GRAPH_SEED", "GRAPH_TRAVERSE", "GRAPH_FUSION",
                        "MEMORY_SEED", "RERANK", "EVIDENCE", "PARENT",
                        "ACL_FINAL"
                );
        assertThat(debug.at("/contextBudget/limitTokens").asInt()).isEqualTo(6_000);
        assertThat(debug.at("/contextBudget/totalTokens").asInt())
                .isLessThanOrEqualTo(6_000);
        assertThat(debug.path("candidates"))
                .allSatisfy(candidate ->
                        assertThat(candidate.path("result").isMissingNode()).isFalse()
                );
    }

    @Test
    void entityLinkShadowDelayDoesNotConsumeRetrievalBudgetOrChangeEvidence()
            throws Exception {
        publishPhase6c();
        String query = "traceable evidence";
        var active = indexes.activeIndex().orElseThrow();
        long graphGeneration = 987L;
        String graphProfileVersion = "entity-link-shadow-test-v1";
        ProfileView graphProfile = new ProfileView(
                graphProfileVersion,
                5,
                2,
                20,
                40,
                30,
                0.6,
                0,
                0,
                250,
                "Evaluation-only entity-link shadow",
                Instant.EPOCH
        );
        Expansion graphResult = new Expansion(
                graphProfile,
                graphGeneration,
                0,
                List.of(),
                List.of(),
                0,
                List.of(),
                "GRAPH_NO_SEED",
                1
        );
        doReturn(graphResult).when(graphs).expand(
                eq(query),
                anyList(),
                anyList(),
                eq(graphProfileVersion),
                eq(graphGeneration)
        );
        ShadowSeedDiagnostics shadow = ShadowSeedDiagnostics.measured(
                1,
                List.of(fixture.publicDocumentId()),
                1,
                List.of("ACRONYM")
        );
        doReturn(shadow).when(graphs).diagnoseEntityLinks(
                eq(query), anyList(), any(Expansion.class)
        );
        var target = new SearchService.EvaluationTarget(
                "phase6c-hybrid-rerank-v1",
                active.generation(),
                active.indexName(),
                active.indexConfigVersion(),
                graphProfileVersion,
                graphGeneration,
                null,
                null,
                SearchService.EvaluationFault.NONE
        );
        SearchContracts.SearchRequest request = new SearchContracts.SearchRequest(
                query, 0, 8, null, null, SearchContracts.GraphMode.LOCAL_GRAPH
        );

        var baseline = searchService.evaluate(
                request, principal(outsider), target
        );
        clearInvocations(reranker, contexts, graphs);
        doAnswer(invocation -> {
            Thread.sleep(250);
            return shadow;
        }).when(graphs).diagnoseEntityLinks(
                eq(query), anyList(), any(Expansion.class)
        );

        long wallStarted = System.nanoTime();
        var delayed = searchService.evaluate(
                request, principal(outsider), target
        );
        long wallMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - wallStarted
        );

        assertThat(delayed.result().items())
                .extracting(SearchContracts.SearchHit::chunkId)
                .containsExactlyElementsOf(baseline.result().items().stream()
                        .map(SearchContracts.SearchHit::chunkId)
                        .toList());
        assertThat(wallMs - delayed.result().tookMs())
                .isGreaterThanOrEqualTo(180);
        InOrder order = inOrder(reranker, contexts, graphs);
        order.verify(reranker).rerank(eq(query), anyList());
        order.verify(contexts).load(anyList());
        order.verify(graphs).diagnoseEntityLinks(
                eq(query), anyList(), any(Expansion.class)
        );

        ShadowSeedDiagnostics limited = ShadowSeedDiagnostics.unavailable(
                "GRAPH_ENTITY_LINK_SHADOW_MATCH_LIMIT"
        );
        doReturn(limited).when(graphs).diagnoseEntityLinks(
                eq(query), anyList(), any(Expansion.class)
        );
        var limitedResponse = searchService.evaluate(
                request, principal(outsider), target
        );
        assertThat(limitedResponse.result().items())
                .extracting(SearchContracts.SearchHit::chunkId)
                .containsExactlyElementsOf(baseline.result().items().stream()
                        .map(SearchContracts.SearchHit::chunkId)
                        .toList());
        assertThat(limitedResponse.graphDiagnostics().entityLinkShadow()
                .measured()).isFalse();
        assertThat(limitedResponse.graphDiagnostics().entityLinkShadow()
                .reasonCode()).isEqualTo(
                        "GRAPH_ENTITY_LINK_SHADOW_MATCH_LIMIT"
                );
    }

    @Test
    void finalAuthorizationRemovesEvidenceRevokedDuringRerank() throws Exception {
        publishPhase6c();
        when(reranker.rerank(eq("restrictedaccesssentinel"), anyList()))
                .thenAnswer(invocation -> {
                    jdbc.update(
                            "DELETE FROM document_acl_entries WHERE document_id = ? AND user_id = ?",
                            fixture.restrictedDocumentId(),
                            reader.getId()
                    );
                    jdbc.update("""
                            UPDATE documents
                            SET acl_version = acl_version + 1,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """, fixture.restrictedDocumentId());
                    return rankedScores(invocation.getArgument(1));
                });

        JsonNode response = search(reader, "restrictedaccesssentinel");

        assertThat(response.path("items"))
                .noneMatch(item -> fixture.restrictedDocumentId().toString()
                        .equals(item.path("documentId").asText()));
        assertThat(response.path("items").toString())
                .doesNotContain("restrictedaccesssentinel");
        verify(reranker).rerank(eq("restrictedaccesssentinel"), anyList());
    }

    @Test
    void rerankFailureFallsBackToRrfAndOpensCircuitWithoutRetrying() throws Exception {
        publishPhase6c();
        when(reranker.rerank(anyString(), anyList()))
                .thenThrow(new IllegalStateException("reranker unavailable"));

        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode response = search(outsider, "traceable evidence");
            assertThat(response.path("items")).isNotEmpty();
            assertThat(response.path("degraded").asBoolean()).isTrue();
            assertThat(response.path("degradationCode").asText())
                    .contains("RERANK_UNAVAILABLE");
        }
        JsonNode circuitOpen = search(outsider, "traceable evidence");
        assertThat(circuitOpen.path("items")).isNotEmpty();
        assertThat(circuitOpen.path("degradationCode").asText())
                .contains("RERANK_CIRCUIT_OPEN");
        verify(reranker, times(3)).rerank(anyString(), anyList());
    }

    @Test
    void parentFailureReturnsVerifiedChildEvidence() throws Exception {
        publishPhase6c();
        doThrow(new IllegalStateException("parent query unavailable"))
                .when(contexts).load(anyList());

        JsonNode response = search(outsider, "traceable evidence");

        assertThat(response.path("items")).isNotEmpty();
        assertThat(response.path("degradationCode").asText())
                .contains("PARENT_UNAVAILABLE");
        assertThat(response.at("/items/0/evidence/childText").asText()).isNotBlank();
        assertThat(response.at("/items/0/evidence/parent").isNull()).isTrue();
        SearchContracts.SearchDebugResponse debug = searchService.debug(
                new SearchContracts.SearchRequest(
                        "traceable evidence", 0, 20, null, null
                ),
                principal(outsider)
        );
        assertThat(debug.contextBudget().limitTokens()).isEqualTo(6_000);
        assertThat(debug.contextBudget().childTokens()).isPositive();
        assertThat(debug.contextBudget().parentTokens()).isZero();
    }

    @Test
    void authorizationRecheckFailureReturns503InsteadOfBypassingDatabase()
            throws Exception {
        indexes.bootstrapActiveIndex();
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(access)
                .authorizedByDocument(any(), any(), any());

        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("traceable evidence"))
                        .with(user(principal(reader)))
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_RECHECK_UNAVAILABLE"));
    }

    @Test
    void bilingualGoldenSetUsesOnlyChildChunksAndWritesLanguageReport() throws Exception {
        indexes.bootstrapActiveIndex();

        JsonNode dataset = json.readTree(new ClassPathResource(
                "retrieval-golden/v1/dataset.json"
        ).getInputStream());
        Map<String, UUID> documentsByKey = Map.of(
                "structured-two-page", fixture.publicDocumentId()
        );
        Map<String, List<Long>> latencies = new LinkedHashMap<>();
        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, Integer> errors = new LinkedHashMap<>();
        Map<String, Integer> timeouts = new LinkedHashMap<>();
        Map<String, Integer> warmupErrors = new LinkedHashMap<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        long peakHeapObserved = usedHeap();

        for (JsonNode query : dataset.path("queries")) {
            String language = query.path("language").asText();
            String expectedKey = query.path("expectedDocumentKey").asText();
            for (int run = 0; run < WARMUP_RUNS; run++) {
                try {
                    search(reader, query.path("query").asText(), CANDIDATE_DEPTH);
                } catch (Exception | AssertionError failure) {
                    warmupErrors.merge(language, 1, Integer::sum);
                }
            }
            peakHeapObserved = Math.max(peakHeapObserved, usedHeap());

            long started = System.nanoTime();
            JsonNode response = null;
            String error = "";
            try {
                response = search(reader, query.path("query").asText(), CANDIDATE_DEPTH);
            } catch (Exception | AssertionError failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
            long wallClockMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean timedOut = wallClockMs > QUERY_TIMEOUT_MS;
            peakHeapObserved = Math.max(peakHeapObserved, usedHeap());

            JsonNode matched = null;
            int rank = 0;
            int candidateRank = 0;
            if (response != null) {
                for (JsonNode item : response.path("items")) {
                    rank++;
                    if (documentsByKey.get(expectedKey).toString()
                            .equals(item.path("documentId").asText())
                            && contains(item.path("headingPath"), query.path("expectedHeading").asText())
                            && item.path("startPage").asInt() == query.path("expectedStartPage").asInt()
                            && item.path("endPage").asInt() == query.path("expectedEndPage").asInt()) {
                        matched = item;
                        candidateRank = rank;
                        break;
                    }
                }
            }
            boolean hit = matched != null && candidateRank <= CANDIDATE_DEPTH;

            totals.merge(language, 1, Integer::sum);
            hits.merge(language, hit ? 1 : 0, Integer::sum);
            errors.merge(language, error.isBlank() ? 0 : 1, Integer::sum);
            timeouts.merge(language, timedOut ? 1 : 0, Integer::sum);
            if (response != null) {
                latencies.computeIfAbsent(language, ignored -> new ArrayList<>())
                        .add(response.path("tookMs").asLong());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", query.path("id").asText());
            result.put("language", language);
            result.put("query", query.path("query").asText());
            result.put("candidateHitAt50", hit);
            result.put("candidateRank", candidateRank);
            result.put("returnedDocumentId", matched == null ? "" : matched.path("documentId").asText());
            result.put("expectedHeading", query.path("expectedHeading").asText());
            result.put("expectedStartPage", query.path("expectedStartPage").asInt());
            result.put("expectedEndPage", query.path("expectedEndPage").asInt());
            result.put("openSearchTookMs", response == null ? -1 : response.path("tookMs").asLong());
            result.put("wallClockMs", wallClockMs);
            result.put("timeout", timedOut);
            result.put("error", error);
            cases.add(result);
        }

        JsonNode parentOnly = search(reader, "parentuniquesentinel");
        assertThat(parentOnly.path("totalElements").asLong()).isZero();
        assertThat(rawDocumentCount(fixture.publicDocumentId())).isEqualTo(2);

        Path report = writeGoldenReport(
                dataset, totals, hits, errors, timeouts, warmupErrors,
                latencies, cases, peakHeapObserved
        );
        JsonNode written = json.readTree(report.toFile());
        assertThat(written.path("datasetVersion").asText()).isEqualTo("phase5-golden-v1");
        assertThat(written.at("/evaluation/queryCount").asInt()).isEqualTo(9);
        assertThat(written.at("/languages/zh/candidateHitAt50").asDouble()).isEqualTo(1.0);
        assertThat(written.at("/languages/en/candidateHitAt50").asDouble()).isEqualTo(1.0);
        assertThat(written.at("/languages/mixed/candidateHitAt50").asDouble()).isEqualTo(1.0);
        assertThat(written.at("/totals/hits").asInt()).isEqualTo(9);
        assertThat(written.at("/totals/errors").asInt()).isZero();
        assertThat(written.at("/totals/timeouts").asInt()).isZero();
        assertThat(written.at("/totals/warmupErrors").asInt()).isZero();
    }

    @Test
    void multilingualGoldenV2ValidatesStableAnchorsAndWritesBm25Baseline() throws Exception {
        RetrievalGoldenV2.Loaded golden = RetrievalGoldenV2.load(json);
        JsonNode runtimeSummary = json.readTree(new ClassPathResource(
                "retrieval-golden/v2/baseline.json"
        ).getInputStream());
        assertThat(runtimeSummary.path("datasetVersion").asText())
                .isEqualTo(golden.dataset().version());
        assertThat(runtimeSummary.path("corpusVersion").asText())
                .isEqualTo(golden.corpus().version());
        assertThat(runtimeSummary.path("datasetSha256").asText())
                .isEqualTo(golden.datasetSha256());
        assertThat(runtimeSummary.path("corpusSha256").asText())
                .isEqualTo(golden.corpusSha256());
        assertThat(runtimeSummary.path("status").asText()).isEqualTo("MEASURED");
        assertThat(runtimeSummary.path("generatedAt").asText()).isNotBlank();
        assertThat(runtimeSummary.path("reportAvailable").asBoolean()).isTrue();
        assertThat(runtimeSummary.path("caseCount").asInt()).isEqualTo(48);
        GoldenCorpusFixture corpus = seedGoldenV2Corpus(golden.corpus());
        assertGoldenAnchors(corpus);
        assertThat(corpus.childCount()).isEqualTo(golden.corpus().childCount());
        assertThat(corpus.hardNegativeCount())
                .isEqualTo(golden.corpus().hardNegatives().childCount());

        indexes.bootstrapActiveIndex();
        assertThat(rawIndexCount(indexes.activeIndexName().orElseThrow()))
                .isEqualTo(3 + corpus.childCount());

        List<GoldenMeasurement> measurements = new ArrayList<>();
        long peakHeapObserved = usedHeap();
        for (RetrievalGoldenV2.Query query : golden.dataset().queries()) {
            int warmupErrors = 0;
            for (int run = 0; run < WARMUP_RUNS; run++) {
                try {
                    search(reader, query.query(), CANDIDATE_DEPTH);
                } catch (Exception | AssertionError failure) {
                    warmupErrors++;
                }
            }

            JsonNode response = null;
            String error = "";
            long wallClockMs = 0;
            for (int run = 0; run < MEASURED_RUNS; run++) {
                long started = System.nanoTime();
                try {
                    response = search(reader, query.query(), CANDIDATE_DEPTH);
                } catch (Exception | AssertionError failure) {
                    error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                }
                wallClockMs += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
            wallClockMs /= MEASURED_RUNS;
            peakHeapObserved = Math.max(peakHeapObserved, usedHeap());
            measurements.add(measureGoldenQuery(
                    query, response, error, warmupErrors,
                    wallClockMs, corpus.passageKeyByChunkId()
            ));
        }

        Path report = writeGoldenV2Report(golden, corpus, measurements, peakHeapObserved);
        JsonNode written = json.readTree(report.toFile());
        assertThat(written.path("datasetVersion").asText())
                .isEqualTo("retrieval-golden-v2");
        assertThat(written.path("corpusVersion").asText())
                .isEqualTo("phase6-retrieval-corpus-v2");
        assertThat(written.at("/evaluation/queryCount").asInt()).isEqualTo(48);
        assertThat(written.at("/evaluation/answerableQueryCount").asInt()).isEqualTo(42);
        assertThat(written.at("/evaluation/noAnswerQueryCount").asInt()).isEqualTo(6);
        assertThat(written.at("/corpus/childCount").asInt()).isEqualTo(80);
        assertThat(written.at("/corpus/hardNegativeCount").asInt()).isEqualTo(60);
        assertThat(written.at("/languages/zh/queries").asInt()).isEqualTo(22);
        assertThat(written.at("/languages/en/queries").asInt()).isEqualTo(16);
        assertThat(written.at("/languages/mixed/queries").asInt()).isEqualTo(10);
        assertThat(written.at("/slices/cross-language/queries").asInt()).isEqualTo(11);
        assertThat(written.at("/slices/exact-identifier/queries").asInt()).isEqualTo(6);
        assertThat(written.at("/slices/proper-noun/queries").asInt()).isEqualTo(6);
        assertThat(written.at("/slices/multi-evidence/queries").asInt()).isEqualTo(6);
        assertThat(written.at("/slices/no-answer/queries").asInt()).isEqualTo(6);
        assertThat(written.at("/totals/errors").asInt()).isZero();
        assertThat(written.at("/totals/timeouts").asInt()).isZero();
        assertThat(written.at("/totals/warmupErrors").asInt()).isZero();
        assertThat(written.at("/totals/candidateAnyHits").asInt()).isPositive();
        assertThat(written.path("cases").size()).isEqualTo(48);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "RUN_PHASE6C_QUALITY", matches = "true")
    void realHybridGoldenV2MeetsQualityAndEndToEndLatencyGates() throws Exception {
        HttpEmbeddingProvider realEmbedding = realEmbedding();
        HttpRerankProvider realReranker = realReranker();
        realEmbedding.health();
        realReranker.health();
        when(embeddings.descriptor()).thenReturn(realEmbedding.descriptor());
        when(embeddings.embed(anyList())).thenAnswer(invocation ->
                realEmbedding.embed(invocation.getArgument(0))
        );
        when(reranker.descriptor()).thenReturn(realReranker.descriptor());
        when(reranker.rerank(anyString(), anyList())).thenAnswer(invocation ->
                realReranker.rerank(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                )
        );

        RetrievalGoldenV2.Loaded golden = RetrievalGoldenV2.load(json);
        GoldenCorpusFixture corpus = seedGoldenV2Corpus(golden.corpus());
        indexes.bootstrapActiveIndex();
        List<HybridMeasurement> bm25 = measureQuality(
                golden.dataset().queries(), corpus
        );

        publishPhase6c();
        List<HybridMeasurement> hybrid = measureQuality(
                golden.dataset().queries(), corpus
        );
        PerformanceSamples performance = measureHybridPerformance(
                golden.dataset().queries()
        );
        Map<String, Object> report = writePhase6cQualityReport(
                golden, bm25, hybrid, performance
        );

        Map<String, Object> zh = qualitySummary(language(hybrid, "zh"));
        Map<String, Object> en = qualitySummary(language(hybrid, "en"));
        Map<String, Object> crossLanguage = qualitySummary(
                tag(hybrid, "cross-language")
        );
        Map<String, Object> totals = qualitySummary(hybrid);
        assertThat(metric(zh, "hitAt10")).isGreaterThanOrEqualTo(0.85);
        assertThat(metric(en, "hitAt10")).isGreaterThanOrEqualTo(0.80);
        assertThat(metric(crossLanguage, "hitAt10")).isGreaterThanOrEqualTo(0.75);
        assertThat(metric(totals, "evidenceRecallAt8"))
                .isGreaterThanOrEqualTo(0.80);
        assertThat(
                metric(zh, "hitAt10")
                        - metric(qualitySummary(language(bm25, "zh")), "hitAt10")
        ).isGreaterThanOrEqualTo(0.05);
        for (String protectedSlice : List.of("exact-identifier", "proper-noun")) {
            assertThat(
                    metric(qualitySummary(tag(hybrid, protectedSlice)), "hitAt10")
                            - metric(
                            qualitySummary(tag(bm25, protectedSlice)),
                            "hitAt10"
                    )
            ).isGreaterThanOrEqualTo(-0.02);
        }
        assertThat(performance.errors()).isZero();
        assertThat(performance.p95("BM25")).isLessThanOrEqualTo(300);
        assertThat(performance.p95("VECTOR")).isLessThanOrEqualTo(300);
        assertThat(performance.p95("RERANK")).isLessThanOrEqualTo(900);
        assertThat(performance.p95("TOTAL")).isLessThanOrEqualTo(1_500);
        assertThat(report).containsEntry("status", "PASSED");
    }

    @Test
    void restrictedAclRevocationAndTombstoneDenyBeforeProjectionCleanup() throws Exception {
        indexes.bootstrapActiveIndex();

        JsonNode authorized = search(reader, "restrictedaccesssentinel");
        assertThat(authorized.path("totalElements").asLong()).isOne();
        mockMvc.perform(get("/api/v1/chunks/{id}", fixture.restrictedChildId())
                        .with(user(principal(reader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.child.id").value(fixture.restrictedChildId().toString()))
                .andExpect(jsonPath("$.parent.type").value("PARENT"));

        assertThat(search(outsider, "restrictedaccesssentinel")
                .path("totalElements").asLong()).isZero();
        mockMvc.perform(get("/api/v1/chunks/{id}", fixture.restrictedChildId())
                        .with(user(principal(outsider))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHUNK_NOT_FOUND"));

        jdbc.update("DELETE FROM document_acl_entries WHERE document_id = ? AND user_id = ?",
                fixture.restrictedDocumentId(), reader.getId());
        jdbc.update("""
                UPDATE documents
                SET acl_version = acl_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, fixture.restrictedDocumentId());

        assertThat(rawDocumentCount(fixture.restrictedDocumentId())).isOne();
        assertThat(search(reader, "restrictedaccesssentinel")
                .path("totalElements").asLong()).isZero();
        mockMvc.perform(get("/api/v1/chunks/{id}", fixture.restrictedChildId())
                        .with(user(principal(reader))))
                .andExpect(status().isNotFound());

        assertThat(search(outsider, "平台上下文检索")
                .path("totalElements").asLong()).isPositive();
        jdbc.update("""
                UPDATE documents
                SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, fixture.publicDocumentId());

        assertThat(rawDocumentCount(fixture.publicDocumentId())).isEqualTo(2);
        assertThat(search(outsider, "平台上下文检索")
                .path("totalElements").asLong()).isZero();
        mockMvc.perform(get("/api/v1/chunks/{id}", fixture.publicChildId())
                        .with(user(principal(outsider))))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheCurrentRevisionIsVisibleAndProjectionSynchronizationIsEquivalent() throws Exception {
        indexes.bootstrapActiveIndex();
        assertThat(search(reader, "legacyrevisionmarker").path("totalElements").asLong()).isOne();

        Revision second = addRevision(
                fixture.publicDocumentId(),
                2,
                "Current Revision",
                "currentrevisionmarker sharedrevisionmarker",
                3
        );
        indexes.bootstrapActiveIndex();
        assertThat(search(reader, "currentrevisionmarker").path("totalElements").asLong()).isZero();
        assertThat(search(reader, "legacyrevisionmarker").path("totalElements").asLong()).isOne();

        jdbc.update("""
                UPDATE documents
                SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, second.revisionId(), fixture.publicDocumentId());
        indexes.synchronizeProjections();

        JsonNode switched = search(reader, "sharedrevisionmarker");
        assertThat(switched.path("totalElements").asLong()).isOne();
        assertThat(switched.at("/items/0/revisionId").asText())
                .isEqualTo(second.revisionId().toString());
        assertThat(switched.at("/items/0/chunkId").asText())
                .isEqualTo(second.childId().toString());
        assertThat(search(reader, "legacyrevisionmarker").path("totalElements").asLong()).isZero();

        List<String> before = resultIdentity(switched);
        indexes.synchronizeProjections();
        List<String> after = resultIdentity(search(reader, "sharedrevisionmarker"));
        assertThat(after).containsExactlyElementsOf(before);
    }

    @Test
    void indexPublicationAndPartialFailureLeaveOnlyTheCurrentRevisionPhysicallyIndexed() throws Exception {
        indexes.bootstrapActiveIndex();
        Revision published = addRevision(
                fixture.publicDocumentId(), 2, "Published Revision", "publishedindexmarker", 3
        );
        ClaimedJob successfulJob = createRunningIndexJob(
                fixture.publicDocumentId(), published.revisionId()
        );

        indexes.index(successfulJob);

        assertThat(currentRevision(fixture.publicDocumentId())).isEqualTo(published.revisionId());
        assertThat(jobStatus(successfulJob.id())).isEqualTo("SUCCEEDED");
        assertThat(search(reader, "publishedindexmarker").path("totalElements").asLong()).isOne();
        assertThat(search(reader, "legacyrevisionmarker").path("totalElements").asLong()).isZero();
        indexes.synchronizeProjections();
        assertThat(rawRevisionIds(fixture.publicDocumentId()))
                .containsExactly(published.revisionId().toString());

        Revision failed = addRevision(
                fixture.publicDocumentId(), 3, "Failed Revision", "failedbulkmarker", 4
        );
        ClaimedJob failedJob = createRunningIndexJob(
                fixture.publicDocumentId(), failed.revisionId()
        );
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("forced failure after OpenSearch accepted the bulk");
        }).when(openSearch).bulk(anyString(), anyList());

        assertThatThrownBy(() -> indexes.index(failedJob))
                .hasMessageContaining("forced failure after OpenSearch accepted the bulk");
        assertThat(currentRevision(fixture.publicDocumentId())).isEqualTo(published.revisionId());
        assertThat(jobStatus(failedJob.id())).isEqualTo("RUNNING");
        reset(openSearch);
        assertThat(rawRevisionIds(fixture.publicDocumentId()))
                .containsExactly(published.revisionId().toString());
        indexes.synchronizeProjections();
        assertThat(rawRevisionIds(fixture.publicDocumentId()))
                .containsExactly(published.revisionId().toString());
        assertThat(search(reader, "failedbulkmarker").path("totalElements").asLong()).isZero();
    }

    @Test
    void delayedOlderIndexSuccessCannotRemainAfterANewerRevisionWasPublished() {
        indexes.bootstrapActiveIndex();
        Revision delayedSecond = addRevision(
                fixture.publicDocumentId(), 2, "Delayed Revision", "delayedrevisionmarker", 3
        );
        Revision publishedThird = addRevision(
                fixture.publicDocumentId(), 3, "Newest Revision", "newestrevisionmarker", 4
        );

        ClaimedJob newestJob = createRunningIndexJob(
                fixture.publicDocumentId(), publishedThird.revisionId()
        );
        indexes.index(newestJob);
        indexes.synchronizeProjections();
        assertThat(currentRevision(fixture.publicDocumentId()))
                .isEqualTo(publishedThird.revisionId());
        assertThat(rawRevisionIds(fixture.publicDocumentId()))
                .containsExactly(publishedThird.revisionId().toString());

        ClaimedJob delayedJob = createRunningIndexJob(
                fixture.publicDocumentId(), delayedSecond.revisionId()
        );
        indexes.index(delayedJob);

        assertThat(jobStatus(delayedJob.id())).isEqualTo("SUCCEEDED");
        assertThat(currentRevision(fixture.publicDocumentId()))
                .isEqualTo(publishedThird.revisionId());
        assertThat(rawRevisionIds(fixture.publicDocumentId()))
                .containsExactly(publishedThird.revisionId().toString());

        indexes.synchronizeProjections();
        assertThat(rawRevisionIds(fixture.publicDocumentId()))
                .containsExactly(publishedThird.revisionId().toString());
    }

    @Test
    void advisoryLockSerializesRevisionIndexingAcrossConnections() throws Exception {
        indexes.bootstrapActiveIndex();
        Revision next = addRevision(
                fixture.publicDocumentId(), 2, "Coordinated Revision", "coordinationmarker", 3
        );
        ClaimedJob job = createRunningIndexJob(fixture.publicDocumentId(), next.revisionId());
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);

        try (Connection lockConnection = dataSource.getConnection()) {
            advisoryLock(lockConnection, true);
            try {
                Future<?> indexing = executor.submit(() -> {
                    started.countDown();
                    indexes.index(job);
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> indexing.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                advisoryLock(lockConnection, false);
                indexing.get(20, TimeUnit.SECONDS);
            } finally {
                advisoryLock(lockConnection, false);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(currentRevision(fixture.publicDocumentId())).isEqualTo(next.revisionId());
        assertThat(jobStatus(job.id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void projectionSynchronizationConvergesForAclAndDeletionChanges() throws Exception {
        indexes.bootstrapActiveIndex();
        indexes.synchronizeProjections();

        jdbc.update("DELETE FROM document_acl_entries WHERE document_id = ?",
                fixture.restrictedDocumentId());
        jdbc.update("""
                INSERT INTO document_acl_entries (id, document_id, user_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), fixture.restrictedDocumentId(), outsider.getId());
        jdbc.update("""
                UPDATE documents
                SET title = 'Projected ACL Title', acl_version = acl_version + 1,
                    updated_at = updated_at + INTERVAL '1 second'
                WHERE id = ?
                """, fixture.restrictedDocumentId());

        indexes.synchronizeProjections();

        JsonNode projected = rawSource(fixture.restrictedDocumentId());
        assertThat(projected.path("title").asText()).isEqualTo("Projected ACL Title");
        assertThat(projected.path("documentTitle").asText()).isEqualTo("Projected ACL Title");
        assertThat(projected.path("aclVersion").asLong()).isEqualTo(2);
        assertThat(textValues(projected.path("grantedUserIds")))
                .containsExactly(outsider.getId().toString());
        assertThat(projected.path("accessProjectionKey").asText())
                .isEqualTo(SearchAccessService.projectionKey(
                        fixture.restrictedDocumentId(), fixture.restrictedRevisionId(), 2
                ));
        assertThat(search(outsider, "Projected ACL Title").path("totalElements").asLong()).isOne();
        assertThat(search(reader, "Projected ACL Title").path("totalElements").asLong()).isZero();
        assertThat(projectionState(fixture.restrictedDocumentId()))
                .containsExactly("ACTIVE", "2");

        jdbc.update("""
                UPDATE documents
                SET deleted_at = CURRENT_TIMESTAMP,
                    updated_at = updated_at + INTERVAL '1 second'
                WHERE id = ?
                """, fixture.restrictedDocumentId());
        indexes.synchronizeProjections();

        assertThat(rawDocumentCount(fixture.restrictedDocumentId())).isZero();
        assertThat(projectionState(fixture.restrictedDocumentId()))
                .containsExactly("DELETED", "2");
    }

    @Test
    @Timeout(10)
    void projectionSynchronizationConvergesWithoutCurrentRevision() {
        SearchIndexService.Manifest active = indexes.bootstrapActiveIndex();
        UUID unpublishedDocumentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO documents (
                    id, owner_user_id, title, visibility
                ) VALUES (?, ?, 'No current revision', 'ALL_USERS')
                """,
                unpublishedDocumentId,
                admin.getId()
        );

        indexes.synchronizeProjections();
        Instant firstProjection = projectionTimestamp(
                unpublishedDocumentId,
                active.generation()
        );
        indexes.synchronizeProjections();

        assertThat(projector.isCaughtUp(active.generation())).isTrue();
        assertThat(projectionState(unpublishedDocumentId))
                .containsExactly("DELETED", "1");
        assertThat(rawDocumentCount(unpublishedDocumentId)).isZero();
        assertThat(projectionTimestamp(
                unpublishedDocumentId,
                active.generation()
        )).isEqualTo(firstProjection);
    }

    @Test
    void searchUsesDatabaseActiveIndexAndSynchronizationRepairsAliasDrift() throws Exception {
        indexes.bootstrapActiveIndex();
        String activeIndex = indexes.activeIndexName().orElseThrow();
        String orphanIndex = searchProperties.getIndexPrefix()
                + "-orphan-" + UUID.randomUUID().toString().substring(0, 8);
        openSearch.createIndex(orphanIndex, SearchIndexService.indexDefinition());
        openSearch.switchAlias(searchProperties.getIndexAlias(), orphanIndex);

        assertThat(rawIndexCount(searchProperties.getIndexAlias())).isZero();
        assertThat(search(reader, "平台上下文检索").path("totalElements").asLong()).isPositive();
        assertThat(indexes.activeIndexName()).contains(activeIndex);

        indexes.synchronizeProjections();

        assertThat(rawIndexCount(searchProperties.getIndexAlias())).isPositive();
        assertThat(manifestStatus(activeIndex)).isEqualTo("ACTIVE");
        openSearch.deleteIndex(orphanIndex);
    }

    @Test
    void savedDeletedProjectionStillRemovesALateIndexWrite() {
        indexes.bootstrapActiveIndex();
        indexes.synchronizeProjections();
        String activeIndex = indexes.activeIndexName().orElseThrow();
        Map<String, Object> lateDocument = sourceMap(rawSource(fixture.restrictedDocumentId()));

        jdbc.update("""
                UPDATE documents
                SET deleted_at = CURRENT_TIMESTAMP,
                    updated_at = updated_at + INTERVAL '1 second'
                WHERE id = ?
                """, fixture.restrictedDocumentId());
        indexes.synchronizeProjections();
        assertThat(projectionState(fixture.restrictedDocumentId()))
                .containsExactly("DELETED", "1");
        assertThat(rawDocumentCount(fixture.restrictedDocumentId())).isZero();
        long activeGeneration = generations.generations().activeGeneration();
        assertThat(generations.generations().generations().stream()
                .filter(generation -> generation.indexGeneration() == activeGeneration)
                .findFirst()
                .orElseThrow()
                .closure()
                .orphanedProjectionCount()).isZero();

        openSearch.bulk(activeIndex, List.of(lateDocument));
        openSearch.refresh(activeIndex);
        assertThat(rawDocumentCount(fixture.restrictedDocumentId())).isOne();

        indexes.synchronizeProjections();

        assertThat(rawDocumentCount(fixture.restrictedDocumentId())).isZero();
        assertThat(projectionState(fixture.restrictedDocumentId()))
                .containsExactly("DELETED", "1");
    }

    @Test
    void adminSearchAndIndexEndpointsRequireRoleAndCsrf() throws Exception {
        indexes.bootstrapActiveIndex();
        String request = request("retrieval context");

        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/indexes").with(user(principal(reader))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/indexes").with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(reader)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user(principal(admin)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievalProfile").value("phase5-bm25-v1"))
                .andExpect(jsonPath("$.candidates").isNotEmpty());

        mockMvc.perform(post("/api/v1/admin/index-builds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "indexConfigVersion": "phase15a-hybrid-qwen3-source-locator-v1",
                                  "confirmation": "BUILD",
                                  "reason": "Phase 6B access-control test"
                                }
                                """)
                        .with(user(principal(admin))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/index-builds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "indexConfigVersion": "phase15a-hybrid-qwen3-source-locator-v1",
                                  "confirmation": "BUILD",
                                  "reason": "Phase 6B access-control test"
                                }
                                """)
                        .with(user(principal(reader)))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/index-builds")
                        .with(user(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeGeneration").isNumber());
    }

    private GoldenCorpusFixture seedGoldenV2Corpus(RetrievalGoldenV2.Corpus corpus) {
        Map<String, GoldenAnchor> anchors = new LinkedHashMap<>();
        Map<UUID, String> passageKeyByChunkId = new HashMap<>();
        int childCount = 0;

        for (RetrievalGoldenV2.Document source : corpus.documents()) {
            UUID documentId = addDocument(admin.getId(), source.title(), "ALL_USERS");
            String revisionText = String.join(
                    "\n",
                    source.passages().stream().map(RetrievalGoldenV2.Passage::text).toList()
            );
            UUID revisionId = insertReadyRevision(
                    documentId,
                    Integer.parseInt(source.revisionKey().substring(1)),
                    source.key(),
                    revisionText
            );
            String parentText = source.title() + "\n" + revisionText;
            UUID parentId = UUID.randomUUID();
            RetrievalGoldenV2.Passage first = source.passages().getFirst();
            int parentStartPage = source.passages().stream()
                    .mapToInt(RetrievalGoldenV2.Passage::startPage)
                    .min()
                    .orElseThrow();
            int parentEndPage = source.passages().stream()
                    .mapToInt(RetrievalGoldenV2.Passage::endPage)
                    .max()
                    .orElseThrow();
            insertChunk(
                    parentId, documentId, revisionId, null, "PARENT", 0,
                    parentText, String.join("\n", first.headingPath()), false
            );
            insertGoldenSpan(
                    parentId, documentId, revisionId, parentText,
                    parentStartPage, parentEndPage, RetrievalGoldenV2.sha256(parentText)
            );

            int childOrder = 0;
            for (RetrievalGoldenV2.Passage passage : source.passages()) {
                UUID childId = UUID.randomUUID();
                insertChunk(
                        childId, documentId, revisionId, parentId, "CHILD", childOrder++,
                        passage.text(), String.join("\n", passage.headingPath()), true
                );
                insertGoldenSpan(
                        childId, documentId, revisionId, passage.text(),
                        passage.startPage(), passage.endPage(), passage.sourceTextHash()
                );
                GoldenAnchor anchor = new GoldenAnchor(
                        documentId, revisionId, childId, passage
                );
                anchors.put(passage.key(), anchor);
                passageKeyByChunkId.put(childId, passage.key());
                childCount++;
            }
            setCurrentRevision(documentId, revisionId);
        }

        RetrievalGoldenV2.HardNegatives hardNegatives = corpus.hardNegatives();
        UUID documentId = addDocument(
                admin.getId(), "Golden Retrieval Hard Negatives", "ALL_USERS"
        );
        List<String> negativeTexts = new ArrayList<>();
        for (RetrievalGoldenV2.HardNegativeTemplate template : hardNegatives.templates()) {
            for (int copy = 1; copy <= hardNegatives.copiesPerTemplate(); copy++) {
                negativeTexts.add(template.textTemplate().replace("{n}", Integer.toString(copy)));
            }
        }
        UUID revisionId = insertReadyRevision(
                documentId, 1, "golden-hard-negatives", String.join("\n", negativeTexts)
        );
        int parentOrder = 0;
        int childOrder = 0;
        int textOffset = 0;
        for (RetrievalGoldenV2.HardNegativeTemplate template : hardNegatives.templates()) {
            List<String> children = negativeTexts.subList(
                    textOffset,
                    textOffset + hardNegatives.copiesPerTemplate()
            );
            textOffset += hardNegatives.copiesPerTemplate();
            int page = 100 + parentOrder;
            String parentText = String.join("\n", children);
            UUID parentId = UUID.randomUUID();
            insertChunk(
                    parentId, documentId, revisionId, null, "PARENT", parentOrder++,
                    parentText, template.heading(), false
            );
            insertGoldenSpan(
                    parentId, documentId, revisionId, parentText,
                    page, page, RetrievalGoldenV2.sha256(parentText)
            );
            for (String text : children) {
                UUID childId = UUID.randomUUID();
                insertChunk(
                        childId, documentId, revisionId, parentId, "CHILD", childOrder++,
                        text, template.heading(), true
                );
                insertGoldenSpan(
                        childId, documentId, revisionId, text,
                        page, page, RetrievalGoldenV2.sha256(text)
                );
                childCount++;
            }
        }
        setCurrentRevision(documentId, revisionId);

        return new GoldenCorpusFixture(
                Map.copyOf(anchors),
                Map.copyOf(passageKeyByChunkId),
                childCount,
                hardNegatives.childCount()
        );
    }

    private UUID insertReadyRevision(
            UUID documentId,
            int revisionNumber,
            String revisionKey,
            String text
    ) {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash, source_object_key,
                    status, parser_version, original_filename, file_size_bytes, media_type
                ) VALUES (?, ?, ?, ?, ?, 'READY', 'golden-v2-fixture', ?, ?, 'application/pdf')
                """,
                revisionId,
                documentId,
                revisionNumber,
                RetrievalGoldenV2.sha256(text),
                "fixtures/golden-v2/" + revisionKey + "-" + revisionId + ".pdf",
                revisionKey + ".pdf",
                Math.max(1, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        );
        return revisionId;
    }

    private void insertGoldenSpan(
            UUID chunkId,
            UUID documentId,
            UUID revisionId,
            String text,
            int startPage,
            int endPage,
            String sourceTextHash
    ) {
        insertPdfSpan(
                chunkId, documentId, revisionId, text,
                startPage, endPage, sourceTextHash
        );
    }

    private void assertGoldenAnchors(GoldenCorpusFixture corpus) {
        corpus.anchors().forEach((passageKey, anchor) -> {
            Map<String, Object> stored = jdbc.queryForMap("""
                    SELECT chunk.document_id, chunk.revision_id, chunk.chunk_type,
                           chunk.text, chunk.heading_path,
                           location.start_page, location.end_page,
                           location.start_source_unit_id,
                           location.end_source_unit_id,
                           span.start_offset, span.end_offset,
                           span.source_text_hash
                    FROM chunks chunk
                    JOIN source_spans span ON span.chunk_id = chunk.id
                    JOIN source_locator_projection location
                      ON location.source_kind = 'SOURCE_SPAN'
                     AND location.source_id = span.id
                    WHERE chunk.id = ?
                    """, anchor.chunkId());
            RetrievalGoldenV2.Passage passage = anchor.passage();
            assertThat(stored.get("document_id")).isEqualTo(anchor.documentId());
            assertThat(stored.get("revision_id")).isEqualTo(anchor.revisionId());
            assertThat(stored.get("chunk_type")).isEqualTo("CHILD");
            assertThat(stored.get("text")).isEqualTo(passage.text());
            assertThat(stored.get("heading_path"))
                    .isEqualTo(String.join("\n", passage.headingPath()));
            assertThat(stored.get("start_page")).isEqualTo(passage.startPage());
            assertThat(stored.get("end_page")).isEqualTo(passage.endPage());
            int startOffset = (Integer) stored.get("start_offset");
            int endOffset = (Integer) stored.get("end_offset");
            assertThat(startOffset).isGreaterThanOrEqualTo(0);
            assertThat(endOffset).isGreaterThan(startOffset);
            UUID startUnit = (UUID) stored.get("start_source_unit_id");
            UUID endUnit = (UUID) stored.get("end_source_unit_id");
            assertThat(startUnit).isNotNull();
            assertThat(endUnit).isNotNull();
            if (startUnit.equals(endUnit)) {
                String canonical = jdbc.queryForObject(
                        "SELECT canonical_text FROM source_units WHERE id = ?",
                        String.class,
                        startUnit
                );
                assertThat(canonical.substring(startOffset, endOffset))
                        .isEqualTo(passage.text());
            }
            assertThat(stored.get("source_text_hash")).isEqualTo(passage.sourceTextHash());
        });
    }

    private Fixture createFixture() {
        UUID publicDocument = addDocument(
                admin.getId(), "Enterprise Context RAG Guide", "ALL_USERS"
        );
        Revision publicRevision = addRevision(
                publicDocument,
                1,
                "Retrieval Context",
                "平台上下文检索依赖授权证据并支持引用溯源。 "
                        + "Enterprise retrieval context provides traceable evidence. "
                        + "RAG 上下文 context supports bilingual search. legacyrevisionmarker sharedrevisionmarker",
                1
        );
        UUID secondPublicChild = addChild(
                publicDocument,
                publicRevision.revisionId(),
                publicRevision.parentId(),
                1,
                "Evaluation",
                "平台检索评测覆盖中文 English 和 mixed query coverage.",
                2
        );
        setCurrentRevision(publicDocument, publicRevision.revisionId());

        UUID restrictedDocument = addDocument(
                admin.getId(), "Restricted Retrieval Notes", "RESTRICTED"
        );
        Revision restrictedRevision = addRevision(
                restrictedDocument,
                1,
                "Restricted Context",
                "restrictedaccesssentinel is visible only to an explicitly authorized user.",
                1
        );
        setCurrentRevision(restrictedDocument, restrictedRevision.revisionId());
        jdbc.update("""
                INSERT INTO document_acl_entries (id, document_id, user_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), restrictedDocument, reader.getId());

        return new Fixture(
                publicDocument,
                publicRevision.revisionId(),
                publicRevision.childId(),
                secondPublicChild,
                restrictedDocument,
                restrictedRevision.revisionId(),
                restrictedRevision.childId()
        );
    }

    private void publishPhase6c() {
        indexes.bootstrapActiveIndex();
        searchProperties.setGenerationWorkerEnabled(true);
        var started = generations.start(
                new IndexGenerationContracts.StartIndexBuildRequest(
                        "phase15a-hybrid-qwen3-source-locator-v1",
                        "BUILD",
                        "Phase 6C integration generation"
                ),
                admin.getId()
        );
        generations.build(generations.claim().orElseThrow());
        var ready = generations.generations().generations().stream()
                .filter(item -> item.id().equals(started.id()))
                .findFirst()
                .orElseThrow();
        generations.publish(
                new IndexGenerationContracts.PublishGenerationRequest(
                        ready.indexGeneration(),
                        "phase6c-hybrid-rerank-v1",
                        "PUBLISH",
                        "Phase 6C integration publication"
                ),
                admin.getId()
        );
    }

    private IndexGenerationContracts.IndexGenerationView generation(UUID id) {
        return generations.generations().generations().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static List<RerankScore> rankedScores(List<String> documents) {
        List<RerankScore> scores = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            scores.add(new RerankScore(
                    index,
                    (double) (documents.size() - index) / (documents.size() + 1)
            ));
        }
        return List.copyOf(scores);
    }

    private UUID addDocument(UUID ownerId, String title, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (
                    id, owner_user_id, title, visibility, acl_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, ownerId, title, visibility);
        return id;
    }

    private Revision addRevision(
            UUID documentId,
            int revisionNumber,
            String heading,
            String childText,
            int page
    ) {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_revisions (
                    id, document_id, revision_number, content_hash, source_object_key,
                    status, parser_version, original_filename, file_size_bytes, media_type
                ) VALUES (?, ?, ?, ?, ?, 'READY', 'fixture-parser', ?, 128, 'application/pdf')
                """,
                revisionId,
                documentId,
                revisionNumber,
                HASH,
                "fixtures/" + revisionId + ".pdf",
                "fixture-r" + revisionNumber + ".pdf"
        );
        String parentText = "parentuniquesentinel " + heading + "\n" + childText;
        UUID parentId = UUID.randomUUID();
        insertChunk(parentId, documentId, revisionId, null, "PARENT", 0,
                parentText, heading, false);
        insertSpan(parentId, documentId, revisionId, parentText, page);
        UUID childId = addChild(documentId, revisionId, parentId, 0, heading, childText, page);
        return new Revision(revisionId, parentId, childId);
    }

    private UUID addChild(
            UUID documentId,
            UUID revisionId,
            UUID parentId,
            int order,
            String heading,
            String text,
            int page
    ) {
        UUID childId = UUID.randomUUID();
        insertChunk(childId, documentId, revisionId, parentId, "CHILD", order,
                text, heading, true);
        insertSpan(childId, documentId, revisionId, text, page);
        return childId;
    }

    private void insertChunk(
            UUID id,
            UUID documentId,
            UUID revisionId,
            UUID parentId,
            String type,
            int order,
            String text,
            String heading,
            boolean searchable
    ) {
        jdbc.update("""
                INSERT INTO chunks (
                    id, document_id, revision_id, parent_chunk_id, chunk_type, chunk_order,
                    text, heading_path, start_block_order, end_block_order,
                    character_count, token_count, token_counter_version,
                    chunking_profile_version, parser_version, chunker_version,
                    content_hash, searchable
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, char_length(?), ?,
                          'unicode-codepoint-v1', ?, 'fixture-parser', 'fixture-chunker', ?, ?)
                """,
                id, documentId, revisionId, parentId, type, order, text, heading, text,
                Math.max(1, text.codePointCount(0, text.length())), PROFILE, HASH, searchable
        );
    }

    private void insertSpan(UUID chunkId, UUID documentId, UUID revisionId, String text, int page) {
        insertPdfSpan(
                chunkId, documentId, revisionId, text,
                page, page, HASH
        );
    }

    private void insertPdfSpan(
            UUID chunkId,
            UUID documentId,
            UUID revisionId,
            String text,
            int startPage,
            int endPage,
            String sourceTextHash
    ) {
        PdfAnchor start = ensurePdfSourceUnit(
                documentId, revisionId, startPage, text
        );
        PdfAnchor end = startPage == endPage
                ? start
                : ensurePdfSourceUnit(
                        documentId, revisionId, endPage, text
                );
        jdbc.update("""
                INSERT INTO source_spans (
                    id, chunk_id, document_id, revision_id, span_order,
                    locator_kind, start_source_unit_id, end_source_unit_id,
                    start_offset, end_offset, chunk_start_offset,
                    chunk_end_offset, source_text_hash, locator_address,
                    normalization_version
                ) VALUES (
                    ?, ?, ?, ?, 0, 'PAGE', ?, ?, ?, ?, 0, ?, ?,
                    jsonb_build_object(
                        'kind', 'PAGE',
                        'startPage', ?,
                        'endPage', ?
                    ),
                    'utf16-v1'
                )
                """,
                UUID.randomUUID(), chunkId, documentId, revisionId,
                start.unitId(), end.unitId(),
                start.startOffset(), end.endOffset(),
                text.length(), sourceTextHash, startPage, endPage
        );
    }

    private PdfAnchor ensurePdfSourceUnit(
            UUID documentId,
            UUID revisionId,
            int page,
            String text
    ) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                """
                SELECT id, canonical_text
                FROM source_units
                WHERE revision_id = ? AND unit_kind = 'PAGE' AND unit_order = ?
                """,
                revisionId, page
        );
        if (existing.isEmpty()) {
            UUID unitId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO source_units (
                        id, document_id, revision_id, unit_order, unit_kind,
                        stable_address, canonical_text, canonical_text_hash,
                        normalization_version, label_metadata
                    ) VALUES (
                        ?, ?, ?, ?, 'PAGE', ?, ?, ?, 'utf16-v1',
                        jsonb_build_object(
                            'pageNumber', ?,
                            'sourceLabel', ?
                        )
                    )
                    """,
                    unitId, documentId, revisionId, page,
                    "page:" + page, text, RetrievalGoldenV2.sha256(text),
                    page, "第 " + page + " 页"
            );
            return new PdfAnchor(unitId, 0, text.length());
        }
        UUID unitId = (UUID) existing.getFirst().get("id");
        String canonicalText = (String) existing.getFirst().get("canonical_text");
        int offset = canonicalText.indexOf(text);
        if (offset < 0) {
            offset = canonicalText.length() + 1;
            canonicalText = canonicalText + "\n" + text;
            jdbc.update("""
                    UPDATE source_units
                    SET canonical_text = ?, canonical_text_hash = ?
                    WHERE id = ?
                    """,
                    canonicalText,
                    RetrievalGoldenV2.sha256(canonicalText),
                    unitId
            );
        }
        return new PdfAnchor(unitId, offset, offset + text.length());
    }

    private void setCurrentRevision(UUID documentId, UUID revisionId) {
        jdbc.update("""
                UPDATE documents
                SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, revisionId, documentId);
    }

    private JsonNode search(UserEntity actor, String query) throws Exception {
        return search(actor, query, 20);
    }

    private JsonNode search(UserEntity actor, String query, int size) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(query, size))
                        .with(user(principal(actor)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private String request(String query) throws Exception {
        return request(query, 20);
    }

    private String request(String query, int size) throws Exception {
        return json.writeValueAsString(Map.of("query", query, "page", 0, "size", size));
    }

    private ClaimedJob createRunningIndexJob(UUID documentId, UUID revisionId) {
        UUID jobId = UUID.randomUUID();
        String sourceObjectKey = jdbc.queryForObject(
                "SELECT source_object_key FROM document_revisions WHERE id = ?",
                String.class,
                revisionId
        );
        jdbc.update("""
                INSERT INTO pipeline_jobs (
                    id, revision_id, document_format, stage, status,
                    attempt, max_attempts, pipeline_version,
                    parser_provider, parser_provider_version,
                    lease_owner, lease_expires_at, heartbeat_at, started_at
                ) VALUES (?, ?, 'PDF', 'INDEX', 'RUNNING', 1, ?, ?,
                          'PDFBOX', 'pdfbox-test-v1', ?,
                          CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                jobId,
                revisionId,
                pipelineProperties.maxAttempts(),
                pipelineProperties.pipelineVersion(),
                pipelineProperties.workerId(),
                pipelineProperties.taskTimeout().toMillis()
        );
        return new ClaimedJob(
                jobId, revisionId, documentId, sourceObjectKey,
                PipelineStage.INDEX, 1, pipelineProperties.maxAttempts()
        );
    }

    private UUID currentRevision(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT current_revision_id FROM documents WHERE id = ?",
                UUID.class,
                documentId
        );
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM pipeline_jobs WHERE id = ?",
                String.class,
                jobId
        );
    }

    private record PdfAnchor(
            UUID unitId,
            int startOffset,
            int endOffset
    ) {
    }

    private static void advisoryLock(Connection connection, boolean acquire) throws Exception {
        String sql = acquire
                ? "SELECT pg_advisory_lock(?)"
                : "SELECT pg_advisory_unlock(?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, INDEX_COORDINATION_LOCK);
            statement.execute();
        }
    }

    private long rawDocumentCount(UUID documentId) {
        return rawDocumentCount(indexes.activeIndexName().orElseThrow(), documentId);
    }

    private long rawDocumentCount(String indexName, UUID documentId) {
        JsonNode result = openSearch.search(indexName, Map.of(
                "size", 0,
                "track_total_hits", true,
                "query", Map.of("term", Map.of("documentId", documentId.toString()))
        ));
        return result.at("/hits/total/value").asLong();
    }

    private long rawIndexCount(String indexName) {
        return openSearch.search(indexName, matchAll()).at("/hits/total/value").asLong();
    }

    private List<String> rawRevisionIds(UUID documentId) {
        JsonNode result = openSearch.search(indexes.activeIndexName().orElseThrow(), Map.of(
                "size", 50,
                "query", Map.of("term", Map.of("documentId", documentId.toString())),
                "_source", List.of("revisionId")
        ));
        List<String> revisions = new ArrayList<>();
        result.at("/hits/hits").forEach(hit ->
                revisions.add(hit.at("/_source/revisionId").asText()));
        return revisions;
    }

    private JsonNode rawSource(UUID documentId) {
        JsonNode result = openSearch.search(indexes.activeIndexName().orElseThrow(), Map.of(
                "size", 1,
                "query", Map.of("term", Map.of("documentId", documentId.toString()))
        ));
        return result.at("/hits/hits/0/_source");
    }

    private Map<String, Object> sourceMap(JsonNode source) {
        return json.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private List<String> projectionState(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT state, acl_version FROM search_projection_states WHERE document_id = ?",
                (resultSet, rowNumber) -> List.of(
                        resultSet.getString("state"),
                        Long.toString(resultSet.getLong("acl_version"))
                ),
                documentId
        );
    }

    private Instant projectionTimestamp(UUID documentId, long generation) {
        return jdbc.queryForObject(
                """
                SELECT projected_at
                FROM search_projection_states
                WHERE document_id = ? AND index_generation = ?
                """,
                (resultSet, rowNumber) ->
                        resultSet.getTimestamp("projected_at").toInstant(),
                documentId,
                generation
        );
    }

    private String manifestStatus(String indexName) {
        return jdbc.queryForObject(
                "SELECT status FROM index_manifests WHERE index_name = ?",
                String.class,
                indexName
        );
    }

    private String activeIndexConfigVersion() {
        return jdbc.queryForObject(
                """
                SELECT index_config_version
                FROM index_manifests
                WHERE index_alias = ? AND status = 'ACTIVE'
                """,
                String.class,
                searchProperties.getIndexAlias()
        );
    }

    private String activeSchemaVersion() {
        return jdbc.queryForObject(
                """
                SELECT config.schema_version
                FROM index_manifests manifest
                JOIN index_configs config
                  ON config.version = manifest.index_config_version
                WHERE manifest.index_alias = ? AND manifest.status = 'ACTIVE'
                """,
                String.class,
                searchProperties.getIndexAlias()
        );
    }

    private String currentRetrievalProfileVersion() {
        return jdbc.queryForObject(
                """
                SELECT profile_version
                FROM retrieval_publications
                WHERE singleton_id = 1
                """,
                String.class
        );
    }

    private static Map<String, Object> matchAll() {
        return Map.of("size", 0, "query", Map.of("match_all", Map.of()));
    }

    private static HttpEmbeddingProvider realEmbedding() {
        ModelServiceProperties.Endpoint endpoint = new ModelServiceProperties.Endpoint(
                "http://embedding-model:8000",
                "Qwen/Qwen3-Embedding-0.6B",
                "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3",
                1024
        );
        endpoint.setEnabled(true);
        return new HttpEmbeddingProvider(endpoint);
    }

    private static HttpRerankProvider realReranker() {
        ModelServiceProperties.Endpoint endpoint = new ModelServiceProperties.Endpoint(
                "http://reranker-model:8000",
                "Qwen/Qwen3-Reranker-0.6B",
                "e61197ed45024b0ed8a2d74b80b4d909f1255473",
                null
        );
        endpoint.setEnabled(true);
        return new HttpRerankProvider(endpoint);
    }

    private List<HybridMeasurement> measureQuality(
            List<RetrievalGoldenV2.Query> queries,
            GoldenCorpusFixture corpus
    ) {
        List<HybridMeasurement> result = new ArrayList<>();
        for (RetrievalGoldenV2.Query query : queries) {
            SearchContracts.SearchDebugResponse response = searchService.debug(
                    new SearchContracts.SearchRequest(
                            query.query(), 0, CANDIDATE_DEPTH, null, null
                    ),
                    principal(reader)
            );
            assertThat(response.degraded()).isFalse();
            result.add(measureHybrid(query, response, corpus));
        }
        return List.copyOf(result);
    }

    private HybridMeasurement measureHybrid(
            RetrievalGoldenV2.Query query,
            SearchContracts.SearchDebugResponse response,
            GoldenCorpusFixture corpus
    ) {
        Map<String, Integer> candidateRanks = new HashMap<>();
        for (SearchContracts.DebugCandidate candidate : response.candidates()) {
            if (candidate.result() == null) {
                continue;
            }
            String passage = corpus.passageKeyByChunkId().get(
                    candidate.result().chunkId()
            );
            if (passage != null) {
                candidateRanks.putIfAbsent(passage, candidate.rank());
            }
        }
        List<String> evidence = response.result().items().stream()
                .map(item -> corpus.passageKeyByChunkId().get(item.chunkId()))
                .toList();
        Map<String, Integer> evidenceRanks = new HashMap<>();
        for (int index = 0; index < evidence.size(); index++) {
            if (evidence.get(index) != null) {
                evidenceRanks.putIfAbsent(evidence.get(index), index + 1);
            }
        }

        int candidateGroups = hitGroups(query, candidateRanks);
        int evidenceGroups = hitGroups(query, evidenceRanks);
        int contextGroups = contextHitGroups(query, response.result().items(), corpus);
        int groupCount = query.evidenceGroups().size();
        int relevantMaterials = relevantMaterials(
                query, response.result().items(), corpus
        );
        int firstRelevant = evidenceRanks(query, evidenceRanks).stream()
                .mapToInt(Integer::intValue)
                .filter(rank -> rank > 0)
                .min()
                .orElse(0);
        double reciprocalRank = firstRelevant > 0 && firstRelevant <= 10
                ? 1.0 / firstRelevant
                : 0.0;
        return new HybridMeasurement(
                query,
                query.answerable() && candidateGroups == groupCount,
                query.answerable() && evidenceGroups == groupCount,
                query.answerable() && groupCount > 0
                        ? (double) evidenceGroups / groupCount : 0.0,
                query.answerable() && groupCount > 0
                        ? (double) contextGroups / groupCount : 0.0,
                query.answerable() && !response.result().items().isEmpty()
                        ? (double) relevantMaterials / response.result().items().size()
                        : 0.0,
                reciprocalRank,
                query.answerable() ? ndcgAt10(query, evidence) : 0.0,
                response.result().tookMs()
        );
    }

    private static int hitGroups(
            RetrievalGoldenV2.Query query,
            Map<String, Integer> ranks
    ) {
        return (int) query.evidenceGroups().stream()
                .filter(group -> group.anyOf().stream()
                        .anyMatch(expected -> ranks.containsKey(expected.passageKey())))
                .count();
    }

    private static List<Integer> evidenceRanks(
            RetrievalGoldenV2.Query query,
            Map<String, Integer> ranks
    ) {
        return query.evidenceGroups().stream()
                .map(group -> group.anyOf().stream()
                        .mapToInt(expected ->
                                ranks.getOrDefault(expected.passageKey(), 0))
                        .filter(rank -> rank > 0)
                        .min()
                        .orElse(0))
                .toList();
    }

    private static int contextHitGroups(
            RetrievalGoldenV2.Query query,
            List<SearchContracts.SearchHit> results,
            GoldenCorpusFixture corpus
    ) {
        return (int) query.evidenceGroups().stream()
                .filter(group -> group.anyOf().stream().anyMatch(expected ->
                        contextContains(expected.passageKey(), results, corpus)))
                .count();
    }

    private static int relevantMaterials(
            RetrievalGoldenV2.Query query,
            List<SearchContracts.SearchHit> results,
            GoldenCorpusFixture corpus
    ) {
        Set<String> relevant = query.relevantPassages().stream()
                .map(RetrievalGoldenV2.ExpectedPassage::passageKey)
                .collect(java.util.stream.Collectors.toSet());
        int count = 0;
        for (SearchContracts.SearchHit result : results) {
            String passage = corpus.passageKeyByChunkId().get(result.chunkId());
            boolean matches = passage != null && relevant.contains(passage);
            if (!matches && result.evidence() != null
                    && result.evidence().parent() != null) {
                String parent = result.evidence().parent().text();
                matches = relevant.stream().anyMatch(key ->
                        parent.contains(corpus.anchors().get(key).passage().text())
                );
            }
            if (matches) {
                count++;
            }
        }
        return count;
    }

    private static boolean contextContains(
            String passageKey,
            List<SearchContracts.SearchHit> results,
            GoldenCorpusFixture corpus
    ) {
        GoldenAnchor anchor = corpus.anchors().get(passageKey);
        if (anchor == null) {
            return false;
        }
        for (SearchContracts.SearchHit result : results) {
            if (anchor.chunkId().equals(result.chunkId())) {
                return true;
            }
            if (result.evidence() != null
                    && result.evidence().parent() != null
                    && result.evidence().parent().text()
                    .contains(anchor.passage().text())) {
                return true;
            }
        }
        return false;
    }

    private PerformanceSamples measureHybridPerformance(
            List<RetrievalGoldenV2.Query> queries
    ) {
        for (int warmup = 0; warmup < 50; warmup++) {
            searchService.debug(
                    new SearchContracts.SearchRequest(
                            queries.get(warmup % queries.size()).query(),
                            0, 8, null, null
                    ),
                    principal(reader)
            );
        }
        Map<String, List<Long>> samples = new LinkedHashMap<>();
        for (String stage : List.of("BM25", "VECTOR", "RERANK", "TOTAL")) {
            samples.put(stage, new ArrayList<>(900));
        }
        int errors = 0;
        for (int round = 0; round < 3; round++) {
            for (int sample = 0; sample < 300; sample++) {
                RetrievalGoldenV2.Query query = queries.get(
                        (round + sample) % queries.size()
                );
                try {
                    SearchContracts.SearchDebugResponse response = searchService.debug(
                            new SearchContracts.SearchRequest(
                                    query.query(), 0, 8, null, null
                            ),
                            principal(reader)
                    );
                    if (response.degraded()) {
                        errors++;
                        continue;
                    }
                    response.stages().stream()
                            .filter(stage -> samples.containsKey(stage.name()))
                            .forEach(stage ->
                                    samples.get(stage.name()).add(stage.tookMs()));
                    samples.get("TOTAL").add(response.tookMs());
                } catch (RuntimeException failure) {
                    errors++;
                }
            }
        }
        return new PerformanceSamples(
                samples.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Map.of(
                                "p50Ms", percentile(entry.getValue(), 0.50),
                                "p95Ms", percentile(entry.getValue(), 0.95),
                                "maxMs", entry.getValue().stream()
                                        .mapToLong(Long::longValue).max().orElse(0)
                        ),
                        (left, right) -> left,
                        LinkedHashMap::new
                )),
                errors
        );
    }

    private Map<String, Object> writePhase6cQualityReport(
            RetrievalGoldenV2.Loaded golden,
            List<HybridMeasurement> bm25,
            List<HybridMeasurement> hybrid,
            PerformanceSamples performance
    ) throws Exception {
        Map<String, Object> languages = new LinkedHashMap<>();
        for (String language : List.of("zh", "en", "mixed")) {
            languages.put(language, qualitySummary(language(hybrid, language)));
        }
        Map<String, Object> slices = new LinkedHashMap<>();
        Set<String> tags = new TreeSet<>();
        hybrid.forEach(item -> tags.addAll(item.query().tags()));
        tags.forEach(tag -> slices.put(tag, qualitySummary(tag(hybrid, tag))));

        Map<String, Object> zh = qualitySummary(language(hybrid, "zh"));
        boolean protectedSlicesPassed = List.of("exact-identifier", "proper-noun")
                .stream()
                .allMatch(slice ->
                        metric(qualitySummary(tag(hybrid, slice)), "hitAt10")
                                - metric(qualitySummary(tag(bm25, slice)), "hitAt10")
                                >= -0.02
                );
        boolean qualityPassed = metric(zh, "hitAt10") >= 0.85
                && metric(qualitySummary(language(hybrid, "en")), "hitAt10") >= 0.80
                && metric(qualitySummary(tag(hybrid, "cross-language")), "hitAt10") >= 0.75
                && metric(qualitySummary(hybrid), "evidenceRecallAt8") >= 0.80
                && metric(zh, "hitAt10")
                - metric(qualitySummary(language(bm25, "zh")), "hitAt10") >= 0.05
                && protectedSlicesPassed;
        boolean performancePassed = performance.errors() == 0
                && performance.p95("BM25") <= 300
                && performance.p95("VECTOR") <= 300
                && performance.p95("RERANK") <= 900
                && performance.p95("TOTAL") <= 1_500;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetVersion", golden.dataset().version());
        report.put("datasetSha256", golden.datasetSha256());
        report.put("corpusVersion", golden.corpus().version());
        report.put("corpusSha256", golden.corpusSha256());
        report.put("generatedAt", Instant.now().toString());
        report.put("status", qualityPassed && performancePassed ? "PASSED" : "FAILED");
        report.put("metrics", List.of(
                "Candidate Hit@50", "MRR@10", "nDCG@10",
                "Evidence Recall@8", "Context Precision@8", "Context Recall@8"
        ));
        report.put("bm25", qualitySummary(bm25));
        report.put("hybrid", qualitySummary(hybrid));
        report.put("languages", languages);
        report.put("slices", slices);
        report.put("performance", Map.of(
                "warmups", 50,
                "rounds", 3,
                "samplesPerRound", 300,
                "errors", performance.errors(),
                "stages", performance.stages()
        ));
        report.put("models", Map.of(
                "embedding", Map.of(
                        "model", "Qwen/Qwen3-Embedding-0.6B",
                        "revision", "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"
                ),
                "reranker", Map.of(
                        "model", "Qwen/Qwen3-Reranker-0.6B",
                        "revision", "e61197ed45024b0ed8a2d74b80b4d909f1255473"
                )
        ));
        Path output = Path.of(
                "target", "phase6c-reports", "retrieval-golden-v2-hybrid.json"
        );
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return report;
    }

    private static Map<String, Object> qualitySummary(
            List<HybridMeasurement> measurements
    ) {
        List<HybridMeasurement> answerable = measurements.stream()
                .filter(item -> item.query().answerable())
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queries", measurements.size());
        result.put("answerableQueries", answerable.size());
        result.put("candidateHitAt50", ratio(
                answerable.stream().filter(HybridMeasurement::candidateHit).count(),
                answerable.size()
        ));
        result.put("hitAt10", ratio(
                answerable.stream().filter(HybridMeasurement::evidenceHit).count(),
                answerable.size()
        ));
        result.put("mrrAt10", average(
                answerable.stream().map(HybridMeasurement::mrrAt10).toList()
        ));
        result.put("ndcgAt10", average(
                answerable.stream().map(HybridMeasurement::ndcgAt10).toList()
        ));
        result.put("evidenceRecallAt8", average(
                answerable.stream().map(HybridMeasurement::evidenceRecallAt8).toList()
        ));
        result.put("contextPrecisionAt8", average(
                answerable.stream().map(HybridMeasurement::contextPrecisionAt8).toList()
        ));
        result.put("contextRecallAt8", average(
                answerable.stream().map(HybridMeasurement::contextRecallAt8).toList()
        ));
        result.put("p95Ms", percentile(
                measurements.stream().map(HybridMeasurement::tookMs).toList(),
                0.95
        ));
        return result;
    }

    private static List<HybridMeasurement> language(
            List<HybridMeasurement> measurements,
            String language
    ) {
        return measurements.stream()
                .filter(item -> item.query().language().equals(language))
                .toList();
    }

    private static List<HybridMeasurement> tag(
            List<HybridMeasurement> measurements,
            String tag
    ) {
        return measurements.stream()
                .filter(item -> item.query().tags().contains(tag))
                .toList();
    }

    private static double metric(Map<String, Object> metrics, String key) {
        return ((Number) metrics.get(key)).doubleValue();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private GoldenMeasurement measureGoldenQuery(
            RetrievalGoldenV2.Query query,
            JsonNode response,
            String error,
            int warmupErrors,
            long wallClockMs,
            Map<UUID, String> passageKeyByChunkId
    ) {
        Map<String, Integer> ranksByPassage = new HashMap<>();
        List<String> rankedPassages = new ArrayList<>();
        if (response != null) {
            int rank = 0;
            for (JsonNode item : response.path("items")) {
                rank++;
                String passageKey = passageKeyByChunkId.get(
                        UUID.fromString(item.path("chunkId").asText())
                );
                rankedPassages.add(passageKey);
                if (passageKey != null) {
                    ranksByPassage.putIfAbsent(passageKey, rank);
                }
            }
        }

        Map<String, Integer> ranksByGroup = new LinkedHashMap<>();
        for (RetrievalGoldenV2.EvidenceGroup group : query.evidenceGroups()) {
            int groupRank = group.anyOf().stream()
                    .map(RetrievalGoldenV2.ExpectedPassage::passageKey)
                    .mapToInt(passage -> ranksByPassage.getOrDefault(passage, 0))
                    .filter(rank -> rank > 0)
                    .min()
                    .orElse(0);
            ranksByGroup.put(group.id(), groupRank);
        }

        int hitGroups = (int) ranksByGroup.values().stream().filter(rank -> rank > 0).count();
        int totalGroups = ranksByGroup.size();
        boolean anyHit = query.answerable() && hitGroups > 0;
        boolean fullHit = query.answerable() && hitGroups == totalGroups;
        int firstRelevantRank = ranksByGroup.values().stream()
                .mapToInt(Integer::intValue)
                .filter(rank -> rank > 0)
                .min()
                .orElse(0);
        double reciprocalRankAt10 = firstRelevantRank > 0 && firstRelevantRank <= 10
                ? 1.0 / firstRelevantRank
                : 0.0;
        double candidateRecallAt50 = query.answerable()
                ? (double) hitGroups / totalGroups
                : 0.0;
        double ndcgAt10 = query.answerable()
                ? ndcgAt10(query, rankedPassages)
                : 0.0;

        return new GoldenMeasurement(
                query,
                fullHit,
                anyHit,
                candidateRecallAt50,
                reciprocalRankAt10,
                ndcgAt10,
                Map.copyOf(ranksByGroup),
                response == null ? 0 : response.path("items").size(),
                response == null ? -1 : response.path("tookMs").asLong(),
                wallClockMs,
                wallClockMs > QUERY_TIMEOUT_MS,
                warmupErrors,
                error
        );
    }

    private static double ndcgAt10(
            RetrievalGoldenV2.Query query,
            List<String> rankedPassages
    ) {
        Map<String, Integer> grades = new HashMap<>();
        for (RetrievalGoldenV2.ExpectedPassage expected : query.relevantPassages()) {
            grades.merge(expected.passageKey(), expected.relevanceGrade(), Math::max);
        }
        List<Integer> observed = rankedPassages.stream()
                .limit(10)
                .map(passage -> passage == null ? 0 : grades.getOrDefault(passage, 0))
                .toList();
        List<Integer> ideal = query.evidenceGroups().stream()
                .map(group -> group.anyOf().stream()
                        .mapToInt(RetrievalGoldenV2.ExpectedPassage::relevanceGrade)
                        .max()
                        .orElse(0))
                .sorted(Comparator.reverseOrder())
                .limit(10)
                .toList();
        double idealDcg = dcg(ideal);
        return idealDcg == 0.0 ? 0.0 : dcg(observed) / idealDcg;
    }

    private static double dcg(List<Integer> grades) {
        double result = 0.0;
        for (int index = 0; index < grades.size(); index++) {
            result += (Math.pow(2.0, grades.get(index)) - 1.0)
                    / (Math.log(index + 2.0) / Math.log(2.0));
        }
        return result;
    }

    private Path writeGoldenV2Report(
            RetrievalGoldenV2.Loaded golden,
            GoldenCorpusFixture corpus,
            List<GoldenMeasurement> measurements,
            long peakHeapObserved
    ) throws Exception {
        Map<String, Object> languages = new LinkedHashMap<>();
        for (String language : List.of("zh", "en", "mixed")) {
            languages.put(
                    language,
                    goldenSummary(measurements.stream()
                            .filter(result -> result.query().language().equals(language))
                            .toList())
            );
        }

        Set<String> tags = new TreeSet<>();
        measurements.forEach(result -> tags.addAll(result.query().tags()));
        Map<String, Object> slices = new LinkedHashMap<>();
        for (String tag : tags) {
            slices.put(
                    tag,
                    goldenSummary(measurements.stream()
                            .filter(result -> result.query().tags().contains(tag))
                            .toList())
            );
        }

        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("metrics", List.of(
                "Candidate Hit@50", "Candidate Recall@50", "MRR@10", "nDCG@10"
        ));
        evaluation.put("candidateDepth", CANDIDATE_DEPTH);
        evaluation.put("queryCount", measurements.size());
        evaluation.put(
                "answerableQueryCount",
                measurements.stream().filter(result -> result.query().answerable()).count()
        );
        evaluation.put(
                "noAnswerQueryCount",
                measurements.stream().filter(result -> !result.query().answerable()).count()
        );
        evaluation.put("warmupRunsPerQuery", WARMUP_RUNS);
        evaluation.put("measuredRunsPerQuery", MEASURED_RUNS);
        evaluation.put("measuredRequestCount", measurements.size() * MEASURED_RUNS);
        evaluation.put("timeoutMs", QUERY_TIMEOUT_MS);
        evaluation.put(
                "noAnswerPolicy",
                "Excluded from relevance metrics; retained for later evidence-threshold calibration"
        );

        Map<String, Object> corpusMetadata = new LinkedHashMap<>();
        corpusMetadata.put("sha256", golden.corpusSha256());
        corpusMetadata.put("childCount", corpus.childCount());
        corpusMetadata.put("hardNegativeCount", corpus.hardNegativeCount());
        corpusMetadata.put("anchoredPassageCount", corpus.anchors().size());

        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("dataset", golden.dataset().version());
        versions.put("corpus", golden.corpus().version());
        versions.put("schema", activeSchemaVersion());
        versions.put("retrievalProfile", currentRetrievalProfileVersion());
        versions.put("pipeline", pipelineProperties.pipelineVersion());
        versions.put("parser", pipelineProperties.parserVersion());
        versions.put("chunker", pipelineProperties.chunkerVersion());
        versions.put("chunkingProfile", pipelineProperties.chunkingProfile().version());
        versions.put("embeddingModel", null);
        versions.put("rerankerModel", null);

        Map<String, Object> manifest = jdbc.queryForObject("""
                SELECT manifest.id, manifest.index_generation, manifest.index_name,
                       config.schema_version, config.version AS index_config_version,
                       publication.profile_version
                FROM index_manifests manifest
                JOIN index_configs config
                  ON config.version = manifest.index_config_version
                CROSS JOIN retrieval_publications publication
                WHERE manifest.status = 'ACTIVE'
                  AND publication.singleton_id = 1
                """, (resultSet, rowNumber) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", resultSet.getObject("id").toString());
            value.put("generation", resultSet.getLong("index_generation"));
            value.put("indexName", resultSet.getString("index_name"));
            value.put("schemaVersion", resultSet.getString("schema_version"));
            value.put("indexConfigVersion", resultSet.getString("index_config_version"));
            value.put("retrievalProfileVersion", resultSet.getString("profile_version"));
            return value;
        });

        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("jvm", System.getProperty("java.vm.name"));
        environment.put("os", System.getProperty("os.name"));
        environment.put("osVersion", System.getProperty("os.version"));
        environment.put("arch", System.getProperty("os.arch"));
        environment.put("processors", runtime.availableProcessors());
        environment.put("maxHeapBytes", runtime.maxMemory());
        environment.put("peakHeapObservedBytes", peakHeapObserved);

        List<Map<String, Object>> cases = measurements.stream()
                .map(SearchIntegrationTests::goldenCase)
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetVersion", golden.dataset().version());
        report.put("corpusVersion", golden.corpus().version());
        report.put("generatedAt", Instant.now().toString());
        report.put("evaluation", evaluation);
        report.put("corpus", corpusMetadata);
        report.put("versions", versions);
        report.put("manifest", manifest);
        report.put("environment", environment);
        report.put("totals", goldenSummary(measurements));
        report.put("languages", languages);
        report.put("slices", slices);
        report.put("cases", cases);

        Path output = Path.of(
                "target", "phase6a-reports", "retrieval-golden-v2-bm25.json"
        );
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return output;
    }

    private static Map<String, Object> goldenSummary(List<GoldenMeasurement> measurements) {
        List<GoldenMeasurement> answerable = measurements.stream()
                .filter(result -> result.query().answerable())
                .toList();
        List<Long> latencies = measurements.stream()
                .filter(result -> result.error().isBlank())
                .map(GoldenMeasurement::wallClockMs)
                .toList();
        int fullHits = (int) answerable.stream().filter(GoldenMeasurement::fullHit).count();
        int anyHits = (int) answerable.stream().filter(GoldenMeasurement::anyHit).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queries", measurements.size());
        summary.put("answerableQueries", answerable.size());
        summary.put("noAnswerQueries", measurements.size() - answerable.size());
        summary.put("candidateFullHits", fullHits);
        summary.put("candidateAnyHits", anyHits);
        summary.put(
                "candidateHitAt50",
                answerable.isEmpty() ? 0.0 : (double) fullHits / answerable.size()
        );
        summary.put(
                "candidateAnyHitAt50",
                answerable.isEmpty() ? 0.0 : (double) anyHits / answerable.size()
        );
        summary.put("candidateRecallAt50", average(
                answerable.stream().map(GoldenMeasurement::candidateRecallAt50).toList()
        ));
        summary.put("mrrAt10", average(
                answerable.stream().map(GoldenMeasurement::reciprocalRankAt10).toList()
        ));
        summary.put("ndcgAt10", average(
                answerable.stream().map(GoldenMeasurement::ndcgAt10).toList()
        ));
        summary.put(
                "errors",
                measurements.stream().filter(result -> !result.error().isBlank()).count()
        );
        summary.put(
                "timeouts",
                measurements.stream().filter(GoldenMeasurement::timedOut).count()
        );
        summary.put(
                "warmupErrors",
                measurements.stream().mapToInt(GoldenMeasurement::warmupErrors).sum()
        );
        summary.put("p50Ms", percentile(latencies, 0.50));
        summary.put("p95Ms", percentile(latencies, 0.95));
        return summary;
    }

    private static double average(List<Double> values) {
        return values.isEmpty()
                ? 0.0
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static Map<String, Object> goldenCase(GoldenMeasurement result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", result.query().id());
        value.put("language", result.query().language());
        value.put("query", result.query().query());
        value.put("tags", result.query().tags());
        value.put("answerable", result.query().answerable());
        value.put("candidateHitAt50", result.query().answerable() ? result.fullHit() : null);
        value.put(
                "candidateRecallAt50",
                result.query().answerable() ? result.candidateRecallAt50() : null
        );
        value.put(
                "reciprocalRankAt10",
                result.query().answerable() ? result.reciprocalRankAt10() : null
        );
        value.put("ndcgAt10", result.query().answerable() ? result.ndcgAt10() : null);
        value.put("ranksByEvidenceGroup", result.ranksByGroup());
        value.put("returnedCandidates", result.returnedCandidates());
        value.put("openSearchTookMs", result.openSearchTookMs());
        value.put("wallClockMs", result.wallClockMs());
        value.put("timeout", result.timedOut());
        value.put("warmupErrors", result.warmupErrors());
        value.put("error", result.error());
        return value;
    }

    private Path writeGoldenReport(
            JsonNode dataset,
            Map<String, Integer> totals,
            Map<String, Integer> hits,
            Map<String, Integer> errors,
            Map<String, Integer> timeouts,
            Map<String, Integer> warmupErrors,
            Map<String, List<Long>> latencies,
            List<Map<String, Object>> cases,
            long peakHeapObserved
    ) throws Exception {
        Map<String, Object> languages = new LinkedHashMap<>();
        for (String language : List.of("zh", "en", "mixed")) {
            int total = totals.getOrDefault(language, 0);
            int hit = hits.getOrDefault(language, 0);
            List<Long> times = latencies.getOrDefault(language, List.of());
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("queries", total);
            metrics.put("hits", hit);
            metrics.put("candidateHitAt50", total == 0 ? 0.0 : (double) hit / total);
            metrics.put("errors", errors.getOrDefault(language, 0));
            metrics.put("timeouts", timeouts.getOrDefault(language, 0));
            metrics.put("warmupErrors", warmupErrors.getOrDefault(language, 0));
            metrics.put("p50Ms", percentile(times, 0.50));
            metrics.put("p95Ms", percentile(times, 0.95));
            languages.put(language, metrics);
        }
        int queryCount = dataset.path("queries").size();
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("metric", "Candidate Hit@50");
        evaluation.put("candidateDepth", CANDIDATE_DEPTH);
        evaluation.put("queryCount", queryCount);
        evaluation.put("warmupRunsPerQuery", WARMUP_RUNS);
        evaluation.put("measuredRunsPerQuery", MEASURED_RUNS);
        evaluation.put("measuredRequestCount", queryCount * MEASURED_RUNS);
        evaluation.put("timeoutMs", QUERY_TIMEOUT_MS);

        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("dataset", dataset.path("version").asText());
        versions.put("schema", activeSchemaVersion());
        versions.put("retrievalProfile", currentRetrievalProfileVersion());
        versions.put("pipeline", pipelineProperties.pipelineVersion());
        versions.put("parser", pipelineProperties.parserVersion());
        versions.put("chunker", pipelineProperties.chunkerVersion());
        versions.put("chunkingProfile", pipelineProperties.chunkingProfile().version());

        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("jvm", System.getProperty("java.vm.name"));
        environment.put("os", System.getProperty("os.name"));
        environment.put("osVersion", System.getProperty("os.version"));
        environment.put("arch", System.getProperty("os.arch"));
        environment.put("processors", runtime.availableProcessors());
        environment.put("maxHeapBytes", runtime.maxMemory());
        environment.put("peakHeapObservedBytes", peakHeapObserved);

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("queries", queryCount);
        aggregate.put("hits", hits.values().stream().mapToInt(Integer::intValue).sum());
        aggregate.put("errors", errors.values().stream().mapToInt(Integer::intValue).sum());
        aggregate.put("timeouts", timeouts.values().stream().mapToInt(Integer::intValue).sum());
        aggregate.put(
                "warmupErrors",
                warmupErrors.values().stream().mapToInt(Integer::intValue).sum()
        );

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetVersion", dataset.path("version").asText());
        report.put("generatedAt", Instant.now().toString());
        report.put("indexName", indexes.activeIndexName().orElseThrow());
        report.put("evaluation", evaluation);
        report.put("versions", versions);
        report.put("environment", environment);
        report.put("totals", aggregate);
        report.put("languages", languages);
        report.put("cases", cases);

        Path output = Path.of("target", "phase5-reports", "retrieval-golden-v1.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return output;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static List<Double> testVector(String input) {
        List<Double> vector = new ArrayList<>(1024);
        vector.add(1.0);
        vector.add((double) Math.floorMod(input.hashCode(), 997) / 997.0);
        while (vector.size() < 1024) {
            vector.add(0.001);
        }
        return List.copyOf(vector);
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static boolean contains(JsonNode array, String expected) {
        for (JsonNode value : array) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> resultIdentity(JsonNode response) {
        List<String> result = new ArrayList<>();
        response.path("items").forEach(item -> result.add(
                item.path("documentId").asText() + ":"
                        + item.path("revisionId").asText() + ":"
                        + item.path("chunkId").asText()
        ));
        return result;
    }

    private UserEntity createUser(String prefix, UserRole role) {
        return users.saveAndFlush(new UserEntity(
                prefix + "-" + UUID.randomUUID(),
                passwordEncoder.encode("local-pass-123"),
                role
        ));
    }

    private PlatformUserPrincipal principal(UserEntity user) {
        return PlatformUserPrincipal.from(user);
    }

    private void resetDedicatedTestState() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (!"rag_test".equals(database)
                || !"rag-documents-test".equals(storageProperties.bucket())
                || !searchProperties.getEndpoint().contains("opensearch-test")) {
            throw new IllegalStateException(
                    "Search tests require rag_test, the test bucket, and opensearch-test"
            );
        }
        jdbc.queryForList("SELECT index_name FROM index_manifests", String.class)
                .forEach(openSearch::deleteIndex);
        jdbc.execute("""
                TRUNCATE TABLE embedding_cache_events, embedding_artifacts,
                    search_projection_states, index_manifests,
                    source_spans, chunks, content_blocks, parsed_documents,
                    pipeline_jobs, document_acl_entries, document_revisions, documents
                CASCADE
                """);
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO retrieval_publication_events (
                    profile_version, action, reason
                ) VALUES ('phase5-bm25-v1', 'MIGRATION', 'Search integration test reset')
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

    private record Revision(UUID revisionId, UUID parentId, UUID childId) {
    }

    private record GoldenAnchor(
            UUID documentId,
            UUID revisionId,
            UUID chunkId,
            RetrievalGoldenV2.Passage passage
    ) {
    }

    private record GoldenCorpusFixture(
            Map<String, GoldenAnchor> anchors,
            Map<UUID, String> passageKeyByChunkId,
            int childCount,
            int hardNegativeCount
    ) {
    }

    private record GoldenMeasurement(
            RetrievalGoldenV2.Query query,
            boolean fullHit,
            boolean anyHit,
            double candidateRecallAt50,
            double reciprocalRankAt10,
            double ndcgAt10,
            Map<String, Integer> ranksByGroup,
            int returnedCandidates,
            long openSearchTookMs,
            long wallClockMs,
            boolean timedOut,
            int warmupErrors,
            String error
    ) {
    }

    private record HybridMeasurement(
            RetrievalGoldenV2.Query query,
            boolean candidateHit,
            boolean evidenceHit,
            double evidenceRecallAt8,
            double contextRecallAt8,
            double contextPrecisionAt8,
            double mrrAt10,
            double ndcgAt10,
            long tookMs
    ) {
    }

    private record PerformanceSamples(
            Map<String, Map<String, Long>> stages,
            int errors
    ) {
        long p95(String stage) {
            return stages.get(stage).get("p95Ms");
        }
    }

    private record Fixture(
            UUID publicDocumentId,
            UUID publicRevisionId,
            UUID publicChildId,
            UUID secondPublicChildId,
            UUID restrictedDocumentId,
            UUID restrictedRevisionId,
            UUID restrictedChildId
    ) {
    }
}
