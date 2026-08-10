package com.example.rag.graph;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class GraphBuildContracts {

    private GraphBuildContracts() {
    }

    record GraphConfig(
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
            Instant createdAt
    ) {
    }

    record ClaimedGeneration(
            UUID id,
            long generation,
            GraphConfig config,
            int attempt
    ) {
    }

    record SourceDocument(
            UUID documentId,
            String title,
            UUID revisionId,
            int revisionNumber,
            long aclVersion,
            List<ParentSource> parents
    ) {
    }

    record ParentSource(
            UUID id,
            int order,
            String text,
            String headingPath,
            String contentHash,
            List<ChildSource> children
    ) {
    }

    record ChildSource(
            UUID id,
            int order,
            String text,
            String headingPath,
            String contentHash,
            List<SpanSource> spans
    ) {
    }

    record SpanSource(
            UUID id,
            int order,
            Integer startPage,
            Integer endPage,
            int startOffset,
            int endOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash
    ) {
    }

    record ResolutionRule(
            int order,
            String action,
            List<String> sourceEntityKeys,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetNormalizedName,
            String targetEntityType
    ) {
    }

    record ExtractionArtifact(
            UUID id,
            String outputJson,
            String outputHash,
            int entityCount,
            int relationshipCount
    ) {
    }

    record GraphBuild(
            long generation,
            List<EntityFact> entities,
            List<AliasFact> aliases,
            List<AliasEvidenceFact> aliasEvidence,
            List<MentionFact> mentions,
            List<RelationshipFact> relationships,
            List<RelationshipEvidenceFact> relationshipEvidence,
            List<AdjacencyFact> adjacency,
            List<CommunityFact> communities,
            List<CommunityMemberFact> communityMembers,
            List<CommunityClaimFact> communityClaims,
            List<ProjectionFact> projections,
            long cacheHits,
            long modelCalls
    ) {
    }

    record EntityFact(
            UUID id,
            String canonicalName,
            String normalizedName,
            String entityType,
            String description
    ) {
    }

    record AliasFact(
            UUID entityId,
            String alias,
            String normalizedAlias
    ) {
    }

    record AliasEvidenceFact(
            UUID entityId,
            String normalizedAlias,
            UUID mentionId
    ) {
    }

    record MentionFact(
            UUID id,
            UUID entityId,
            UUID documentId,
            UUID revisionId,
            UUID parentChunkId,
            UUID childChunkId,
            UUID sourceSpanId,
            String surfaceText,
            int startOffset,
            int endOffset
    ) {
    }

    record RelationshipFact(
            UUID id,
            UUID sourceEntityId,
            UUID targetEntityId,
            String relationshipType,
            String description
    ) {
    }

    record RelationshipEvidenceFact(
            UUID id,
            UUID relationshipId,
            UUID documentId,
            UUID revisionId,
            UUID parentChunkId,
            UUID childChunkId,
            UUID sourceSpanId,
            String evidenceText,
            String evidenceTextHash,
            int startOffset,
            int endOffset
    ) {
    }

    record AdjacencyFact(
            UUID sourceEntityId,
            UUID targetEntityId,
            UUID relationshipId,
            String direction,
            double weight
    ) {
    }

    record CommunityFact(
            UUID id,
            int key,
            String title,
            String summary,
            int entityCount
    ) {
    }

    record CommunityMemberFact(
            UUID communityId,
            UUID entityId,
            int order
    ) {
    }

    record CommunityClaimFact(
            UUID id,
            UUID communityId,
            UUID relationshipId,
            UUID relationshipEvidenceId,
            String claimText
    ) {
    }

    record ProjectionFact(
            UUID documentId,
            UUID revisionId,
            long aclVersion,
            String inputHash,
            List<UUID> artifactIds
    ) {
    }
}
