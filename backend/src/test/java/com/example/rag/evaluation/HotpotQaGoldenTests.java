package com.example.rag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HotpotQaGoldenTests {

    private static final String RESOURCE_V1 =
            "/hotpotqa-golden/v1/dataset.json";
    private static final String RESOURCE_V2 =
            "/hotpotqa-golden/v2/dataset.json";
    private static final String REVISION =
            "1908d6afbbead072334abe2965f91bd2709910ab";
    private static final String RESOURCE_V1_SHA256 =
            "f83c6c0b09b968f86819d5c80ea03ab40f60a36dc598d810311730520b3c0809";
    private static final String RESOURCE_V2_SHA256 =
            "676bb31f320e9eb5af49ea05c17fde7fcefbed583e1ee0998823417cdd7aeea5";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void v1GoldenRemainsImmutableAndEvidenceComplete() throws Exception {
        assertGolden(resource(RESOURCE_V1), 1, 18, 180, 9);
    }

    @Test
    void v2GoldenIsBalancedVersionedAndEvidenceComplete() throws Exception {
        assertGolden(resource(RESOURCE_V2), 2, 50, 500, 25);
    }

    @Test
    void v1CasesAreTheStableV2Prefix() throws Exception {
        JsonNode v1 = resource(RESOURCE_V1);
        JsonNode v2 = resource(RESOURCE_V2);

        for (int index = 0; index < v1.path("cases").size(); index++) {
            assertThat(v2.path("cases").get(index).path("id").asText())
                    .isEqualTo(v1.path("cases").get(index).path("id").asText());
        }
    }

    @Test
    void v2RetainsTenDistinctContextsAndTwoSupportingTitlesPerCase()
            throws Exception {
        JsonNode dataset = resource(RESOURCE_V2);
        Map<String, Integer> contextCounts = new HashMap<>();
        for (JsonNode document : dataset.path("corpus")) {
            for (JsonNode caseId : document.path("caseIds")) {
                contextCounts.merge(caseId.asText(), 1, Integer::sum);
            }
        }

        for (JsonNode candidate : dataset.path("cases")) {
            assertThat(contextCounts.get(candidate.path("id").asText()))
                    .isEqualTo(10);
            assertThat(candidate.path("evidenceRefs")).hasSize(2);
            Set<String> supportingTitles = new HashSet<>();
            for (JsonNode evidence : candidate.path("evidenceRefs")) {
                supportingTitles.add(evidence.path("title").asText());
            }
            assertThat(supportingTitles).hasSize(2);
        }
    }

    @Test
    void sharedV1EvidenceHasIdenticalV2Content() throws Exception {
        JsonNode v1 = resource(RESOURCE_V1);
        JsonNode v2 = resource(RESOURCE_V2);
        Map<String, JsonNode> v2Corpus = new HashMap<>();
        for (JsonNode document : v2.path("corpus")) {
            v2Corpus.put(document.path("evidenceKey").asText(), document);
        }

        for (JsonNode v1Document : v1.path("corpus")) {
            JsonNode v2Document = v2Corpus.get(
                    v1Document.path("evidenceKey").asText()
            );
            assertThat(v2Document).isNotNull();
            assertThat(v2Document.path("sourceContentHash"))
                    .isEqualTo(v1Document.path("sourceContentHash"));
            assertThat(v2Document.path("sentences"))
                    .isEqualTo(v1Document.path("sentences"));
        }
    }

    private void assertGolden(
            JsonNode dataset,
            int version,
            int caseCount,
            int corpusDocumentCount,
            int casesPerType
    ) {

        assertThat(dataset.path("suiteVersion").asText())
                .isEqualTo("hotpotqa-answer-citation-v" + version);
        assertThat(dataset.path("source").path("revision").asText())
                .isEqualTo(REVISION);
        assertThat(dataset.path("source").path("license").asText())
                .isEqualTo("CC-BY-SA-4.0");
        assertThat(dataset.path("selection").path("windowRows").asInt())
                .isEqualTo(1_000);
        assertThat(dataset.path("selection").path("casesPerType").asInt())
                .isEqualTo(casesPerType);
        assertThat(dataset.path("cases")).hasSize(caseCount);
        assertThat(dataset.path("corpus")).hasSize(corpusDocumentCount);

        Map<String, JsonNode> corpus = new HashMap<>();
        for (JsonNode document : dataset.path("corpus")) {
            String key = document.path("evidenceKey").asText();
            assertThat(key).startsWith("hotpotqa:").hasSize(41);
            assertThat(corpus.put(key, document)).isNull();
            assertThat(document.path("sentences").isArray()).isTrue();
            assertThat(document.path("sentences")).isNotEmpty();
        }

        Map<String, Integer> buckets = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode candidate : dataset.path("cases")) {
            assertThat(ids.add(candidate.path("id").asText())).isTrue();
            assertThat(candidate.path("level").asText()).isEqualTo("hard");
            buckets.merge(candidate.path("type").asText(), 1, Integer::sum);
            assertThat(candidate.path("input").path("query").asText())
                    .isNotBlank();
            assertThat(candidate.path("expected").path("expectedAnswer").asText())
                    .isNotBlank();
            assertThat(candidate.path("expected").path("shouldRefuse").asBoolean())
                    .isFalse();
            assertThat(candidate.path("evidenceRefs").size())
                    .isGreaterThanOrEqualTo(2);
            for (JsonNode evidence : candidate.path("evidenceRefs")) {
                JsonNode document = corpus.get(
                        evidence.path("evidenceKey").asText()
                );
                assertThat(document).isNotNull();
                assertThat(document.path("title").asText())
                        .isEqualTo(evidence.path("title").asText());
                for (JsonNode sentenceId : evidence.path("sentenceIds")) {
                    assertThat(sentenceId.asInt())
                            .isBetween(0, document.path("sentences").size() - 1);
                }
            }
        }
        assertThat(buckets).containsExactlyInAnyOrderEntriesOf(
                Map.of("bridge", casesPerType, "comparison", casesPerType)
        );
    }

    @Test
    void answerMetricsFollowHotpotNormalization() {
        assertThat(RealEvaluationExecutor.normalizeAnswer(
                "The President Richard Nixon."
        )).isEqualTo("president richard nixon");
        assertThat(RealEvaluationExecutor.tokenF1(
                "Richard Nixon was the president.",
                "President Richard Nixon"
        )).isEqualTo(6.0 / 7.0);
        assertThat(RealEvaluationExecutor.tokenF1("no", "yes")).isZero();
    }

    @Test
    void selectedResourceHashesAreStable() throws Exception {
        assertResourceHash(RESOURCE_V1, RESOURCE_V1_SHA256);
        assertResourceHash(RESOURCE_V2, RESOURCE_V2_SHA256);
    }

    private void assertResourceHash(String resource, String expected) throws Exception {
        byte[] bytes = resourceBytes(resource);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
        assertThat(hash).isEqualTo(expected);
    }

    private JsonNode resource(String resource) throws Exception {
        return objectMapper.readTree(resourceBytes(resource));
    }

    private byte[] resourceBytes(String resource) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assumeTrue(
                    stream != null,
                    "External HotpotQA resources are intentionally not checked in"
            );
            return stream.readAllBytes();
        }
    }

}
