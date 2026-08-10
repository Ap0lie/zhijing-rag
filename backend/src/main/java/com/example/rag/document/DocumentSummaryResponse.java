package com.example.rag.document;

import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.RevisionStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentSummaryResponse(
        UUID id,
        String title,
        DocumentVisibility visibility,
        String ownerUsername,
        long aclVersion,
        UUID effectiveRevisionId,
        Integer latestRevisionNumber,
        RevisionStatus latestRevisionStatus,
        Instant createdAt,
        Instant updatedAt,
        DocumentFormat documentFormat,
        String mediaType,
        EvaluationProvenanceResponse effectiveEvaluationProvenance,
        EvaluationProvenanceResponse latestEvaluationProvenance
) {
    static DocumentSummaryResponse from(
            DocumentEntity document,
            DocumentRevisionEntity latest,
            DocumentRevisionEntity effective
    ) {
        DocumentRevisionEntity formatRevision = effective == null
                ? latest
                : effective;
        return new DocumentSummaryResponse(
                document.getId(),
                document.getTitle(),
                document.getVisibility(),
                document.getOwner().getUsername(),
                document.getAclVersion(),
                effective == null ? null : effective.getId(),
                latest == null ? null : latest.getRevisionNumber(),
                latest == null ? null : latest.getStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                formatRevision == null
                        ? null : formatRevision.getDocumentFormat(),
                formatRevision == null
                        ? null : formatRevision.getMediaType(),
                EvaluationProvenanceResponse.from(
                        effective == null
                                ? null
                                : effective.getEvaluationProvenance()
                ),
                EvaluationProvenanceResponse.from(
                        latest == null
                                ? null
                                : latest.getEvaluationProvenance()
                )
        );
    }
}
