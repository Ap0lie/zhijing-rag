package com.example.rag.pipeline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PipelineActionRequest(
        @NotBlank @Size(min = 8, max = 500) String reason,
        @NotBlank String confirmation
) {
}
