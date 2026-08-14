package com.example.rag.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves immutable corpus facts to the current public parse projection. */
@Component
final class EvaluationSupportingFactResolver {

    static final String SCHEMA = "hotpotqa-supporting-fact-v1";

    private final JdbcTemplate jdbc;

    EvaluationSupportingFactResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Resolution resolve(
            Map<String, Object> metadata,
            Map<String, UUID> currentRevisions
    ) {
        Object schema = metadata.get("supportingFactSchema");
        if (schema == null) {
            return Resolution.notApplicable();
        }
        if (!SCHEMA.equals(schema.toString())) {
            return Resolution.invalid();
        }
        List<ExpectedFact> expected;
        try {
            expected = expectedFacts(metadata.get("supportingFacts"));
        } catch (RuntimeException exception) {
            return Resolution.invalid();
        }
        List<ResolvedFact> resolved = expected.stream()
                .map(fact -> resolve(fact, currentRevisions.get(
                        fact.evidenceKey()
                )))
                .toList();
        return new Resolution(true, true, resolved);
    }

    private ResolvedFact resolve(ExpectedFact fact, UUID revisionId) {
        if (revisionId == null) {
            return new ResolvedFact(fact, 0, List.of());
        }
        List<ResolutionRow> rows = jdbc.query(
                """
                SELECT rag_utf16_code_unit_length(
                           unit.canonical_text
                       ) AS source_length,
                       child.id AS child_chunk_id,
                       span.id AS source_span_id,
                       span.start_offset,
                       span.end_offset
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                 AND revision.id = ?
                 AND revision.evaluation_evidence_key = ?
                 AND revision.status = 'READY'
                JOIN source_units unit
                  ON unit.document_id = document.id
                 AND unit.revision_id = revision.id
                 AND unit.unit_order = ?
                 AND unit.canonical_text_hash = ?
                 AND unit.normalization_version = ?
                LEFT JOIN source_spans span
                  ON span.document_id = document.id
                 AND span.revision_id = revision.id
                 AND span.start_source_unit_id = unit.id
                 AND span.end_source_unit_id = unit.id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                 AND location.document_id = document.id
                 AND location.revision_id = revision.id
                LEFT JOIN chunks child
                  ON child.id = span.chunk_id
                 AND child.document_id = document.id
                 AND child.revision_id = revision.id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                WHERE document.deleted_at IS NULL
                  AND document.visibility = 'ALL_USERS'
                ORDER BY child.chunk_order NULLS LAST,
                         child.id NULLS LAST,
                         span.span_order NULLS LAST,
                         span.id NULLS LAST
                """,
                (row, ignored) -> new ResolutionRow(
                        row.getInt("source_length"),
                        row.getObject("child_chunk_id", UUID.class),
                        row.getObject("source_span_id", UUID.class),
                        row.getObject("start_offset", Integer.class),
                        row.getObject("end_offset", Integer.class)
                ),
                revisionId,
                fact.evidenceKey(),
                fact.sourceUnitOrder(),
                fact.sourceTextHash(),
                fact.normalizationVersion()
        );
        if (rows.isEmpty()) {
            return new ResolvedFact(fact, 0, List.of());
        }
        int sourceLength = rows.getFirst().sourceLength();
        List<SpanAnchor> anchors = rows.stream()
                .filter(row -> row.childChunkId() != null
                        && row.sourceSpanId() != null
                        && row.startOffset() != null
                        && row.endOffset() != null)
                .map(row -> new SpanAnchor(
                        row.childChunkId(), row.sourceSpanId(),
                        row.startOffset(), row.endOffset()
                ))
                .distinct()
                .toList();
        return new ResolvedFact(fact, sourceLength, anchors);
    }

    static List<ExpectedFact> expectedFacts(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("Supporting facts are absent");
        }
        List<ExpectedFact> facts = new ArrayList<>();
        Set<Integer> ordinals = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> source)) {
                throw new IllegalArgumentException("Supporting fact is invalid");
            }
            int ordinal = integer(source.get("ordinal"));
            String evidenceKey = text(source.get("evidenceKey"));
            int sentenceId = integer(source.get("sentenceId"));
            int sourceUnitOrder = integer(source.get("sourceUnitOrder"));
            String hash = text(source.get("sourceTextHash"));
            String normalization = text(source.get("normalizationVersion"));
            if (ordinal < 0 || sentenceId < 0 || sourceUnitOrder < 2
                    || evidenceKey.isBlank()
                    || !hash.matches("[0-9a-f]{64}")
                    || !"markdown-nfc-line-endings-v1".equals(
                    normalization)
                    || !ordinals.add(ordinal)
                    || !identities.add(evidenceKey + ":" + sentenceId)) {
                throw new IllegalArgumentException(
                        "Supporting fact contract is invalid"
                );
            }
            facts.add(new ExpectedFact(
                    ordinal, evidenceKey, sentenceId, sourceUnitOrder,
                    hash, normalization
            ));
        }
        facts.sort(Comparator.comparingInt(ExpectedFact::ordinal));
        for (int index = 0; index < facts.size(); index++) {
            if (facts.get(index).ordinal() != index) {
                throw new IllegalArgumentException(
                        "Supporting fact ordinals are not contiguous"
                );
            }
        }
        return List.copyOf(facts);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(text(value));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    static boolean covers(
            int sourceLength,
            List<SpanAnchor> anchors
    ) {
        if (sourceLength <= 0 || anchors.isEmpty()) {
            return false;
        }
        List<SpanAnchor> ordered = anchors.stream()
                .filter(anchor -> anchor.startOffset() >= 0
                        && anchor.endOffset() > anchor.startOffset())
                .sorted(Comparator.comparingInt(SpanAnchor::startOffset)
                        .thenComparing(
                                Comparator.comparingInt(
                                        SpanAnchor::endOffset
                                ).reversed()
                        ))
                .toList();
        int covered = 0;
        for (SpanAnchor anchor : ordered) {
            if (anchor.startOffset() > covered) {
                return false;
            }
            covered = Math.max(covered, anchor.endOffset());
            if (covered >= sourceLength) {
                return true;
            }
        }
        return false;
    }

    record Resolution(
            boolean applicable,
            boolean contractValid,
            List<ResolvedFact> facts
    ) {
        static Resolution notApplicable() {
            return new Resolution(false, true, List.of());
        }

        static Resolution invalid() {
            return new Resolution(true, false, List.of());
        }

        boolean complete() {
            return !applicable || contractValid
                    && !facts.isEmpty()
                    && facts.stream().allMatch(ResolvedFact::resolved);
        }

        int expectedCount() {
            return facts.size();
        }

        int matchedByChunks(Set<UUID> chunkIds) {
            return (int) facts.stream()
                    .filter(fact -> fact.matchesChunks(chunkIds))
                    .count();
        }

        int matchedBySpans(Set<UUID> spanIds) {
            return (int) facts.stream()
                    .filter(fact -> fact.matchesSpans(spanIds))
                    .count();
        }

        List<String> blockingKeys() {
            if (!contractValid) {
                return List.of("SUPPORTING_FACT_CONTRACT");
            }
            return facts.stream()
                    .filter(fact -> !fact.resolved())
                    .map(fact -> fact.expected().evidenceKey())
                    .distinct()
                    .toList();
        }
    }

    record ResolvedFact(
            ExpectedFact expected,
            int sourceLength,
            List<SpanAnchor> anchors
    ) {
        boolean resolved() {
            return covers(sourceLength, anchors);
        }

        boolean matchesChunks(Set<UUID> chunkIds) {
            return covers(
                    sourceLength,
                    anchors.stream()
                            .filter(anchor -> chunkIds.contains(
                                    anchor.childChunkId()
                            ))
                            .toList()
            );
        }

        boolean matchesSpans(Set<UUID> spanIds) {
            return covers(
                    sourceLength,
                    anchors.stream()
                            .filter(anchor -> spanIds.contains(
                                    anchor.sourceSpanId()
                            ))
                            .toList()
            );
        }
    }

    record ExpectedFact(
            int ordinal,
            String evidenceKey,
            int sentenceId,
            int sourceUnitOrder,
            String sourceTextHash,
            String normalizationVersion
    ) {
    }

    record SpanAnchor(
            UUID childChunkId,
            UUID sourceSpanId,
            int startOffset,
            int endOffset
    ) {
    }

    private record ResolutionRow(
            int sourceLength,
            UUID childChunkId,
            UUID sourceSpanId,
            Integer startOffset,
            Integer endOffset
    ) {
    }
}
