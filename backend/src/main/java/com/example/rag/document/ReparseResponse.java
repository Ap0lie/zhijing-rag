package com.example.rag.document;

import com.example.rag.pipeline.ParserEngine;
import com.example.rag.persistence.RevisionStatus;

import java.util.UUID;

public record ReparseResponse(
        UUID documentId,
        UUID sourceRevisionId,
        UUID revisionId,
        int revisionNumber,
        UUID pipelineJobId,
        ParserEngine targetParser,
        RevisionStatus status
) {
}
