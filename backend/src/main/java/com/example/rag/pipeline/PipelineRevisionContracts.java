package com.example.rag.pipeline;

import com.example.rag.governance.GovernanceContracts.OperationImpact;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.PipelineJobStatus;
import com.example.rag.persistence.PipelineStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PipelineRevisionContracts {

    private PipelineRevisionContracts() {
    }

    public record RevisionPage(
            List<RevisionSummary> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            AttentionCounts counts
    ) {
    }

    public record AttentionCounts(
            long attention,
            long failed,
            long quarantined,
            long running,
            long completed
    ) {
    }

    public record RevisionSummary(
            UUID documentId,
            UUID revisionId,
            int revisionNumber,
            String documentTitle,
            DocumentFormat documentFormat,
            String revisionStatus,
            boolean currentRevision,
            String aggregateStatus,
            PipelineStage currentStage,
            Instant updatedAt,
            String nextActionCode,
            String nextActionLabel,
            boolean automaticRetryExhausted,
            String isolationCode,
            String isolationReason,
            String parserProvider,
            List<StageFact> stages,
            List<JobAttempt> jobs,
            DownstreamProjection downstream
    ) {
    }

    public record StageFact(
            PipelineStage stage,
            String status,
            String source,
            Instant updatedAt
    ) {
    }

    public record JobAttempt(
            UUID id,
            PipelineStage stage,
            PipelineJobStatus status,
            int attempt,
            int maxAttempts,
            String parserProvider,
            String parserDecisionCode,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant heartbeatAt,
            String errorCode,
            String errorMessage,
            String quarantineReason,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            Instant createdAt,
            Instant updatedAt,
            boolean automaticRetryExhausted,
            String manualActionCode
    ) {
    }

    public record DownstreamProjection(
            ProjectionState index,
            ProjectionState graph,
            ProjectionState global
    ) {
    }

    public record ProjectionState(
            String kind,
            Long generation,
            String status,
            String reasonCode
    ) {
    }

    public record RecoveryResponse(
            PipelineJobResponse job,
            RevisionSummary revision,
            OperationImpact impact,
            boolean replayed
    ) {
    }
}
