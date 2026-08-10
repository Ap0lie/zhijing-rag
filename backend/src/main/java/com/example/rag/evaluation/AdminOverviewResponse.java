package com.example.rag.evaluation;

import java.time.Instant;
import java.util.List;

public record AdminOverviewResponse(
        String schemaVersion,
        Instant capturedAt,
        List<TaskDomain> domains,
        List<AttentionItem> attentionItems
) {
    public record TaskDomain(
            String key,
            String title,
            String description,
            String href,
            List<TaskLink> links
    ) {
    }

    public record TaskLink(String title, String href) {
    }

    public record AttentionItem(
            String code,
            String title,
            String description,
            Long count,
            String severity,
            String valueState,
            String reasonCode,
            Instant updatedAt,
            String href
    ) {
    }
}
