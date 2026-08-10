package com.example.rag.projection;

import java.util.List;

public record ProjectionClosureStatus(
        boolean sourceLocatorCompatible,
        boolean caughtUp,
        long expectedDocumentCount,
        long projectedDocumentCount,
        long locatorReadyDocumentCount,
        long staleDocumentCount,
        long missingLocatorDocumentCount,
        long orphanedProjectionCount,
        long allUsersSourceDocumentCount,
        long restrictedSourceDocumentCount,
        long invalidEvidenceCount,
        List<FormatCoverage> formats,
        List<String> blockers
) {

    public ProjectionClosureStatus {
        formats = List.copyOf(formats);
        blockers = List.copyOf(blockers);
    }

    public boolean ready() {
        return sourceLocatorCompatible && caughtUp && blockers.isEmpty();
    }

    public record FormatCoverage(
            String documentFormat,
            long expectedDocumentCount,
            long projectedDocumentCount,
            long locatorReadyDocumentCount,
            long staleDocumentCount
    ) {
    }
}
