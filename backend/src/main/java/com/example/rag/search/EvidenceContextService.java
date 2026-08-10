package com.example.rag.search;

import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.search.SearchContracts.ParentContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class EvidenceContextService {

    static final int MAX_CONTEXT_TOKENS = 6_000;
    static final int MAX_PARENT_TOKENS = 800;
    static final int MAX_PARENTS = 4;
    static final int MAX_PARENTS_PER_DOCUMENT = 2;

    private final NamedParameterJdbcTemplate jdbc;

    EvidenceContextService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Map<UUID, ContextRow> load(List<UUID> childIds) {
        if (childIds.isEmpty()) {
            return Map.of();
        }
        var parameters = new MapSqlParameterSource("childIds", childIds);
        Map<UUID, ContextRow> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT child.id AS child_id,
                       child.document_id,
                       child.revision_id,
                       revision.document_format,
                       child.text AS child_text,
                       child.token_count AS child_token_count,
                       parent.id AS parent_id,
                       parent.text AS parent_text,
                       parent.heading_path AS parent_heading_path,
                       parent.token_count AS parent_token_count,
                       parent_span.locator_kind AS parent_locator_kind,
                       parent_span.start_page AS parent_start_page,
                       parent_span.end_page AS parent_end_page,
                       parent_span.start_source_unit_id,
                       parent_span.end_source_unit_id,
                       parent_span.start_offset,
                       parent_span.end_offset,
                       parent_span.normalization_version,
                       parent_span.locator_address,
                       parent_span.source_label
                FROM chunks child
                JOIN document_revisions revision
                  ON revision.id = child.revision_id
                LEFT JOIN chunks parent
                  ON parent.id = child.parent_chunk_id
                 AND parent.chunk_type = 'PARENT'
                 AND parent.document_id = child.document_id
                 AND parent.revision_id = child.revision_id
                 AND parent.start_block_order <= child.start_block_order
                 AND parent.end_block_order >= child.end_block_order
                LEFT JOIN LATERAL (
                    SELECT
                           (array_agg(
                               location.locator_kind
                               ORDER BY span.span_order
                           ))[1] AS locator_kind,
                           MIN(location.start_page) AS start_page,
                           MAX(location.end_page) AS end_page,
                           (array_agg(
                               location.start_source_unit_id
                               ORDER BY span.span_order
                           ))[1] AS start_source_unit_id,
                           (array_agg(
                               location.end_source_unit_id
                               ORDER BY span.span_order DESC
                           ))[1] AS end_source_unit_id,
                           (array_agg(
                               location.start_offset
                               ORDER BY span.span_order
                           ))[1] AS start_offset,
                           (array_agg(
                               location.end_offset
                               ORDER BY span.span_order DESC
                           ))[1] AS end_offset,
                           (array_agg(
                               location.normalization_version
                               ORDER BY span.span_order
                           ))[1] AS normalization_version,
                           (array_agg(
                               location.address::text
                               ORDER BY span.span_order
                           ))[1] AS locator_address,
                           (array_agg(
                               location.source_label
                               ORDER BY span.span_order
                           ))[1] AS source_label
                    FROM source_spans span
                    JOIN source_locator_projection location
                      ON location.source_kind = 'SOURCE_SPAN'
                     AND location.source_id = span.id
                    WHERE span.chunk_id = parent.id
                ) parent_span ON TRUE
                WHERE child.id IN (:childIds)
                  AND child.chunk_type = 'CHILD'
                  AND child.searchable = TRUE
                """,
                parameters,
                resultSet -> {
                    UUID childId = resultSet.getObject("child_id", UUID.class);
                    result.put(childId, new ContextRow(
                            childId,
                            resultSet.getObject("document_id", UUID.class),
                            resultSet.getObject("revision_id", UUID.class),
                            resultSet.getString("document_format"),
                            resultSet.getString("child_text"),
                            resultSet.getInt("child_token_count"),
                            resultSet.getObject("parent_id", UUID.class),
                            resultSet.getString("parent_text"),
                            resultSet.getString("parent_heading_path"),
                            resultSet.getString("parent_locator_kind"),
                            resultSet.getObject(
                                    "parent_start_page", Integer.class
                            ),
                            resultSet.getObject(
                                    "parent_end_page", Integer.class
                            ),
                            resultSet.getObject(
                                    "start_source_unit_id", UUID.class
                            ),
                            resultSet.getObject(
                                    "end_source_unit_id", UUID.class
                            ),
                            resultSet.getInt("start_offset"),
                            resultSet.getInt("end_offset"),
                            resultSet.getString(
                                    "normalization_version"
                            ),
                            resultSet.getString("locator_address"),
                            resultSet.getString("source_label"),
                            resultSet.getObject("parent_token_count", Integer.class)
                    ));
                }
        );
        return Map.copyOf(result);
    }

    ContextPlan plan(
            List<ContextSeed> seeds,
            Map<UUID, ContextRow> loaded,
            int configuredBudget
    ) {
        int limit = Math.min(MAX_CONTEXT_TOKENS, Math.max(0, configuredBudget));
        Map<UUID, Material> materials = new LinkedHashMap<>();
        int childTokens = 0;
        for (ContextSeed seed : seeds) {
            ContextRow row = matchingRow(seed, loaded.get(seed.chunkId()));
            String childText = row == null ? seed.childText() : row.childText();
            int tokens = row == null
                    ? estimatedTokens(childText)
                    : Math.max(1, row.childTokenCount());
            childTokens += tokens;
            materials.put(seed.chunkId(), new Material(childText, tokens, null));
        }

        int remaining = Math.max(0, limit - childTokens);
        int parentTokens = 0;
        Map<UUID, ParentContext> includedParents = new LinkedHashMap<>();
        Map<UUID, Integer> parentsPerDocument = new LinkedHashMap<>();
        Set<String> trimReasons = new LinkedHashSet<>();

        for (ContextSeed seed : seeds) {
            ContextRow row = matchingRow(seed, loaded.get(seed.chunkId()));
            if (row == null || row.parentId() == null || row.parentText() == null) {
                trimReasons.add("PARENT_MISSING");
                continue;
            }
            ParentContext existing = includedParents.get(row.parentId());
            if (existing != null) {
                Material child = materials.get(seed.chunkId());
                materials.put(seed.chunkId(), child.withParent(existing));
                continue;
            }
            if (includedParents.size() >= MAX_PARENTS) {
                trimReasons.add("MAX_PARENT_COUNT");
                continue;
            }
            int documentParents = parentsPerDocument.getOrDefault(seed.documentId(), 0);
            if (documentParents >= MAX_PARENTS_PER_DOCUMENT) {
                trimReasons.add("PER_DOCUMENT_PARENT_LIMIT");
                continue;
            }
            if (remaining <= 0) {
                trimReasons.add("CONTEXT_TOKEN_BUDGET");
                continue;
            }

            int originalTokens = Math.max(1, row.parentTokenCount());
            int contribution = Math.min(Math.min(MAX_PARENT_TOKENS, originalTokens), remaining);
            String text = truncateAround(
                    row.parentText(),
                    row.childText(),
                    contribution,
                    originalTokens
            );
            boolean truncated = contribution < originalTokens;
            if (truncated) {
                trimReasons.add("PARENT_TRUNCATED");
            }
            SourceLocatorResponse locator = parentLocator(row);
            ParentContext parent = new ParentContext(
                    row.parentId(),
                    text,
                    SearchService.path(row.parentHeadingPath()),
                    row.parentStartPage(),
                    row.parentEndPage(),
                    originalTokens,
                    contribution,
                    truncated,
                    row.documentFormat(),
                    locator,
                    locator.sourceLabel()
            );
            includedParents.put(row.parentId(), parent);
            parentsPerDocument.put(seed.documentId(), documentParents + 1);
            parentTokens += contribution;
            remaining -= contribution;
            Material child = materials.get(seed.chunkId());
            materials.put(seed.chunkId(), child.withParent(parent));
        }

        return new ContextPlan(
                Map.copyOf(materials),
                limit,
                childTokens,
                parentTokens,
                includedParents.size(),
                List.copyOf(trimReasons)
        );
    }

    private static ContextRow matchingRow(ContextSeed seed, ContextRow row) {
        return row != null
                && row.documentId().equals(seed.documentId())
                && row.revisionId().equals(seed.revisionId())
                ? row
                : null;
    }

    private static SourceLocatorResponse parentLocator(ContextRow row) {
        if ("PAGE".equals(row.parentLocatorKind())
                && row.parentStartPage() != null
                && row.parentEndPage() != null) {
            return SourceLocatorResponse.pdf(
                    row.parentStartUnitId(),
                    row.parentEndUnitId(),
                    row.parentStartPage(),
                    row.parentEndPage(),
                    row.parentStartOffset(),
                    row.parentEndOffset(),
                    row.parentLocatorAddress(),
                    null,
                    row.parentNormalizationVersion()
            );
        }
        return new SourceLocatorResponse(
                row.parentLocatorKind(),
                row.parentStartUnitId(),
                row.parentEndUnitId(),
                row.parentStartOffset(),
                row.parentEndOffset(),
                row.parentLocatorAddress(),
                null,
                row.parentNormalizationVersion(),
                row.parentStartPage(),
                row.parentEndPage(),
                row.parentSourceLabel()
        );
    }

    private static int estimatedTokens(String value) {
        return Math.max(1, value.codePointCount(0, value.length()));
    }

    private static String truncateAround(
            String parent,
            String child,
            int tokenBudget,
            int parentTokens
    ) {
        if (tokenBudget >= parentTokens) {
            return parent;
        }
        int totalCodePoints = parent.codePointCount(0, parent.length());
        int window = Math.max(
                1,
                (int) Math.min(
                        totalCodePoints,
                        Math.floor((double) totalCodePoints * tokenBudget / parentTokens)
                )
        );
        int childChar = parent.indexOf(child);
        int childStart = childChar < 0 ? 0 : parent.codePointCount(0, childChar);
        int childLength = childChar < 0 ? 0 : child.codePointCount(0, child.length());
        int start = Math.max(0, childStart - Math.max(0, window - childLength) / 2);
        start = Math.min(start, totalCodePoints - window);
        int startChar = parent.offsetByCodePoints(0, start);
        int endChar = parent.offsetByCodePoints(startChar, window);
        String prefix = start == 0 ? "" : "…";
        String suffix = start + window == totalCodePoints ? "" : "…";
        return prefix + parent.substring(startChar, endChar) + suffix;
    }

    record ContextSeed(
            UUID chunkId,
            UUID documentId,
            UUID revisionId,
            String childText
    ) {
    }

    record ContextRow(
            UUID childId,
            UUID documentId,
            UUID revisionId,
            String documentFormat,
            String childText,
            int childTokenCount,
            UUID parentId,
            String parentText,
            String parentHeadingPath,
            String parentLocatorKind,
            Integer parentStartPage,
            Integer parentEndPage,
            UUID parentStartUnitId,
            UUID parentEndUnitId,
            int parentStartOffset,
            int parentEndOffset,
            String parentNormalizationVersion,
            String parentLocatorAddress,
            String parentSourceLabel,
            Integer parentTokenCount
    ) {
        ContextRow(
                UUID childId,
                UUID documentId,
                UUID revisionId,
                String childText,
                int childTokenCount,
                UUID parentId,
                String parentText,
                String parentHeadingPath,
                int parentStartPage,
                int parentEndPage,
                Integer parentTokenCount
        ) {
            this(
                    childId,
                    documentId,
                    revisionId,
                    "PDF",
                    childText,
                    childTokenCount,
                    parentId,
                    parentText,
                    parentHeadingPath,
                    "PAGE",
                    parentStartPage,
                    parentEndPage,
                    null,
                    null,
                    0,
                    0,
                    "pdf-page-compat-v1",
                    null,
                    SourceLocatorResponse.pdfCompatibility(
                            parentStartPage, parentEndPage
                    ).sourceLabel(),
                    parentTokenCount
            );
        }
    }

    record Material(
            String childText,
            int childTokenCount,
            ParentContext parent
    ) {
        Material withParent(ParentContext value) {
            return new Material(childText, childTokenCount, value);
        }
    }

    record ContextPlan(
            Map<UUID, Material> materials,
            int limitTokens,
            int childTokens,
            int parentTokens,
            int parentCount,
            List<String> trimReasons
    ) {
        static ContextPlan empty() {
            return new ContextPlan(Map.of(), 0, 0, 0, 0, List.of());
        }

        int totalTokens() {
            return childTokens + parentTokens;
        }
    }
}
