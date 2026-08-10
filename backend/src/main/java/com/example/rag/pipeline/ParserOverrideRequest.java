package com.example.rag.pipeline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ParserOverrideRequest(
        @NotNull ParserEngine targetParser,
        @NotBlank @Size(min = 8, max = 500) String reason,
        @Pattern(regexp = "OVERRIDE_PARSER") String confirmation,
        @NotBlank @Size(max = 128) String idempotencyKey
) {
}
