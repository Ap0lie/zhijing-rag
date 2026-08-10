package com.example.rag.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.memory.suggestion")
public record MemorySuggestionProperties(
        boolean workerEnabled,
        String workerId,
        Duration pollInterval,
        Duration leaseDuration,
        int maxAttempts,
        int maxSuggestions,
        String extractorVersion,
        String promptVersion
) {

    public MemorySuggestionProperties {
        workerId = required(workerId, "workerId", 128);
        extractorVersion = required(
                extractorVersion, "extractorVersion", 64
        );
        promptVersion = required(promptVersion, "promptVersion", 64);
        if (pollInterval == null || pollInterval.isNegative()
                || pollInterval.isZero()) {
            throw new IllegalArgumentException(
                    "Memory suggestion pollInterval must be positive"
            );
        }
        if (leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero()) {
            throw new IllegalArgumentException(
                    "Memory suggestion leaseDuration must be positive"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException(
                    "Memory suggestion maxAttempts must be between 1 and 10"
            );
        }
        if (maxSuggestions < 1 || maxSuggestions > 10) {
            throw new IllegalArgumentException(
                    "Memory suggestion maxSuggestions must be between 1 and 10"
            );
        }
    }

    private static String required(
            String value,
            String field,
            int maxLength
    ) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    "Memory suggestion " + field + " is invalid"
            );
        }
        return normalized;
    }
}
