package com.example.rag.pipeline;

import com.example.rag.document.SourceLocatorResponse;

import java.util.List;
import java.util.UUID;

public record RevisionStructureResponse(
        UUID revisionId,
        PackageResponse resultPackage,
        List<TableResponse> tables,
        List<ImageResponse> images,
        List<SourceSpanResponse> sourceSpans,
        boolean truncated
) {
    public record PackageResponse(
            String parserVersion,
            String parserRevision,
            String inputHash,
            String outputHash,
            String schemaVersion,
            String offsetEncoding,
            Integer pageCount,
            int sourceUnitCount,
            String documentFormat,
            String parserProvider,
            String textEncoding,
            String sanitization,
            String parseDecision,
            String delimiter
    ) {
        public PackageResponse(
                String parserVersion,
                String parserRevision,
                String inputHash,
                String outputHash,
                String schemaVersion,
                String offsetEncoding,
                int pageCount
        ) {
            this(
                    parserVersion,
                    parserRevision,
                    inputHash,
                    outputHash,
                    schemaVersion,
                    offsetEncoding,
                    pageCount,
                    pageCount,
                    "PDF",
                    parserVersion != null
                            && parserVersion.startsWith("mineru-")
                            ? "MINERU"
                            : "PDFBOX",
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    public record BoundingBoxResponse(
            UUID sourceUnitId,
            int sourceUnitOrder,
            String sourceUnitKind,
            Integer pageNumber,
            int x0,
            int y0,
            int x1,
            int y1
    ) {
        public BoundingBoxResponse(
                int pageNumber,
                int x0,
                int y0,
                int x1,
                int y1
        ) {
            this(
                    null,
                    pageNumber,
                    "PAGE",
                    pageNumber,
                    x0,
                    y0,
                    x1,
                    y1
            );
        }
    }

    public record CellResponse(
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
        public CellResponse(
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

    public record TableResponse(
            UUID id,
            int order,
            UUID contentBlockId,
            UUID previewAssetId,
            Integer pageNumber,
            BoundingBoxResponse boundingBox,
            String caption,
            String sourceTextHash,
            List<CellResponse> cells,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public TableResponse(
                UUID id,
                int order,
                UUID contentBlockId,
                UUID previewAssetId,
                int pageNumber,
                BoundingBoxResponse boundingBox,
                String caption,
                String sourceTextHash,
                List<CellResponse> cells
        ) {
            this(
                    id,
                    order,
                    contentBlockId,
                    previewAssetId,
                    pageNumber,
                    boundingBox,
                    caption,
                    sourceTextHash,
                    cells,
                    "PDF",
                    SourceLocatorResponse.pdfCompatibility(
                            pageNumber, pageNumber
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            pageNumber, pageNumber
                    ).sourceLabel()
            );
        }
    }

    public record ImageResponse(
            UUID id,
            int order,
            String type,
            UUID contentBlockId,
            Integer pageNumber,
            BoundingBoxResponse boundingBox,
            String filename,
            String mediaType,
            long byteSize,
            String contentHash,
            String caption,
            String contentUrl,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public ImageResponse(
                UUID id,
                int order,
                String type,
                UUID contentBlockId,
                int pageNumber,
                BoundingBoxResponse boundingBox,
                String filename,
                String mediaType,
                long byteSize,
                String contentHash,
                String caption,
                String contentUrl
        ) {
            this(
                    id,
                    order,
                    type,
                    contentBlockId,
                    pageNumber,
                    boundingBox,
                    filename,
                    mediaType,
                    byteSize,
                    contentHash,
                    caption,
                    contentUrl,
                    "PDF",
                    SourceLocatorResponse.pdfCompatibility(
                            pageNumber, pageNumber
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            pageNumber, pageNumber
                    ).sourceLabel()
            );
        }
    }

    public record SourceSpanResponse(
            UUID id,
            UUID chunkId,
            String chunkType,
            int chunkOrder,
            int order,
            Integer startPage,
            Integer endPage,
            int pageStartOffset,
            int pageEndOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash,
            List<BoundingBoxResponse> boundingBoxes,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public SourceSpanResponse(
                UUID id,
                UUID chunkId,
                String chunkType,
                int chunkOrder,
                int order,
                int startPage,
                int endPage,
                int pageStartOffset,
                int pageEndOffset,
                int chunkStartOffset,
                int chunkEndOffset,
                String sourceTextHash,
                List<BoundingBoxResponse> boundingBoxes
        ) {
            this(
                    id,
                    chunkId,
                    chunkType,
                    chunkOrder,
                    order,
                    startPage,
                    endPage,
                    pageStartOffset,
                    pageEndOffset,
                    chunkStartOffset,
                    chunkEndOffset,
                    sourceTextHash,
                    boundingBoxes,
                    "PDF",
                    SourceLocatorResponse.pdf(
                            null,
                            null,
                            startPage,
                            endPage,
                            pageStartOffset,
                            pageEndOffset,
                            null,
                            sourceTextHash,
                            "pdf-page-compat-v1"
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
            );
        }
    }
}
