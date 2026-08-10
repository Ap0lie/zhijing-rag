package com.example.rag.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EvaluationContracts {

    private EvaluationContracts() {
    }

    public record DatasetView(
            UUID id,
            String key,
            String title,
            String description,
            List<DatasetVersionView> versions
    ) {
    }

    public record DatasetVersionView(
            UUID id,
            String version,
            String schemaVersion,
            String caseType,
            String sourceRevision,
            String sourceLicense,
            String sourceSha256,
            int caseCount,
            int mappedCases,
            int unmappedCases,
            int notRequiredCases,
            int readyCases,
            int blockedPrerequisiteCases,
            Instant createdAt
    ) {
    }

    public record CaseMappingView(
            UUID caseId,
            String caseKey,
            String language,
            String storedStatus,
            String effectiveStatus,
            List<String> missingEvidenceKeys,
            String documentFormat,
            String originalFilename,
            String fileSha256,
            String sourceLicense,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            String locatorKind,
            String sourceLabel,
            String locatorHash,
            String blockedReason
    ) {
    }

    public record MappingPage(
            UUID datasetVersionId,
            int page,
            int size,
            long total,
            List<CaseMappingView> items
    ) {
    }

    public record CreateSubjectRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID targetId
    ) {
    }

    public enum SubjectType {
        RETRIEVAL,
        LOCAL_GRAPH,
        GLOBAL_GRAPH,
        ANSWER_CITATION,
        MULTI_TURN,
        INTENT,
        PARSER,
        MULTIFORMAT_RELEASE
    }

    public record SubjectView(
            UUID id,
            String name,
            SubjectType subjectType,
            UUID targetId,
            String targetKey,
            String targetKind,
            UUID datasetVersionId,
            String datasetVersion,
            Map<String, Object> snapshot,
            String snapshotHash,
            String readinessStatus,
            String blockedReason,
            Instant createdAt
    ) {
    }

    public record FreezeMultiformatReleaseRequest(
            @NotBlank
            @Pattern(regexp = "FREEZE_MULTIFORMAT_RELEASE")
            String confirmation,
            @NotBlank @Size(min = 8, max = 500) String reason
    ) {
    }

    public record MultiformatFormatView(
            String documentFormat,
            String mappingStatus,
            String blockedReason,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            String documentTitle,
            String documentVisibility,
            long aclVersion,
            String originalFilename,
            String fileSha256,
            String sourceTitle,
            String sourceLicense,
            String sourceRevision,
            String expectedParserProvider,
            String expectedParserVersion,
            String expectedChunkerVersion,
            String locatorKind,
            String sourceLabel,
            String locatorHash,
            List<String> securityAssertions
    ) {
    }

    public record MultiformatReleaseView(
            String state,
            String version,
            UUID datasetVersionId,
            UUID subjectId,
            String subjectReadinessStatus,
            String subjectBlockedReason,
            String subjectSnapshotHash,
            int readyFormats,
            int totalFormats,
            List<MultiformatFormatView> formats
    ) {
    }

    public record TargetView(
            UUID id,
            String targetKey,
            SubjectType subjectType,
            String targetKind,
            Map<String, Object> snapshot,
            String snapshotHash,
            String readinessStatus,
            String blockedReason,
            Instant createdAt
    ) {
    }

    public record CreateRunRequest(
            @NotNull UUID evaluationSubjectId,
            @NotBlank @Size(max = 64) String datasetVersion,
            @NotBlank
            @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
            String idempotencyKey
    ) {
    }

    public record RunView(
            UUID id,
            UUID evaluationSubjectId,
            String subjectName,
            String subjectType,
            UUID datasetVersionId,
            String datasetKey,
            String datasetVersion,
            UUID originalRunId,
            String status,
            String evaluatorVersion,
            int totalCases,
            int completedCases,
            int succeededCases,
            int failedCases,
            int blockedCases,
            boolean cancelRequested,
            int attempt,
            String leaseOwner,
            Instant leaseExpiresAt,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt
    ) {
    }

    public record RunPage(
            int page,
            int size,
            long total,
            List<RunView> items
    ) {
    }

    public record ResultView(
            UUID id,
            UUID caseId,
            String caseKey,
            String language,
            String caseType,
            String status,
            Map<String, Object> output,
            String errorCode,
            String errorMessage,
            long durationMs,
            Instant createdAt,
            List<MetricView> metrics
    ) {
    }

    public record MetricView(
            String key,
            String status,
            Double value,
            Map<String, Object> details
    ) {
    }

    public record ResultPage(
            UUID runId,
            int page,
            int size,
            long total,
            List<ResultView> items
    ) {
    }

    public record PerformanceStatsView(
            int samples,
            Double p50Ms,
            Double p95Ms,
            Double maxMs,
            double errorRate
    ) {
    }

    public record FormatReleaseResultView(
            String documentFormat,
            UUID caseId,
            String caseKey,
            String status,
            UUID documentId,
            UUID revisionId,
            String locatorKind,
            String sourceLabel,
            boolean hardGatePassed,
            boolean citationResolved,
            boolean degraded,
            String degradationCode,
            String errorCode,
            long durationMs
    ) {
    }

    public record ReleaseReportView(
            UUID runId,
            String runStatus,
            String evaluatorVersion,
            String datasetVersion,
            UUID subjectId,
            String subjectSnapshotHash,
            Map<String, Object> frozenSubject,
            int totalCases,
            int succeededCases,
            int failedCases,
            int blockedCases,
            Double locatorResolutionRate,
            Double citationResolutionRate,
            int hardGateFailures,
            int degradationCount,
            ExecutionBaselineView executionBaseline,
            Map<String, PerformanceStatsView> performance,
            List<FormatReleaseResultView> formats,
            List<String> blockers,
            List<String> unmeasuredItems,
            String recommendation
    ) {
    }

    public record ExecutionBaselineView(
            String queryProfileVersion,
            int plannerCallCount,
            int retrievalCallCount,
            int rerankCallCount,
            int queryDegradedCount,
            String memoryContractVersion,
            int memoryInjectedCount,
            int memoryUsedCount,
            int memoryTokenCount
    ) {
    }

    public record RunEventView(
            long id,
            int sequence,
            String eventType,
            String fromStatus,
            String toStatus,
            Map<String, Object> details,
            Instant createdAt
    ) {
    }

    public record RuntimeAnswerProfileView(
            boolean enabled,
            String modelProvider,
            String modelId,
            String modelRevision,
            String endpointIdentity,
            String promptVersion,
            String orchestrationVersion,
            int timeoutMs,
            int maxOutputTokens,
            boolean remoteEvidenceAllowed,
            boolean remoteMemoryAllowed
    ) {
    }

    public record CancelRunRequest(
            @NotBlank
            @Pattern(regexp = "CANCEL_EVALUATION_RUN")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record RetryRunRequest(
            @NotBlank
            @Pattern(regexp = "RETRY_EVALUATION_RUN")
            String confirmation,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
            String idempotencyKey
    ) {
    }

    public record CreateAnswerProfileRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String version,
            @NotBlank @Size(max = 64) String modelProvider,
            @NotBlank @Size(max = 160) String modelId,
            @NotBlank @Size(max = 160) String modelRevision,
            @NotBlank @Size(max = 255) String endpointIdentity,
            @NotBlank @Size(max = 64) String promptVersion,
            @NotBlank @Size(max = 64) String orchestrationVersion,
            @Min(1000) @Max(120000) int timeoutMs,
            @Min(64) @Max(4096) int maxOutputTokens,
            boolean remoteEvidenceAllowed,
            boolean remoteMemoryAllowed,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AnswerProfileView(
            String version,
            String modelProvider,
            String modelId,
            String modelRevision,
            String endpointIdentity,
            String promptVersion,
            String orchestrationVersion,
            int timeoutMs,
            int maxOutputTokens,
            boolean remoteEvidenceAllowed,
            boolean remoteMemoryAllowed,
            String reason,
            boolean published,
            Instant createdAt
    ) {
    }

    public record AnswerProfilePublicationRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String profileVersion,
            @NotBlank @Pattern(regexp = "PUBLISH_ANSWER_PROFILE")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AnswerProfileRollbackRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String profileVersion,
            @NotBlank @Pattern(regexp = "ROLLBACK_ANSWER_PROFILE")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AnswerProfilePublicationView(
            long eventId,
            String profileVersion,
            String previousProfileVersion,
            String action,
            String reason,
            Instant createdAt
    ) {
    }

    public record CompareView(
            RunView left,
            RunView right,
            boolean sameDatasetVersion,
            String comparisonReason,
            List<MetricDeltaView> metrics,
            List<SliceDeltaView> slices,
            List<CaseDeltaView> changedCases
    ) {
    }

    public record MetricDeltaView(
            String metricKey,
            Double leftValue,
            Double rightValue,
            Double delta,
            long leftMeasured,
            long rightMeasured
    ) {
    }

    public record SliceDeltaView(
            String dimension,
            String value,
            long leftSucceeded,
            long leftFailed,
            long leftBlocked,
            long rightSucceeded,
            long rightFailed,
            long rightBlocked
    ) {
    }

    public record CaseDeltaView(
            String caseKey,
            String language,
            String caseType,
            String leftStatus,
            String rightStatus
    ) {
    }

    public record BaselinePublicationRequest(
            @NotBlank @Pattern(regexp = "PUBLISH|ROLLBACK") String action,
            UUID runId,
            UUID baselineId,
            @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "PUBLISH_BASELINE")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record BaselineView(
            UUID id,
            String name,
            String baselineKey,
            UUID datasetVersionId,
            UUID evaluationSubjectId,
            UUID runId,
            String gateStatus,
            Map<String, Object> gateSummary,
            Map<String, Object> metricSummary,
            Map<String, Object> judgeAdvisory,
            boolean published,
            Instant createdAt
    ) {
    }

    public record BaselinePublicationEventView(
            long id,
            String baselineKey,
            UUID baselineId,
            UUID previousBaselineId,
            String action,
            String reason,
            Instant createdAt
    ) {
    }

    public record CreateFeedbackRequest(
            @Min(1) @Max(5) int rating,
            @Size(max = 2000) String comment,
            boolean consentToShare
    ) {
    }

    public record FeedbackView(
            UUID id,
            UUID chatRunId,
            int rating,
            String comment,
            boolean consentToShare,
            Map<String, Object> redactedSample,
            String reviewStatus,
            String reviewReason,
            UUID createdDatasetVersionId,
            Instant createdAt
    ) {
    }

    public record ReviewFeedbackRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record WorkloadPermitView(
            boolean onlineChatActive,
            long activeChatRuns,
            boolean evaluationMayClaim,
            String pauseReason
    ) {
    }

    public record ObservabilityView(
            boolean enabled,
            Instant capturedAt,
            int windowHours,
            boolean captureContent,
            boolean highCardinalityLabels,
            int retentionDays,
            WorkloadPermitView workloadPermit,
            Map<String, Long> queues,
            Map<String, Double> rates,
            Map<String, Double> latencyP50Ms,
            Map<String, Double> latencyP95Ms,
            Map<String, Long> embeddingCache,
            Map<String, Long> graph
    ) {
    }

    public record GateView(
            UUID baselineId,
            String baselineName,
            String baselineKey,
            UUID runId,
            String runStatus,
            String gateStatus,
            boolean published,
            List<String> blockers,
            Map<String, Object> metricSummary,
            Instant createdAt
    ) {
    }

    public enum DrillType {
        MODEL_TIMEOUT,
        OPENSEARCH_UNAVAILABLE,
        GRAPH_STALE,
        CANARY_LEAK_SCAN
    }

    public enum DrillExecutionMode {
        SIMULATION_ONLY,
        REAL_VERIFY
    }

    public record CreateDrillRequest(
            @NotNull DrillType drillType,
            @NotNull DrillExecutionMode executionMode,
            @NotBlank
            @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
            String idempotencyKey,
            @NotBlank @Pattern(regexp = "RUN_EVALUATION_DRILL")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record DrillActionRequest(
            @NotBlank
            @Pattern(
                    regexp =
                            "CANCEL_EVALUATION_DRILL|RETRY_EVALUATION_DRILL"
            )
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record DrillView(
            UUID id,
            UUID originalDrillId,
            DrillType drillType,
            DrillExecutionMode executionMode,
            String status,
            int attempt,
            int maxAttempts,
            boolean cancelRequested,
            String leaseOwner,
            Instant leaseExpiresAt,
            Map<String, Object> resultSummary,
            String errorCode,
            String errorMessage,
            String reason,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt
    ) {
    }

    public record DrillEventView(
            long id,
            int sequence,
            String eventType,
            String fromStatus,
            String toStatus,
            Map<String, Object> details,
            Instant createdAt
    ) {
    }

    record DatasetSeed(
            UUID datasetId,
            String datasetKey,
            String title,
            String description,
            UUID versionId,
            String version,
            String schemaVersion,
            String caseType,
            String sourceRevision,
            String sourceLicense,
            String sourceSha256,
            String sourceManifest
    ) {
    }

    record CaseSeed(
            UUID id,
            UUID datasetVersionId,
            String key,
            String language,
            String caseType,
            String inputData,
            String expectedData,
            String mappingStatus,
            String mappingRequirements,
            String metadata
    ) {
    }

    record ClaimedRun(
            UUID id,
            UUID subjectId,
            UUID datasetVersionId,
            String subjectType,
            String evaluatorVersion,
            int attempt
    ) {
    }

    record CaseWork(
            UUID id,
            String key,
            String language,
            String caseType,
            String storedMappingStatus,
            List<String> requiredEvidenceKeys,
            Map<String, Object> input,
            Map<String, Object> expected,
            Map<String, Object> metadata
    ) {
    }

    record ClaimedDrill(
            UUID id,
            DrillType drillType,
            DrillExecutionMode executionMode,
            UUID requestedBy,
            int attempt
    ) {
    }

    record MetricResult(
            String key,
            String status,
            Double value,
            Map<String, Object> details
    ) {
    }

    record CaseEvaluation(
            String status,
            Map<String, Object> output,
            String errorCode,
            String errorMessage,
            List<MetricResult> metrics
    ) {
    }
}
