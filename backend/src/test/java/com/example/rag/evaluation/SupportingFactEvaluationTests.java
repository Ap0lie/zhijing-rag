package com.example.rag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportingFactEvaluationTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void importerValidatesCorpusAndStoresOnlyOrdinalAndHash() throws Exception {
        JsonNode root = JSON.readTree("""
                {
                  "corpus": [{
                    "evidenceKey": "hotpotqa:gold",
                    "sentences": ["first", "   ", " Café\\r\\nline "]
                  }]
                }
                """);
        JsonNode candidate = JSON.readTree("""
                {
                  "expected": {"supportingFactCount": 1},
                  "evidenceRefs": [{
                    "evidenceKey": "hotpotqa:gold",
                    "sentenceIds": [2],
                    "supportingSentences": ["Café\\nline"]
                  }]
                }
                """);

        List<Map<String, Object>> facts =
                EvaluationCatalogImporter.supportingFacts(root, candidate);

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact)
                    .containsEntry("ordinal", 0)
                    .containsEntry("sentenceId", 2)
                    .containsEntry("sourceUnitOrder", 3)
                    .containsEntry(
                            "normalizationVersion",
                            "markdown-nfc-line-endings-v1"
                    )
                    .containsEntry(
                            "sourceTextHash", sha256("Café\nline")
                    )
                    .doesNotContainKeys("sentenceText", "title");
        });
    }

    @Test
    void importerRejectsSupportingTextThatDiffersFromCorpus() throws Exception {
        JsonNode root = JSON.readTree("""
                {"corpus": [{
                  "evidenceKey": "hotpotqa:gold",
                  "sentences": ["actual"]
                }]}
                """);
        JsonNode candidate = JSON.readTree("""
                {
                  "expected": {"supportingFactCount": 1},
                  "evidenceRefs": [{
                    "evidenceKey": "hotpotqa:gold",
                    "sentenceIds": [0],
                    "supportingSentences": ["different"]
                  }]
                }
                """);

        assertThatThrownBy(() ->
                EvaluationCatalogImporter.supportingFacts(root, candidate)
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differs from corpus");
    }

    @Test
    void resolverRequiresCompleteSourceSpanCoverageAcrossChildChunks() {
        UUID childOne = UUID.randomUUID();
        UUID childTwo = UUID.randomUUID();
        UUID spanOne = UUID.randomUUID();
        UUID spanTwo = UUID.randomUUID();
        var expected = new EvaluationSupportingFactResolver.ExpectedFact(
                0, "hotpotqa:gold", 0, 2,
                "a".repeat(64), "markdown-nfc-line-endings-v1"
        );
        var fact = new EvaluationSupportingFactResolver.ResolvedFact(
                expected,
                10,
                List.of(
                        new EvaluationSupportingFactResolver.SpanAnchor(
                                childOne, spanOne, 0, 4
                        ),
                        new EvaluationSupportingFactResolver.SpanAnchor(
                                childTwo, spanTwo, 4, 10
                        )
                )
        );
        var resolution = new EvaluationSupportingFactResolver.Resolution(
                true, true, List.of(fact)
        );

        assertThat(resolution.complete()).isTrue();
        assertThat(resolution.matchedByChunks(Set.of(childOne))).isZero();
        assertThat(resolution.matchedByChunks(
                Set.of(childOne, childTwo)
        )).isOne();
        assertThat(resolution.matchedBySpans(Set.of(spanTwo))).isZero();
        assertThat(resolution.matchedBySpans(
                Set.of(spanOne, spanTwo)
        )).isOne();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        value.getBytes(StandardCharsets.UTF_8)
                )
        );
    }
}
