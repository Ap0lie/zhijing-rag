package com.example.rag.document;

import com.example.rag.pipeline.ParserEngine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReparseRequest(
        @NotNull UUID sourceRevisionId,
        @NotNull ParserEngine targetParser,
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*")
        String idempotencyKey,
        @NotBlank @Size(min = 8, max = 500) String reason,
        @NotBlank String confirmation
) {
}
