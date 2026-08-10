package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class EvaluationProvenance {

    @Column(name = "evaluation_suite_version", length = 64)
    private String evaluationSuiteVersion;

    @Column(name = "evaluation_evidence_key", length = 128)
    private String evaluationEvidenceKey;

    @Column(name = "source_dataset", length = 64)
    private String sourceDataset;

    @Column(name = "source_title", length = 500)
    private String sourceTitle;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "source_license", length = 64)
    private String sourceLicense;

    @Column(name = "source_revision", length = 255)
    private String sourceRevision;

    @Column(name = "source_content_hash", length = 64)
    private String sourceContentHash;

    protected EvaluationProvenance() {
    }

    public EvaluationProvenance(
            String evaluationSuiteVersion,
            String evaluationEvidenceKey,
            String sourceDataset,
            String sourceTitle,
            String sourceUrl,
            String sourceLicense,
            String sourceRevision,
            String sourceContentHash
    ) {
        this.evaluationSuiteVersion = evaluationSuiteVersion;
        this.evaluationEvidenceKey = evaluationEvidenceKey;
        this.sourceDataset = sourceDataset;
        this.sourceTitle = sourceTitle;
        this.sourceUrl = sourceUrl;
        this.sourceLicense = sourceLicense;
        this.sourceRevision = sourceRevision;
        this.sourceContentHash = sourceContentHash;
    }

    public String getEvaluationSuiteVersion() {
        return evaluationSuiteVersion;
    }

    public String getEvaluationEvidenceKey() {
        return evaluationEvidenceKey;
    }

    public String getSourceDataset() {
        return sourceDataset;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceLicense() {
        return sourceLicense;
    }

    public String getSourceRevision() {
        return sourceRevision;
    }

    public String getSourceContentHash() {
        return sourceContentHash;
    }
}
