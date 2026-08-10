package com.example.rag.pipeline;

import com.example.rag.document.SourceLocatorResponse;

import java.util.List;
import java.util.UUID;

public record RevisionArtifactsResponse(
        UUID revisionId,
        String parserVersion,
        String chunkerVersion,
        String tokenCounterVersion,
        String markdown,
        List<ContentBlockResponse> contentBlocks,
        List<ChunkResponse> chunks
) {
    public record ContentBlockResponse(
            UUID id,
            String type,
            int order,
            String text,
            List<String> headingPath,
            Integer startPage,
            Integer endPage,
            int startOffset,
            int endOffset,
            int charCount,
            int tokenCount,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public ContentBlockResponse(
                UUID id,
                String type,
                int order,
                String text,
                List<String> headingPath,
                int startPage,
                int endPage,
                int startOffset,
                int endOffset,
                int charCount,
                int tokenCount
        ) {
            this(
                    id,
                    type,
                    order,
                    text,
                    headingPath,
                    startPage,
                    endPage,
                    startOffset,
                    endOffset,
                    charCount,
                    tokenCount,
                    "PDF",
                    SourceLocatorResponse.pdf(
                            null,
                            null,
                            startPage,
                            endPage,
                            startOffset,
                            endOffset,
                            null,
                            null,
                            "pdf-page-compat-v1"
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
            );
        }
    }

    public record ChunkResponse(
            UUID id,
            String type,
            UUID parentChunkId,
            int order,
            String text,
            List<String> headingPath,
            Integer startPage,
            Integer endPage,
            int charCount,
            int tokenCount,
            boolean searchable,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public ChunkResponse(
                UUID id,
                String type,
                UUID parentChunkId,
                int order,
                String text,
                List<String> headingPath,
                int startPage,
                int endPage,
                int charCount,
                int tokenCount,
                boolean searchable
        ) {
            this(
                    id,
                    type,
                    parentChunkId,
                    order,
                    text,
                    headingPath,
                    startPage,
                    endPage,
                    charCount,
                    tokenCount,
                    searchable,
                    "PDF",
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
            );
        }
    }
}
