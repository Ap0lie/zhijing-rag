package com.example.rag.pipeline;

import com.example.rag.persistence.DocumentFormat;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable description of one already validated, bounded parser input.
 *
 * <p>The Path belongs to the caller and is only valid for the duration of the
 * parser invocation. Providers must not retain it.</p>
 */
public record ParserInput(
        Path path,
        UUID documentId,
        UUID revisionId,
        DocumentFormat documentFormat,
        String mediaType,
        long byteSize,
        String inputHash
) {
    public ParserInput {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(documentFormat, "documentFormat");
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType is required");
        }
        if (byteSize < 1) {
            throw new IllegalArgumentException("byteSize must be positive");
        }
        if (inputHash == null || !inputHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inputHash must be a lowercase SHA-256 value");
        }
    }
}
