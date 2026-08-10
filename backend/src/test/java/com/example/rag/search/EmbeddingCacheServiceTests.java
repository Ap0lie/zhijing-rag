package com.example.rag.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.rag.search.EmbeddingArtifactRepository.ArtifactSummary;
import com.example.rag.search.EmbeddingArtifactRepository.StoredArtifact;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingCacheServiceTests {

    private static final int QUERY_CACHE_DIMENSIONS = 1_024;
    private static final int QUERY_CACHE_ENTRIES = 2_048;
    private static final int QUERY_CACHE_WARMUPS = 50;
    private static final int QUERY_CACHE_ROUNDS = 3;
    private static final int QUERY_CACHE_CALLS_PER_ROUND = 300;
    private static final double QUERY_CACHE_P95_LIMIT_MS = 5.0d;
    private static final long QUERY_CACHE_HEAP_LIMIT_BYTES = 32L * 1024 * 1024;

    @Test
    void fp32CodecRejectsCorruptAndInvalidVectors() {
        var encoded = EmbeddingCacheService.encode(List.of(0.1d, 0.2d, 0.3d), 3);

        assertThat(EmbeddingCacheService.decode(
                encoded.bytes(), encoded.checksum(), 3
        )).containsExactly(
                (double) (float) 0.1d,
                (double) (float) 0.2d,
                (double) (float) 0.3d
        );
        assertThatThrownBy(() -> EmbeddingCacheService.decode(
                encoded.bytes(), "invalid", 3
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingCacheService.encode(
                List.of(0.0d, 0.0d, 0.0d), 3
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingCacheService.encode(
                List.of(0.1d, Double.NaN, 0.3d), 3
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneHundredConcurrentQueriesUseOneModelCall() throws Exception {
        EmbeddingProvider provider = mock(EmbeddingProvider.class);
        EmbeddingArtifactRepository artifacts = emptyArtifacts();
        SearchProperties properties = new SearchProperties();
        when(provider.descriptor()).thenReturn(descriptor("revision-1"));
        CountDownLatch enteredModel = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        when(provider.embed(anyList())).thenAnswer(invocation -> {
            enteredModel.countDown();
            assertThat(releaseModel.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(List.of(0.1d, 0.2d, 0.3d));
        });
        EmbeddingCacheService cache = service(provider, artifacts, properties);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<Double>>> requests = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                requests.add(CompletableFuture.supplyAsync(
                        () -> cache.embedQuery(config("revision-1"), "相同查询"),
                        executor
                ));
            }
            assertThat(enteredModel.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cache.stats().query().coalesced() < 99
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(cache.stats().query().coalesced()).isEqualTo(99);
            releaseModel.countDown();
            CompletableFuture.allOf(
                    requests.toArray(CompletableFuture[]::new)
            ).get(5, TimeUnit.SECONDS);
            assertThat(requests)
                    .allSatisfy(request -> assertThat(request.join())
                            .containsExactly(
                                    (double) (float) 0.1d,
                                    (double) (float) 0.2d,
                                    (double) (float) 0.3d
                            ));
        }

        assertThat(cache.embedQuery(config("revision-1"), "相同查询"))
                .containsExactly(
                        (double) (float) 0.1d,
                        (double) (float) 0.2d,
                        (double) (float) 0.3d
                );
        verify(provider).embed(List.of("相同查询"));
        assertThat(cache.stats().query().modelCalls()).isEqualTo(1);
        assertThat(cache.stats().query().savedModelCalls()).isEqualTo(100);
    }

    @Test
    void queryMissesAreBatchedAndReturnedInInputOrder() {
        EmbeddingProvider provider = mock(EmbeddingProvider.class);
        EmbeddingArtifactRepository artifacts = emptyArtifacts();
        SearchProperties properties = new SearchProperties();
        when(provider.descriptor()).thenReturn(descriptor("revision-1"));
        when(provider.embed(List.of("甲问题", "乙问题"))).thenReturn(
                List.of(
                        List.of(0.1d, 0.2d, 0.3d),
                        List.of(0.4d, 0.5d, 0.6d)
                )
        );
        EmbeddingCacheService cache = service(
                provider, artifacts, properties
        );

        assertThat(cache.embedQueries(
                config("revision-1"),
                List.of("甲问题", "乙问题")
        )).containsExactly(
                List.of(
                        (double) (float) 0.1d,
                        (double) (float) 0.2d,
                        (double) (float) 0.3d
                ),
                List.of(
                        (double) (float) 0.4d,
                        (double) (float) 0.5d,
                        (double) (float) 0.6d
                )
        );
        assertThat(cache.embedQueries(
                config("revision-1"),
                List.of("乙问题", "甲问题")
        )).containsExactly(
                List.of(
                        (double) (float) 0.4d,
                        (double) (float) 0.5d,
                        (double) (float) 0.6d
                ),
                List.of(
                        (double) (float) 0.1d,
                        (double) (float) 0.2d,
                        (double) (float) 0.3d
                )
        );
        verify(provider).embed(List.of("甲问题", "乙问题"));
        assertThat(cache.stats().query().modelCalls()).isEqualTo(1);
    }

    @Test
    void childArtifactHitSkipsModelAndCorruptionIsRepaired() {
        EmbeddingProvider provider = mock(EmbeddingProvider.class);
        EmbeddingArtifactRepository artifacts = emptyArtifacts();
        SearchProperties properties = new SearchProperties();
        when(provider.descriptor()).thenReturn(descriptor("revision-1"));
        String text = "cached child";
        String hash = EmbeddingCacheService.sha256(text);
        var encoded = EmbeddingCacheService.encode(List.of(0.1d, 0.2d, 0.3d), 3);
        StoredArtifact stored = new StoredArtifact(
                UUID.randomUUID(),
                hash,
                encoded.bytes(),
                encoded.checksum(),
                encoded.bytes().length
        );
        when(artifacts.find(any(), eq("CHILD_INDEX"), anyList()))
                .thenReturn(Map.of(hash, stored));
        EmbeddingCacheService cache = service(provider, artifacts, properties);

        assertThat(cache.embedChildren(
                config("revision-1"),
                List.of(new ChildEmbeddingInput(hash, text))
        )).containsExactly(List.of(
                (double) (float) 0.1d,
                (double) (float) 0.2d,
                (double) (float) 0.3d
        ));
        verify(provider, never()).embed(anyList());
        assertThat(cache.stats().artifacts().hits()).isEqualTo(1);

        StoredArtifact corrupt = new StoredArtifact(
                stored.id(),
                hash,
                encoded.bytes(),
                "broken",
                encoded.bytes().length
        );
        when(artifacts.find(any(), eq("CHILD_INDEX"), anyList()))
                .thenReturn(Map.of(hash, corrupt));
        when(provider.embed(anyList())).thenReturn(List.of(List.of(0.4d, 0.5d, 0.6d)));

        assertThat(cache.embedChildren(
                config("revision-1"),
                List.of(new ChildEmbeddingInput(hash, text))
        )).containsExactly(List.of(
                (double) (float) 0.4d,
                (double) (float) 0.5d,
                (double) (float) 0.6d
        ));
        verify(artifacts).delete(stored.id());
        verify(artifacts).save(any(), eq("CHILD_INDEX"), anyList());
        assertThat(cache.stats().artifacts().corruptions()).isEqualTo(1);
    }

    @Test
    void childArtifactsPersistOnceWhileProviderCallsStayBounded() {
        EmbeddingProvider provider = mock(EmbeddingProvider.class);
        EmbeddingArtifactRepository artifacts = emptyArtifacts();
        SearchProperties properties = new SearchProperties();
        properties.setEmbeddingBatchSize(32);
        when(provider.descriptor()).thenReturn(descriptor("revision-1"));
        when(provider.embed(anyList())).thenAnswer(invocation ->
                Collections.nCopies(
                        invocation.<List<String>>getArgument(0).size(),
                        List.of(0.1d, 0.2d, 0.3d)
                )
        );
        EmbeddingCacheService cache = service(provider, artifacts, properties);
        List<String> texts = new ArrayList<>();
        List<ChildEmbeddingInput> inputs = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            String text = "child-" + index;
            texts.add(text);
            inputs.add(new ChildEmbeddingInput("content-" + index, text));
        }

        assertThat(cache.embedChildren(config("revision-1"), inputs)).hasSize(65);

        verify(provider).embed(texts.subList(0, 32));
        verify(provider).embed(texts.subList(32, 64));
        verify(provider).embed(texts.subList(64, 65));
        verify(artifacts).save(
                any(),
                eq("CHILD_INDEX"),
                org.mockito.ArgumentMatchers.argThat(writes -> writes.size() == 65)
        );
    }

    @Test
    void queryCacheMeetsLatencyAndHeapBudgets() throws Exception {
        SearchProperties properties = new SearchProperties();
        properties.setEmbeddingQueryCacheMaxEntries(QUERY_CACHE_ENTRIES);
        FixedEmbeddingProvider provider = new FixedEmbeddingProvider(
                "revision-performance",
                QUERY_CACHE_DIMENSIONS
        );
        EmbeddingCacheService cache = service(provider, emptyArtifacts(), properties);
        IndexConfigView config = config(
                "revision-performance",
                QUERY_CACHE_DIMENSIONS
        );
        List<String> queries = new ArrayList<>(QUERY_CACHE_ENTRIES);
        for (int index = 0; index < QUERY_CACHE_ENTRIES; index++) {
            queries.add("phase-7a-query-cache-" + index);
        }

        for (int index = 0; index < QUERY_CACHE_WARMUPS; index++) {
            cache.embedQuery(config, queries.getFirst());
        }
        cache.clearQueryCache();
        long heapBefore = stableUsedHeap();

        for (String query : queries) {
            cache.embedQuery(config, query);
        }
        assertThat(cache.stats().query().entries()).isEqualTo(QUERY_CACHE_ENTRIES);
        long heapAfter = stableUsedHeap();
        long heapIncrement = Math.max(0, heapAfter - heapBefore);

        for (int index = 0; index < QUERY_CACHE_WARMUPS; index++) {
            cache.embedQuery(config, queries.get(index));
        }
        List<LatencySummary> rounds = new ArrayList<>(QUERY_CACHE_ROUNDS);
        List<Long> allSamples = new ArrayList<>(
                QUERY_CACHE_ROUNDS * QUERY_CACHE_CALLS_PER_ROUND
        );
        int errors = 0;
        for (int round = 0; round < QUERY_CACHE_ROUNDS; round++) {
            long[] samples = new long[QUERY_CACHE_CALLS_PER_ROUND];
            for (int call = 0; call < QUERY_CACHE_CALLS_PER_ROUND; call++) {
                long started = System.nanoTime();
                try {
                    cache.embedQuery(config, queries.get(call));
                } catch (RuntimeException exception) {
                    errors++;
                }
                samples[call] = System.nanoTime() - started;
                allSamples.add(samples[call]);
            }
            rounds.add(summary(samples));
        }
        LatencySummary overall = summary(
                allSamples.stream().mapToLong(Long::longValue).toArray()
        );
        var stats = cache.stats();
        long fp32PayloadBytes = (long) QUERY_CACHE_ENTRIES
                * QUERY_CACHE_DIMENSIONS
                * Float.BYTES;
        boolean passed = errors == 0
                && rounds.stream().allMatch(
                        round -> round.p95Ms() <= QUERY_CACHE_P95_LIMIT_MS
                )
                && heapIncrement <= QUERY_CACHE_HEAP_LIMIT_BYTES
                && stats.query().entries() == QUERY_CACHE_ENTRIES;

        writeQueryCacheReport(
                rounds,
                overall,
                errors,
                heapBefore,
                heapAfter,
                heapIncrement,
                fp32PayloadBytes,
                stats,
                passed
        );

        assertThat(errors).isZero();
        assertThat(rounds)
                .allSatisfy(round -> assertThat(round.p95Ms())
                        .isLessThanOrEqualTo(QUERY_CACHE_P95_LIMIT_MS));
        assertThat(heapIncrement).isLessThanOrEqualTo(QUERY_CACHE_HEAP_LIMIT_BYTES);
        assertThat(stats.query().entries()).isEqualTo(QUERY_CACHE_ENTRIES);
        assertThat(provider.calls()).isEqualTo(QUERY_CACHE_ENTRIES + 1L);
    }

    private static EmbeddingArtifactRepository emptyArtifacts() {
        EmbeddingArtifactRepository artifacts = mock(EmbeddingArtifactRepository.class);
        when(artifacts.find(any(), any(), anyList())).thenReturn(Map.of());
        when(artifacts.summary()).thenReturn(new ArtifactSummary(0, 0));
        when(artifacts.modelSummaries()).thenReturn(List.of());
        return artifacts;
    }

    private static EmbeddingCacheService service(
            EmbeddingProvider provider,
            EmbeddingArtifactRepository artifacts,
            SearchProperties properties
    ) {
        return new EmbeddingCacheService(
                provider,
                artifacts,
                new ModelCircuitBreakers(properties),
                properties
        );
    }

    private static ModelDescriptor descriptor(String revision) {
        return new ModelDescriptor(true, "embedding-model", revision, 3);
    }

    private static IndexConfigView config(String revision) {
        return config(revision, 3);
    }

    private static IndexConfigView config(String revision, int dimensions) {
        return new IndexConfigView(
                "index-config",
                "schema",
                "analyzer",
                "openai-compatible",
                "raw-text-v1",
                "none-v1",
                "embedding-model",
                revision,
                dimensions,
                "COSINE",
                16,
                128,
                Instant.now()
        );
    }

    private static long stableUsedHeap() throws InterruptedException {
        long[] samples = new long[7];
        Runtime runtime = Runtime.getRuntime();
        for (int index = 0; index < samples.length; index++) {
            System.gc();
            Thread.sleep(25);
            samples[index] = runtime.totalMemory() - runtime.freeMemory();
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static LatencySummary summary(long[] source) {
        long[] samples = source.clone();
        Arrays.sort(samples);
        return new LatencySummary(
                millis(samples[0]),
                millis(percentile(samples, 0.50d)),
                millis(percentile(samples, 0.95d)),
                millis(samples[samples.length - 1])
        );
    }

    private static long percentile(long[] samples, double percentile) {
        int index = Math.max(
                0,
                (int) Math.ceil(samples.length * percentile) - 1
        );
        return samples[index];
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static void writeQueryCacheReport(
            List<LatencySummary> rounds,
            LatencySummary overall,
            int errors,
            long heapBefore,
            long heapAfter,
            long heapIncrement,
            long fp32PayloadBytes,
            EmbeddingCacheContracts.EmbeddingCacheStatsResponse stats,
            boolean passed
    ) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", "7A");
        report.put("generatedAt", Instant.now().toString());
        report.put("dimensions", QUERY_CACHE_DIMENSIONS);
        report.put("entries", QUERY_CACHE_ENTRIES);
        report.put("warmupCalls", QUERY_CACHE_WARMUPS);
        report.put("rounds", QUERY_CACHE_ROUNDS);
        report.put("callsPerRound", QUERY_CACHE_CALLS_PER_ROUND);
        report.put("latencyMs", Map.of(
                "rounds", rounds,
                "overall", overall,
                "p95Limit", QUERY_CACHE_P95_LIMIT_MS,
                "errors", errors
        ));
        report.put("heap", Map.of(
                "beforeBytes", heapBefore,
                "afterBytes", heapAfter,
                "incrementBytes", heapIncrement,
                "limitBytes", QUERY_CACHE_HEAP_LIMIT_BYTES,
                "fp32PayloadBytes", fp32PayloadBytes
        ));
        report.put("cache", stats.query());
        report.put("passed", passed);
        Path reportPath = Path.of(
                "target",
                "phase7a-reports",
                "query-cache-performance.json"
        );
        Files.createDirectories(reportPath.getParent());
        new ObjectMapper()
                .findAndRegisterModules()
                .writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), report);
    }

    private record LatencySummary(
            double minMs,
            double p50Ms,
            double p95Ms,
            double maxMs
    ) {
    }

    private static final class FixedEmbeddingProvider implements EmbeddingProvider {

        private final ModelDescriptor descriptor;
        private final List<Double> vector;
        private final AtomicLong calls = new AtomicLong();

        private FixedEmbeddingProvider(String revision, int dimensions) {
            descriptor = new ModelDescriptor(
                    true,
                    "embedding-model",
                    revision,
                    dimensions
            );
            List<Double> values = new ArrayList<>(dimensions);
            for (int index = 0; index < dimensions; index++) {
                values.add((index + 1.0d) / dimensions);
            }
            vector = List.copyOf(values);
        }

        @Override
        public ModelDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public List<List<Double>> embed(List<String> inputs) {
            calls.incrementAndGet();
            return Collections.nCopies(inputs.size(), vector);
        }

        @Override
        public void health() {
        }

        long calls() {
            return calls.get();
        }
    }
}
