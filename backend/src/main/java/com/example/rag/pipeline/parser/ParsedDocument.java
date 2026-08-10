package com.example.rag.pipeline.parser;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.SourceLocatorKind;
import com.example.rag.persistence.SourceUnitKind;
import com.example.rag.pipeline.ParserProviderKind;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ParsedDocument(
        UUID documentId,
        UUID revisionId,
        DocumentFormat documentFormat,
        ParserProviderKind parserProvider,
        List<SourceUnit> sourceUnits,
        String markdown,
        List<ContentBlock> contentBlocks,
        List<Chunk> chunks,
        ParsedStructure.PackageMetadata packageMetadata,
        List<ParsedStructure.Table> tables,
        List<ParsedStructure.Image> images,
        int characterCount,
        int tokenCount,
        String chunkingProfileVersion,
        String parserVersion,
        String chunkerVersion,
        String tokenCounterVersion,
        long durationMillis
) {

    public ParsedDocument {
        sourceUnits = List.copyOf(sourceUnits);
        contentBlocks = List.copyOf(contentBlocks);
        chunks = List.copyOf(chunks);
        tables = List.copyOf(tables);
        images = List.copyOf(images);
        if (sourceUnits.isEmpty()) {
            throw new IllegalArgumentException("parsed document must contain a SourceUnit");
        }
        Set<UUID> identities = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (SourceUnit unit : sourceUnits) {
            if (!identities.add(unit.id()) || !orders.add(unit.order())) {
                throw new IllegalArgumentException("SourceUnit identity and order must be unique");
            }
        }
    }

    /**
     * PDF-only compatibility projection. Page count is no longer an independent
     * parser fact; it is derived from PAGE SourceUnits.
     */
    public int pageCount() {
        if (documentFormat != DocumentFormat.PDF) {
            return 0;
        }
        return Math.toIntExact(sourceUnits.stream()
                .filter(unit -> unit.kind() == SourceUnitKind.PAGE)
                .count());
    }

    public enum BlockType {
        HEADING,
        PARAGRAPH,
        LIST,
        TABLE
    }

    public enum ChunkType {
        PARENT,
        CHILD
    }

    public record SourceUnit(
            UUID id,
            int order,
            SourceUnitKind kind,
            String stableAddress,
            String canonicalText,
            String sourceTextHash,
            String normalizationVersion,
            String labelMetadataJson
    ) {
        public SourceUnit {
            if (id == null || order < 1 || kind == null
                    || stableAddress == null || stableAddress.isBlank()
                    || canonicalText == null
                    || sourceTextHash == null || !sourceTextHash.matches("[0-9a-f]{64}")
                    || normalizationVersion == null || normalizationVersion.isBlank()
                    || labelMetadataJson == null || labelMetadataJson.isBlank()) {
                throw new IllegalArgumentException("SourceUnit is invalid");
            }
        }
    }

    /**
     * Source offsets are zero-based, end-exclusive UTF-16 code-unit indexes in
     * the referenced SourceUnit canonical text. Chunk offsets address Chunk.text.
     */
    public record SourceSpan(
            UUID startSourceUnitId,
            UUID endSourceUnitId,
            int startSourceUnitOrder,
            int endSourceUnitOrder,
            SourceLocatorKind locatorKind,
            String address,
            int startOffset,
            int endOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash,
            String normalizationVersion,
            List<ParsedStructure.BoundingBox> boundingBoxes
    ) {
        public SourceSpan {
            boundingBoxes = List.copyOf(boundingBoxes);
            if (startSourceUnitId == null || endSourceUnitId == null
                    || startSourceUnitOrder < 1
                    || endSourceUnitOrder < startSourceUnitOrder
                    || locatorKind == null
                    || address == null || address.isBlank()
                    || startOffset < 0 || endOffset < 0
                    || (startSourceUnitId.equals(endSourceUnitId) && endOffset <= startOffset)
                    || chunkStartOffset < 0 || chunkEndOffset <= chunkStartOffset
                    || sourceTextHash == null || !sourceTextHash.matches("[0-9a-f]{64}")
                    || normalizationVersion == null || normalizationVersion.isBlank()) {
                throw new IllegalArgumentException("source span is invalid");
            }
        }

        /**
         * Legacy constructor kept for old unit tests and migration readers only.
         * New parser output must use revision-scoped SourceUnit identities.
         */
        public SourceSpan(
                int startPage,
                int endPage,
                int startOffset,
                int endOffset,
                int chunkStartOffset,
                int chunkEndOffset,
                String sourceTextHash,
                List<ParsedStructure.BoundingBox> boundingBoxes
        ) {
            this(
                    legacyPageId(startPage),
                    legacyPageId(endPage),
                    startPage,
                    endPage,
                    SourceLocatorKind.PAGE,
                    pageAddress(startPage, endPage, startOffset, endOffset),
                    startOffset,
                    endOffset,
                    chunkStartOffset,
                    chunkEndOffset,
                    sourceTextHash,
                    "legacy-pdf-page-v2",
                    boundingBoxes
            );
        }

        public int startPage() {
            requirePageLocator();
            return startSourceUnitOrder;
        }

        public int endPage() {
            requirePageLocator();
            return endSourceUnitOrder;
        }

        private void requirePageLocator() {
            if (locatorKind != SourceLocatorKind.PAGE) {
                throw new IllegalStateException("Non-PDF locator has no page number");
            }
        }
    }

    public record ContentBlock(
            UUID id,
            int order,
            BlockType type,
            String text,
            List<String> headingPath,
            int characterCount,
            int tokenCount,
            String tokenCounterVersion,
            SourceSpan sourceSpan
    ) {
        public ContentBlock {
            headingPath = List.copyOf(headingPath);
        }
    }

    public record Chunk(
            UUID id,
            ChunkType type,
            UUID parentId,
            int order,
            int startBlockOrder,
            int endBlockOrder,
            String text,
            List<String> headingPath,
            int characterCount,
            int tokenCount,
            String tokenCounterVersion,
            List<SourceSpan> sourceSpans
    ) {
        public Chunk {
            headingPath = List.copyOf(headingPath);
            sourceSpans = List.copyOf(sourceSpans);
            if (startBlockOrder < 0 || endBlockOrder < startBlockOrder) {
                throw new IllegalArgumentException("chunk block range is invalid");
            }
            if (type == ChunkType.PARENT && parentId != null) {
                throw new IllegalArgumentException("parent chunk cannot reference another parent");
            }
            if (type == ChunkType.CHILD && parentId == null) {
                throw new IllegalArgumentException("child chunk must reference a parent");
            }
        }
    }

    private static UUID legacyPageId(int page) {
        return UUID.nameUUIDFromBytes(
                ("legacy-pdf-page:" + page).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String pageAddress(int startPage, int endPage, int start, int end) {
        String page = startPage == endPage
                ? "page:" + startPage
                : "page:" + startPage + "-" + endPage;
        return page + "#chars=" + start + "-" + end;
    }
}
