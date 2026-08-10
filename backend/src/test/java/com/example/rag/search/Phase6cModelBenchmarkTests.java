package com.example.rag.search;

import com.example.rag.search.ModelServiceProperties.Endpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_PHASE6C_BENCHMARK", matches = "true")
class Phase6cModelBenchmarkTests {

    private static final int WARMUPS = 50;
    private static final int ROUNDS = 3;
    private static final int SAMPLES_PER_ROUND = 300;
    private static final String EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-0.6B";
    private static final String EMBEDDING_REVISION =
            "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3";
    private static final String RERANK_MODEL = "Qwen/Qwen3-Reranker-0.6B";
    private static final String RERANK_REVISION =
            "e61197ed45024b0ed8a2d74b80b4d909f1255473";
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("权限撤销后如何处理引用", 0),
            new Scenario("How are citations handled after access is revoked?", 1),
            new Scenario("ACL change 后 Citation 如何重新校验", 2)
    );
    private static final List<String> DOCUMENTS = documents();

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void validatesRealModelContractsAndLatencyGates() throws Exception {
        HttpEmbeddingProvider embedding = embeddingProvider();
        HttpRerankProvider reranker = rerankProvider();
        embedding.health();
        reranker.health();

        for (Scenario scenario : SCENARIOS) {
            List<Double> vector = embedding.embed(List.of(scenario.query())).getFirst();
            assertThat(vector).hasSize(1024).allMatch(Double::isFinite);
            List<RerankScore> scores = reranker.rerank(scenario.query(), DOCUMENTS);
            assertThat(scores).hasSize(30);
            assertThat(scores.getFirst().index()).isEqualTo(scenario.expectedDocument());
        }

        warmUp(embedding, reranker);
        Samples embeddingSamples = sampleEmbedding(embedding);
        Samples rerankSamples = sampleRerank(reranker);
        Map<String, Object> report = report(embeddingSamples, rerankSamples);
        writeReport(report);

        assertThat(embeddingSamples.errors()).isZero();
        assertThat(rerankSamples.errors()).isZero();
        assertThat(embeddingSamples.p95()).isLessThanOrEqualTo(400);
        assertThat(rerankSamples.p95()).isLessThanOrEqualTo(900);
    }

    private static HttpEmbeddingProvider embeddingProvider() {
        Endpoint endpoint = new Endpoint(
                "http://embedding-model:8000",
                EMBEDDING_MODEL,
                EMBEDDING_REVISION,
                1024
        );
        endpoint.setEnabled(true);
        return new HttpEmbeddingProvider(endpoint);
    }

    private static HttpRerankProvider rerankProvider() {
        Endpoint endpoint = new Endpoint(
                "http://reranker-model:8000",
                RERANK_MODEL,
                RERANK_REVISION,
                null
        );
        endpoint.setEnabled(true);
        return new HttpRerankProvider(endpoint);
    }

    private static void warmUp(
            HttpEmbeddingProvider embedding,
            HttpRerankProvider reranker
    ) {
        for (int sample = 0; sample < WARMUPS; sample++) {
            Scenario scenario = SCENARIOS.get(sample % SCENARIOS.size());
            embedding.embed(List.of(scenario.query()));
            reranker.rerank(scenario.query(), DOCUMENTS);
        }
    }

    private static Samples sampleEmbedding(HttpEmbeddingProvider provider) {
        return sample((round, sample) -> {
            Scenario scenario = SCENARIOS.get((round + sample) % SCENARIOS.size());
            provider.embed(List.of(scenario.query()));
        });
    }

    private static Samples sampleRerank(HttpRerankProvider provider) {
        return sample((round, sample) -> {
            Scenario scenario = SCENARIOS.get((round + sample) % SCENARIOS.size());
            provider.rerank(scenario.query(), DOCUMENTS);
        });
    }

    private static Samples sample(Operation operation) {
        List<Long> elapsed = new ArrayList<>(ROUNDS * SAMPLES_PER_ROUND);
        int errors = 0;
        for (int round = 0; round < ROUNDS; round++) {
            for (int sample = 0; sample < SAMPLES_PER_ROUND; sample++) {
                long started = System.nanoTime();
                try {
                    operation.run(round, sample);
                    elapsed.add(TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - started
                    ));
                } catch (RuntimeException exception) {
                    errors++;
                }
            }
        }
        elapsed.sort(Long::compareTo);
        return new Samples(
                percentile(elapsed, 0.50),
                percentile(elapsed, 0.95),
                elapsed.isEmpty() ? Long.MAX_VALUE : elapsed.getLast(),
                errors,
                elapsed.size()
        );
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return Long.MAX_VALUE;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static Map<String, Object> report(
            Samples embedding,
            Samples rerank
    ) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("warmups", WARMUPS);
        report.put("rounds", ROUNDS);
        report.put("samplesPerRound", SAMPLES_PER_ROUND);
        report.put("embedding", Map.of(
                "model", EMBEDDING_MODEL,
                "revision", EMBEDDING_REVISION,
                "dimensions", 1024,
                "p50Ms", embedding.p50(),
                "p95Ms", embedding.p95(),
                "maxMs", embedding.max(),
                "errors", embedding.errors(),
                "samples", embedding.samples(),
                "gateMs", 400,
                "passed", embedding.errors() == 0 && embedding.p95() <= 400
        ));
        report.put("rerank", Map.of(
                "model", RERANK_MODEL,
                "revision", RERANK_REVISION,
                "documentsPerRequest", DOCUMENTS.size(),
                "p50Ms", rerank.p50(),
                "p95Ms", rerank.p95(),
                "maxMs", rerank.max(),
                "errors", rerank.errors(),
                "samples", rerank.samples(),
                "gateMs", 900,
                "passed", rerank.errors() == 0 && rerank.p95() <= 900
        ));
        return report;
    }

    private static void writeReport(Map<String, Object> report) throws Exception {
        Path output = Path.of(
                "target", "phase6c-reports", "real-model-latency.json"
        );
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);
    }

    private static List<String> documents() {
        List<String> values = new ArrayList<>(30);
        values.add("权限被撤销后，服务必须重新检查文档 ACL，并隐藏依赖旧引用的回答。");
        values.add("After access is revoked, citations are checked again and unauthorized evidence is hidden.");
        values.add("ACL change 后，Citation 在展示和送入模型前都要再次执行权限校验。");
        for (int index = values.size(); index < 30; index++) {
            values.add(
                    "通用平台说明 " + index
                            + "：索引构建、日志与文档版本采用确定性处理。"
            );
        }
        return List.copyOf(values);
    }

    private record Scenario(String query, int expectedDocument) {
    }

    private record Samples(
            long p50,
            long p95,
            long max,
            int errors,
            int samples
    ) {
    }

    @FunctionalInterface
    private interface Operation {

        void run(int round, int sample);
    }
}
