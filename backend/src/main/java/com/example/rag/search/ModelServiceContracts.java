package com.example.rag.search;

import java.time.Instant;
import java.util.List;

public final class ModelServiceContracts {

    private ModelServiceContracts() {
    }

    public record ModelServicesHealthResponse(List<ModelServiceHealth> services) {
    }

    public record ModelServiceHealth(
            String type,
            String status,
            String model,
            String revision,
            Integer dimensions,
            Long latencyMs,
            Instant checkedAt,
            String errorCode
    ) {
    }
}
