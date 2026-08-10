package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_spans")
public class SourceSpanEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private ChunkEntity chunk;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "span_order", nullable = false)
    private int spanOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "locator_kind", nullable = false, length = 32)
    private SourceLocatorKind locatorKind;

    @Column(name = "start_source_unit_id", nullable = false)
    private UUID startSourceUnitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "start_source_unit_id",
            insertable = false,
            updatable = false
    )
    private SourceUnitEntity startSourceUnit;

    @Column(name = "end_source_unit_id", nullable = false)
    private UUID endSourceUnitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "end_source_unit_id",
            insertable = false,
            updatable = false
    )
    private SourceUnitEntity endSourceUnit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "locator_address", nullable = false, columnDefinition = "jsonb")
    private String locatorAddress;

    @Column(name = "normalization_version", nullable = false, length = 64)
    private String normalizationVersion;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Column(name = "chunk_start_offset", nullable = false)
    private int chunkStartOffset;

    @Column(name = "chunk_end_offset", nullable = false)
    private int chunkEndOffset;

    @Column(name = "source_text_hash", nullable = false, length = 64)
    private String sourceTextHash;

    @Column(name = "bounding_boxes_json", columnDefinition = "text")
    private String boundingBoxesJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SourceSpanEntity() {
    }

    public SourceSpanEntity(
            UUID id,
            ChunkEntity chunk,
            int spanOrder,
            SourceLocatorKind locatorKind,
            UUID startSourceUnitId,
            UUID endSourceUnitId,
            String locatorAddress,
            int startOffset,
            int endOffset,
            int chunkStartOffset,
            int chunkEndOffset,
            String sourceTextHash,
            String normalizationVersion,
            String boundingBoxesJson
    ) {
        this.id = id;
        this.chunk = chunk;
        this.documentId = chunk.getDocumentId();
        this.revisionId = chunk.getRevision().getId();
        this.spanOrder = spanOrder;
        this.locatorKind = locatorKind;
        this.startSourceUnitId = startSourceUnitId;
        this.endSourceUnitId = endSourceUnitId;
        this.locatorAddress = locatorAddress;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.chunkStartOffset = chunkStartOffset;
        this.chunkEndOffset = chunkEndOffset;
        this.sourceTextHash = sourceTextHash;
        this.normalizationVersion = normalizationVersion;
        this.boundingBoxesJson = boundingBoxesJson;
    }

    public UUID getId() {
        return id;
    }

    public ChunkEntity getChunk() {
        return chunk;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public int getSpanOrder() {
        return spanOrder;
    }

    public SourceLocatorKind getLocatorKind() {
        return locatorKind;
    }

    public UUID getStartSourceUnitId() {
        return startSourceUnitId;
    }

    public SourceUnitEntity getStartSourceUnit() {
        return startSourceUnit;
    }

    public UUID getEndSourceUnitId() {
        return endSourceUnitId;
    }

    public SourceUnitEntity getEndSourceUnit() {
        return endSourceUnit;
    }

    public String getLocatorAddress() {
        return locatorAddress;
    }

    public String getNormalizationVersion() {
        return normalizationVersion;
    }

    /**
     * PDF-only compatibility projection.
     */
    public int getStartPage() {
        return pageOrder(startSourceUnit);
    }

    /**
     * PDF-only compatibility projection.
     */
    public int getEndPage() {
        return pageOrder(endSourceUnit);
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public int getChunkStartOffset() {
        return chunkStartOffset;
    }

    public int getChunkEndOffset() {
        return chunkEndOffset;
    }

    public String getSourceTextHash() {
        return sourceTextHash;
    }

    public String getBoundingBoxesJson() {
        return boundingBoxesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static int pageOrder(SourceUnitEntity sourceUnit) {
        if (sourceUnit == null || sourceUnit.getUnitKind() != SourceUnitKind.PAGE) {
            throw new IllegalStateException("Source locator is not a loaded PDF page");
        }
        return sourceUnit.getUnitOrder();
    }

}
