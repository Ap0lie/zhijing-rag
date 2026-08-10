package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.search.SearchAccessService.AuthorizedRevision;
import com.example.rag.search.SearchContracts.ChunkContext;
import com.example.rag.search.SearchContracts.ChunkView;
import com.example.rag.search.SearchContracts.SourceSpanView;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
public class ChunkContextService {

    private final JdbcTemplate jdbc;
    private final SearchAccessService access;

    ChunkContextService(JdbcTemplate jdbc, SearchAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public ChunkContext get(UUID chunkId, PlatformUserPrincipal user) {
        ChildRow child = jdbc.query(
                """
                SELECT chunk.id, chunk.document_id, document.title, chunk.revision_id,
                       revision.revision_number, chunk.parent_chunk_id, chunk.chunk_order,
                       chunk.text, chunk.heading_path, chunk.token_count,
                       revision.document_format
                FROM chunks chunk
                JOIN documents document ON document.id = chunk.document_id
                JOIN document_revisions revision ON revision.id = chunk.revision_id
                WHERE chunk.id = ? AND chunk.chunk_type = 'CHILD' AND chunk.searchable = TRUE
                """,
                resultSet -> resultSet.next() ? new ChildRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getInt("revision_number"),
                        resultSet.getObject("parent_chunk_id", UUID.class),
                        resultSet.getInt("chunk_order"),
                        resultSet.getString("text"),
                        resultSet.getString("heading_path"),
                        resultSet.getInt("token_count"),
                        resultSet.getString("document_format")
                ) : null,
                chunkId
        );
        if (child == null) {
            throw notFound();
        }
        Map<UUID, AuthorizedRevision> allowed = access.authorizedByDocument(user, child.documentId(), null);
        AuthorizedRevision current = allowed.get(child.documentId());
        if (current == null || !current.revisionId().equals(child.revisionId())) {
            throw notFound();
        }
        ChunkView parent = jdbc.query(
                """
                SELECT chunk.id, chunk.chunk_order, chunk.text, chunk.heading_path,
                       chunk.token_count,
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
                       COALESCE(
                           (array_agg(
                               location.source_label
                               ORDER BY span.span_order
                           ) FILTER (
                               WHERE location.locator_kind <> 'CELL_RANGE'
                                  OR location.source_label LIKE '%!%'
                           ))[1],
                           (array_agg(
                               location.source_label
                               ORDER BY span.span_order
                           ))[1]
                       ) AS source_label
                FROM chunks chunk
                LEFT JOIN source_spans span ON span.chunk_id = chunk.id
                LEFT JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE chunk.id = ? AND chunk.chunk_type = 'PARENT'
                  AND chunk.document_id = ? AND chunk.revision_id = ?
                GROUP BY chunk.id, chunk.chunk_order, chunk.text,
                         chunk.heading_path, chunk.token_count
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    Integer startPage = resultSet.getObject(
                            "start_page", Integer.class
                    );
                    Integer endPage = resultSet.getObject(
                            "end_page", Integer.class
                    );
                    SourceLocatorResponse locator =
                            new SourceLocatorResponse(
                                    resultSet.getString("locator_kind"),
                                    resultSet.getObject(
                                            "start_source_unit_id",
                                            UUID.class
                                    ),
                                    resultSet.getObject(
                                            "end_source_unit_id",
                                            UUID.class
                                    ),
                                    resultSet.getInt("start_offset"),
                                    resultSet.getInt("end_offset"),
                                    null,
                                    null,
                                    resultSet.getString(
                                            "normalization_version"
                                    ),
                                    startPage,
                                    endPage,
                                    resultSet.getString("source_label")
                            );
                    return new ChunkView(
                            resultSet.getObject("id", UUID.class),
                            "PARENT",
                            resultSet.getInt("chunk_order"),
                            resultSet.getString("text"),
                            SearchService.path(
                                    resultSet.getString("heading_path")
                            ),
                            startPage,
                            endPage,
                            resultSet.getInt("token_count"),
                            child.documentFormat(),
                            locator,
                            locator.sourceLabel()
                    );
                },
                child.parentId(), child.documentId(), child.revisionId()
        );
        if (parent == null) {
            throw notFound();
        }
        List<SourceSpanView> spans = jdbc.query(
                """
                SELECT span.id, span.span_order,
                       location.locator_kind,
                       location.start_page, location.end_page,
                       location.start_offset, location.end_offset,
                       span.chunk_start_offset, span.chunk_end_offset,
                       location.source_text_hash,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.address::text AS locator_address,
                       location.normalization_version,
                       location.source_label
                FROM source_spans span
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE span.chunk_id = ?
                ORDER BY span.span_order
                """,
                (resultSet, rowNumber) -> {
                    Integer startPage = resultSet.getObject(
                            "start_page", Integer.class
                    );
                    Integer endPage = resultSet.getObject(
                            "end_page", Integer.class
                    );
                    SourceLocatorResponse locator =
                            new SourceLocatorResponse(
                                    resultSet.getString("locator_kind"),
                                    resultSet.getObject(
                                            "start_source_unit_id",
                                            UUID.class
                                    ),
                                    resultSet.getObject(
                                            "end_source_unit_id",
                                            UUID.class
                                    ),
                                    resultSet.getInt("start_offset"),
                                    resultSet.getInt("end_offset"),
                                    resultSet.getString(
                                            "locator_address"
                                    ),
                                    resultSet.getString(
                                            "source_text_hash"
                                    ),
                                    resultSet.getString(
                                            "normalization_version"
                                    ),
                                    startPage,
                                    endPage,
                                    resultSet.getString("source_label")
                            );
                    return new SourceSpanView(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getInt("span_order"),
                            startPage,
                            endPage,
                            resultSet.getInt("start_offset"),
                            resultSet.getInt("end_offset"),
                            resultSet.getInt("chunk_start_offset"),
                            resultSet.getInt("chunk_end_offset"),
                            resultSet.getString("source_text_hash"),
                            child.documentFormat(),
                            locator,
                            resultSet.getString("source_label")
                    );
                },
                child.id()
        );
        Integer childStartPage = spans.stream()
                .map(SourceSpanView::startPage)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
        Integer childEndPage = spans.stream()
                .map(SourceSpanView::endPage)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        SourceLocatorResponse childLocator = aggregateLocator(
                spans, childStartPage, childEndPage
        );
        AuthorizedRevision confirmed = access.authorizedByDocument(user, child.documentId(), null)
                .get(child.documentId());
        if (confirmed == null
                || !confirmed.revisionId().equals(child.revisionId())
                || confirmed.aclVersion() != current.aclVersion()) {
            throw notFound();
        }
        return new ChunkContext(
                child.documentId(),
                child.title(),
                child.revisionId(),
                child.revisionNumber(),
                new ChunkView(
                        child.id(), "CHILD", child.order(), child.text(),
                        SearchService.path(child.headingPath()),
                        childStartPage,
                        childEndPage,
                        child.tokenCount(),
                        child.documentFormat(),
                        childLocator,
                        childLocator.sourceLabel()
                ),
                parent,
                spans,
                child.documentFormat()
        );
    }

    private static SourceLocatorResponse aggregateLocator(
            List<SourceSpanView> spans,
            Integer startPage,
            Integer endPage
    ) {
        if (spans.isEmpty()) {
            return new SourceLocatorResponse(
                    null, null, null, 0, 0, null, null, null,
                    startPage, endPage, null
            );
        }
        List<SourceSpanView> precise = spans.stream()
                .filter(span -> span.sourceLocator() != null
                        && "CELL_RANGE".equals(span.sourceLocator().kind())
                        && span.sourceLabel() != null
                        && span.sourceLabel().contains("!"))
                .toList();
        List<SourceSpanView> selected = precise.isEmpty() ? spans : precise;
        SourceLocatorResponse first = selected.getFirst().sourceLocator();
        SourceLocatorResponse last = selected.getLast().sourceLocator();
        String sourceLabel = first.sourceLabel();
        if (!Objects.equals(sourceLabel, last.sourceLabel())) {
            sourceLabel = sourceLabel + " – " + last.sourceLabel();
        }
        return new SourceLocatorResponse(
                first.kind(),
                first.startUnit(),
                last.endUnit(),
                first.startOffset(),
                last.endOffset(),
                first.address(),
                selected.size() == 1 ? first.sourceTextHash() : null,
                first.normalizationVersion(),
                startPage,
                endPage,
                sourceLabel
        );
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Chunk 不存在");
    }

    private record ChildRow(
            UUID id,
            UUID documentId,
            String title,
            UUID revisionId,
            int revisionNumber,
            UUID parentId,
            int order,
            String text,
            String headingPath,
            int tokenCount,
            String documentFormat
    ) {
    }
}
