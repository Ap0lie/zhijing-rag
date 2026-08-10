package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.graph.LocalGraphRetrievalService;
import com.example.rag.graph.LocalGraphRetrievalService.GraphCandidate;
import com.example.rag.graph.LocalGraphRetrievalService.GraphPath;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalMode;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalProfileView;
import com.example.rag.search.SearchAccessService.AuthorizedRevision;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchContracts.SearchPage;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.search.SearchIndexService.ActiveIndex;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchContractTests {

    @Test
    void coverageRequiresEnoughAuthorizedEvidenceCandidates() {
        assertThat(SearchService.coverageSufficient(8, 8)).isTrue();
        assertThat(SearchService.coverageSufficient(7, 8)).isFalse();
    }

    @Test
    void graphCandidatesPreferQueryRelevantPathEvidence() {
        GraphCandidate unrelated = graphCandidate(
                1, "Unrelated archive text", "ARCHIVED_WITH"
        );
        GraphCandidate relevant = graphCandidate(
                2,
                "Ada Lovelace was born in London.",
                "BORN_IN"
        );

        assertThat(SearchService.rankGraphCandidates(
                "Where was Ada Lovelace born?",
                List.of(unrelated, relevant)
        )).extracting(GraphCandidate::childId)
                .containsExactly(relevant.childId(), unrelated.childId());
    }

    @Test
    void evidenceSelectionKeepsTheBestCompleteTwoHopPath() {
        List<SearchService.Candidate> ordered = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ordered.add(candidate(UUID.randomUUID()));
        }
        UUID parentRelationship = UUID.randomUUID();
        SearchService.Candidate parent = candidate(UUID.randomUUID());
        parent.addGraph(1, List.of(path(
                1, parentRelationship, null, parent.chunkId()
        )));
        SearchService.Candidate child = candidate(UUID.randomUUID());
        child.addGraph(2, List.of(path(
                2, UUID.randomUUID(), parentRelationship, child.chunkId()
        )));
        ordered.add(parent);
        ordered.add(child);

        List<SearchService.Candidate> selected =
                SearchService.diversifiedEvidence(
                        "Find the complete relationship path",
                        ordered, 8, List.of()
                );

        assertThat(selected).hasSize(8).contains(parent, child);
    }

    @Test
    void evidenceSelectionKeepsCrossDocumentBridgeEvidence() {
        List<SearchService.Candidate> ordered = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ordered.add(candidate(
                    UUID.randomUUID(), "Unrelated archive evidence " + index
            ));
        }
        SearchService.Candidate seed = candidate(
                UUID.randomUUID(),
                "American sweetgum is Liquidambar styraciflua."
        );
        SearchService.Candidate graph = candidate(
                UUID.randomUUID(),
                "Phyllocnistis liquidambarisella is a moth."
        );
        UUID graphRelationship = UUID.randomUUID();
        graph.addGraph(1, List.of(new GraphPath(
                1,
                graphRelationship,
                null,
                "HOST_PLANT_OF",
                graph.chunkId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Phyllocnistis liquidambarisella",
                1,
                1,
                "The hostplant is Liquidambar styraciflua."
        )));
        ordered.add(seed);
        ordered.add(graph);

        List<SearchService.Candidate> selected =
                SearchService.diversifiedEvidence(
                        "The American Sweetgum is hostplant of what bug?",
                        ordered, 8, List.of()
                );

        assertThat(selected).hasSize(8).contains(seed, graph);
    }

    @Test
    void rerankInputReservesQueryRankedGraphCandidates() {
        List<SearchService.Candidate> verified = new java.util.ArrayList<>();
        for (int index = 0; index < 30; index++) {
            verified.add(candidate(
                    UUID.randomUUID(), "Hybrid candidate " + index
            ));
        }
        SearchService.Candidate graph = candidate(
                UUID.randomUUID(), "Graph bridge evidence"
        );
        graph.addGraph(1, List.of(path(
                1, UUID.randomUUID(), null, graph.chunkId()
        )));
        verified.add(graph);

        List<SearchService.Candidate> selected =
                SearchService.rerankInput(verified, 30, List.of());

        assertThat(selected).hasSize(30).contains(graph);
    }

    @Test
    void rerankInputUsesAStableQueryAndBranchReservoir() {
        List<SearchService.Candidate> verified = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            verified.add(candidate(
                    UUID.randomUUID(), "RRF candidate " + index
            ));
        }
        SearchService.Candidate primary = verified.get(0);
        SearchService.Candidate secondaryFirst = verified.get(30);
        SearchService.Candidate secondarySecond = verified.get(31);
        SearchService.Candidate bm25Only = verified.get(32);
        SearchService.Candidate vectorOnly = verified.get(33);
        SearchService.Candidate graph = verified.get(34);
        primary.add(SearchService.Branch.BM25, "1:1", 1, 1.0, null);
        secondaryFirst.add(
                SearchService.Branch.BM25, "1:2", 7, 1.0, null
        );
        secondarySecond.add(
                SearchService.Branch.VECTOR, "1:2", 8, 1.0, null
        );
        bm25Only.add(SearchService.Branch.BM25, "1:1", 2, 1.0, null);
        vectorOnly.add(SearchService.Branch.VECTOR, "1:1", 1, 1.0, null);
        graph.addGraph(1, List.of(path(
                1, UUID.randomUUID(), null, graph.chunkId()
        )));
        List<SearchContracts.QuerySlot> slots = List.of(
                new SearchContracts.QuerySlot(
                        1, 1, "primary", "SUCCESS", 20, null
                ),
                new SearchContracts.QuerySlot(
                        1, 2, "secondary", "SUCCESS", 20, null
                )
        );

        List<SearchService.Candidate> first =
                SearchService.rerankInput(verified, 30, slots);
        List<SearchService.Candidate> second =
                SearchService.rerankInput(verified, 30, slots);

        assertThat(first)
                .hasSize(30)
                .doesNotHaveDuplicates()
                .contains(
                        secondaryFirst,
                        secondarySecond,
                        bm25Only,
                        vectorOnly,
                        graph
                );
        assertThat(second).containsExactlyElementsOf(first);
    }

    @Test
    void rerankInputKeepsTheOriginalTopThirtyForOneQuery() {
        List<SearchService.Candidate> verified = new java.util.ArrayList<>();
        for (int index = 0; index < 35; index++) {
            SearchService.Candidate item = candidate(
                    UUID.randomUUID(), "Single query candidate " + index
            );
            item.add(
                    SearchService.Branch.BM25,
                    "1:1",
                    index + 1,
                    1.0,
                    null
            );
            item.add(
                    SearchService.Branch.VECTOR,
                    "1:1",
                    index + 1,
                    1.0,
                    null
            );
            verified.add(item);
        }

        List<SearchService.Candidate> selected = SearchService.rerankInput(
                verified,
                30,
                List.of(new SearchContracts.QuerySlot(
                        1, 1, "query", "SUCCESS", 35, null
                ))
        );

        assertThat(selected).containsExactlyElementsOf(verified.subList(0, 30));
    }

    @Test
    void evidenceSelectionSkipsEmptySlotsAndKeepsComplementaryDocuments() {
        List<SearchService.Candidate> ordered = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ordered.add(candidate(
                    UUID.randomUUID(), "Leading evidence " + index
            ));
        }
        SearchService.Candidate first = candidate(
                UUID.randomUUID(), "First complementary evidence"
        );
        SearchService.Candidate second = candidate(
                UUID.randomUUID(), "Second complementary evidence"
        );
        first.add(SearchService.Branch.BM25, "1:3", 1, 1.0, null);
        second.add(SearchService.Branch.VECTOR, "2:1", 1, 1.0, null);
        ordered.add(first);
        ordered.add(second);
        List<SearchContracts.QuerySlot> slots = List.of(
                new SearchContracts.QuerySlot(
                        1, 1, "primary", "SUCCESS", 20, null
                ),
                new SearchContracts.QuerySlot(
                        1, 2, "failed", "FAILED", 0, "BRANCH_FAILED"
                ),
                new SearchContracts.QuerySlot(
                        1, 3, "first", "SUCCESS", 10, null
                ),
                new SearchContracts.QuerySlot(
                        2, 1, "second", "SUCCESS", 10, null
                )
        );

        List<SearchService.Candidate> selected =
                SearchService.diversifiedEvidence(
                        "primary", ordered, 8, slots
                );

        assertThat(selected).hasSize(8).contains(first, second);
    }

    @Test
    void resultDrivenSecondHopUsesNovelEntityFromAuthorizedEvidence() {
        String query = "What other political position did the person who introduced the DISCLOSE Act hold?";
        SearchService.Candidate evidence = candidate(
                UUID.randomUUID(),
                "[EVAL] DISCLOSE Act",
                "The DISCLOSE Act was introduced by Chris Van Hollen in the United States Senate."
        );

        List<String> queries = SearchService.resultDrivenSecondHopQueries(
                query,
                List.of(evidence),
                List.of(new SearchContracts.QuerySlot(
                        1, 1, query, "SUCCESS", 20, null
                )),
                2
        );

        assertThat(queries)
                .contains(query + " Chris Van Hollen")
                .noneMatch(value -> value.endsWith("DISCLOSE Act"));
    }

    @Test
    void resultDrivenSecondHopRespectsRemainingSlotBudget() {
        SearchService.Candidate first = candidate(
                UUID.randomUUID(), "Adele",
                "Adele broke the sales record held by Robson & Jerome."
        );

        assertThat(SearchService.resultDrivenSecondHopQueries(
                "How many days did the Best New Artist need?",
                List.of(first),
                List.of(),
                1
        )).singleElement();
    }

    @Test
    void secondRoundRrfSupplementsWithoutOutweighingFirstRound() {
        double firstRound = SearchService.rrfContribution("1:1", 1, 60);
        double twoSecondRoundBranches = 2
                * SearchService.rrfContribution("2:1", 1, 60);

        assertThat(twoSecondRoundBranches).isLessThan(firstRound);
    }

    @Test
    void searchControllerStartsWithoutChatRoutingBean() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SearchOnlyConfiguration.class)
                .withBean(SearchService.class, () -> mock(SearchService.class))
                .withPropertyValues(
                        "rag.search.enabled=true",
                        "rag.chat.enabled=false"
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(SearchController.class))
                            .hasSize(1);

                    SearchService search = context.getBean(SearchService.class);
                    SearchController controller =
                            context.getBean(SearchController.class);
                    SearchRequest request = new SearchRequest(
                            "query", null, null, null, null
                    );
                    PlatformUserPrincipal user =
                            mock(PlatformUserPrincipal.class);
                    SearchPage expected = new SearchPage(
                            List.of(), 0, 20, 0, 0, 0,
                            "profile", 0, "BM25", "BM25",
                            false, null, "EXACT"
                    );
                    when(search.search(request, user)).thenReturn(expected);

                    assertThat(controller.search(request, user))
                            .isSameAs(expected);
                });
    }

    @Test
    void projectionKeyBindsDocumentRevisionAndAclVersion() {
        UUID documentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID revisionId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(SearchAccessService.projectionKey(documentId, revisionId, 7))
                .isEqualTo(documentId + ":" + revisionId + ":7");
    }

    @Test
    void requestBoundsPagination() {
        assertThat(new SearchRequest("query", -1, 500, null, null).safePage()).isZero();
        assertThat(new SearchRequest("query", -1, 500, null, null).safeSize()).isEqualTo(50);
        assertThat(new SearchRequest("query", null, null, null, null).safeSize()).isEqualTo(20);
        assertThat(new SearchRequest("query", Integer.MAX_VALUE, 50, null, null).safePage())
                .isEqualTo(199);
    }

    @Test
    void serviceEnforcesThePublicQueryLimitForInternalCallers() {
        SearchAccessService access = mock(SearchAccessService.class);
        SearchIndexService indexes = mock(SearchIndexService.class);
        RetrievalConfigurationRepository configurations =
                mock(RetrievalConfigurationRepository.class);
        SearchService service = service(access, indexes, configurations);

        assertThatThrownBy(() -> service.search(
                new SearchRequest(
                        "x".repeat(SearchContracts.MAX_QUERY_LENGTH + 1),
                        null, null, null, null
                ),
                mock(PlatformUserPrincipal.class)
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo("SEARCH_QUERY_TOO_LONG");
        });
        verifyNoInteractions(access, indexes, configurations);
    }

    @Test
    void explicitLocalGraphReportsNoAuthorizedSourceOnEarlyEmptyResult() {
        SearchAccessService access = mock(SearchAccessService.class);
        SearchIndexService indexes = mock(SearchIndexService.class);
        RetrievalConfigurationRepository configurations =
                mock(RetrievalConfigurationRepository.class);
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(configurations.currentProfile()).thenReturn(profile());
        when(access.authorized(user, null, null)).thenReturn(List.of());
        when(indexes.activeIndex()).thenReturn(Optional.of(
                new ActiveIndex("rag-chunks-1", 1, "phase6-hybrid-qwen3-v1")
        ));

        var result = service(access, indexes, configurations).search(
                new SearchRequest("query", null, null, null, null, GraphMode.LOCAL_GRAPH),
                user
        );

        assertThat(result.graphModeUsed()).isEqualTo(GraphMode.HYBRID.name());
        assertThat(result.graphDegraded()).isTrue();
        assertThat(result.graphDegradationCode()).isEqualTo("GRAPH_NO_AUTHORIZED_SOURCE");
        assertThat(result.queryExecution().standaloneQuery()).isEqualTo("query");
        assertThat(result.queryExecution().plannerCallCount()).isZero();
        assertThat(result.queryExecution().retrievalCallCount()).isZero();
        assertThat(result.queryExecution().rerankCallCount()).isZero();
        assertThat(result.queryExecution().degraded()).isTrue();
        assertThat(result.queryExecution().degradationCode())
                .isEqualTo("NO_AUTHORIZED_SOURCE");
        assertThat(result.queryExecution().slots())
                .singleElement()
                .satisfies(slot -> {
                    assertThat(slot.query()).isEqualTo("query");
                    assertThat(slot.status()).isEqualTo("SKIPPED");
                    assertThat(slot.candidateCount()).isZero();
                    assertThat(slot.degradationCode())
                            .isEqualTo("NO_AUTHORIZED_SOURCE");
                });
    }

    @Test
    void explicitAutoReportsUnavailableActiveIndexOnEarlyEmptyResult() {
        SearchAccessService access = mock(SearchAccessService.class);
        SearchIndexService indexes = mock(SearchIndexService.class);
        RetrievalConfigurationRepository configurations =
                mock(RetrievalConfigurationRepository.class);
        PlatformUserPrincipal user = mock(PlatformUserPrincipal.class);
        when(configurations.currentProfile()).thenReturn(profile());
        when(access.authorized(user, null, null)).thenReturn(List.of(
                new AuthorizedRevision(
                        UUID.randomUUID(),
                        "Document",
                        UUID.randomUUID(),
                        1,
                        1
                )
        ));
        when(indexes.activeIndex()).thenReturn(Optional.empty());

        var result = service(access, indexes, configurations).searchPlanned(
                new SearchRequest(
                        "query", null, null, null, null, GraphMode.AUTO
                ),
                user,
                null,
                new SearchService.QueryPlan(
                        "rewritten query",
                        List.of("rewritten query", "related query"),
                        1,
                        false,
                        null,
                        GraphMode.GLOBAL_GRAPH,
                        "GLOBAL_SYNTHESIS"
                ),
                null,
                new SearchService.RoutingDecision(
                        GraphMode.GLOBAL_GRAPH,
                        1,
                        "GLOBAL_SYNTHESIS",
                        false,
                        null
                ),
                SearchService.QueryExecutionPolicy.start(
                        3, 2, 2, 5_000
                )
        );

        assertThat(result.graphModeUsed()).isEqualTo(GraphMode.HYBRID.name());
        assertThat(result.graphDegraded()).isTrue();
        assertThat(result.graphDegradationCode())
                .isEqualTo("GRAPH_ACTIVE_INDEX_UNAVAILABLE");
        assertThat(result.routeExecution().requestedMode())
                .isEqualTo(GraphMode.AUTO.name());
        assertThat(result.routeExecution().selectedMode())
                .isEqualTo(GraphMode.GLOBAL_GRAPH.name());
        assertThat(result.routeExecution().routerCallCount()).isEqualTo(1);
        assertThat(result.routeExecution().reasonCode())
                .isEqualTo("GLOBAL_SYNTHESIS");
        assertThat(result.queryExecution().standaloneQuery())
                .isEqualTo("rewritten query");
        assertThat(result.queryExecution().plannerCallCount()).isEqualTo(1);
        assertThat(result.queryExecution().retrievalCallCount()).isZero();
        assertThat(result.queryExecution().rerankCallCount()).isZero();
        assertThat(result.queryExecution().degraded()).isTrue();
        assertThat(result.queryExecution().degradationCode())
                .isEqualTo("ACTIVE_INDEX_UNAVAILABLE");
        assertThat(result.queryExecution().slots())
                .extracting(
                        SearchContracts.QuerySlot::query,
                        SearchContracts.QuerySlot::status,
                        SearchContracts.QuerySlot::degradationCode
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "rewritten query",
                                "SKIPPED",
                                "ACTIVE_INDEX_UNAVAILABLE"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "related query",
                                "SKIPPED",
                                "ACTIVE_INDEX_UNAVAILABLE"
                        )
                );
    }

    @Test
    @SuppressWarnings("unchecked")
    void mappingUsesCjkWithEnglishMultifields() {
        Map<String, Object> definition = SearchIndexService.indexDefinition();
        Map<String, Object> mappings = (Map<String, Object>) definition.get("mappings");
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");

        for (String field : new String[]{"text", "title", "heading"}) {
            Map<String, Object> mapping = (Map<String, Object>) properties.get(field);
            assertThat(mapping.get("analyzer")).isEqualTo("cjk");
            Map<String, Object> multifields = (Map<String, Object>) mapping.get("fields");
            assertThat((Map<String, Object>) multifields.get("english"))
                    .containsEntry("analyzer", "english");
        }
        assertThat(properties).containsKeys(
                "chunkId", "parentChunkId", "accessProjectionKey", "aclVersion"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceLocatorGenerationUsesStrictFormatNeutralMapping() {
        IndexConfigView config = new IndexConfigView(
                "phase15-source-locator-bm25-v1",
                "source-locator-v1",
                "cjk+english-multifield",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH
        );

        Map<String, Object> definition =
                SearchIndexService.indexDefinition(config);
        Map<String, Object> mappings =
                (Map<String, Object>) definition.get("mappings");
        Map<String, Object> properties =
                (Map<String, Object>) mappings.get("properties");
        Map<String, Object> locator =
                (Map<String, Object>) properties.get("sourceLocator");

        assertThat(properties).containsKeys(
                "documentFormat", "sourceLocator", "sourceLabel"
        );
        assertThat(locator).containsEntry("dynamic", "strict");
        assertThat((Map<String, Object>) locator.get("properties"))
                .containsKeys(
                        "kind", "startUnit", "endUnit",
                        "startOffset", "endOffset", "address",
                        "sourceTextHash", "normalizationVersion",
                        "startPage", "endPage", "sourceLabel"
                );
    }

    private static RetrievalProfileView profile() {
        return new RetrievalProfileView(
                "phase6c-hybrid-rerank-v1",
                RetrievalMode.HYBRID,
                20,
                50,
                50,
                50,
                60,
                30,
                8,
                6_000,
                Instant.EPOCH
        );
    }

    private static GraphCandidate graphCandidate(
            int rank,
            String evidence,
            String relationshipType
    ) {
        UUID childId = UUID.randomUUID();
        return new GraphCandidate(
                childId,
                rank,
                List.of(new GraphPath(
                        1,
                        UUID.randomUUID(),
                        null,
                        relationshipType,
                        childId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Reference",
                        1,
                        1,
                        evidence
                ))
        );
    }

    private static SearchService.Candidate candidate(UUID documentId) {
        return candidate(documentId, "Evidence");
    }

    private static SearchService.Candidate candidate(
            UUID documentId,
            String text
    ) {
        return candidate(documentId, "Document", text);
    }

    private static SearchService.Candidate candidate(
            UUID documentId,
            String title,
            String text
    ) {
        UUID childId = UUID.randomUUID();
        var source = com.fasterxml.jackson.databind.node.JsonNodeFactory
                .instance.objectNode();
        source.put("documentId", documentId.toString());
        source.put("revisionId", UUID.randomUUID().toString());
        source.put("title", title);
        source.put("headingPath", "");
        source.put("text", text);
        return new SearchService.Candidate(childId, source);
    }

    private static GraphPath path(
            int depth,
            UUID relationshipId,
            UUID parentRelationshipId,
            UUID childId
    ) {
        return new GraphPath(
                depth,
                relationshipId,
                parentRelationshipId,
                "RELATED_TO",
                childId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Document",
                1,
                1,
                "Path evidence"
        );
    }

    private static SearchService service(
            SearchAccessService access,
            SearchIndexService indexes,
            RetrievalConfigurationRepository configurations
    ) {
        return new SearchService(
                access,
                indexes,
                mock(OpenSearchGateway.class),
                configurations,
                mock(EmbeddingCacheService.class),
                mock(RerankProvider.class),
                mock(EvidenceContextService.class),
                mock(LocalGraphRetrievalService.class),
                java.util.Optional.of(mock(
                        com.example.rag.graph.GlobalGraphRetrievalService.class
                )),
                mock(ModelCircuitBreakers.class),
                mock(SearchProperties.class),
                mock(ExecutorService.class)
        );
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SearchController.class)
    static class SearchOnlyConfiguration {
    }
}
