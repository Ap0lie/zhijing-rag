package com.example.rag.pipeline;

import com.example.rag.document.ObjectStorageService;
import com.example.rag.document.StorageProperties;
import com.example.rag.persistence.ChunkEntity;
import com.example.rag.persistence.ChunkRepository;
import com.example.rag.persistence.ChunkType;
import com.example.rag.persistence.ChunkingProfileEntity;
import com.example.rag.persistence.ChunkingProfileRepository;
import com.example.rag.persistence.ContentBlockEntity;
import com.example.rag.persistence.ContentBlockRepository;
import com.example.rag.persistence.ContentBlockType;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.ParsedDocumentEntity;
import com.example.rag.persistence.ParsedDocumentRepository;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.SourceSpanEntity;
import com.example.rag.persistence.SourceSpanRepository;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitEntity;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.persistence.SourceUnitRepository;
import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.pipeline.parser.ChunkingProfile;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.pipeline.parser.ParsedPackageIntegrity;
import com.example.rag.pipeline.parser.ParsedStructure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class PipelineArtifactService {

    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ParserRoutingService parser;
    private final PipelineProperties properties;
    private final PipelineJobLeaseService leases;
    private final DocumentRevisionRepository revisions;
    private final ChunkingProfileRepository profiles;
    private final ParsedDocumentRepository parsedDocuments;
    private final ContentBlockRepository blocks;
    private final ChunkRepository chunks;
    private final SourceSpanRepository spans;
    private final SourceUnitRepository sourceUnits;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PipelineArtifactService(
            ObjectStorageService storage,
            StorageProperties storageProperties,
            ParserRoutingService parser,
            PipelineProperties properties,
            PipelineJobLeaseService leases,
            DocumentRevisionRepository revisions,
            ChunkingProfileRepository profiles,
            ParsedDocumentRepository parsedDocuments,
            ContentBlockRepository blocks,
            ChunkRepository chunks,
            SourceSpanRepository spans,
            SourceUnitRepository sourceUnits,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.parser = parser;
        this.properties = properties;
        this.leases = leases;
        this.revisions = revisions;
        this.profiles = profiles;
        this.parsedDocuments = parsedDocuments;
        this.blocks = blocks;
        this.chunks = chunks;
        this.spans = spans;
        this.sourceUnits = sourceUnits;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ParsedDocument parse(ClaimedJob task)
            throws IOException, ParseQuarantineException, ParserProcessingException {
        long maximum = storageProperties.maxFileSize().toBytes();
        var revision = revisions.findById(task.revisionId())
                .orElseThrow(() -> new IllegalStateException("Claimed revision no longer exists"));
        Path temporary = Files.createTempFile(
                "rag-parse-" + task.revisionId() + "-",
                "." + revision.getDocumentFormat().name().toLowerCase()
        );
        try {
            MessageDigest digest = digest();
            long byteSize;
            try (InputStream input = storage.open(task.sourceObjectKey());
                 OutputStream output = Files.newOutputStream(temporary)) {
                byteSize = copyBounded(input, output, digest, maximum);
            }
            String inputHash = HexFormat.of().formatHex(digest.digest());
            if (byteSize != revision.getFileSizeBytes()
                    || !inputHash.equals(revision.getContentHash())) {
                throw new IOException("Stored object no longer matches its immutable Revision");
            }
            return parser.parse(
                    new ParserInput(
                            temporary,
                            task.documentId(),
                            task.revisionId(),
                            revision.getDocumentFormat(),
                            revision.getMediaType(),
                            byteSize,
                            inputHash
                    ),
                    task,
                    parserProfile()
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long copyBounded(
            InputStream input,
            OutputStream output,
            MessageDigest digest,
            long maximum
    ) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > maximum) {
                throw new IOException("Stored document exceeds the configured size limit");
            }
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
        }
        return total;
    }

    @Transactional
    public boolean replaceArtifacts(ClaimedJob task, ParsedDocument parsed)
            throws ParserProcessingException {
        if (!leases.lockOwned(task.id(), task.attempt())) {
            return false;
        }
        if (!task.documentId().equals(parsed.documentId()) || !task.revisionId().equals(parsed.revisionId())) {
            throw new IllegalArgumentException("Parsed artifact identity does not match the claimed job");
        }
        var revision = revisions.findForUpdate(task.revisionId())
                .orElseThrow(() -> new IllegalStateException("Claimed revision no longer exists"));
        if (revision.getStatus() != RevisionStatus.PROCESSING
                || revision.getDocument().getDeletedAt() != null) {
            throw new LostPipelineLeaseException();
        }
        ChunkingProfileEntity profile = profiles.findById(parsed.chunkingProfileVersion())
                .orElseThrow(() -> new IllegalStateException("Chunking profile is not registered"));
        verifyProfile(profile, parsed, properties.chunkingProfile());
        verifyPackage(revision.getContentHash(), parsed);
        uploadImages(task, parsed.images());

        jdbc.update("DELETE FROM document_tables WHERE revision_id = ?", task.revisionId());
        jdbc.update("DELETE FROM document_image_assets WHERE revision_id = ?", task.revisionId());
        jdbc.update("DELETE FROM source_spans WHERE revision_id = ?", task.revisionId());
        jdbc.update("DELETE FROM chunks WHERE revision_id = ?", task.revisionId());
        jdbc.update("DELETE FROM content_blocks WHERE revision_id = ?", task.revisionId());
        jdbc.update("DELETE FROM parsed_documents WHERE revision_id = ?", task.revisionId());
        jdbc.update("DELETE FROM source_units WHERE revision_id = ?", task.revisionId());

        parsedDocuments.save(new ParsedDocumentEntity(
                revision,
                parsed.markdown(),
                parsed.parserVersion(),
                parsed.packageMetadata().parserRevision(),
                parsed.packageMetadata().inputHash(),
                parsed.packageMetadata().outputHash(),
                ParsedPackageIntegrity.PACKAGE_SCHEMA,
                parsed.packageMetadata().manifestJson(),
                parsed.documentFormat(),
                parsed.parserProvider(),
                parsed.sourceUnits().size(),
                parsed.characterCount(),
                parsed.durationMillis()
        ));
        sourceUnits.saveAllAndFlush(parsed.sourceUnits().stream()
                .map(unit -> new SourceUnitEntity(
                        unit.id(),
                        revision,
                        unit.order(),
                        unit.kind(),
                        unit.stableAddress(),
                        unit.canonicalText(),
                        unit.sourceTextHash(),
                        unit.normalizationVersion(),
                        unit.labelMetadataJson()
                ))
                .toList());
        List<ContentBlockEntity> savedBlocks = blocks.saveAll(parsed.contentBlocks().stream()
                .map(block -> new ContentBlockEntity(
                        block.id(),
                        revision,
                        block.order(),
                        ContentBlockType.valueOf(block.type().name()),
                        block.text(),
                        encodePath(block.headingPath()),
                        locatorKind(block.sourceSpan()),
                        block.sourceSpan().startSourceUnitId(),
                        block.sourceSpan().endSourceUnitId(),
                        locatorAddressJson(block.sourceSpan()),
                        block.sourceSpan().startOffset(),
                        block.sourceSpan().endOffset(),
                        block.tokenCount(),
                        block.tokenCounterVersion(),
                        block.sourceSpan().sourceTextHash(),
                        block.sourceSpan().normalizationVersion(),
                        parsed.parserVersion(),
                        boxesJson(block.sourceSpan().boundingBoxes())
                ))
                .toList());
        Map<Integer, UUID> blockIds = savedBlocks.stream().collect(
                java.util.stream.Collectors.toMap(
                        ContentBlockEntity::getBlockOrder,
                        ContentBlockEntity::getId
                )
        );

        Map<UUID, ChunkEntity> savedChunks = new HashMap<>();
        List<ParsedDocument.Chunk> ordered = parsed.chunks().stream()
                .sorted(Comparator.comparing(chunk -> chunk.type() == ParsedDocument.ChunkType.PARENT ? 0 : 1))
                .toList();
        for (ParsedDocument.Chunk chunk : ordered) {
            ChunkEntity entity = new ChunkEntity(
                    chunk.id(),
                    revision,
                    chunk.parentId(),
                    ChunkType.valueOf(chunk.type().name()),
                    chunk.order(),
                    chunk.text(),
                    encodePath(chunk.headingPath()),
                    chunk.startBlockOrder(),
                    chunk.endBlockOrder(),
                    chunk.tokenCount(),
                    chunk.tokenCounterVersion(),
                    profile,
                    parsed.parserVersion(),
                    parsed.chunkerVersion(),
                    sha256(chunk.text())
            );
            chunks.saveAndFlush(entity);
            savedChunks.put(entity.getId(), entity);
        }

        List<SourceSpanEntity> sourceSpans = new ArrayList<>();
        for (ParsedDocument.Chunk chunk : parsed.chunks()) {
            ChunkEntity entity = savedChunks.get(chunk.id());
            for (int index = 0; index < chunk.sourceSpans().size(); index++) {
                ParsedDocument.SourceSpan span = chunk.sourceSpans().get(index);
                sourceSpans.add(new SourceSpanEntity(
                        stableSpanId(chunk.id(), index),
                        entity,
                        index,
                        locatorKind(span),
                        span.startSourceUnitId(),
                        span.endSourceUnitId(),
                        locatorAddressJson(span),
                        span.startOffset(),
                        span.endOffset(),
                        span.chunkStartOffset(),
                        span.chunkEndOffset(),
                        span.sourceTextHash(),
                        span.normalizationVersion(),
                        boxesJson(span.boundingBoxes())
                ));
            }
        }
        spans.saveAll(sourceSpans);
        Map<UUID, ParsedDocument.SourceUnit> sourceUnitsById = parsed.sourceUnits().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ParsedDocument.SourceUnit::id,
                        java.util.function.Function.identity()
                ));
        saveImages(task, parsed.images(), blockIds, sourceUnitsById);
        saveTables(task, parsed.tables(), blockIds, sourceUnitsById);
        revision.markReady(parsed.parserVersion(), parsed.parserProvider());
        if (revision.getParserProvider() != parsed.parserProvider()
                || revision.getDocumentFormat() != parsed.documentFormat()) {
            throw new IllegalStateException(
                    "Revision format/provider does not match the sealed parser package"
            );
        }
        revisions.flush();
        jdbc.update(
                """
                INSERT INTO pipeline_jobs (
                    id, revision_id, document_format, stage, status, attempt,
                    max_attempts, pipeline_version, parser_provider,
                    parser_provider_version
                ) VALUES (?, ?, ?, 'INDEX', 'PENDING', 0, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                task.revisionId(),
                parsed.documentFormat().name(),
                properties.maxAttempts(),
                properties.pipelineVersion(),
                parsed.parserProvider().name(),
                parsed.parserVersion()
        );
        if (!leases.markSucceeded(task.id(), task.attempt())) {
            throw new LostPipelineLeaseException();
        }
        return true;
    }

    private void uploadImages(ClaimedJob task, List<ParsedStructure.Image> images) {
        for (ParsedStructure.Image image : images) {
            storage.upload(
                    assetObjectKey(task, image),
                    image.bytes(),
                    image.mediaType()
            );
        }
    }

    private void saveImages(
            ClaimedJob task,
            List<ParsedStructure.Image> images,
            Map<Integer, UUID> blockIds,
            Map<UUID, ParsedDocument.SourceUnit> sourceUnitsById
    ) {
        for (ParsedStructure.Image image : images) {
            UUID contentBlockId = image.sourceBlockOrder() == null
                    ? null
                    : blockIds.get(image.sourceBlockOrder());
            ParsedDocument.SourceUnit sourceUnit =
                    requireSourceUnit(sourceUnitsById, image.boundingBox());
            SourceLocatorKind locatorKind = sourceLocatorKind(sourceUnit);
            jdbc.update(
                    """
                    INSERT INTO document_image_assets (
                        id, document_id, revision_id, content_block_id,
                        asset_order, asset_type, source_unit_id,
                        locator_kind, locator_address, normalization_version,
                        bbox_x0, bbox_y0, bbox_x1, bbox_y1,
                        original_name, object_key, media_type, byte_size,
                        content_hash, caption
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    image.id(),
                    task.documentId(),
                    task.revisionId(),
                    contentBlockId,
                    image.order(),
                    image.type().name(),
                    sourceUnit.id(),
                    locatorKind.name(),
                    unitLocatorAddressJson(sourceUnit, locatorKind),
                    sourceUnit.normalizationVersion(),
                    image.boundingBox().x0(),
                    image.boundingBox().y0(),
                    image.boundingBox().x1(),
                    image.boundingBox().y1(),
                    image.originalName(),
                    assetObjectKey(task, image),
                    image.mediaType(),
                    image.bytes().length,
                    image.contentHash(),
                    image.caption()
            );
        }
    }

    private void saveTables(
            ClaimedJob task,
            List<ParsedStructure.Table> tables,
            Map<Integer, UUID> blockIds,
            Map<UUID, ParsedDocument.SourceUnit> sourceUnitsById
    ) {
        for (ParsedStructure.Table table : tables) {
            UUID blockId = blockIds.get(table.sourceBlockOrder());
            ParsedDocument.SourceUnit sourceUnit =
                    requireSourceUnit(sourceUnitsById, table.boundingBox());
            SourceLocatorKind locatorKind = sourceLocatorKind(sourceUnit);
            jdbc.update(
                    """
                    INSERT INTO document_tables (
                        id, document_id, revision_id, content_block_id,
                        preview_asset_id, table_order, source_unit_id,
                        locator_kind, locator_address, normalization_version,
                        bbox_x0, bbox_y0, bbox_x1, bbox_y1,
                        caption, html, source_text_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    table.id(),
                    task.documentId(),
                    task.revisionId(),
                    blockId,
                    table.previewAssetId(),
                    table.order(),
                    sourceUnit.id(),
                    locatorKind.name(),
                    unitLocatorAddressJson(sourceUnit, locatorKind),
                    sourceUnit.normalizationVersion(),
                    table.boundingBox().x0(),
                    table.boundingBox().y0(),
                    table.boundingBox().x1(),
                    table.boundingBox().y1(),
                    table.caption(),
                    table.html(),
                    table.sourceTextHash()
            );
            for (ParsedStructure.Cell cell : table.cells()) {
                jdbc.update(
                        """
                        INSERT INTO document_table_cells (
                            id, table_id, row_index, column_index,
                            row_span, column_span, header, text,
                            source_text_hash, cell_reference, cell_type,
                            raw_value, display_value, formula_text,
                            number_format
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        cell.id(),
                        table.id(),
                        cell.rowIndex(),
                        cell.columnIndex(),
                        cell.rowSpan(),
                        cell.columnSpan(),
                        cell.header(),
                        cell.text(),
                        cell.sourceTextHash(),
                        cell.cellReference(),
                        cell.cellType(),
                        cell.rawValue(),
                        cell.displayValue(),
                        cell.formulaText(),
                        cell.numberFormat()
                );
            }
        }
    }

    private void verifyPackage(String revisionHash, ParsedDocument parsed)
            throws ParserProcessingException {
        ParsedStructure.PackageMetadata metadata = parsed.packageMetadata();
        if (metadata == null
                || !hash(metadata.inputHash())
                || !hash(metadata.outputHash())
                || !revisionHash.equals(metadata.inputHash())
                || metadata.schemaVersion() == null
                || metadata.schemaVersion().isBlank()) {
            throw invalidPackage("Parser package identity or input Hash is invalid");
        }
        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(metadata.manifestJson());
        } catch (JsonProcessingException exception) {
            throw new ParserProcessingException(
                    "PARSER_RESULT_INVALID",
                    "Parser result manifest is not valid JSON",
                    exception
            );
        }
        if (!metadata.parserName().equals(manifest.path("parser").asText())
                || !metadata.parserVersion().equals(manifest.path("parserVersion").asText())
                || !metadata.schemaVersion().equals(manifest.path("schema").asText())
                || !metadata.inputHash().equals(manifest.path("inputHash").asText())
                || !metadata.outputHash().equals(manifest.path("outputHash").asText())
                || !parsed.revisionId().toString().equals(manifest.path("revisionId").asText())
                || !ParsedPackageIntegrity.PACKAGE_SCHEMA.equals(
                manifest.path("packageSchema").asText())
                || !ParsedPackageIntegrity.SOURCE_LOCATOR_SCHEMA.equals(
                manifest.path("sourceLocatorSchema").asText())
                || !parsed.documentFormat().name().equals(
                manifest.path("documentFormat").asText())
                || !parsed.parserProvider().name().equals(
                manifest.path("parserProvider").asText())
                || parsed.sourceUnits().size() != manifest.path("sourceUnitCount").asInt(-1)
                || !ParsedPackageIntegrity.OFFSET_ENCODING.equals(
                manifest.path("offsetEncoding").asText())
                || !Objects.equals(
                metadata.parserRevision(),
                nullableText(manifest.get("parserRevision")))
                || !metadata.outputHash().equals(
                ParsedPackageIntegrity.canonicalHash(parsed))) {
            throw invalidPackage("Parser result manifest does not match its package");
        }

        Map<UUID, ParsedDocument.SourceUnit> sourceUnits = verifySourceUnits(
                manifest.path("sourceUnits"),
                parsed.sourceUnits()
        );
        Map<Integer, ParsedDocument.ContentBlock> blocksByOrder = new LinkedHashMap<>();
        for (int index = 0; index < parsed.contentBlocks().size(); index++) {
            ParsedDocument.ContentBlock block = parsed.contentBlocks().get(index);
            if (block.order() != index
                    || blocksByOrder.put(block.order(), block) != null
                    || block.characterCount() != block.text().length()
                    || !block.sourceSpan().sourceTextHash().equals(sha256(block.text()))
                    || block.sourceSpan().chunkStartOffset() != 0
                    || block.sourceSpan().chunkEndOffset() != block.text().length()) {
                throw invalidPackage("ContentBlock identity or source Hash is invalid");
            }
            verifySpan(block.sourceSpan(), block.text(), sourceUnits);
        }

        Set<UUID> chunkIds = new HashSet<>();
        for (ParsedDocument.Chunk chunk : parsed.chunks()) {
            if (!chunkIds.add(chunk.id())
                    || chunk.characterCount() != chunk.text().length()) {
                throw invalidPackage("Chunk identity is duplicated");
            }
            int previousEnd = 0;
            for (ParsedDocument.SourceSpan span : chunk.sourceSpans()) {
                verifySpan(span, chunk.text(), sourceUnits);
                if (span.chunkStartOffset() < previousEnd) {
                    throw invalidPackage("SourceSpan order overlaps within its Chunk");
                }
                previousEnd = span.chunkEndOffset();
            }
        }
        if (parsed.characterCount() != parsed.contentBlocks().stream()
                .mapToInt(block -> block.text().length()).sum()) {
            throw invalidPackage("Parsed document character count is inconsistent");
        }

        Set<UUID> imageIds = new HashSet<>();
        Set<Integer> imageOrders = new HashSet<>();
        Map<UUID, ParsedStructure.Image> imagesById = new HashMap<>();
        for (ParsedStructure.Image image : parsed.images()) {
            ParsedDocument.ContentBlock source = image.sourceBlockOrder() == null
                    ? null : blocksByOrder.get(image.sourceBlockOrder());
            if (!imageIds.add(image.id())
                    || !imageOrders.add(image.order())
                    || image.order() < 0
                    || image.sourceBlockOrder() != null && source == null
                    || source != null
                    && !sourceContains(source, image.boundingBox().sourceUnitId())
                    || !image.contentHash().equals(sha256(image.bytes()))) {
                throw invalidPackage("Image asset identity, source, or Hash is invalid");
            }
            verifyBoxes(List.of(image.boundingBox()), sourceUnits);
            imagesById.put(image.id(), image);
        }
        verifyManifestFiles(manifest.path("files"), parsed.images());

        Set<UUID> tableIds = new HashSet<>();
        Set<Integer> tableOrders = new HashSet<>();
        for (ParsedStructure.Table table : parsed.tables()) {
            ParsedDocument.ContentBlock block = blocksByOrder.get(table.sourceBlockOrder());
            ParsedStructure.Image preview = table.previewAssetId() == null
                    ? null : imagesById.get(table.previewAssetId());
            if (!tableIds.add(table.id())
                    || !tableOrders.add(table.order())
                    || block == null
                    || block.type() != ParsedDocument.BlockType.TABLE
                    || !sourceContains(block, table.boundingBox().sourceUnitId())
                    || !table.sourceTextHash().equals(sha256(block.text()))
                    || table.previewAssetId() != null
                    && (preview == null
                    || preview.type() != ParsedStructure.AssetType.TABLE_PREVIEW
                    || !Objects.equals(preview.sourceBlockOrder(), table.sourceBlockOrder())
                    || !preview.boundingBox().sourceUnitId()
                    .equals(table.boundingBox().sourceUnitId()))) {
                throw invalidPackage("Table identity, source, or preview is invalid");
            }
            verifyBoxes(List.of(table.boundingBox()), sourceUnits);
            Set<String> positions = new HashSet<>();
            for (ParsedStructure.Cell cell : table.cells()) {
                String position = cell.rowIndex() + ":" + cell.columnIndex();
                if (!positions.add(position)
                        || cell.rowIndex() < 0
                        || cell.columnIndex() < 0
                        || cell.rowSpan() < 1
                        || cell.columnSpan() < 1
                        || !cell.sourceTextHash().equals(sha256(cell.text()))
                        || cell.cellReference() != null
                        && !cell.cellReference().matches("[A-Z]{1,3}[1-9][0-9]*")
                        || cell.formulaText() != null
                        && !cell.formulaText().startsWith("=")) {
                    throw invalidPackage("Table cell relation or Hash is invalid");
                }
            }
        }
        verifyManifestLocators(manifest.path("locators"), parsed);
    }

    private static Map<UUID, ParsedDocument.SourceUnit> verifySourceUnits(
            JsonNode manifestUnits,
            List<ParsedDocument.SourceUnit> sourceUnits
    ) throws ParserProcessingException {
        if (!manifestUnits.isArray() || manifestUnits.size() != sourceUnits.size()) {
            throw invalidPackage("Parser manifest SourceUnit list is incomplete");
        }
        Map<UUID, ParsedDocument.SourceUnit> expected = new LinkedHashMap<>();
        for (int index = 0; index < sourceUnits.size(); index++) {
            ParsedDocument.SourceUnit unit = sourceUnits.get(index);
            if (unit.order() != index + 1
                    || expected.put(unit.id(), unit) != null
                    || !unit.sourceTextHash().equals(sha256(unit.canonicalText()))) {
                throw invalidPackage("SourceUnit identity, order, or Hash is invalid");
            }
        }
        Set<UUID> seen = new HashSet<>();
        for (JsonNode value : manifestUnits) {
            UUID id;
            try {
                id = UUID.fromString(value.path("id").asText(""));
            } catch (IllegalArgumentException exception) {
                throw invalidPackage("Parser manifest SourceUnit identity is invalid");
            }
            ParsedDocument.SourceUnit unit = expected.get(id);
            if (unit == null
                    || !seen.add(id)
                    || unit.order() != value.path("order").asInt(-1)
                    || !unit.kind().name().equals(value.path("kind").asText())
                    || !unit.stableAddress().equals(value.path("stableAddress").asText())
                    || unit.canonicalText().length()
                    != value.path("characterCount").asInt(-1)
                    || !unit.sourceTextHash().equals(value.path("sourceTextHash").asText())
                    || !unit.normalizationVersion().equals(
                    value.path("normalizationVersion").asText())
                    || !unit.labelMetadataJson().equals(
                    value.path("labelMetadataJson").asText())) {
                throw invalidPackage("Parser manifest SourceUnit metadata is invalid");
            }
        }
        return Map.copyOf(expected);
    }

    private static void verifySpan(
            ParsedDocument.SourceSpan span,
            String chunkText,
            Map<UUID, ParsedDocument.SourceUnit> sourceUnits
    ) throws ParserProcessingException {
        ParsedDocument.SourceUnit start = sourceUnits.get(span.startSourceUnitId());
        ParsedDocument.SourceUnit end = sourceUnits.get(span.endSourceUnitId());
        if (start == null
                || end == null
                || start.order() != span.startSourceUnitOrder()
                || end.order() != span.endSourceUnitOrder()
                || start.order() > end.order()
                || !locatorMatchesUnits(
                        span.locatorKind(),
                        start.kind(),
                        end.kind()
                )
                || span.startOffset() > start.canonicalText().length()
                || span.endOffset() > end.canonicalText().length()
                || span.chunkEndOffset() > chunkText.length()
                || !span.sourceTextHash().equals(sha256(chunkText.substring(
                span.chunkStartOffset(),
                span.chunkEndOffset()
        )))) {
            throw invalidPackage("SourceSpan does not map to its Chunk text");
        }
        if (start.id().equals(end.id())
                && !span.sourceTextHash().equals(sha256(
                start.canonicalText().substring(span.startOffset(), span.endOffset())
        ))) {
            throw invalidPackage("SourceSpan does not map to its SourceUnit text");
        }
        for (ParsedStructure.BoundingBox box : span.boundingBoxes()) {
            if (box.sourceUnitOrder() < start.order()
                    || box.sourceUnitOrder() > end.order()) {
                throw invalidPackage("SourceSpan bounding box is outside its SourceUnit range");
            }
        }
        verifyBoxes(span.boundingBoxes(), sourceUnits);
    }

    private static boolean locatorMatchesUnits(
            SourceLocatorKind locator,
            SourceUnitKind start,
            SourceUnitKind end
    ) {
        return switch (locator) {
            case PAGE -> start == SourceUnitKind.PAGE
                    && end == SourceUnitKind.PAGE;
            case LINE_RANGE -> start == SourceUnitKind.LINE
                    && end == SourceUnitKind.LINE;
            case HEADING_BLOCK -> start == SourceUnitKind.SECTION
                    && end == SourceUnitKind.SECTION;
            case DOM_PATH -> start == SourceUnitKind.DOM_BLOCK
                    && end == SourceUnitKind.DOM_BLOCK;
            case PARAGRAPH -> start == SourceUnitKind.PARAGRAPH
                    && end == SourceUnitKind.PARAGRAPH;
            case TABLE_CELL -> start == SourceUnitKind.TABLE_CELL
                    && end == SourceUnitKind.TABLE_CELL;
            case SLIDE_SHAPE -> isSlideUnit(start) && isSlideUnit(end);
            case CELL_RANGE -> start == SourceUnitKind.SHEET
                    && end == SourceUnitKind.SHEET;
        };
    }

    private static boolean isSlideUnit(SourceUnitKind value) {
        return value == SourceUnitKind.SLIDE
                || value == SourceUnitKind.SHAPE
                || value == SourceUnitKind.NOTES;
    }

    private static boolean sourceContains(
            ParsedDocument.ContentBlock block,
            UUID sourceUnitId
    ) {
        return sourceUnitId.equals(block.sourceSpan().startSourceUnitId())
                || sourceUnitId.equals(block.sourceSpan().endSourceUnitId());
    }

    private static void verifyManifestFiles(
            JsonNode files,
            List<ParsedStructure.Image> images
    ) throws ParserProcessingException {
        if (!files.isArray() || files.size() != images.size()) {
            throw invalidPackage("Parser manifest file list is incomplete");
        }
        Map<String, ParsedStructure.Image> expected = new HashMap<>();
        for (ParsedStructure.Image image : images) {
            if (expected.put(image.originalName(), image) != null) {
                throw invalidPackage("Parser image filename is duplicated");
            }
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode file : files) {
            String name = file.path("name").asText("");
            ParsedStructure.Image image = expected.get(name);
            if (image == null
                    || !seen.add(name)
                    || !image.mediaType().equals(file.path("mediaType").asText())
                    || image.bytes().length != file.path("byteSize").asLong(-1)
                    || !image.contentHash().equals(file.path("hash").asText())) {
                throw invalidPackage("Parser manifest file metadata is invalid");
            }
        }
    }

    private static void verifyManifestLocators(
            JsonNode manifestLocators,
            ParsedDocument parsed
    ) throws ParserProcessingException {
        Set<String> expected = new HashSet<>();
        parsed.contentBlocks().forEach(block -> expected.add(locatorKey(block.sourceSpan())));
        parsed.chunks().forEach(chunk ->
                chunk.sourceSpans().forEach(span -> expected.add(locatorKey(span))));
        if (!manifestLocators.isArray() || manifestLocators.size() != expected.size()) {
            throw invalidPackage("Parser manifest locator list is incomplete");
        }
        for (JsonNode value : manifestLocators) {
            String key = value.path("startSourceUnitId").asText() + "|"
                    + value.path("endSourceUnitId").asText() + "|"
                    + value.path("kind").asText() + "|"
                    + value.path("address").asText() + "|"
                    + value.path("startOffset").asInt(-1) + "|"
                    + value.path("endOffset").asInt(-1) + "|"
                    + value.path("sourceTextHash").asText() + "|"
                    + value.path("normalizationVersion").asText();
            if (!expected.remove(key)) {
                throw invalidPackage("Parser manifest locator metadata is invalid");
            }
        }
        if (!expected.isEmpty()) {
            throw invalidPackage("Parser manifest locator list is incomplete");
        }
    }

    private static String locatorKey(ParsedDocument.SourceSpan span) {
        return span.startSourceUnitId() + "|" + span.endSourceUnitId()
                + "|" + span.locatorKind() + "|" + span.address()
                + "|" + span.startOffset() + "|" + span.endOffset()
                + "|" + span.sourceTextHash() + "|" + span.normalizationVersion();
    }

    private static void verifyBoxes(
            List<ParsedStructure.BoundingBox> boxes,
            Map<UUID, ParsedDocument.SourceUnit> sourceUnits
    ) throws ParserProcessingException {
        for (ParsedStructure.BoundingBox box : boxes) {
            ParsedDocument.SourceUnit sourceUnit = sourceUnits.get(box.sourceUnitId());
            if (sourceUnit == null
                    || sourceUnit.order() != box.sourceUnitOrder()
                    || sourceUnit.kind() != box.sourceUnitKind()) {
                throw invalidPackage("Bounding box SourceUnit is not part of the parsed document");
            }
        }
    }

    private String boxesJson(List<ParsedStructure.BoundingBox> boxes) {
        if (boxes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(boxes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize bounding boxes", exception);
        }
    }

    private static SourceLocatorKind locatorKind(ParsedDocument.SourceSpan span) {
        return span.locatorKind();
    }

    private String locatorAddressJson(ParsedDocument.SourceSpan span) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", span.locatorKind().name());
        value.put("address", span.address());
        value.put("startSourceUnitId", span.startSourceUnitId());
        value.put("endSourceUnitId", span.endSourceUnitId());
        value.put("startSourceUnitOrder", span.startSourceUnitOrder());
        value.put("endSourceUnitOrder", span.endSourceUnitOrder());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize SourceLocator address", exception);
        }
    }

    private static ParsedDocument.SourceUnit requireSourceUnit(
            Map<UUID, ParsedDocument.SourceUnit> sourceUnits,
            ParsedStructure.BoundingBox boundingBox
    ) {
        ParsedDocument.SourceUnit sourceUnit = sourceUnits.get(boundingBox.sourceUnitId());
        if (sourceUnit == null
                || sourceUnit.order() != boundingBox.sourceUnitOrder()
                || sourceUnit.kind() != boundingBox.sourceUnitKind()) {
            throw new IllegalArgumentException(
                    "Structured asset references an unknown SourceUnit"
            );
        }
        return sourceUnit;
    }

    private String unitLocatorAddressJson(
            ParsedDocument.SourceUnit sourceUnit,
            SourceLocatorKind locatorKind
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", locatorKind.name());
        value.put("address", sourceUnitAddress(sourceUnit, locatorKind));
        value.put("sourceUnitId", sourceUnit.id());
        value.put("sourceUnitOrder", sourceUnit.order());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize asset SourceLocator address", exception);
        }
    }

    private String sourceUnitAddress(
            ParsedDocument.SourceUnit sourceUnit,
            SourceLocatorKind locatorKind
    ) {
        if (locatorKind != SourceLocatorKind.CELL_RANGE) {
            return sourceUnit.stableAddress();
        }
        try {
            JsonNode value = objectMapper.readTree(
                    sourceUnit.labelMetadataJson()
            ).get("cellRange");
            return value == null || value.isNull() || value.asText().isBlank()
                    ? sourceUnit.stableAddress()
                    : value.asText();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not read SourceUnit range metadata",
                    exception
            );
        }
    }

    private static SourceLocatorKind sourceLocatorKind(
            ParsedDocument.SourceUnit sourceUnit
    ) {
        return switch (sourceUnit.kind()) {
            case PAGE -> SourceLocatorKind.PAGE;
            case SECTION -> SourceLocatorKind.HEADING_BLOCK;
            case LINE -> SourceLocatorKind.LINE_RANGE;
            case DOM_BLOCK -> SourceLocatorKind.DOM_PATH;
            case PARAGRAPH -> SourceLocatorKind.PARAGRAPH;
            case TABLE_CELL -> SourceLocatorKind.TABLE_CELL;
            case SLIDE, SHAPE, NOTES -> SourceLocatorKind.SLIDE_SHAPE;
            case SHEET -> SourceLocatorKind.CELL_RANGE;
        };
    }

    private static String assetObjectKey(ClaimedJob task, ParsedStructure.Image image) {
        return "documents/" + task.documentId()
                + "/revisions/" + task.revisionId()
                + "/assets/" + image.contentHash() + "-" + image.originalName();
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static ParserProcessingException invalidPackage(String message) {
        return new ParserProcessingException("PARSER_RESULT_INVALID", message);
    }

    private ChunkingProfile parserProfile() {
        PipelineProperties.Chunking chunking = properties.chunkingProfile();
        return new ChunkingProfile(
                chunking.version(),
                chunking.parentMaxTokens(),
                chunking.childMaxTokens(),
                chunking.overlapTokens(),
                40,
                0.02,
                0.30,
                properties.parserVersion(),
                properties.chunkerVersion(),
                chunking.tokenCounterVersion()
        );
    }

    private static void verifyProfile(
            ChunkingProfileEntity profile,
            ParsedDocument parsed,
            PipelineProperties.Chunking configured
    ) {
        if (profile.getParentMaxTokens() != configured.parentMaxTokens()
                || profile.getChildMaxTokens() != configured.childMaxTokens()
                || profile.getChildOverlapTokens() != configured.overlapTokens()
                || !profile.getTokenCounterVersion().equals(parsed.tokenCounterVersion())) {
            throw new IllegalStateException("Parser settings do not match the registered chunking profile");
        }
    }

    private static String encodePath(List<String> headingPath) {
        return String.join("\n", headingPath);
    }

    private static UUID stableSpanId(UUID chunkId, int order) {
        return UUID.nameUUIDFromBytes((chunkId + ":span:" + order).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class LostPipelineLeaseException extends RuntimeException {
    }
}
