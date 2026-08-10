package com.example.rag.document;

record EvaluationProvenanceInput(
        String evaluationSuiteVersion,
        String evaluationEvidenceKey,
        String sourceDataset,
        String sourceTitle,
        String sourceUrl,
        String sourceLicense,
        String sourceRevision,
        String sourceContentHash
) {
}
