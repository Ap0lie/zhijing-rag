package com.example.rag.pipeline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PipelineRecoveryRequest(
        @NotBlank @Size(min = 8, max = 500) String reason,
        @NotBlank @Pattern(regexp = "RECOVER_PIPELINE_JOB") String confirmation,
        @NotBlank
        @Size(min = 8, max = 120)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
        String idempotencyKey
) {
}
