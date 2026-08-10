package com.example.rag.pipeline;

import java.util.List;

public record PipelineJobPageResponse(
        List<PipelineJobResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
