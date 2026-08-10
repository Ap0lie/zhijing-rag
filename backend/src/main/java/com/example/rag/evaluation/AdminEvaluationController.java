package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.CreateRunRequest;
import com.example.rag.evaluation.EvaluationContracts.CreateSubjectRequest;
import com.example.rag.evaluation.EvaluationContracts.BaselinePublicationRequest;
import com.example.rag.evaluation.EvaluationContracts.BaselinePublicationEventView;
import com.example.rag.evaluation.EvaluationContracts.BaselineView;
import com.example.rag.evaluation.EvaluationContracts.CancelRunRequest;
import com.example.rag.evaluation.EvaluationContracts.CompareView;
import com.example.rag.evaluation.EvaluationContracts.DatasetView;
import com.example.rag.evaluation.EvaluationContracts.FeedbackView;
import com.example.rag.evaluation.EvaluationContracts.FreezeMultiformatReleaseRequest;
import com.example.rag.evaluation.EvaluationContracts.MappingPage;
import com.example.rag.evaluation.EvaluationContracts.MultiformatReleaseView;
import com.example.rag.evaluation.EvaluationContracts.ReviewFeedbackRequest;
import com.example.rag.evaluation.EvaluationContracts.ResultPage;
import com.example.rag.evaluation.EvaluationContracts.ReleaseReportView;
import com.example.rag.evaluation.EvaluationContracts.RetryRunRequest;
import com.example.rag.evaluation.EvaluationContracts.RunEventView;
import com.example.rag.evaluation.EvaluationContracts.RunPage;
import com.example.rag.evaluation.EvaluationContracts.RunView;
import com.example.rag.evaluation.EvaluationContracts.SubjectView;
import com.example.rag.evaluation.EvaluationContracts.TargetView;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/admin/evaluations")
class AdminEvaluationController {

    private final EvaluationService evaluations;
    private final EvaluationGovernanceService governance;
    private final MultiformatReleaseService multiformatRelease;
    private final EvaluationReleaseReportService releaseReports;

    AdminEvaluationController(
            EvaluationService evaluations,
            EvaluationGovernanceService governance,
            MultiformatReleaseService multiformatRelease,
            EvaluationReleaseReportService releaseReports
    ) {
        this.evaluations = evaluations;
        this.governance = governance;
        this.multiformatRelease = multiformatRelease;
        this.releaseReports = releaseReports;
    }

    @GetMapping("/datasets")
    List<DatasetView> datasets() {
        return evaluations.datasets();
    }

    @GetMapping("/multiformat-release")
    MultiformatReleaseView multiformatRelease() {
        return multiformatRelease.view();
    }

    @PostMapping("/multiformat-release")
    @ResponseStatus(HttpStatus.CREATED)
    MultiformatReleaseView freezeMultiformatRelease(
            @Valid @RequestBody FreezeMultiformatReleaseRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return multiformatRelease.freeze(request.reason(), user);
    }

    @GetMapping("/datasets/versions/{versionId}/mappings")
    MappingPage mappings(
            @PathVariable UUID versionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return evaluations.mappings(versionId, page, size);
    }

    @GetMapping("/subjects")
    List<SubjectView> subjects() {
        return evaluations.subjects();
    }

    @GetMapping("/targets")
    List<TargetView> targets() {
        return evaluations.targets();
    }

    @PostMapping("/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    SubjectView createSubject(
            @Valid @RequestBody CreateSubjectRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return evaluations.createSubject(
                request.name(), request.targetId(), user
        );
    }

    @GetMapping("/runs")
    RunPage runs(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return evaluations.runs(page, size);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunView createRun(
            @Valid @RequestBody CreateRunRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return evaluations.createRun(
                request.evaluationSubjectId(),
                request.datasetVersion(),
                request.idempotencyKey(),
                user
        );
    }

    @GetMapping("/runs/{runId}")
    RunView run(@PathVariable UUID runId) {
        return evaluations.run(runId);
    }

    @GetMapping("/runs/{runId}/events")
    List<RunEventView> events(@PathVariable UUID runId) {
        return evaluations.events(runId);
    }

    @GetMapping("/runs/{runId}/results")
    ResultPage results(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return evaluations.results(runId, page, size);
    }

    @GetMapping("/runs/{runId}/release-report")
    ReleaseReportView releaseReport(@PathVariable UUID runId) {
        return releaseReports.report(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    RunView cancel(
            @PathVariable UUID runId,
            @Valid @RequestBody CancelRunRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return evaluations.cancel(runId, request.reason(), user);
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunView retry(
            @PathVariable UUID runId,
            @Valid @RequestBody RetryRunRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return evaluations.retry(
                runId, request.reason(), request.idempotencyKey(), user
        );
    }

    @GetMapping("/compare")
    CompareView compare(
            @RequestParam UUID leftRunId,
            @RequestParam UUID rightRunId,
            @RequestParam(defaultValue = "") @Size(max = 500) String reason
    ) {
        return governance.compare(leftRunId, rightRunId, reason);
    }

    @GetMapping("/baselines")
    List<BaselineView> baselines() {
        return governance.baselines();
    }

    @GetMapping("/baseline-events")
    List<BaselinePublicationEventView> baselineEvents() {
        return governance.baselineEvents();
    }

    @PostMapping("/baseline-publications")
    BaselineView publishBaseline(
            @Valid @RequestBody BaselinePublicationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        if ("ROLLBACK".equals(request.action())) {
            if (request.baselineId() == null) {
                throw new com.example.rag.common.ApiException(
                        HttpStatus.BAD_REQUEST, "BASELINE_ID_REQUIRED",
                        "回滚必须指定 baselineId"
                );
            }
            return governance.rollbackBaseline(
                    request.baselineId(), request.reason(), user
            );
        }
        if (request.runId() == null) {
            throw new com.example.rag.common.ApiException(
                    HttpStatus.BAD_REQUEST, "RUN_ID_REQUIRED",
                    "发布必须指定 runId"
            );
        }
        return governance.publishBaseline(
                request.runId(), request.name(), request.reason(), user
        );
    }

    @GetMapping("/feedback")
    List<FeedbackView> feedback() {
        return governance.reviewQueue();
    }

    @PostMapping("/feedback/{feedbackId}/review")
    FeedbackView reviewFeedback(
            @PathVariable UUID feedbackId,
            @Valid @RequestBody ReviewFeedbackRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return governance.reviewFeedback(
                feedbackId, request.decision(), request.reason(), user
        );
    }
}
