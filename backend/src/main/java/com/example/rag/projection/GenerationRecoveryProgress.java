package com.example.rag.projection;

import java.time.Instant;

public record GenerationRecoveryProgress(
        String state,
        int attempt,
        Instant heartbeatAt,
        Instant leaseExpiresAt
) {

    public static GenerationRecoveryProgress of(
            String status,
            int attempt,
            Instant heartbeatAt,
            Instant leaseExpiresAt
    ) {
        String state;
        if ("BUILDING".equals(status)) {
            state = leaseExpiresAt != null && leaseExpiresAt.isAfter(Instant.now())
                    ? "RUNNING"
                    : "AWAITING_TAKEOVER";
        } else if ("FAILED".equals(status)) {
            state = "FAILED";
        } else {
            state = "COMPLETE";
        }
        return new GenerationRecoveryProgress(
                state, attempt, heartbeatAt, leaseExpiresAt
        );
    }
}
