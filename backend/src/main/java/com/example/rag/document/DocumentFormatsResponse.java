package com.example.rag.document;

import com.example.rag.persistence.DocumentFormat;

import java.util.List;

public record DocumentFormatsResponse(
        String schemaVersion,
        List<DocumentFormatCapability> formats
) {
    public record DocumentFormatCapability(
            DocumentFormat format,
            boolean enabled,
            String runtimeStatus,
            String policyStatus,
            long policyVersion,
            long runningJobs,
            String displayName,
            List<String> extensions,
            List<String> mediaTypes,
            long maxFileSizeBytes,
            List<String> locatorKinds,
            List<ParserProviderCapability> parserProviders,
            boolean parserOverrideAllowed
    ) {
    }

    public record ParserProviderCapability(
            String provider,
            boolean available,
            String reasonCode,
            String runtimeStatus,
            String policyStatus,
            long policyVersion,
            long runningJobs
    ) {
    }
}
