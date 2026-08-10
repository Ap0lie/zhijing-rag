package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.PipelineJobEntity;
import com.example.rag.persistence.PipelineJobStatus;
import com.example.rag.persistence.PipelineStage;

import java.time.Instant;
import java.util.UUID;

public record PipelineJobResponse(
        UUID id,
        UUID documentId,
        UUID revisionId,
        int revisionNumber,
        String documentTitle,
        DocumentFormat documentFormat,
        PipelineStage stage,
        PipelineJobStatus status,
        int attempt,
        int maxAttempts,
        String leaseOwner,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorCode,
        String errorMessage,
        String quarantineReason,
        ParserEngine parserRequestedEngine,
        ParserEngine parserSelectedEngine,
        String parserProvider,
        String parserDecisionCode,
        String parserEngineVersion,
        Integer parserPageCount,
        Integer parserSourceUnitCount,
        Boolean parserScannedCandidate,
        Boolean parserOcrRequired,
        Boolean parserMulticolumnCandidate,
        Boolean parserTableCandidate,
        Boolean parserImageCandidate,
        String parserModelRevision,
        String parserModelManifestChecksum,
        Instant parserDecidedAt,
        String parserOverrideReason,
        Instant createdAt,
        Instant updatedAt,
        boolean retryable,
        boolean cancelable
) {
    static PipelineJobResponse from(PipelineJobEntity job, String currentPipelineVersion) {
        return from(job, currentPipelineVersion, false);
    }

    static PipelineJobResponse from(
            PipelineJobEntity job,
            String currentPipelineVersion,
            boolean parseRetryBlocked
    ) {
        var revision = job.getRevision();
        var document = revision.getDocument();
        return new PipelineJobResponse(
                job.getId(),
                document.getId(),
                revision.getId(),
                revision.getRevisionNumber(),
                document.getTitle(),
                revision.getDocumentFormat(),
                job.getStage(),
                job.getStatus(),
                job.getAttempt(),
                job.getMaxAttempts(),
                job.getLeaseOwner(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getDurationMs(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getQuarantineReason(),
                job.getParserRequestedEngine(),
                job.getParserSelectedEngine(),
                job.getParserProvider() == null
                        ? null
                        : job.getParserProvider().name(),
                job.getParserDecisionCode(),
                job.getParserEngineVersion(),
                job.getParserPageCount(),
                job.getParserSourceUnitCount(),
                job.getParserScannedCandidate(),
                job.getParserOcrRequired(),
                job.getParserMulticolumnCandidate(),
                job.getParserTableCandidate(),
                job.getParserImageCandidate(),
                job.getParserModelRevision(),
                job.getParserModelManifestChecksum(),
                job.getParserDecidedAt(),
                job.getParserOverrideReason(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.isRetryable()
                        && (job.getStage() == PipelineStage.PARSE || job.getStage() == PipelineStage.INDEX)
                        && !(job.getStage() == PipelineStage.PARSE && parseRetryBlocked)
                        && job.getPipelineVersion().equals(currentPipelineVersion)
                        && document.getDeletedAt() == null,
                job.isCancelable()
                        && (job.getStage() == PipelineStage.PARSE
                        || job.getStage() == PipelineStage.INDEX)
                        && job.getPipelineVersion().equals(currentPipelineVersion)
                        && document.getDeletedAt() == null
        );
    }
}
