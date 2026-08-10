package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

import com.example.rag.pipeline.ParserEngine;
import com.example.rag.pipeline.ParserProviderKind;

@Entity
@Table(name = "document_revisions")
public class DocumentRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "source_object_key", nullable = false, length = 1000)
    private String sourceObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RevisionStatus status;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_format", nullable = false, length = 16)
    private DocumentFormat documentFormat = DocumentFormat.PDF;

    @Enumerated(EnumType.STRING)
    @Column(name = "parser_provider", length = 32)
    private ParserProviderKind parserProvider;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "staging_expires_at")
    private Instant stagingExpiresAt;

    @Embedded
    private EvaluationProvenance evaluationProvenance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_revision_id")
    private DocumentRevisionEntity sourceRevision;

    @Column(name = "reparse_reason", length = 500)
    private String reparseReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "reparse_requested_parser", length = 16)
    private ParserEngine reparseRequestedParser;

    @Column(name = "reparse_requested_by")
    private UUID reparseRequestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "format_change_from", length = 16)
    private DocumentFormat formatChangeFrom;

    @Column(name = "format_change_reason", length = 500)
    private String formatChangeReason;

    @Column(name = "format_change_requested_by")
    private UUID formatChangeRequestedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentRevisionEntity() {
    }

    public DocumentRevisionEntity(
            DocumentEntity document,
            int revisionNumber,
            String contentHash,
            String sourceObjectKey,
            RevisionStatus status,
            String originalFilename,
            long fileSizeBytes,
            String mediaType,
            String idempotencyKey
    ) {
        this(
                document,
                revisionNumber,
                contentHash,
                sourceObjectKey,
                status,
                originalFilename,
                fileSizeBytes,
                mediaType,
                idempotencyKey,
                idempotencyKey == null ? null : contentHash,
                null
        );
    }

    public DocumentRevisionEntity(
            DocumentEntity document,
            int revisionNumber,
            String contentHash,
            String sourceObjectKey,
            RevisionStatus status,
            String originalFilename,
            long fileSizeBytes,
            String mediaType,
            String idempotencyKey,
            String requestFingerprint,
            Instant stagingExpiresAt
    ) {
        this(
                document,
                revisionNumber,
                contentHash,
                sourceObjectKey,
                status,
                originalFilename,
                fileSizeBytes,
                mediaType,
                idempotencyKey,
                requestFingerprint,
                stagingExpiresAt,
                null
        );
    }

    public DocumentRevisionEntity(
            DocumentEntity document,
            int revisionNumber,
            String contentHash,
            String sourceObjectKey,
            RevisionStatus status,
            String originalFilename,
            long fileSizeBytes,
            String mediaType,
            String idempotencyKey,
            String requestFingerprint,
            Instant stagingExpiresAt,
            EvaluationProvenance evaluationProvenance
    ) {
        this(
                document,
                revisionNumber,
                contentHash,
                sourceObjectKey,
                status,
                originalFilename,
                fileSizeBytes,
                mediaType,
                DocumentFormat.PDF,
                idempotencyKey,
                requestFingerprint,
                stagingExpiresAt,
                evaluationProvenance
        );
    }

    public DocumentRevisionEntity(
            DocumentEntity document,
            int revisionNumber,
            String contentHash,
            String sourceObjectKey,
            RevisionStatus status,
            String originalFilename,
            long fileSizeBytes,
            String mediaType,
            DocumentFormat documentFormat,
            String idempotencyKey,
            String requestFingerprint,
            Instant stagingExpiresAt,
            EvaluationProvenance evaluationProvenance
    ) {
        this.document = document;
        this.revisionNumber = revisionNumber;
        this.contentHash = contentHash;
        this.sourceObjectKey = sourceObjectKey;
        this.status = status;
        this.originalFilename = originalFilename;
        this.fileSizeBytes = fileSizeBytes;
        this.mediaType = mediaType;
        this.documentFormat = documentFormat;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.stagingExpiresAt = stagingExpiresAt;
        this.evaluationProvenance = evaluationProvenance;
    }

    public UUID getId() {
        return id;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public RevisionStatus getStatus() {
        return status;
    }

    public DocumentEntity getDocument() {
        return document;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getSourceObjectKey() {
        return sourceObjectKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getMediaType() {
        return mediaType;
    }

    public DocumentFormat getDocumentFormat() {
        return documentFormat;
    }

    public ParserProviderKind getParserProvider() {
        return parserProvider;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getStagingExpiresAt() {
        return stagingExpiresAt;
    }

    public EvaluationProvenance getEvaluationProvenance() {
        return evaluationProvenance;
    }

    public DocumentRevisionEntity getSourceRevision() {
        return sourceRevision;
    }

    public String getReparseReason() {
        return reparseReason;
    }

    public ParserEngine getReparseRequestedParser() {
        return reparseRequestedParser;
    }

    public UUID getReparseRequestedBy() {
        return reparseRequestedBy;
    }

    public DocumentFormat getFormatChangeFrom() {
        return formatChangeFrom;
    }

    public String getFormatChangeReason() {
        return formatChangeReason;
    }

    public UUID getFormatChangeRequestedBy() {
        return formatChangeRequestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markDeleted() {
        status = RevisionStatus.DELETED;
        stagingExpiresAt = null;
    }

    public void reclaimStaging(String objectKey, Instant expiresAt) {
        sourceObjectKey = objectKey;
        status = RevisionStatus.STAGED;
        stagingExpiresAt = expiresAt;
    }

    public void markUploaded() {
        status = RevisionStatus.UPLOADED;
        stagingExpiresAt = null;
    }

    public void markUploadFailed() {
        status = RevisionStatus.UPLOAD_FAILED;
        stagingExpiresAt = null;
    }

    public void configureReparse(
            DocumentRevisionEntity source,
            ParserEngine parser,
            String reason,
            UUID actorId
    ) {
        if (source == null
                || !source.getDocument().getId().equals(document.getId())) {
            throw new IllegalArgumentException(
                    "Reparse source belongs to a different document"
            );
        }
        sourceRevision = source;
        reparseRequestedParser = parser;
        reparseReason = reason;
        reparseRequestedBy = actorId;
    }

    public void configureFormatChange(
            DocumentFormat previousFormat,
            String reason,
            UUID actorId
    ) {
        if (previousFormat == null || previousFormat == documentFormat) {
            throw new IllegalArgumentException(
                    "Format change must identify a different previous format"
            );
        }
        formatChangeFrom = previousFormat;
        formatChangeReason = reason;
        formatChangeRequestedBy = actorId;
    }

    public void markProcessing() {
        if (status != RevisionStatus.UPLOADED
                && status != RevisionStatus.FAILED
                && status != RevisionStatus.QUARANTINED
                && status != RevisionStatus.PROCESSING) {
            throw new IllegalStateException("Revision cannot enter processing from " + status);
        }
        status = RevisionStatus.PROCESSING;
        stagingExpiresAt = null;
    }

    public void markReady(String parserVersion) {
        markReady(parserVersion, parserProvider);
    }

    public void markReady(
            String parserVersion,
            ParserProviderKind parserProvider
    ) {
        requireProcessing();
        this.parserVersion = parserVersion;
        this.parserProvider = parserProvider;
        status = RevisionStatus.READY;
    }

    public void markFailed() {
        requireProcessing();
        status = RevisionStatus.FAILED;
    }

    public void markQuarantined() {
        requireProcessing();
        status = RevisionStatus.QUARANTINED;
    }

    public void markCancelled() {
        if (status != RevisionStatus.UPLOADED
                && status != RevisionStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Revision cannot be cancelled from " + status
            );
        }
        status = RevisionStatus.FAILED;
        stagingExpiresAt = null;
    }

    private void requireProcessing() {
        if (status != RevisionStatus.PROCESSING) {
            throw new IllegalStateException("Revision is not processing");
        }
    }
}
