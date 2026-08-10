package com.example.rag.chat;

import com.example.rag.chat.ChatApiContracts.CitationSummary;
import com.example.rag.chat.ChatPersistenceContracts.Citation;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.memory.MemoryPackService;
import com.example.rag.memory.MemoryPackService.RunMemoryUsageView;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class AnswerSourceService {

    private final JdbcTemplate jdbc;
    private final MemoryPackService memories;

    AnswerSourceService(JdbcTemplate jdbc, MemoryPackService memories) {
        this.jdbc = jdbc;
        this.memories = memories;
    }

    @Transactional(readOnly = true)
    Map<UUID, RunSources> load(
            PlatformUserPrincipal user,
            List<UUID> requestedRunIds
    ) {
        List<UUID> runIds = requestedRunIds == null
                ? List.of()
                : requestedRunIds.stream().distinct().limit(100).toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CitationSource>> citations = new LinkedHashMap<>();
        citationSources(user, runIds).forEach(source ->
                citations.computeIfAbsent(
                        source.citation().runId(),
                        ignored -> new ArrayList<>()
                ).add(source));
        Map<UUID, List<RunMemoryUsageView>> usages = new LinkedHashMap<>();
        memories.runUsages(user, runIds).forEach(usage ->
                usages.computeIfAbsent(
                        usage.runId(),
                        ignored -> new ArrayList<>()
                ).add(usage));

        Map<UUID, RunSources> result = new LinkedHashMap<>();
        for (UUID runId : runIds) {
            List<CitationSource> runCitations = citations.getOrDefault(
                    runId,
                    List.of()
            );
            List<RunMemoryUsageView> usedMemories = usages.getOrDefault(
                            runId,
                            List.of()
                    ).stream()
                    .filter(usage -> "USED".equals(usage.usageStatus()))
                    .toList();
            boolean current = (!runCitations.isEmpty()
                    || !usedMemories.isEmpty())
                    && runCitations.stream().allMatch(CitationSource::available)
                    && usedMemories.stream().allMatch(
                    RunMemoryUsageView::available
            );
            List<CitationSummary> summaries = current
                    ? runCitations.stream()
                    .map(AnswerSourceService::summary)
                    .toList()
                    : List.of();
            result.put(
                    runId,
                    new RunSources(
                            current,
                            !usedMemories.isEmpty(),
                            summaries
                    )
            );
        }
        return Map.copyOf(result);
    }

    private List<CitationSource> citationSources(
            PlatformUserPrincipal user,
            List<UUID> runIds
    ) {
        String placeholders = String.join(
                ",",
                Collections.nCopies(runIds.size(), "?")
        );
        List<Object> arguments = new ArrayList<>();
        arguments.add(user.id());
        arguments.add(user.role().name());
        arguments.add(user.getPassword());
        arguments.add(user.id());
        arguments.addAll(runIds);
        return jdbc.query(
                """
                SELECT citation.id, citation.owner_user_id,
                       citation.session_id, citation.run_id,
                       citation.message_id, citation.document_id,
                       citation.revision_id, citation.child_chunk_id,
                       citation.source_span_id, citation.citation_order,
                       location.start_page, location.end_page,
                       location.start_offset, location.end_offset,
                       location.source_text_hash,
                       location.document_format,
                       location.locator_kind,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.address::text AS locator_address,
                       location.normalization_version,
                       location.source_label,
                       citation.created_at, document.title,
                       revision.revision_number,
                       (
                           request_user.id IS NOT NULL
                           AND document.deleted_at IS NULL
                           AND document.current_revision_id =
                               citation.revision_id
                           AND revision.status = 'READY'
                           AND child.chunk_type = 'CHILD'
                           AND child.searchable
                           AND (
                               request_user.role = 'ADMIN'
                               OR document.visibility = 'ALL_USERS'
                               OR document.owner_user_id = request_user.id
                               OR EXISTS (
                                   SELECT 1
                                   FROM document_acl_entries acl
                                   WHERE acl.document_id = document.id
                                     AND acl.user_id = request_user.id
                               )
                           )
                       ) AS available
                FROM citations citation
                JOIN source_spans span
                  ON span.id = citation.source_span_id
                 AND span.chunk_id = citation.child_chunk_id
                 AND span.document_id = citation.document_id
                 AND span.revision_id = citation.revision_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                JOIN documents document
                  ON document.id = citation.document_id
                JOIN document_revisions revision
                  ON revision.id = citation.revision_id
                 AND revision.document_id = citation.document_id
                JOIN chunks child
                  ON child.id = citation.child_chunk_id
                 AND child.document_id = citation.document_id
                 AND child.revision_id = citation.revision_id
                LEFT JOIN users request_user
                  ON request_user.id = ?
                 AND request_user.enabled
                 AND request_user.role = ?
                 AND request_user.password_hash = ?
                WHERE citation.owner_user_id = ?
                  AND citation.run_id IN (%s)
                ORDER BY citation.run_id, citation.citation_order
                """.formatted(placeholders),
                (rs, row) -> new CitationSource(
                        citation(rs),
                        rs.getString("title"),
                        rs.getInt("revision_number"),
                        rs.getString("document_format"),
                        locator(rs),
                        rs.getBoolean("available")
                ),
                arguments.toArray()
        );
    }

    private static Citation citation(ResultSet rs) throws SQLException {
        return new Citation(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("revision_id", UUID.class),
                rs.getObject("child_chunk_id", UUID.class),
                rs.getObject("source_span_id", UUID.class),
                rs.getInt("citation_order"),
                rs.getObject("start_page", Integer.class),
                rs.getObject("end_page", Integer.class),
                rs.getInt("start_offset"),
                rs.getInt("end_offset"),
                rs.getString("source_text_hash"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static CitationSummary summary(CitationSource source) {
        Citation citation = source.citation();
        return new CitationSummary(
                citation.id(),
                citation.documentId(),
                source.documentTitle(),
                citation.revisionId(),
                source.revisionNumber(),
                citation.childChunkId(),
                citation.startPage(),
                citation.endPage(),
                "[" + citation.order() + "] "
                        + source.locator().sourceLabel(),
                source.documentFormat(),
                source.locator(),
                source.locator().sourceLabel()
        );
    }

    private static SourceLocatorResponse locator(
            ResultSet rs
    ) throws SQLException {
        return new SourceLocatorResponse(
                rs.getString("locator_kind"),
                rs.getObject("start_source_unit_id", UUID.class),
                rs.getObject("end_source_unit_id", UUID.class),
                rs.getInt("start_offset"),
                rs.getInt("end_offset"),
                rs.getString("locator_address"),
                rs.getString("source_text_hash"),
                rs.getString("normalization_version"),
                rs.getObject("start_page", Integer.class),
                rs.getObject("end_page", Integer.class),
                rs.getString("source_label")
        );
    }

    record RunSources(
            boolean current,
            boolean usedMemory,
            List<CitationSummary> citations
    ) {
        static RunSources invalid() {
            return new RunSources(false, false, List.of());
        }
    }

    private record CitationSource(
            Citation citation,
            String documentTitle,
            int revisionNumber,
            String documentFormat,
            SourceLocatorResponse locator,
            boolean available
    ) {
    }
}
