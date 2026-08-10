package com.example.rag.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

final class RetrievalGoldenV2 {

    private static final Set<String> LANGUAGES = Set.of("zh", "en", "mixed");

    private RetrievalGoldenV2() {
    }

    static Loaded load(ObjectMapper json) throws Exception {
        byte[] corpusBytes = resource("retrieval-golden/v2/corpus.json");
        byte[] datasetBytes = resource("retrieval-golden/v2/dataset.json");
        Corpus corpus = json.readValue(corpusBytes, Corpus.class);
        Dataset dataset = json.readValue(datasetBytes, Dataset.class);

        Map<String, Passage> passages = validateCorpus(corpus);
        validateDataset(dataset, corpus, passages);
        return new Loaded(
                corpus,
                dataset,
                sha256(corpusBytes),
                sha256(datasetBytes),
                Map.copyOf(passages)
        );
    }

    private static byte[] resource(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static Map<String, Passage> validateCorpus(Corpus corpus) {
        assertThat(corpus.version()).isEqualTo("phase6-retrieval-corpus-v2");
        assertThat(corpus.documents()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(corpus.hardNegatives()).isNotNull();
        assertThat(corpus.hardNegatives().copiesPerTemplate()).isGreaterThanOrEqualTo(1);
        assertThat(corpus.hardNegatives().templates()).isNotEmpty();

        Set<String> documentKeys = new HashSet<>();
        Map<String, Passage> passages = new LinkedHashMap<>();
        for (Document document : corpus.documents()) {
            assertThat(documentKeys.add(document.key()))
                    .as("duplicate document key %s", document.key())
                    .isTrue();
            assertThat(document.title()).isNotBlank();
            assertThat(document.language()).isIn("zh", "en", "mixed");
            assertThat(document.revisionKey()).isNotBlank();
            assertThat(document.passages()).isNotEmpty();
            for (Passage passage : document.passages()) {
                assertThat(passages.putIfAbsent(passage.key(), passage))
                        .as("duplicate passage key %s", passage.key())
                        .isNull();
                assertThat(passage.headingPath()).isNotEmpty();
                assertThat(passage.startPage()).isPositive();
                assertThat(passage.endPage()).isGreaterThanOrEqualTo(passage.startPage());
                assertThat(passage.text()).isNotBlank();
                assertThat(passage.sourceTextHash())
                        .as("source hash for %s", passage.key())
                        .isEqualTo(sha256(passage.text()));
            }
        }

        Set<String> templateKeys = new HashSet<>();
        for (HardNegativeTemplate template : corpus.hardNegatives().templates()) {
            assertThat(templateKeys.add(template.key()))
                    .as("duplicate hard-negative template key %s", template.key())
                    .isTrue();
            assertThat(template.heading()).isNotBlank();
            assertThat(template.textTemplate()).contains("{n}");
        }
        assertThat(corpus.childCount()).isGreaterThanOrEqualTo(50);
        assertThat(corpus.hardNegatives().childCount()).isGreaterThanOrEqualTo(50);
        return passages;
    }

    private static void validateDataset(
            Dataset dataset,
            Corpus corpus,
            Map<String, Passage> passages
    ) {
        assertThat(dataset.version()).isEqualTo("retrieval-golden-v2");
        assertThat(dataset.corpusVersion()).isEqualTo(corpus.version());
        assertThat(dataset.queries()).hasSize(48);

        Set<String> queryIds = new HashSet<>();
        Map<String, Integer> languageCounts = new HashMap<>();
        Map<String, Integer> tagCounts = new HashMap<>();
        int noAnswerCount = 0;

        for (Query query : dataset.queries()) {
            assertThat(queryIds.add(query.id()))
                    .as("duplicate query id %s", query.id())
                    .isTrue();
            assertThat(query.query()).isNotBlank();
            assertThat(query.language()).isIn(LANGUAGES);
            assertThat(query.tags()).isNotEmpty();
            assertThat(new HashSet<>(query.tags())).hasSameSizeAs(query.tags());
            languageCounts.merge(query.language(), 1, Integer::sum);
            query.tags().forEach(tag -> tagCounts.merge(tag, 1, Integer::sum));

            if (query.answerable()) {
                assertThat(query.evidenceGroups()).isNotEmpty();
            } else {
                noAnswerCount++;
                assertThat(query.tags()).contains("no-answer");
                assertThat(query.evidenceGroups()).isEmpty();
            }

            Set<String> groupIds = new HashSet<>();
            for (EvidenceGroup group : query.evidenceGroups()) {
                assertThat(groupIds.add(group.id()))
                        .as("duplicate evidence group %s in %s", group.id(), query.id())
                        .isTrue();
                assertThat(group.anyOf()).isNotEmpty();
                for (ExpectedPassage expected : group.anyOf()) {
                    assertThat(passages)
                            .as("unknown passage %s in %s", expected.passageKey(), query.id())
                            .containsKey(expected.passageKey());
                    assertThat(expected.relevanceGrade()).isBetween(1, 2);
                }
            }
        }

        assertThat(noAnswerCount).isEqualTo(6);
        assertThat(languageCounts.getOrDefault("zh", 0)).isGreaterThanOrEqualTo(16);
        assertThat(languageCounts.getOrDefault("en", 0)).isGreaterThanOrEqualTo(12);
        assertThat(languageCounts.getOrDefault("mixed", 0)).isGreaterThanOrEqualTo(8);
        assertThat(tagCounts.getOrDefault("semantic-paraphrase", 0)).isGreaterThanOrEqualTo(16);
        assertThat(tagCounts.getOrDefault("cross-language", 0)).isGreaterThanOrEqualTo(8);
        assertThat(tagCounts.getOrDefault("exact-identifier", 0)).isGreaterThanOrEqualTo(6);
        assertThat(tagCounts.getOrDefault("proper-noun", 0)).isGreaterThanOrEqualTo(6);
        assertThat(tagCounts.getOrDefault("multi-evidence", 0)).isEqualTo(6);
        assertThat(tagCounts.getOrDefault("no-answer", 0)).isEqualTo(6);
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    record Loaded(
            Corpus corpus,
            Dataset dataset,
            String corpusSha256,
            String datasetSha256,
            Map<String, Passage> passages
    ) {
    }

    record Corpus(
            String version,
            List<Document> documents,
            HardNegatives hardNegatives
    ) {
        int childCount() {
            return documents.stream().mapToInt(document -> document.passages().size()).sum()
                    + hardNegatives.childCount();
        }
    }

    record Document(
            String key,
            String title,
            String language,
            String revisionKey,
            List<Passage> passages
    ) {
    }

    record Passage(
            String key,
            List<String> headingPath,
            int startPage,
            int endPage,
            String text,
            String sourceTextHash
    ) {
    }

    record HardNegatives(
            int copiesPerTemplate,
            List<HardNegativeTemplate> templates
    ) {
        int childCount() {
            return copiesPerTemplate * templates.size();
        }
    }

    record HardNegativeTemplate(
            String key,
            String heading,
            String textTemplate
    ) {
    }

    record Dataset(
            String version,
            String corpusVersion,
            String description,
            List<Query> queries
    ) {
    }

    record Query(
            String id,
            String language,
            String query,
            List<String> tags,
            boolean answerable,
            List<EvidenceGroup> evidenceGroups
    ) {
        List<ExpectedPassage> relevantPassages() {
            List<ExpectedPassage> result = new ArrayList<>();
            evidenceGroups.forEach(group -> result.addAll(group.anyOf()));
            return List.copyOf(result);
        }
    }

    record EvidenceGroup(
            String id,
            List<ExpectedPassage> anyOf
    ) {
    }

    record ExpectedPassage(
            String passageKey,
            int relevanceGrade
    ) {
    }
}
