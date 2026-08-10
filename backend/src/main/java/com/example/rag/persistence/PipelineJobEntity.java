package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

import com.example.rag.pipeline.ParserProviderKind;

@Entity
@Table(name = "pipeline_jobs")
public class PipelineJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private DocumentRevisionEntity revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PipelineStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PipelineJobStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "pipeline_version", nullable = false, length = 64)
    private String pipelineVersion;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "quarantine_reason", length = 512)
    private String quarantineReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_format", nullable = false, length = 16)
    private DocumentFormat documentFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "parser_requested_engine", length = 16)
    private com.example.rag.pipeline.ParserEngine parserRequestedEngine;

    @Enumerated(EnumType.STRING)
    @Column(name = "parser_provider", length = 32)
    private ParserProviderKind parserProvider;

    @Column(name = "parser_decision_code", length = 64)
    private String parserDecisionCode;

    @Column(name = "parser_provider_version", length = 64)
    private String parserProviderVersion;

    @Column(name = "parser_source_unit_count")
    private Integer parserSourceUnitCount;

    @Column(name = "parser_scanned_candidate")
    private Boolean parserScannedCandidate;

    @Column(name = "parser_ocr_required")
    private Boolean parserOcrRequired;

    @Column(name = "parser_multicolumn_candidate")
    private Boolean parserMulticolumnCandidate;

    @Column(name = "parser_table_candidate")
    private Boolean parserTableCandidate;

    @Column(name = "parser_image_candidate")
    private Boolean parserImageCandidate;

    @Column(name = "parser_model_revision", length = 64)
    private String parserModelRevision;

    @Column(name = "parser_model_manifest_checksum", length = 64)
    private String parserModelManifestChecksum;

    @Column(name = "parser_decided_at")
    private Instant parserDecidedAt;

    @Column(name = "parser_override_reason", length = 500)
    private String parserOverrideReason;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PipelineJobEntity() {
    }

    public PipelineJobEntity(
            DocumentRevisionEntity revision,
            PipelineStage stage,
            PipelineJobStatus status,
            String pipelineVersion
    ) {
        this.revision = revision;
        this.stage = stage;
        this.status = status;
        this.pipelineVersion = pipelineVersion;
        this.documentFormat = revision.getDocumentFormat();
        if (stage == PipelineStage.PARSE) {
            this.parserRequestedEngine = com.example.rag.pipeline.ParserEngine.AUTO;
        }
    }

    public UUID getId() {
        return id;
    }

    public DocumentRevisionEntity getRevision() {
        return revision;
    }

    public PipelineStage getStage() {
        return stage;
    }

    public PipelineJobStatus getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getQuarantineReason() {
        return quarantineReason;
    }

    public DocumentFormat getDocumentFormat() {
        return documentFormat;
    }

    public com.example.rag.pipeline.ParserEngine getParserRequestedEngine() {
        return parserRequestedEngine;
    }

    public com.example.rag.pipeline.ParserEngine getParserSelectedEngine() {
        if (parserProvider == ParserProviderKind.PDFBOX) {
            return com.example.rag.pipeline.ParserEngine.PDFBOX;
        }
        if (parserProvider == ParserProviderKind.MINERU) {
            return com.example.rag.pipeline.ParserEngine.MINERU;
        }
        return null;
    }

    public ParserProviderKind getParserProvider() {
        return parserProvider;
    }

    public String getParserDecisionCode() {
        return parserDecisionCode;
    }

    public String getParserEngineVersion() {
        return parserProviderVersion;
    }

    public String getParserProviderVersion() {
        return parserProviderVersion;
    }

    public Integer getParserPageCount() {
        return documentFormat == DocumentFormat.PDF
                ? parserSourceUnitCount
                : null;
    }

    public Integer getParserSourceUnitCount() {
        return parserSourceUnitCount;
    }

    public Boolean getParserScannedCandidate() {
        return parserScannedCandidate;
    }

    public Boolean getParserOcrRequired() {
        return parserOcrRequired;
    }

    public Boolean getParserMulticolumnCandidate() {
        return parserMulticolumnCandidate;
    }

    public Boolean getParserTableCandidate() {
        return parserTableCandidate;
    }

    public Boolean getParserImageCandidate() {
        return parserImageCandidate;
    }

    public String getParserModelRevision() {
        return parserModelRevision;
    }

    public String getParserModelManifestChecksum() {
        return parserModelManifestChecksum;
    }

    public Instant getParserDecidedAt() {
        return parserDecidedAt;
    }

    public String getParserOverrideReason() {
        return parserOverrideReason;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isRetryable() {
        return status == PipelineJobStatus.FAILED
                || status == PipelineJobStatus.QUARANTINED
                || status == PipelineJobStatus.CANCELLED;
    }

    public boolean isCancelable() {
        return status == PipelineJobStatus.PENDING
                || status == PipelineJobStatus.RUNNING;
    }

    public void cancel() {
        if (!isCancelable()) {
            throw new IllegalStateException(
                    "Only pending or running jobs can be cancelled"
            );
        }
        Instant now = Instant.now();
        status = PipelineJobStatus.CANCELLED;
        completedAt = now;
        durationMs = startedAt == null
                ? 0L
                : Math.max(0L, java.time.Duration.between(startedAt, now).toMillis());
        errorCode = "CANCELLED_BY_ADMIN";
        errorMessage = "Pipeline job was cancelled by an administrator";
        quarantineReason = null;
        leaseOwner = null;
        leaseExpiresAt = null;
        heartbeatAt = null;
    }

    public void retry(int configuredMaxAttempts) {
        if (!isRetryable()) {
            throw new IllegalStateException(
                    "Only failed, quarantined, or cancelled jobs can be retried"
            );
        }
        if (status == PipelineJobStatus.CANCELLED) {
            maxAttempts = Math.max(maxAttempts, attempt + configuredMaxAttempts);
        } else {
            attempt = 0;
        }
        status = PipelineJobStatus.PENDING;
        leaseOwner = null;
        leaseExpiresAt = null;
        heartbeatAt = null;
        startedAt = null;
        completedAt = null;
        durationMs = null;
        errorCode = null;
        errorMessage = null;
        quarantineReason = null;
        parserProvider = null;
        parserDecisionCode = null;
        parserProviderVersion = null;
        parserSourceUnitCount = null;
        parserScannedCandidate = null;
        parserOcrRequired = null;
        parserMulticolumnCandidate = null;
        parserTableCandidate = null;
        parserImageCandidate = null;
        parserModelRevision = null;
        parserModelManifestChecksum = null;
        parserDecidedAt = null;
    }
}
