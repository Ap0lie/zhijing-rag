package com.example.rag.document;

import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.pipeline.ParserEngine;

import java.time.Instant;
import java.util.UUID;

public record DocumentRevisionResponse(
        UUID id,
        int revisionNumber,
        RevisionStatus status,
        String originalFilename,
        long fileSizeBytes,
        String contentHash,
        DocumentFormat documentFormat,
        String mediaType,
        Instant createdAt,
        boolean current,
        boolean effective,
        EvaluationProvenanceResponse evaluationProvenance,
        UUID sourceRevisionId,
        String reparseReason,
        ParserEngine reparseRequestedParser,
        DocumentFormat formatChangeFrom,
        String formatChangeReason
) {
    static DocumentRevisionResponse from(
            DocumentRevisionEntity revision,
            UUID currentRevisionId,
            UUID effectiveRevisionId
    ) {
        return new DocumentRevisionResponse(
                revision.getId(),
                revision.getRevisionNumber(),
                revision.getStatus(),
                revision.getOriginalFilename(),
                revision.getFileSizeBytes(),
                revision.getContentHash(),
                revision.getDocumentFormat(),
                revision.getMediaType(),
                revision.getCreatedAt(),
                revision.getId().equals(currentRevisionId),
                revision.getId().equals(effectiveRevisionId),
                EvaluationProvenanceResponse.from(
                        revision.getEvaluationProvenance()
                ),
                revision.getSourceRevision() == null
                        ? null : revision.getSourceRevision().getId(),
                revision.getReparseReason(),
                revision.getReparseRequestedParser(),
                revision.getFormatChangeFrom(),
                revision.getFormatChangeReason()
        );
    }
}
