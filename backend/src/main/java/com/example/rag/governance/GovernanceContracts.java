package com.example.rag.governance;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GovernanceContracts {

    private GovernanceContracts() {
    }

    public record AuditEventView(
            String sourceEvent,
            String module,
            String action,
            UUID actorId,
            String actorSnapshot,
            String objectType,
            String objectId,
            String objectLabel,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason,
            Instant occurredAt
    ) {
    }

    public record AuditEventPage(List<AuditEventView> items, String nextCursor) {
    }

    public record OperationImpactRequest(
            @NotBlank String operation,
            String objectId,
            Map<String, Object> parameters
    ) {
        public Map<String, Object> safeParameters() {
            return parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public record OperationImpact(
            String operation,
            String objectType,
            String objectId,
            String confirmation,
            long factVersion,
            List<String> immediateEffects,
            List<String> asynchronousEffects,
            List<String> notAffected,
            List<String> blockers,
            Map<String, Long> affectedCounts,
            String rollback
    ) {
    }
}
