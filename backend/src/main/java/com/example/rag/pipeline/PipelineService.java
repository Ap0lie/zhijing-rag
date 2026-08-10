package com.example.rag.pipeline;

import com.example.rag.common.ApiException;
import com.example.rag.document.DocumentAccessService;
import com.example.rag.document.DocumentRuntimePolicyService;
import com.example.rag.document.ObjectStorageService;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.governance.GovernanceContracts.OperationImpactRequest;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.governance.OperationImpactService;
import com.example.rag.persistence.ChunkEntity;
import com.example.rag.persistence.ChunkRepository;
import com.example.rag.persistence.ChunkType;
import com.example.rag.persistence.ContentBlockEntity;
import com.example.rag.persistence.ContentBlockRepository;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.ParsedDocumentRepository;
import com.example.rag.persistence.PipelineJobRepository;
import com.example.rag.persistence.PipelineJobStatus;
import com.example.rag.persistence.PipelineStage;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.SourceSpanEntity;
import com.example.rag.persistence.SourceSpanRepository;
import com.example.rag.persistence.SourceUnitEntity;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Service
public class PipelineService {

    private static final int PREVIEW_LIMIT = 200;
    private static final int TABLE_CELL_PREVIEW_LIMIT = 20_000;
    private static final int MARKDOWN_PREVIEW_LIMIT = 100_000;
    private static final int SOURCE_SPAN_PREVIEW_LIMIT = 1_000;

    private final PipelineJobRepository jobs;
    private final DocumentRevisionRepository revisions;
    private final ParsedDocumentRepository parsedDocuments;
    private final ContentBlockRepository blocks;
    private final ChunkRepository chunks;
    private final SourceSpanRepository spans;
    private final DocumentAccessService access;
    private final PipelineProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectStorageService storage;
    private final ObjectMapper objectMapper;
    private final DocumentRuntimePolicyService runtimePolicies;
    private final GovernanceEventService governanceEvents;
    private final OperationImpactService operationImpacts;
    private final PipelineRevisionService pipelineRevisions;

    public PipelineService(
            PipelineJobRepository jobs,
            DocumentRevisionRepository revisions,
            ParsedDocumentRepository parsedDocuments,
            ContentBlockRepository blocks,
            ChunkRepository chunks,
            SourceSpanRepository spans,
            DocumentAccessService access,
            PipelineProperties properties,
            JdbcTemplate jdbc,
            ObjectStorageService storage,
            ObjectMapper objectMapper,
            DocumentRuntimePolicyService runtimePolicies,
            GovernanceEventService governanceEvents,
            OperationImpactService operationImpacts,
            PipelineRevisionService pipelineRevisions
    ) {
        this.jobs = jobs;
        this.revisions = revisions;
        this.parsedDocuments = parsedDocuments;
        this.blocks = blocks;
        this.chunks = chunks;
        this.spans = spans;
        this.access = access;
        this.properties = properties;
        this.jdbc = jdbc;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.runtimePolicies = runtimePolicies;
        this.governanceEvents = governanceEvents;
        this.operationImpacts = operationImpacts;
        this.pipelineRevisions = pipelineRevisions;
    }

    @Transactional(readOnly = true)
    public PipelineJobPageResponse list(
            PipelineStage stage,
            PipelineJobStatus status,
            int page,
            int size
    ) {
        var result = jobs.findFiltered(
                stage,
                status,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 50))
        );
        var pageItems = result.getContent();
        Set<UUID> activeParseRevisions = activeParseRevisions(
                pageItems.stream()
                        .map(job -> job.getRevision().getId())
                        .distinct()
                        .toList()
        );
        return new PipelineJobPageResponse(
                pageItems.stream()
                        .map(job -> PipelineJobResponse.from(
                                job,
                                properties.pipelineVersion(),
                                activeParseRevisions.contains(job.getRevision().getId())
                        ))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public List<PipelineJobResponse> timeline(
            UUID documentId,
            UUID revisionId,
            PlatformUserPrincipal user
    ) {
        access.requireVisibleRevision(documentId, revisionId, user);
        var timeline = jobs.findAllByRevisionIdOrderByCreatedAtAsc(revisionId);
        Set<UUID> activeParseRevisions = activeParseRevisions(List.of(revisionId));
        return timeline.stream()
                .map(job -> PipelineJobResponse.from(
                        job,
                        properties.pipelineVersion(),
                        activeParseRevisions.contains(job.getRevision().getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public RevisionArtifactsResponse artifacts(
            UUID documentId,
            UUID revisionId,
            PlatformUserPrincipal user
    ) {
        access.requireVisibleRevision(documentId, revisionId, user);
        var parsed = parsedDocuments.findByRevisionId(revisionId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PARSED_ARTIFACT_NOT_FOUND",
                        "该版本尚无解析产物"
                ));
        var contentBlocks = blocks.findAllByRevisionId(
                revisionId,
                PageRequest.of(0, PREVIEW_LIMIT, Sort.by("blockOrder"))
        ).getContent();
        List<ChunkEntity> previewChunks = new ArrayList<>();
        for (ChunkType type : ChunkType.values()) {
            previewChunks.addAll(chunks.findAllByRevisionIdAndChunkType(
                    revisionId,
                    type,
                    PageRequest.of(0, PREVIEW_LIMIT, Sort.by("chunkOrder"))
            ).getContent());
        }
        previewChunks.sort(Comparator
                .comparing(ChunkEntity::getChunkType)
                .thenComparingInt(ChunkEntity::getChunkOrder));
        Map<UUID, List<SourceSpanEntity>> spansByChunk = spans
                .findAllByChunkIdInOrderByChunkIdAscSpanOrderAsc(
                        previewChunks.stream().map(ChunkEntity::getId).toList()
                )
                .stream()
                .collect(Collectors.groupingBy(span -> span.getChunk().getId()));
        String chunkerVersion = previewChunks.isEmpty() ? properties.chunkerVersion()
                : previewChunks.getFirst().getChunkerVersion();
        String tokenCounterVersion = previewChunks.isEmpty()
                ? properties.chunkingProfile().tokenCounterVersion()
                : previewChunks.getFirst().getTokenCounterVersion();
        return new RevisionArtifactsResponse(
                revisionId,
                parsed.getParserVersion(),
                chunkerVersion,
                tokenCounterVersion,
                markdownPreview(parsed.getMarkdown()),
                contentBlocks.stream().map(block -> blockResponse(
                        block,
                        parsed.getDocumentFormat().name()
                )).toList(),
                previewChunks.stream().map(chunk -> chunkResponse(
                        chunk,
                        spansByChunk.getOrDefault(chunk.getId(), List.of()),
                        parsed.getDocumentFormat().name()
                )).toList()
        );
    }

    @Transactional(readOnly = true)
    public RevisionStructureResponse structure(
            UUID documentId,
            UUID revisionId,
            PlatformUserPrincipal user
    ) {
        access.requireVisibleRevision(documentId, revisionId, user);
        var parsed = parsedDocuments.findByRevisionId(revisionId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PARSED_ARTIFACT_NOT_FOUND",
                        "该版本尚无解析产物"
                ));

        List<TableRow> tableRows = jdbc.query(
                """
                SELECT table_asset.id, table_asset.content_block_id,
                       table_asset.preview_asset_id,
                       table_asset.table_order,
                       location.start_page AS page_number,
                       table_asset.bbox_x0, table_asset.bbox_y0,
                       table_asset.bbox_x1, table_asset.bbox_y1,
                       table_asset.caption,
                       location.source_text_hash,
                       location.document_format,
                       location.locator_kind,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.start_unit_order,
                       location.start_unit_kind,
                       location.start_offset,
                       location.end_offset,
                       location.address::text AS locator_address,
                       location.normalization_version,
                       location.start_page,
                       location.end_page,
                       location.source_label
                FROM document_tables table_asset
                JOIN source_locator_projection location
                  ON location.source_kind = 'TABLE'
                 AND location.source_id = table_asset.id
                WHERE table_asset.document_id = ?
                  AND table_asset.revision_id = ?
                ORDER BY table_asset.table_order
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new TableRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("content_block_id", UUID.class),
                        resultSet.getObject("preview_asset_id", UUID.class),
                        resultSet.getInt("table_order"),
                        resultSet.getObject("page_number", Integer.class),
                        box(resultSet),
                        resultSet.getString("caption"),
                        resultSet.getString("source_text_hash"),
                        resultSet.getString("document_format"),
                        locator(resultSet)
                ),
                documentId,
                revisionId,
                PREVIEW_LIMIT + 1
        );
        boolean tablesTruncated = tableRows.size() > PREVIEW_LIMIT;
        tableRows = tableRows.stream().limit(PREVIEW_LIMIT).toList();
        Map<UUID, List<RevisionStructureResponse.CellResponse>> cells = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT cell.table_id, cell.id, cell.row_index, cell.column_index,
                       cell.row_span, cell.column_span, cell.header,
                       cell.text, cell.source_text_hash,
                       cell.cell_reference, cell.cell_type,
                       cell.raw_value, cell.display_value,
                       cell.formula_text, cell.number_format
                FROM document_table_cells cell
                JOIN document_tables table_asset ON table_asset.id = cell.table_id
                WHERE table_asset.document_id = ? AND table_asset.revision_id = ?
                  AND table_asset.id IN (
                      SELECT selected.id
                      FROM document_tables selected
                      WHERE selected.document_id = ? AND selected.revision_id = ?
                      ORDER BY selected.table_order
                      LIMIT ?
                  )
                ORDER BY table_asset.table_order, cell.row_index, cell.column_index
                LIMIT ?
                """,
                resultSet -> {
                    int count = 0;
                    while (resultSet.next()) {
                        if (count++ >= TABLE_CELL_PREVIEW_LIMIT) {
                            continue;
                        }
                        UUID tableId = resultSet.getObject("table_id", UUID.class);
                        cells.computeIfAbsent(tableId, ignored -> new ArrayList<>()).add(
                                new RevisionStructureResponse.CellResponse(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getInt("row_index"),
                                        resultSet.getInt("column_index"),
                                        resultSet.getInt("row_span"),
                                        resultSet.getInt("column_span"),
                                        resultSet.getBoolean("header"),
                                        resultSet.getString("text"),
                                        resultSet.getString("source_text_hash"),
                                        resultSet.getString("cell_reference"),
                                        resultSet.getString("cell_type"),
                                        resultSet.getString("raw_value"),
                                        resultSet.getString("display_value"),
                                        resultSet.getString("formula_text"),
                                        resultSet.getString("number_format")
                                )
                        );
                    }
                    return null;
                },
                documentId,
                revisionId,
                documentId,
                revisionId,
                PREVIEW_LIMIT,
                TABLE_CELL_PREVIEW_LIMIT + 1
        );
        boolean cellsTruncated = cells.values().stream()
                .mapToInt(List::size)
                .sum() >= TABLE_CELL_PREVIEW_LIMIT;

        List<RevisionStructureResponse.ImageResponse> imageRows = jdbc.query(
                """
                SELECT asset.id, asset.content_block_id,
                       asset.asset_order, asset.asset_type,
                       location.start_page AS page_number,
                       asset.bbox_x0, asset.bbox_y0,
                       asset.bbox_x1, asset.bbox_y1,
                       asset.original_name, asset.media_type,
                       asset.byte_size, asset.content_hash, asset.caption,
                       location.document_format,
                       location.locator_kind,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.start_unit_order,
                       location.start_unit_kind,
                       location.start_offset,
                       location.end_offset,
                       location.address::text AS locator_address,
                       location.source_text_hash,
                       location.normalization_version,
                       location.start_page,
                       location.end_page,
                       location.source_label
                FROM document_image_assets asset
                JOIN source_locator_projection location
                  ON location.source_kind = 'IMAGE_ASSET'
                 AND location.source_id = asset.id
                WHERE asset.document_id = ?
                  AND asset.revision_id = ?
                ORDER BY asset.asset_order
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    return new RevisionStructureResponse.ImageResponse(
                            id,
                            resultSet.getInt("asset_order"),
                            resultSet.getString("asset_type"),
                            resultSet.getObject("content_block_id", UUID.class),
                            resultSet.getObject("page_number", Integer.class),
                            box(resultSet),
                            resultSet.getString("original_name"),
                            resultSet.getString("media_type"),
                            resultSet.getLong("byte_size"),
                            resultSet.getString("content_hash"),
                            resultSet.getString("caption"),
                            "/api/v1/documents/" + documentId
                                    + "/revisions/" + revisionId
                                    + "/assets/" + id + "/content",
                            resultSet.getString("document_format"),
                            locator(resultSet),
                            resultSet.getString("source_label")
                    );
                },
                documentId,
                revisionId,
                PREVIEW_LIMIT + 1
        );
        boolean imagesTruncated = imageRows.size() > PREVIEW_LIMIT;
        imageRows = imageRows.stream().limit(PREVIEW_LIMIT).toList();

        List<RevisionStructureResponse.SourceSpanResponse> spanRows = jdbc.query(
                """
                SELECT span.id, span.chunk_id, chunk.chunk_type, chunk.chunk_order,
                       span.span_order,
                       location.start_page, location.end_page,
                       location.start_offset, location.end_offset,
                       span.chunk_start_offset, span.chunk_end_offset,
                       location.source_text_hash,
                       span.bounding_boxes_json,
                       location.document_format,
                       location.locator_kind,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.address::text AS locator_address,
                       location.normalization_version,
                       location.source_label
                FROM source_spans span
                JOIN chunks chunk ON chunk.id = span.chunk_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE span.document_id = ? AND span.revision_id = ?
                ORDER BY chunk.chunk_type, chunk.chunk_order, span.span_order
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new RevisionStructureResponse.SourceSpanResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("chunk_id", UUID.class),
                        resultSet.getString("chunk_type"),
                        resultSet.getInt("chunk_order"),
                        resultSet.getInt("span_order"),
                        resultSet.getObject("start_page", Integer.class),
                        resultSet.getObject("end_page", Integer.class),
                        resultSet.getInt("start_offset"),
                        resultSet.getInt("end_offset"),
                        resultSet.getInt("chunk_start_offset"),
                        resultSet.getInt("chunk_end_offset"),
                        resultSet.getString("source_text_hash"),
                        boxes(resultSet.getString("bounding_boxes_json")),
                        resultSet.getString("document_format"),
                        locator(resultSet),
                        resultSet.getString("source_label")
                ),
                documentId,
                revisionId,
                SOURCE_SPAN_PREVIEW_LIMIT + 1
        );
        boolean spansTruncated = spanRows.size() > SOURCE_SPAN_PREVIEW_LIMIT;
        spanRows = spanRows.stream().limit(SOURCE_SPAN_PREVIEW_LIMIT).toList();

        return new RevisionStructureResponse(
                revisionId,
                new RevisionStructureResponse.PackageResponse(
                        parsed.getParserVersion(),
                        parsed.getParserRevision(),
                        parsed.getInputHash(),
                        parsed.getOutputHash(),
                        parsed.getResultSchemaVersion(),
                        parsed.getOffsetEncoding(),
                        parsed.getDocumentFormat().name().equals("PDF")
                                ? parsed.getSourceUnitCount()
                                : null,
                        parsed.getSourceUnitCount(),
                        parsed.getDocumentFormat().name(),
                        parsed.getParserProvider().name(),
                        manifestField(
                                parsed.getResultManifestJson(),
                                "encoding"
                        ),
                        manifestField(
                                parsed.getResultManifestJson(),
                                "sanitization"
                        ),
                        manifestField(
                                parsed.getResultManifestJson(),
                                "decisionCode"
                        ),
                        manifestField(
                                parsed.getResultManifestJson(),
                                "delimiter"
                        )
                ),
                tableRows.stream().map(table -> new RevisionStructureResponse.TableResponse(
                        table.id(),
                        table.order(),
                        table.contentBlockId(),
                        table.previewAssetId(),
                        table.pageNumber(),
                        table.boundingBox(),
                        table.caption(),
                        table.sourceTextHash(),
                        cells.getOrDefault(table.id(), List.of()),
                        table.documentFormat(),
                        table.sourceLocator(),
                        table.sourceLocator().sourceLabel()
                )).toList(),
                imageRows,
                spanRows,
                tablesTruncated || cellsTruncated
                        || imagesTruncated || spansTruncated
        );
    }

    private String manifestField(String manifest, String field) {
        try {
            JsonNode value = objectMapper.readTree(manifest).get(field);
            return value == null || value.isNull()
                    ? null
                    : value.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    public StructureAssetDownload openAsset(
            UUID documentId,
            UUID revisionId,
            UUID assetId,
            PlatformUserPrincipal user
    ) {
        access.requireVisibleRevision(documentId, revisionId, user);
        AssetRow asset = jdbc.query(
                """
                SELECT object_key, original_name, media_type, byte_size
                FROM document_image_assets
                WHERE id = ? AND document_id = ? AND revision_id = ?
                """,
                resultSet -> resultSet.next()
                        ? new AssetRow(
                        resultSet.getString("object_key"),
                        resultSet.getString("original_name"),
                        resultSet.getString("media_type"),
                        resultSet.getLong("byte_size")
                )
                        : null,
                assetId,
                documentId,
                revisionId
        );
        if (asset == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "STRUCTURE_ASSET_NOT_FOUND",
                    "结构资产不存在"
            );
        }
        access.requireVisibleRevision(documentId, revisionId, user);
        return new StructureAssetDownload(
                storage.open(asset.objectKey()),
                asset.filename(),
                asset.mediaType(),
                asset.size()
        );
    }

    @Transactional
    public PipelineJobResponse retry(UUID jobId, PlatformUserPrincipal actor) {
        var job = jobs.findForUpdate(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PIPELINE_JOB_NOT_FOUND",
                        "Pipeline 任务不存在"
                ));
        if (!job.isRetryable()
                || (job.getStage() != PipelineStage.PARSE && job.getStage() != PipelineStage.INDEX)
                || !job.getPipelineVersion().equals(properties.pipelineVersion())
                || job.getRevision().getDocument().getDeletedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_JOB_NOT_RETRYABLE",
                    "当前任务状态不允许重试"
            );
        }
        requireRuntimePolicy(job.getRevision().getDocumentFormat(), job.getParserRequestedEngine());
        if (job.getStage() == PipelineStage.PARSE) {
            var revision = revisions.findForUpdate(job.getRevision().getId())
                    .orElseThrow();
            ensureNoOtherActiveParse(revision.getId(), job.getId());
            revision.markProcessing();
        } else if (job.getRevision().getStatus() != RevisionStatus.READY) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_JOB_NOT_RETRYABLE",
                    "只有解析完成的版本可以重试索引"
            );
        }
        job.retry(properties.maxAttempts());
        audit(jobId, "RETRY", "管理员人工重试 Pipeline 任务", actor.id());
        revisions.flush();
        flushRetry();
        return PipelineJobResponse.from(job, properties.pipelineVersion());
    }

    @Transactional
    public PipelineRevisionContracts.RecoveryResponse recover(
            UUID jobId,
            PipelineRecoveryRequest request,
            PlatformUserPrincipal actor
    ) {
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String requestHash = governanceEvents.requestHash(
                jobId + "\n" + reason + "\n" + request.confirmation()
        );
        governanceEvents.lockIdempotency(actor, "PIPELINE_RECOVER", request.idempotencyKey());
        String existingHash = governanceEvents.existingRequestHash(
                actor, "PIPELINE_MANUAL_RECOVERY", request.idempotencyKey()
        );
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的 Pipeline 恢复请求"
                );
            }
            var existing = jobs.findById(jobId).orElseThrow(() -> notFound(jobId));
            var impact = operationImpacts.replayedPipelineRecovery(jobId);
            return new PipelineRevisionContracts.RecoveryResponse(
                    PipelineJobResponse.from(existing, properties.pipelineVersion()),
                    pipelineRevisions.get(
                            existing.getRevision().getDocument().getId(),
                            existing.getRevision().getId()
                    ),
                    impact,
                    true
            );
        }

        var impact = operationImpacts.preflight(new OperationImpactRequest(
                "PIPELINE_RECOVER", jobId.toString(), Map.of()
        ));
        if (!impact.blockers().isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_RECOVERY_BLOCKED",
                    impact.blockers().getFirst()
            );
        }
        var job = jobs.findForUpdate(jobId).orElseThrow(() -> notFound(jobId));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", job.getStatus().name());
        before.put("stage", job.getStage().name());
        before.put("attempt", job.getAttempt());
        before.put("maxAttempts", job.getMaxAttempts());
        before.put("errorCode", job.getErrorCode());
        before.put("errorMessage", job.getErrorMessage());
        before.put("quarantineReason", job.getQuarantineReason());

        validateRetry(job);
        prepareRetry(job);
        revisions.flush();
        flushRetry();

        governanceEvents.append(
                "PIPELINE",
                "PIPELINE_MANUAL_RECOVERY",
                actor,
                "PIPELINE_JOB",
                jobId.toString(),
                job.getRevision().getDocument().getTitle(),
                before,
                Map.of(
                        "status", job.getStatus().name(),
                        "stage", job.getStage().name(),
                        "revisionId", job.getRevision().getId().toString()
                ),
                reason,
                request.idempotencyKey(),
                requestHash
        );
        audit(jobId, "RETRY", reason, actor.id());
        return new PipelineRevisionContracts.RecoveryResponse(
                PipelineJobResponse.from(job, properties.pipelineVersion()),
                pipelineRevisions.get(
                        job.getRevision().getDocument().getId(),
                        job.getRevision().getId()
                ),
                impact,
                false
        );
    }

    private void validateRetry(com.example.rag.persistence.PipelineJobEntity job) {
        if (!job.isRetryable()
                || job.getStatus() == PipelineJobStatus.QUARANTINED
                || (job.getStage() != PipelineStage.PARSE && job.getStage() != PipelineStage.INDEX)
                || !job.getPipelineVersion().equals(properties.pipelineVersion())
                || job.getRevision().getDocument().getDeletedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_JOB_NOT_RECOVERABLE",
                    "当前任务不能人工重新排队"
            );
        }
        requireRuntimePolicy(job.getRevision().getDocumentFormat(), job.getParserRequestedEngine());
    }

    private void prepareRetry(com.example.rag.persistence.PipelineJobEntity job) {
        if (job.getStage() == PipelineStage.PARSE) {
            var revision = revisions.findForUpdate(job.getRevision().getId()).orElseThrow();
            ensureNoOtherActiveParse(revision.getId(), job.getId());
            revision.markProcessing();
        } else if (job.getRevision().getStatus() != RevisionStatus.READY) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_JOB_NOT_RECOVERABLE",
                    "只有解析完成的版本可以人工重新排队索引"
            );
        }
        job.retry(properties.maxAttempts());
    }

    @Transactional
    public PipelineJobResponse releaseQuarantine(
            UUID jobId,
            PipelineActionRequest request,
            PlatformUserPrincipal actor
    ) {
        requireConfirmation(request.confirmation(), "RELEASE_QUARANTINE");
        var job = jobs.findForUpdate(jobId)
                .orElseThrow(() -> notFound(jobId));
        if (job.getStage() != PipelineStage.PARSE
                || job.getStatus() != PipelineJobStatus.QUARANTINED
                || !job.getPipelineVersion().equals(properties.pipelineVersion())
                || job.getRevision().getStatus() != RevisionStatus.QUARANTINED
                || job.getRevision().getDocument().getDeletedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_QUARANTINE_RELEASE_NOT_ALLOWED",
                    "当前任务不能解除隔离"
            );
        }
        requireRuntimePolicy(job.getRevision().getDocumentFormat(), job.getParserRequestedEngine());
        var revision = revisions.findForUpdate(job.getRevision().getId())
                .orElseThrow();
        ensureNoOtherActiveParse(revision.getId(), job.getId());
        revision.markProcessing();
        job.retry(properties.maxAttempts());
        audit(jobId, "RELEASE_QUARANTINE", request.reason(), actor.id());
        revisions.flush();
        flushRetry();
        return PipelineJobResponse.from(job, properties.pipelineVersion());
    }

    @Transactional
    public PipelineJobResponse cancel(
            UUID jobId,
            PipelineActionRequest request,
            PlatformUserPrincipal actor
    ) {
        requireConfirmation(request.confirmation(), "CANCEL");
        var job = jobs.findForUpdate(jobId)
                .orElseThrow(() -> notFound(jobId));
        if (!job.isCancelable()
                || (job.getStage() != PipelineStage.PARSE
                && job.getStage() != PipelineStage.INDEX)
                || !job.getPipelineVersion().equals(properties.pipelineVersion())
                || job.getRevision().getDocument().getDeletedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PIPELINE_JOB_NOT_CANCELABLE",
                    "当前任务不能取消"
            );
        }
        if (job.getStage() == PipelineStage.PARSE) {
            job.getRevision().markCancelled();
        }
        job.cancel();
        audit(jobId, "CANCEL", request.reason(), actor.id());
        revisions.flush();
        jobs.flush();
        return PipelineJobResponse.from(job, properties.pipelineVersion());
    }

    @Transactional
    public PipelineJobResponse overrideParser(
            UUID sourceJobId,
            ParserOverrideRequest request,
            PlatformUserPrincipal actor
    ) {
        if (request.targetParser() == ParserEngine.AUTO) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PARSER_OVERRIDE_INVALID",
                    "管理员覆盖必须明确选择 PDFBOX 或 MINERU"
            );
        }
        PipelineJobResponse existing = existingOverride(sourceJobId, request, actor.id());
        if (existing != null) {
            return existing;
        }

        var source = jobs.findForUpdate(sourceJobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PIPELINE_JOB_NOT_FOUND",
                        "Pipeline 任务不存在"
                ));
        var revision = revisions.findForUpdate(source.getRevision().getId())
                .orElseThrow();
        requireRuntimePolicy(revision.getDocumentFormat(), request.targetParser());
        if (source.getStage() != PipelineStage.PARSE
                || (source.getStatus() != PipelineJobStatus.FAILED
                && source.getStatus() != PipelineJobStatus.QUARANTINED)
                || !source.getPipelineVersion().equals(properties.pipelineVersion())
                || (revision.getStatus() != RevisionStatus.FAILED
                && revision.getStatus() != RevisionStatus.QUARANTINED)
                || revision.getDocument().getDeletedAt() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PARSER_OVERRIDE_NOT_ALLOWED",
                    "只有当前版本中失败或隔离的 PARSE 任务可以创建解析覆盖"
            );
        }
        ensureNoOtherActiveParse(revision.getId(), sourceJobId);

        UUID jobId = UUID.randomUUID();
        int inserted;
        try {
            inserted = jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, document_format, stage, status, attempt,
                    max_attempts, pipeline_version,
                    parser_requested_engine, parser_decision_code,
                    parser_override_source_job_id, parser_override_key,
                    parser_override_reason, parser_override_by
                ) VALUES (?, ?, ?, 'PARSE', 'PENDING', 0, ?, ?, ?, 'ADMIN_OVERRIDE_REQUESTED', ?, ?, ?, ?)
                ON CONFLICT (parser_override_by, parser_override_key)
                    WHERE parser_override_key IS NOT NULL
                    DO NOTHING
                """,
                jobId,
                source.getRevision().getId(),
                revision.getDocumentFormat().name(),
                properties.maxAttempts(),
                properties.pipelineVersion(),
                request.targetParser().name(),
                sourceJobId,
                request.idempotencyKey(),
                request.reason().strip(),
                    actor.id()
            );
        } catch (DuplicateKeyException exception) {
            throw parseAlreadyActive(exception);
        }
        if (inserted == 0) {
            PipelineJobResponse concurrent = existingOverride(sourceJobId, request, actor.id());
            if (concurrent != null) {
                return concurrent;
            }
            throw new IllegalStateException("Parser override idempotency conflict could not be resolved");
        }
        revision.markProcessing();
        revisions.flush();
        return PipelineJobResponse.from(
                jobs.findForUpdate(jobId).orElseThrow(),
                properties.pipelineVersion()
        );
    }

    public UUID enqueue(UUID revisionId) {
        return enqueue(revisionId, ParserEngine.AUTO);
    }

    public UUID enqueue(UUID revisionId, ParserEngine parser) {
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, document_format, stage, status, attempt,
                    max_attempts, pipeline_version,
                    parser_requested_engine
                ) SELECT ?, revision.id, revision.document_format,
                         'PARSE', 'PENDING', 0, ?, ?, ?
                  FROM document_revisions revision
                 WHERE revision.id = ?
                ON CONFLICT DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                id,
                properties.maxAttempts(),
                properties.pipelineVersion(),
                parser.name(),
                revisionId
        );
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        return jdbc.queryForObject(
                """
                SELECT id
                FROM pipeline_jobs
                WHERE revision_id = ?
                  AND stage = 'PARSE'
                  AND pipeline_version = ?
                  AND parser_override_key IS NULL
                """,
                UUID.class,
                revisionId,
                properties.pipelineVersion()
        );
    }

    private void requireRuntimePolicy(
            com.example.rag.persistence.DocumentFormat format,
            ParserEngine parser
    ) {
        runtimePolicies.requireFormatEnabled(format);
        if (parser != null && parser != ParserEngine.AUTO) {
            runtimePolicies.requireProviderEnabled(
                    format,
                    ParserProviderKind.valueOf(parser.name())
            );
        }
    }

    public UUID parseJobId(UUID revisionId) {
        return jdbc.queryForObject(
                """
                SELECT id
                FROM pipeline_jobs
                WHERE revision_id = ?
                  AND stage = 'PARSE'
                  AND pipeline_version = ?
                  AND parser_override_key IS NULL
                """,
                UUID.class,
                revisionId,
                properties.pipelineVersion()
        );
    }

    private PipelineJobResponse existingOverride(
            UUID sourceJobId,
            ParserOverrideRequest request,
            UUID actorId
    ) {
        OverrideIdentity existing = findOverride(actorId, request.idempotencyKey());
        if (existing == null) {
            return null;
        }
        if (!existing.sourceJobId().equals(sourceJobId)
                || existing.engine() != request.targetParser()
                || !existing.reason().equals(request.reason().strip())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "该幂等键已用于其他解析覆盖请求"
            );
        }
        return PipelineJobResponse.from(
                jobs.findForUpdate(existing.jobId()).orElseThrow(),
                properties.pipelineVersion()
        );
    }

    private OverrideIdentity findOverride(UUID actorId, String key) {
        return jdbc.query(
                """
                SELECT id, parser_override_source_job_id,
                       parser_requested_engine, parser_override_reason
                FROM pipeline_jobs
                WHERE parser_override_by = ? AND parser_override_key = ?
                """,
                resultSet -> resultSet.next()
                        ? new OverrideIdentity(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("parser_override_source_job_id", UUID.class),
                                ParserEngine.valueOf(resultSet.getString("parser_requested_engine")),
                                resultSet.getString("parser_override_reason")
                        )
                        : null,
                actorId,
                key
        );
    }

    private Set<UUID> activeParseRevisions(List<UUID> revisionIds) {
        if (revisionIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(
                ",",
                Collections.nCopies(revisionIds.size(), "?")
        );
        return Set.copyOf(jdbc.queryForList(
                ("""
                SELECT DISTINCT revision_id
                FROM pipeline_jobs
                WHERE stage = 'PARSE'
                  AND status IN ('PENDING', 'RUNNING')
                  AND revision_id IN (%s)
                """).formatted(placeholders),
                UUID.class,
                revisionIds.toArray()
        ));
    }

    private void ensureNoOtherActiveParse(UUID revisionId, UUID jobId) {
        Boolean active = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pipeline_jobs
                    WHERE revision_id = ?
                      AND stage = 'PARSE'
                      AND status IN ('PENDING', 'RUNNING')
                      AND id <> ?
                )
                """,
                Boolean.class,
                revisionId,
                jobId
        );
        if (Boolean.TRUE.equals(active)) {
            throw parseAlreadyActive(null);
        }
    }

    private void flushRetry() {
        try {
            jobs.flush();
        } catch (DataIntegrityViolationException exception) {
            throw parseAlreadyActive(exception);
        }
    }

    private static ApiException parseAlreadyActive(Exception cause) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "PIPELINE_PARSE_ALREADY_ACTIVE",
                "该 Revision 已有活动的解析任务",
                cause
        );
    }

    public void cancelForDocument(UUID documentId) {
        jdbc.update(
                """
                UPDATE pipeline_jobs job
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    duration_ms = CASE
                        WHEN started_at IS NULL THEN 0
                        ELSE GREATEST(
                            0,
                            (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000)::BIGINT
                        )
                    END,
                    error_code = 'DOCUMENT_DELETED',
                    error_message = 'Document was deleted before pipeline completion',
                    quarantine_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM document_revisions revision
                WHERE revision.document_id = ?
                  AND job.revision_id = revision.id
                  AND job.status IN ('PENDING', 'RUNNING')
                """,
                documentId
        );
    }

    private void audit(
            UUID jobId,
            String action,
            String reason,
            UUID actorId
    ) {
        jdbc.update(
                """
                INSERT INTO pipeline_job_action_events (
                    job_id, action, actor_user_id, reason
                ) VALUES (?, ?, ?, ?)
                """,
                jobId,
                action,
                actorId,
                reason.strip()
        );
    }

    private static void requireConfirmation(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CONFIRMATION_INVALID",
                    "确认字段无效"
            );
        }
    }

    private static ApiException notFound(UUID jobId) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "PIPELINE_JOB_NOT_FOUND",
                "Pipeline 任务不存在: " + jobId
        );
    }

    private RevisionArtifactsResponse.ContentBlockResponse blockResponse(
            ContentBlockEntity block,
            String documentFormat
    ) {
        SourceLocatorResponse locator = new SourceLocatorResponse(
                block.getLocatorKind().name(),
                block.getStartSourceUnitId(),
                block.getEndSourceUnitId(),
                block.getStartOffset(),
                block.getEndOffset(),
                block.getLocatorAddress(),
                block.getSourceTextHash(),
                block.getNormalizationVersion(),
                page(block.getStartSourceUnit()),
                page(block.getEndSourceUnit()),
                sourceLabel(
                        block.getStartSourceUnit(),
                        block.getEndSourceUnit()
                )
        );
        return new RevisionArtifactsResponse.ContentBlockResponse(
                block.getId(),
                block.getBlockType().name(),
                block.getBlockOrder(),
                block.getText(),
                decodePath(block.getHeadingPath()),
                locator.startPage(),
                locator.endPage(),
                block.getStartOffset(),
                block.getEndOffset(),
                block.getCharacterCount(),
                block.getTokenCount(),
                documentFormat,
                locator,
                locator.sourceLabel()
        );
    }

    private RevisionArtifactsResponse.ChunkResponse chunkResponse(
            ChunkEntity chunk,
            List<SourceSpanEntity> sourceSpans,
            String documentFormat
    ) {
        SourceSpanEntity first = sourceSpans.isEmpty()
                ? null : sourceSpans.getFirst();
        SourceSpanEntity last = sourceSpans.isEmpty()
                ? null : sourceSpans.getLast();
        SourceLocatorResponse locator = first == null ? null
                : new SourceLocatorResponse(
                        first.getLocatorKind().name(),
                        first.getStartSourceUnitId(),
                        last.getEndSourceUnitId(),
                        first.getStartOffset(),
                        last.getEndOffset(),
                        first.getLocatorAddress(),
                        sourceSpans.size() == 1
                                ? first.getSourceTextHash() : null,
                        first.getNormalizationVersion(),
                        page(first.getStartSourceUnit()),
                        page(last.getEndSourceUnit()),
                        sourceLabel(
                                first.getStartSourceUnit(),
                                last.getEndSourceUnit()
                        )
                );
        return new RevisionArtifactsResponse.ChunkResponse(
                chunk.getId(),
                chunk.getChunkType().name(),
                chunk.getParentChunkId(),
                chunk.getChunkOrder(),
                chunk.getText(),
                decodePath(chunk.getHeadingPath()),
                locator == null ? null : locator.startPage(),
                locator == null ? null : locator.endPage(),
                chunk.getCharacterCount(),
                chunk.getTokenCount(),
                chunk.isSearchable(),
                documentFormat,
                locator,
                locator == null ? null : locator.sourceLabel()
        );
    }

    private static Integer page(SourceUnitEntity unit) {
        return unit != null && unit.getUnitKind() == SourceUnitKind.PAGE
                ? unit.getUnitOrder() : null;
    }

    private String sourceLabel(
            SourceUnitEntity start,
            SourceUnitEntity end
    ) {
        if (start == null || end == null) {
            return null;
        }
        if (start.getUnitKind() == SourceUnitKind.PAGE
                && end.getUnitKind() == SourceUnitKind.PAGE) {
            return start.getUnitOrder() == end.getUnitOrder()
                    ? "第 " + start.getUnitOrder() + " 页"
                    : "第 " + start.getUnitOrder() + "–"
                    + end.getUnitOrder() + " 页";
        }
        String startLabel = unitSourceLabel(start);
        String endLabel = unitSourceLabel(end);
        return start.getId().equals(end.getId()) || startLabel.equals(endLabel)
                ? startLabel
                : startLabel + " – " + endLabel;
    }

    private String unitSourceLabel(SourceUnitEntity unit) {
        try {
            JsonNode metadata = objectMapper.readTree(unit.getLabelMetadata());
            String label = metadata.path("sourceLabel").asText().strip();
            if (!label.isBlank()) {
                return label;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Historical rows without label metadata retain their stable address.
        }
        return unit.getStableAddress();
    }

    private static List<String> decodePath(String value) {
        return value.isBlank() ? List.of() : value.lines().toList();
    }

    private static RevisionStructureResponse.BoundingBoxResponse box(ResultSet resultSet)
            throws SQLException {
        String sourceUnitKind = resultSet.getString("start_unit_kind");
        int sourceUnitOrder = resultSet.getInt("start_unit_order");
        return new RevisionStructureResponse.BoundingBoxResponse(
                resultSet.getObject("start_source_unit_id", UUID.class),
                sourceUnitOrder,
                sourceUnitKind,
                "PAGE".equals(sourceUnitKind) ? sourceUnitOrder : null,
                resultSet.getInt("bbox_x0"),
                resultSet.getInt("bbox_y0"),
                resultSet.getInt("bbox_x1"),
                resultSet.getInt("bbox_y1")
        );
    }

    private static SourceLocatorResponse locator(
            ResultSet resultSet
    ) throws SQLException {
        return new SourceLocatorResponse(
                resultSet.getString("locator_kind"),
                resultSet.getObject(
                        "start_source_unit_id", UUID.class
                ),
                resultSet.getObject(
                        "end_source_unit_id", UUID.class
                ),
                resultSet.getInt("start_offset"),
                resultSet.getInt("end_offset"),
                resultSet.getString("locator_address"),
                resultSet.getString("source_text_hash"),
                resultSet.getString("normalization_version"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("source_label")
        );
    }

    private List<RevisionStructureResponse.BoundingBoxResponse> boxes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            if (!root.isArray()) {
                throw new IllegalStateException("Stored bounding boxes are invalid");
            }
            List<RevisionStructureResponse.BoundingBoxResponse> result = new ArrayList<>();
            for (JsonNode box : root) {
                String sourceUnitKind = box.path("sourceUnitKind").asText();
                int sourceUnitOrder = box.path("sourceUnitOrder").asInt();
                UUID sourceUnitId = null;
                if (box.hasNonNull("sourceUnitId")) {
                    sourceUnitId = UUID.fromString(box.get("sourceUnitId").asText());
                }
                if (sourceUnitKind.isBlank() && box.has("pageNumber")) {
                    sourceUnitKind = "PAGE";
                    sourceUnitOrder = box.path("pageNumber").asInt();
                }
                if (sourceUnitOrder < 1 || sourceUnitKind.isBlank()) {
                    throw new IllegalStateException("Stored bounding box SourceUnit is invalid");
                }
                result.add(new RevisionStructureResponse.BoundingBoxResponse(
                        sourceUnitId,
                        sourceUnitOrder,
                        sourceUnitKind,
                        "PAGE".equals(sourceUnitKind) ? sourceUnitOrder : null,
                        box.path("x0").asInt(),
                        box.path("y0").asInt(),
                        box.path("x1").asInt(),
                        box.path("y1").asInt()
                ));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored bounding boxes are invalid", exception);
        }
    }

    private static String markdownPreview(String markdown) {
        if (markdown.length() <= MARKDOWN_PREVIEW_LIMIT) {
            return markdown;
        }
        return markdown.substring(0, MARKDOWN_PREVIEW_LIMIT) + "\n\n<!-- 预览已截断 -->\n";
    }

    private record OverrideIdentity(
            UUID jobId,
            UUID sourceJobId,
            ParserEngine engine,
            String reason
    ) {
    }

    private record TableRow(
            UUID id,
            UUID contentBlockId,
            UUID previewAssetId,
            int order,
            Integer pageNumber,
            RevisionStructureResponse.BoundingBoxResponse boundingBox,
            String caption,
            String sourceTextHash,
            String documentFormat,
            SourceLocatorResponse sourceLocator
    ) {
    }

    private record AssetRow(
            String objectKey,
            String filename,
            String mediaType,
            long size
    ) {
    }

    public record StructureAssetDownload(
            GetObjectResponse stream,
            String filename,
            String mediaType,
            long size
    ) {
    }
}
