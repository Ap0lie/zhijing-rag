package com.example.rag.graph;

import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.projection.GenerationRecoveryProgress;
import com.example.rag.projection.ProjectionClosureStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GlobalGraphContracts {

    private GlobalGraphContracts() {
    }

    public record CreateConfigRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String version,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Pattern(regexp = "CREATE") String confirmation
    ) {
    }

    public record StartBuildRequest(
            @NotBlank @Size(max = 64) String globalConfigVersion,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Pattern(regexp = "BUILD") String confirmation
    ) {
    }

    public record ReleaseRequest(
            long globalGeneration,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank String confirmation
    ) {
    }

    public record RuntimeStatus(
            boolean enabled,
            String model,
            String revision,
            String promptVersion,
            String schemaVersion
    ) {
    }

    public record ConfigView(
            String version,
            String reportModel,
            String reportRevision,
            String promptVersion,
            String schemaVersion,
            String communityAlgorithm,
            String communityAlgorithmVersion,
            long communitySeed,
            double communityResolution,
            String indexConfigVersion,
            int bm25TopK,
            int vectorTopK,
            int rrfRankConstant,
            int reportLimit,
            int contextTokenBudget,
            int mapCallLimit,
            int modelCallLimit,
            int hardTimeoutMs,
            int statementTimeoutMs,
            String reason,
            boolean runtimeCompatible,
            Instant createdAt
    ) {
    }

    public record GenerationView(
            UUID id,
            long globalGeneration,
            String globalConfigVersion,
            long sourceGraphGeneration,
            String indexName,
            String status,
            long expectedSourceCount,
            long reportCount,
            long claimCount,
            long evidenceCount,
            long indexedReportCount,
            long validVectorCount,
            long modelCallCount,
            boolean caughtUp,
            ProjectionClosureStatus closure,
            GenerationRecoveryProgress recovery,
            int buildAttempt,
            String failureCode,
            String failureReason,
            String buildReason,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant retentionUntil,
            Instant updatedAt
    ) {
    }

    public record OverviewResponse(
            Long activeGeneration,
            RuntimeStatus runtime,
            List<ConfigView> configs,
            List<GenerationView> generations
    ) {
    }

    public record ReportSummary(
            UUID id,
            long globalGeneration,
            int communityKey,
            String title,
            String summary,
            int tokenCount,
            int claimCount,
            int evidenceCount
    ) {
    }

    public record ReportPage(
            long globalGeneration,
            int page,
            int size,
            long total,
            List<ReportSummary> items
    ) {
    }

    public record ReportDetail(
            ReportSummary report,
            List<ReportClaimView> claims
    ) {
    }

    public record ReportClaimView(
            UUID id,
            int order,
            String claimText,
            List<ReportEvidenceView> evidence
    ) {
    }

    public record ReportEvidenceView(
            UUID id,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            UUID childChunkId,
            UUID sourceSpanId,
            String evidenceText,
            Integer startPage,
            Integer endPage,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public ReportEvidenceView(
                UUID id,
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                UUID childChunkId,
                UUID sourceSpanId,
                String evidenceText,
                int startPage,
                int endPage
        ) {
            this(
                    id,
                    documentId,
                    documentTitle,
                    revisionId,
                    revisionNumber,
                    childChunkId,
                    sourceSpanId,
                    evidenceText,
                    startPage,
                    endPage,
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

    record GlobalConfig(
            String version,
            String reportModel,
            String reportRevision,
            String promptVersion,
            String schemaVersion,
            String communityAlgorithm,
            String communityAlgorithmVersion,
            long communitySeed,
            double communityResolution,
            String indexConfigVersion,
            int bm25TopK,
            int vectorTopK,
            int rrfRankConstant,
            int reportLimit,
            int contextTokenBudget,
            int mapCallLimit,
            int modelCallLimit,
            int hardTimeoutMs,
            int statementTimeoutMs,
            String reason,
            Instant createdAt
    ) {
    }

    record ClaimedGeneration(
            long generation,
            UUID id,
            GlobalConfig config,
            long sourceGraphGeneration,
            String indexName,
            int attempt,
            String leaseOwner
    ) {
    }

    record ManifestRow(
            UUID id,
            long generation,
            String configVersion,
            long sourceGraphGeneration,
            String indexName,
            String status,
            long expectedSources,
            long reports,
            long claims,
            long evidence,
            long indexedReports,
            long validVectors,
            long modelCalls,
            int attempt,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            String failureCode,
            String failureReason,
            String buildReason,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant retentionUntil,
            Instant updatedAt
    ) {
    }

    record ArtifactSource(
            UUID artifactId,
            UUID documentId,
            UUID revisionId,
            long aclVersion,
            String documentTitle,
            String outputJson
    ) {
    }

    record EvidenceAnchor(
            UUID id,
            UUID relationshipId,
            UUID documentId,
            UUID revisionId,
            long aclVersion,
            String documentTitle,
            int revisionNumber,
            UUID childChunkId,
            UUID sourceSpanId,
            String evidenceText,
            String evidenceTextHash,
            Integer startPage,
            Integer endPage
    ) {
    }

    record ReportFact(
            UUID id,
            int communityKey,
            String title,
            String summary,
            String searchText,
            String contentHash,
            int tokenCount,
            List<ClaimFact> claims
    ) {
    }

    record ClaimFact(
            UUID id,
            int order,
            String text,
            List<EvidenceAnchor> evidence
    ) {
    }

    record BuildResult(
            List<ReportFact> reports,
            long modelCalls
    ) {
        int claimCount() {
            return reports.stream().mapToInt(
                    report -> report.claims().size()
            ).sum();
        }

        int evidenceCount() {
            return reports.stream()
                    .flatMap(report -> report.claims().stream())
                    .mapToInt(claim -> claim.evidence().size())
                    .sum();
        }
    }
}
