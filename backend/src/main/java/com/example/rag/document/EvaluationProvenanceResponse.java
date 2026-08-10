package com.example.rag.document;

import com.example.rag.persistence.EvaluationProvenance;

public record EvaluationProvenanceResponse(
        String evaluationSuiteVersion,
        String evaluationEvidenceKey,
        String sourceDataset,
        String sourceTitle,
        String sourceUrl,
        String sourceLicense,
        String sourceRevision,
        String sourceContentHash
) {
    static EvaluationProvenanceResponse from(EvaluationProvenance value) {
        return value == null ? null : new EvaluationProvenanceResponse(
                value.getEvaluationSuiteVersion(),
                value.getEvaluationEvidenceKey(),
                value.getSourceDataset(),
                value.getSourceTitle(),
                value.getSourceUrl(),
                value.getSourceLicense(),
                value.getSourceRevision(),
                value.getSourceContentHash()
        );
    }
}
