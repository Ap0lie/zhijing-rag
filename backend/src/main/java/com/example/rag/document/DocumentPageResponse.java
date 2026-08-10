package com.example.rag.document;

import java.util.List;

public record DocumentPageResponse(
        List<DocumentSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
