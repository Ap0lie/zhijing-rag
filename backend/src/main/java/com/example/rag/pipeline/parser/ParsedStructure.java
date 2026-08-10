package com.example.rag.pipeline.parser;

import com.example.rag.persistence.SourceUnitKind;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public record ParsedStructure(
        PackageMetadata packageMetadata,
        List<Block> blocks,
        List<Table> tables,
        List<Image> images
) {
    public ParsedStructure {
        blocks = List.copyOf(blocks);
        tables = List.copyOf(tables);
        images = List.copyOf(images);
    }

    public record PackageMetadata(
            String parserName,
            String parserVersion,
            String parserRevision,
            String inputHash,
            String outputHash,
            String schemaVersion,
            String manifestJson
    ) {
    }

    public record BoundingBox(
            UUID sourceUnitId,
            int sourceUnitOrder,
            SourceUnitKind sourceUnitKind,
            int x0,
            int y0,
            int x1,
            int y1
    ) {
        public BoundingBox {
            if (sourceUnitId == null || sourceUnitOrder < 1 || sourceUnitKind == null
                    || x0 < 0 || y0 < 0 || x1 > 1000 || y1 > 1000
                    || x1 <= x0 || y1 <= y0) {
                throw new IllegalArgumentException("Bounding box is outside the normalized source unit");
            }
        }

        /**
         * Compatibility constructor for raw PDF parser provider results. The final
         * parsed package rebinds this placeholder to a revision-scoped SourceUnit.
         */
        public BoundingBox(int pageNumber, int x0, int y0, int x1, int y1) {
            this(legacyPageId(pageNumber), pageNumber, SourceUnitKind.PAGE, x0, y0, x1, y1);
        }

        public int pageNumber() {
            if (sourceUnitKind != SourceUnitKind.PAGE) {
                throw new IllegalStateException("Non-PDF bounding box has no page number");
            }
            return sourceUnitOrder;
        }

        public BoundingBox bind(ParsedDocument.SourceUnit sourceUnit) {
            return new BoundingBox(
                    sourceUnit.id(),
                    sourceUnit.order(),
                    sourceUnit.kind(),
                    x0,
                    y0,
                    x1,
                    y1
            );
        }
    }

    public record Block(
            int order,
            ParsedDocument.BlockType type,
            String text,
            int headingLevel,
            BoundingBox boundingBox
    ) {
        public Block {
            if (order < 0 || text == null || text.isBlank()
                    || headingLevel < 0 || headingLevel > 6 || boundingBox == null) {
                throw new IllegalArgumentException("Structured block is invalid");
            }
        }
    }

    public record Table(
            UUID id,
            int order,
            int sourceBlockOrder,
            UUID previewAssetId,
            BoundingBox boundingBox,
            String caption,
            String html,
            String sourceTextHash,
            List<Cell> cells
    ) {
        public Table {
            cells = List.copyOf(cells);
        }

        public Table bind(ParsedDocument.SourceUnit sourceUnit) {
            return new Table(
                    id,
                    order,
                    sourceBlockOrder,
                    previewAssetId,
                    boundingBox.bind(sourceUnit),
                    caption,
                    html,
                    sourceTextHash,
                    cells
            );
        }
    }

    public record Cell(
            UUID id,
            int rowIndex,
            int columnIndex,
            int rowSpan,
            int columnSpan,
            boolean header,
            String text,
            String sourceTextHash,
            String cellReference,
            String cellType,
            String rawValue,
            String displayValue,
            String formulaText,
            String numberFormat
    ) {
        public Cell(
                UUID id,
                int rowIndex,
                int columnIndex,
                int rowSpan,
                int columnSpan,
                boolean header,
                String text,
                String sourceTextHash
        ) {
            this(
                    id,
                    rowIndex,
                    columnIndex,
                    rowSpan,
                    columnSpan,
                    header,
                    text,
                    sourceTextHash,
                    null,
                    "TEXT",
                    text,
                    text,
                    null,
                    null
            );
        }
    }

    public record Image(
            UUID id,
            int order,
            Integer sourceBlockOrder,
            AssetType type,
            BoundingBox boundingBox,
            String originalName,
            String mediaType,
            byte[] bytes,
            String contentHash,
            String caption
    ) {
        public Image {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public Image bind(ParsedDocument.SourceUnit sourceUnit) {
            return new Image(
                    id,
                    order,
                    sourceBlockOrder,
                    type,
                    boundingBox.bind(sourceUnit),
                    originalName,
                    mediaType,
                    bytes,
                    contentHash,
                    caption
            );
        }
    }

    public enum AssetType {
        FIGURE,
        TABLE_PREVIEW
    }

    private static UUID legacyPageId(int page) {
        return UUID.nameUUIDFromBytes(
                ("legacy-pdf-page:" + page).getBytes(StandardCharsets.UTF_8)
        );
    }
}
