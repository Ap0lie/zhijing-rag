package com.example.rag.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.evaluation")
public record EvaluationProperties(
        boolean catalogImportEnabled,
        boolean workerEnabled,
        String workerId,
        Duration pollInterval,
        Duration leaseDuration,
        Duration heartbeatInterval,
        int maxAttempts,
        boolean observabilityEnabled,
        boolean drillsEnabled,
        boolean realEnabled,
        boolean realDrillsEnabled,
        Duration retention,
        Duration drillStepDuration
) {
    public EvaluationProperties {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("rag.evaluation.worker-id must contain 1-128 characters");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("Evaluation durations must be positive");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Evaluation heartbeat must be shorter than its lease");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("rag.evaluation.max-attempts must be between 1 and 10");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("rag.evaluation.retention must be positive");
        }
        if (drillStepDuration == null
                || drillStepDuration.compareTo(Duration.ofMillis(100)) < 0
                || drillStepDuration.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException(
                    "rag.evaluation.drill-step-duration must be between 100 ms and 10 s"
            );
        }
    }
}
