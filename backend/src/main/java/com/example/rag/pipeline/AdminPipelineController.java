package com.example.rag.pipeline;

import com.example.rag.persistence.PipelineJobStatus;
import com.example.rag.persistence.PipelineStage;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/pipeline-jobs")
public class AdminPipelineController {

    private final PipelineService service;

    public AdminPipelineController(PipelineService service) {
        this.service = service;
    }

    @GetMapping
    PipelineJobPageResponse list(
            @RequestParam(required = false) PipelineStage stage,
            @RequestParam(required = false) PipelineJobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(stage, status, page, size);
    }

    @PostMapping("/{jobId}/retry")
    PipelineJobResponse retry(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.retry(jobId, user);
    }

    @PostMapping("/{jobId}/recover")
    PipelineRevisionContracts.RecoveryResponse recover(
            @PathVariable UUID jobId,
            @Valid @RequestBody PipelineRecoveryRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.recover(jobId, request, user);
    }

    @PostMapping("/{jobId}/parser-override")
    PipelineJobResponse overrideParser(
            @PathVariable UUID jobId,
            @Valid @org.springframework.web.bind.annotation.RequestBody ParserOverrideRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.overrideParser(jobId, request, user);
    }

    @PostMapping("/{jobId}/cancel")
    PipelineJobResponse cancel(
            @PathVariable UUID jobId,
            @Valid @RequestBody PipelineActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.cancel(jobId, request, user);
    }

    @PostMapping("/{jobId}/quarantine-release")
    PipelineJobResponse releaseQuarantine(
            @PathVariable UUID jobId,
            @Valid @RequestBody PipelineActionRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.releaseQuarantine(jobId, request, user);
    }
}
