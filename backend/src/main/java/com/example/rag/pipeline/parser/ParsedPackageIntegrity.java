package com.example.rag.pipeline.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seals and verifies the format-neutral parser output after normalization and
 * chunk construction. Source and chunk offsets are Java UTF-16 code units.
 */
public final class ParsedPackageIntegrity {

    public static final String PACKAGE_SCHEMA = "parsed-package-v3";
    public static final String SOURCE_LOCATOR_SCHEMA = "source-locator-v1";
    public static final String OFFSET_ENCODING = "UTF16_CODE_UNIT";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ParsedPackageIntegrity() {
    }

    public static ParsedDocument seal(ParsedDocument parsed) {
        String outputHash = canonicalHash(parsed);
        ParsedStructure.PackageMetadata metadata = parsed.packageMetadata();
        ObjectNode manifest = manifest(metadata.manifestJson());
        String previousOutputHash = manifest.path("outputHash").asText("");
        if (!previousOutputHash.isBlank() && !previousOutputHash.equals(outputHash)) {
            manifest.put("rawOutputHash", previousOutputHash);
        }
        manifest.put("packageSchema", PACKAGE_SCHEMA);
        manifest.put("sourceLocatorSchema", SOURCE_LOCATOR_SCHEMA);
        manifest.put("revisionId", parsed.revisionId().toString());
        manifest.put("documentFormat", parsed.documentFormat().name());
        manifest.put("parserProvider", parsed.parserProvider().name());
        manifest.put("sourceUnitCount", parsed.sourceUnits().size());
        manifest.put("offsetEncoding", OFFSET_ENCODING);
        manifest.put("outputHash", outputHash);
        writeSourceUnits(manifest, parsed.sourceUnits());
        writeLocators(manifest, locators(parsed));
        writeFiles(manifest, parsed.images());

        ParsedStructure.PackageMetadata sealedMetadata =
                new ParsedStructure.PackageMetadata(
                        metadata.parserName(),
                        metadata.parserVersion(),
                        metadata.parserRevision(),
                        metadata.inputHash(),
                        outputHash,
                        metadata.schemaVersion(),
                        json(manifest)
                );
        return new ParsedDocument(
                parsed.documentId(),
                parsed.revisionId(),
                parsed.documentFormat(),
                parsed.parserProvider(),
                parsed.sourceUnits(),
                parsed.markdown(),
                parsed.contentBlocks(),
                parsed.chunks(),
                sealedMetadata,
                parsed.tables(),
                parsed.images(),
                parsed.characterCount(),
                parsed.tokenCount(),
                parsed.chunkingProfileVersion(),
                parsed.parserVersion(),
                parsed.chunkerVersion(),
                parsed.tokenCounterVersion(),
                parsed.durationMillis()
        );
    }

    public static String canonicalHash(ParsedDocument parsed) {
        MessageDigest digest = digest();
        put(digest, PACKAGE_SCHEMA);
        put(digest, SOURCE_LOCATOR_SCHEMA);
        put(digest, OFFSET_ENCODING);
        put(digest, parsed.documentId().toString());
        put(digest, parsed.revisionId().toString());
        put(digest, parsed.documentFormat().name());
        put(digest, parsed.parserProvider().name());
        put(digest, parsed.markdown());
        put(digest, parsed.characterCount());
        put(digest, parsed.tokenCount());
        put(digest, parsed.chunkingProfileVersion());
        put(digest, parsed.parserVersion());
        put(digest, parsed.chunkerVersion());
        put(digest, parsed.tokenCounterVersion());

        ParsedStructure.PackageMetadata metadata = parsed.packageMetadata();
        put(digest, metadata.parserName());
        put(digest, metadata.parserVersion());
        put(digest, metadata.parserRevision());
        put(digest, metadata.inputHash());
        put(digest, metadata.schemaVersion());

        List<ParsedDocument.SourceUnit> sourceUnits = parsed.sourceUnits().stream()
                .sorted(Comparator.comparingInt(ParsedDocument.SourceUnit::order))
                .toList();
        put(digest, sourceUnits.size());
        sourceUnits.forEach(unit -> put(digest, unit));

        put(digest, parsed.contentBlocks().size());
        for (ParsedDocument.ContentBlock block : parsed.contentBlocks()) {
            put(digest, block.id().toString());
            put(digest, block.order());
            put(digest, block.type().name());
            put(digest, block.text());
            put(digest, block.headingPath());
            put(digest, block.characterCount());
            put(digest, block.tokenCount());
            put(digest, block.tokenCounterVersion());
            put(digest, block.sourceSpan());
        }

        put(digest, parsed.chunks().size());
        for (ParsedDocument.Chunk chunk : parsed.chunks()) {
            put(digest, chunk.id().toString());
            put(digest, chunk.type().name());
            put(digest, chunk.parentId() == null ? null : chunk.parentId().toString());
            put(digest, chunk.order());
            put(digest, chunk.startBlockOrder());
            put(digest, chunk.endBlockOrder());
            put(digest, chunk.text());
            put(digest, chunk.headingPath());
            put(digest, chunk.characterCount());
            put(digest, chunk.tokenCount());
            put(digest, chunk.tokenCounterVersion());
            put(digest, chunk.sourceSpans().size());
            chunk.sourceSpans().forEach(span -> put(digest, span));
        }

        put(digest, parsed.images().size());
        for (ParsedStructure.Image image : parsed.images()) {
            put(digest, image.id().toString());
            put(digest, image.order());
            put(digest, image.sourceBlockOrder());
            put(digest, image.type().name());
            put(digest, image.boundingBox());
            put(digest, image.originalName());
            put(digest, image.mediaType());
            put(digest, image.bytes());
            put(digest, image.contentHash());
            put(digest, image.caption());
        }

        put(digest, parsed.tables().size());
        for (ParsedStructure.Table table : parsed.tables()) {
            put(digest, table.id().toString());
            put(digest, table.order());
            put(digest, table.sourceBlockOrder());
            put(digest, table.previewAssetId() == null
                    ? null : table.previewAssetId().toString());
            put(digest, table.boundingBox());
            put(digest, table.caption());
            put(digest, table.html());
            put(digest, table.sourceTextHash());
            put(digest, table.cells().size());
            for (ParsedStructure.Cell cell : table.cells()) {
                put(digest, cell.id().toString());
                put(digest, cell.rowIndex());
                put(digest, cell.columnIndex());
                put(digest, cell.rowSpan());
                put(digest, cell.columnSpan());
                put(digest, cell.header());
                put(digest, cell.text());
                put(digest, cell.sourceTextHash());
                put(digest, cell.cellReference());
                put(digest, cell.cellType());
                put(digest, cell.rawValue());
                put(digest, cell.displayValue());
                put(digest, cell.formulaText());
                put(digest, cell.numberFormat());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<ParsedDocument.SourceSpan> locators(ParsedDocument parsed) {
        Map<String, ParsedDocument.SourceSpan> unique = new LinkedHashMap<>();
        parsed.contentBlocks().stream()
                .map(ParsedDocument.ContentBlock::sourceSpan)
                .forEach(span -> unique.putIfAbsent(locatorKey(span), span));
        parsed.chunks().stream()
                .flatMap(chunk -> chunk.sourceSpans().stream())
                .forEach(span -> unique.putIfAbsent(locatorKey(span), span));
        return unique.values().stream()
                .sorted(Comparator
                        .comparingInt(ParsedDocument.SourceSpan::startSourceUnitOrder)
                        .thenComparingInt(ParsedDocument.SourceSpan::startOffset)
                        .thenComparingInt(ParsedDocument.SourceSpan::endSourceUnitOrder)
                        .thenComparingInt(ParsedDocument.SourceSpan::endOffset)
                        .thenComparing(ParsedDocument.SourceSpan::address))
                .toList();
    }

    private static String locatorKey(ParsedDocument.SourceSpan span) {
        return span.startSourceUnitId() + "|" + span.endSourceUnitId()
                + "|" + span.locatorKind() + "|" + span.address()
                + "|" + span.startOffset() + "|" + span.endOffset()
                + "|" + span.sourceTextHash() + "|" + span.normalizationVersion();
    }

    private static void writeSourceUnits(
            ObjectNode manifest,
            List<ParsedDocument.SourceUnit> values
    ) {
        ArrayNode units = manifest.putArray("sourceUnits");
        values.stream()
                .sorted(Comparator.comparingInt(ParsedDocument.SourceUnit::order))
                .forEach(unit -> {
                    ObjectNode value = units.addObject();
                    value.put("id", unit.id().toString());
                    value.put("order", unit.order());
                    value.put("kind", unit.kind().name());
                    value.put("stableAddress", unit.stableAddress());
                    value.put("characterCount", unit.canonicalText().length());
                    value.put("sourceTextHash", unit.sourceTextHash());
                    value.put("normalizationVersion", unit.normalizationVersion());
                    value.put("labelMetadataJson", unit.labelMetadataJson());
                });
    }

    private static void writeLocators(
            ObjectNode manifest,
            List<ParsedDocument.SourceSpan> values
    ) {
        ArrayNode locators = manifest.putArray("locators");
        values.forEach(span -> {
            ObjectNode value = locators.addObject();
            value.put("kind", span.locatorKind().name());
            value.put("startSourceUnitId", span.startSourceUnitId().toString());
            value.put("endSourceUnitId", span.endSourceUnitId().toString());
            value.put("startOffset", span.startOffset());
            value.put("endOffset", span.endOffset());
            value.put("address", span.address());
            value.put("sourceTextHash", span.sourceTextHash());
            value.put("normalizationVersion", span.normalizationVersion());
        });
    }

    private static void writeFiles(
            ObjectNode manifest,
            List<ParsedStructure.Image> images
    ) {
        ArrayNode files = manifest.putArray("files");
        images.stream()
                .sorted(Comparator.comparingInt(ParsedStructure.Image::order))
                .forEach(image -> {
                    ObjectNode file = files.addObject();
                    file.put("name", image.originalName());
                    file.put("mediaType", image.mediaType());
                    file.put("byteSize", image.bytes().length);
                    file.put("hash", image.contentHash());
                });
    }

    private static void put(MessageDigest digest, ParsedDocument.SourceUnit unit) {
        put(digest, unit.id().toString());
        put(digest, unit.order());
        put(digest, unit.kind().name());
        put(digest, unit.stableAddress());
        put(digest, unit.canonicalText());
        put(digest, unit.sourceTextHash());
        put(digest, unit.normalizationVersion());
        put(digest, unit.labelMetadataJson());
    }

    private static void put(MessageDigest digest, ParsedDocument.SourceSpan span) {
        put(digest, span.startSourceUnitId().toString());
        put(digest, span.endSourceUnitId().toString());
        put(digest, span.startSourceUnitOrder());
        put(digest, span.endSourceUnitOrder());
        put(digest, span.locatorKind().name());
        put(digest, span.address());
        put(digest, span.startOffset());
        put(digest, span.endOffset());
        put(digest, span.chunkStartOffset());
        put(digest, span.chunkEndOffset());
        put(digest, span.sourceTextHash());
        put(digest, span.normalizationVersion());
        put(digest, span.boundingBoxes().size());
        span.boundingBoxes().forEach(box -> put(digest, box));
    }

    private static void put(MessageDigest digest, ParsedStructure.BoundingBox box) {
        put(digest, box.sourceUnitId().toString());
        put(digest, box.sourceUnitOrder());
        put(digest, box.sourceUnitKind().name());
        put(digest, box.x0());
        put(digest, box.y0());
        put(digest, box.x1());
        put(digest, box.y1());
    }

    private static void put(MessageDigest digest, List<String> values) {
        put(digest, values.size());
        values.forEach(value -> put(digest, value));
    }

    private static void put(MessageDigest digest, Integer value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void put(MessageDigest digest, boolean value) {
        digest.update(value ? (byte) 1 : (byte) 0);
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            put(digest, (Integer) null);
            return;
        }
        put(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(MessageDigest digest, byte[] value) {
        put(digest, value.length);
        digest.update(value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ObjectNode manifest(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Parser manifest must be a JSON object");
            }
            return object.deepCopy();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Parser manifest is invalid JSON", exception);
        }
    }

    private static String json(ObjectNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Parser manifest cannot be serialized", exception);
        }
    }
}
