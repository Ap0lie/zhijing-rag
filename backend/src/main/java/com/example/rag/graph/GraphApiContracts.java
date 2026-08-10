package com.example.rag.graph;

import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.projection.GenerationRecoveryProgress;
import com.example.rag.projection.ProjectionClosureStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GraphApiContracts {

    private GraphApiContracts() {
    }

    public record CreateGraphConfigRequest(
            @NotBlank @Size(max = 64) String version,
            @NotBlank @Size(max = 255) String extractionModel,
            @NotBlank @Size(max = 255) String extractionRevision,
            @NotBlank @Pattern(regexp = "CREATE") String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record StartGraphBuildRequest(
            @NotBlank @Size(max = 64) String graphConfigVersion,
            @NotBlank @Pattern(regexp = "BUILD") String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record ReleaseGraphGenerationRequest(
            @Min(1) long graphGeneration,
            @NotBlank @Pattern(regexp = "PUBLISH|ROLLBACK")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record CreateResolutionRuleRequest(
            @NotNull UUID previewToken,
            @NotBlank @Size(max = 64) String newConfigVersion,
            @NotBlank @Pattern(regexp = "APPLY_NEXT_BUILD")
            String confirmation,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record ResolutionRulePreviewRequest(
            @Min(1) long graphGeneration,
            @NotBlank @Size(max = 64) String baseConfigVersion,
            @NotBlank @Pattern(regexp = "MERGE|SPLIT") String action,
            @NotNull @Size(min = 1, max = 20) List<UUID> sourceEntityIds,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> matchAliases,
            @NotBlank @Size(max = 300) String targetCanonicalName,
            @NotBlank @Size(max = 64) String targetEntityType
    ) {
    }

    public record ResolutionRulePreviewResponse(
            UUID previewToken,
            Instant expiresAt,
            long graphGeneration,
            String graphStatus,
            String baseConfigVersion,
            String sourceSetHash,
            String action,
            List<ResolutionEntityView> entities,
            ResolutionImpact impact,
            List<ResolutionNotice> blockers,
            List<ResolutionNotice> warnings
    ) {
    }

    public record ResolutionEntityView(
            UUID id,
            String canonicalName,
            String entityType,
            List<String> aliases,
            int mentionCount,
            int relationshipCount,
            int relationshipEvidenceCount
    ) {
    }

    public record ResolutionImpact(
            int mentionCount,
            int sourceSpanCount,
            int relationshipCount,
            int relationshipEvidenceCount,
            int communityCount,
            int documentCount,
            String queryImpactState,
            String queryImpactReason
    ) {
    }

    public record ResolutionNotice(
            String code,
            String message
    ) {
    }

    public record RefreshResolutionCandidatesRequest(
            @Min(1) long graphGeneration,
            @NotBlank @Pattern(regexp = "REFRESH_RESOLUTION_CANDIDATES")
            String confirmation,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record UpdateResolutionCandidateRequest(
            @Min(1) int expectedVersion,
            @NotBlank @Pattern(regexp = "IGNORE_RESOLUTION_CANDIDATE|RESTORE_RESOLUTION_CANDIDATE")
            String confirmation,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record GraphResolutionCandidateSnapshotView(
            UUID id,
            long graphGeneration,
            String graphConfigVersion,
            String sourceSetHash,
            String algorithmVersion,
            String inputHash,
            String status,
            int duplicateCandidateCount,
            int splitCandidateCount,
            Instant createdAt,
            Instant staleAt,
            String staleReason
    ) {
    }

    public record GraphResolutionCandidatePage(
            GraphResolutionCandidateSnapshotView snapshot,
            String nextCursor,
            List<GraphResolutionCandidateSummary> items
    ) {
    }

    public record GraphResolutionCandidateSummary(
            UUID id,
            String candidateType,
            String suggestedAction,
            String status,
            int version,
            List<ResolutionEntityView> entities,
            String suggestedTargetName,
            String suggestedTargetType,
            List<String> suggestedAliases,
            List<GraphResolutionCandidateSignalView> signals,
            int evidenceCount,
            int sourceDocumentCount,
            int stableRank,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record GraphResolutionCandidateSignalView(
            String code,
            String strength,
            String explanation,
            Double numericValue
    ) {
    }

    public record GraphResolutionCandidateEvidenceView(
            String anchorType,
            UUID anchorId,
            UUID entityId,
            String entityName,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            UUID childChunkId,
            UUID sourceSpanId,
            String excerpt,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
    }

    public record GraphResolutionCandidateNeighborView(
            UUID entityId,
            String entityName,
            UUID neighborId,
            String neighborName,
            String neighborType,
            boolean shared,
            int evidenceCount
    ) {
    }

    public record GraphResolutionCandidateEventView(
            long id,
            String eventType,
            String previousStatus,
            String nextStatus,
            int version,
            String reason,
            Instant createdAt
    ) {
    }

    public record GraphResolutionCandidateDetail(
            GraphResolutionCandidateSummary candidate,
            List<GraphResolutionCandidateEvidenceView> evidence,
            List<GraphResolutionCandidateNeighborView> neighbors,
            List<GraphResolutionCandidateEventView> events
    ) {
    }

    public record CreateResolutionProposalRequest(
            UUID candidateId,
            @Min(1) long graphGeneration,
            @NotBlank @Size(max = 64) String baseConfigVersion,
            @NotBlank @Pattern(regexp = "MERGE|SPLIT") String action,
            @NotNull @Size(min = 1, max = 20) List<UUID> sourceEntityIds,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> matchAliases,
            @NotBlank @Size(max = 300) String targetCanonicalName,
            @NotBlank @Size(max = 64) String targetEntityType,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record ReviseResolutionProposalRequest(
            @Min(1) int expectedRevision,
            @Min(1) int expectedVersion,
            @NotBlank @Pattern(regexp = "MERGE|SPLIT") String action,
            @NotNull @Size(min = 1, max = 20) List<UUID> sourceEntityIds,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> matchAliases,
            @NotBlank @Size(max = 300) String targetCanonicalName,
            @NotBlank @Size(max = 64) String targetEntityType,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record WithdrawResolutionProposalRequest(
            @Min(1) int expectedRevision,
            @Min(1) int expectedVersion,
            @NotBlank @Pattern(regexp = "WITHDRAW_RESOLUTION_PROPOSAL")
            String confirmation,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record MaterializeResolutionProposalRequest(
            @Min(1) int expectedRevision,
            @Min(1) int expectedVersion,
            @NotNull UUID previewToken,
            @NotBlank @Size(max = 64) String newConfigVersion,
            @NotBlank @Pattern(regexp = "MATERIALIZE_RESOLUTION_PROPOSAL")
            String confirmation,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey
    ) {
    }

    public record GraphResolutionProposalPage(
            int page,
            int size,
            long total,
            List<GraphResolutionProposalSummary> items
    ) {
    }

    public record GraphResolutionProposalSummary(
            UUID id,
            UUID candidateId,
            String status,
            int version,
            int currentRevision,
            long baseGraphGeneration,
            String baseGraphConfigVersion,
            String materializedConfigVersion,
            Long appliedGraphGeneration,
            String action,
            List<ResolutionEntityView> entities,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetEntityType,
            ResolutionImpact impact,
            List<ResolutionNotice> blockers,
            List<ResolutionNotice> warnings,
            List<GraphResolutionProposalConflictView> conflicts,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            String nextStep
    ) {
    }

    public record GraphResolutionProposalConflictView(
            UUID conflictingProposalId,
            String code,
            String message
    ) {
    }

    public record GraphResolutionProposalRevisionView(
            UUID id,
            int revision,
            Integer supersedesRevision,
            String action,
            List<ResolutionEntityView> entities,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetEntityType,
            ResolutionImpact impact,
            List<ResolutionNotice> blockers,
            List<ResolutionNotice> warnings,
            String reason,
            String createdBy,
            Instant createdAt
    ) {
    }

    public record GraphResolutionProposalEventView(
            long id,
            String eventType,
            int revision,
            String previousStatus,
            String nextStatus,
            int proposalVersion,
            String reason,
            Instant createdAt
    ) {
    }

    public record GraphResolutionProposalDetail(
            GraphResolutionProposalSummary proposal,
            List<GraphResolutionProposalRevisionView> revisions,
            List<GraphResolutionProposalEventView> events
    ) {
    }

    public record GraphOverviewResponse(
            Long activeGeneration,
            GraphExtractionStatus extraction,
            List<GraphConfigView> configs,
            List<GraphGenerationView> generations
    ) {
    }

    public record GraphExtractionStatus(
            boolean enabled,
            String model,
            String revision,
            String promptVersion,
            String schemaVersion
    ) {
    }

    public record GraphConfigView(
            String version,
            String extractionModel,
            String extractionRevision,
            String promptVersion,
            String schemaVersion,
            String normalizationVersion,
            String resolutionRuleSetVersion,
            String communityAlgorithm,
            String communityAlgorithmVersion,
            long communitySeed,
            double communityResolution,
            String reason,
            boolean runtimeCompatible,
            Instant createdAt
    ) {
    }

    public record GraphGenerationView(
            UUID id,
            long graphGeneration,
            String graphConfigVersion,
            String status,
            long expectedDocumentCount,
            long projectedDocumentCount,
            long entityCount,
            long mentionCount,
            long relationshipCount,
            long relationshipEvidenceCount,
            long communityCount,
            long communityClaimCount,
            long cacheHitCount,
            long modelCallCount,
            double cacheHitRate,
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

    public record GraphEntityPage(
            long graphGeneration,
            int page,
            int size,
            long total,
            String nextCursor,
            List<GraphEntitySummary> items
    ) {
    }

    public record GraphEntitySummary(
            UUID id,
            String canonicalName,
            String entityType,
            String description,
            int mentionCount,
            int relationshipCount,
            Integer communityKey,
            List<String> aliases,
            String matchSource,
            String matchedAlias
    ) {
    }

    public record GraphEntityDetail(
            GraphEntitySummary entity,
            List<String> aliases,
            List<GraphMentionView> mentions,
            List<GraphRelationshipView> relationships
    ) {
    }

    public record GraphMentionView(
            UUID id,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            UUID childChunkId,
            UUID sourceSpanId,
            String surfaceText,
            Integer startPage,
            Integer endPage,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public GraphMentionView(
                UUID id,
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                UUID childChunkId,
                UUID sourceSpanId,
                String surfaceText,
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
                    surfaceText,
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

    public record GraphRelationshipView(
            UUID id,
            UUID sourceEntityId,
            String sourceName,
            UUID targetEntityId,
            String targetName,
            String relationshipType,
            String description,
            List<GraphRelationshipEvidenceView> evidence
    ) {
    }

    public enum GraphRootType {
        ENTITY,
        COMMUNITY
    }

    public record GraphSubgraphView(
            long generation,
            GraphRootType rootType,
            UUID rootId,
            String rootLabel,
            int hops,
            boolean truncated,
            List<GraphSubgraphNodeView> nodes,
            List<GraphSubgraphEdgeView> edges
    ) {
    }

    public record GraphSubgraphNodeView(
            UUID id,
            String name,
            String entityType,
            Integer communityKey,
            int depth,
            int mentionCount,
            int relationshipCount,
            boolean root
    ) {
    }

    public record GraphSubgraphEdgeView(
            UUID id,
            UUID sourceEntityId,
            UUID targetEntityId,
            String relationshipType,
            String description,
            int evidenceCount
    ) {
    }

    public record GraphRelationshipEvidenceView(
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
        public GraphRelationshipEvidenceView(
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

    public record GraphCommunityPage(
            long graphGeneration,
            int page,
            int size,
            long total,
            List<GraphCommunitySummary> items
    ) {
    }

    public record GraphCommunitySummary(
            UUID id,
            int communityKey,
            String title,
            String summary,
            int entityCount,
            int claimCount
    ) {
    }

    public record GraphCommunityDetail(
            GraphCommunitySummary community,
            List<GraphEntitySummary> entities,
            List<GraphCommunityClaimView> claims
    ) {
    }

    public record GraphCommunityClaimView(
            UUID id,
            String claimText,
            UUID relationshipId,
            GraphRelationshipEvidenceView evidence
    ) {
    }

    public record PageRequest(
            @Min(0) int page,
            @Min(1) @Max(100) int size
    ) {
    }
}
