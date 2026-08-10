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
@Table(name = "content_blocks")
public class ContentBlockEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private DocumentRevisionEntity revision;

    @Column(name = "block_order", nullable = false)
    private int blockOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 16)
    private ContentBlockType blockType;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "heading_path", nullable = false, columnDefinition = "text")
    private String headingPath;

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

    @Column(name = "character_count", nullable = false)
    private int characterCount;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "token_counter_version", nullable = false, length = 64)
    private String tokenCounterVersion;

    @Column(name = "source_text_hash", nullable = false, length = 64)
    private String sourceTextHash;

    @Column(name = "parser_version", nullable = false, length = 64)
    private String parserVersion;

    @Column(name = "bounding_boxes_json", columnDefinition = "text")
    private String boundingBoxesJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContentBlockEntity() {
    }

    public ContentBlockEntity(
            UUID id,
            DocumentRevisionEntity revision,
            int blockOrder,
            ContentBlockType blockType,
            String text,
            String headingPath,
            SourceLocatorKind locatorKind,
            UUID startSourceUnitId,
            UUID endSourceUnitId,
            String locatorAddress,
            int startOffset,
            int endOffset,
            int tokenCount,
            String tokenCounterVersion,
            String sourceTextHash,
            String normalizationVersion,
            String parserVersion,
            String boundingBoxesJson
    ) {
        this.id = id;
        this.documentId = revision.getDocument().getId();
        this.revision = revision;
        this.blockOrder = blockOrder;
        this.blockType = blockType;
        this.text = text;
        this.headingPath = headingPath;
        this.locatorKind = locatorKind;
        this.startSourceUnitId = startSourceUnitId;
        this.endSourceUnitId = endSourceUnitId;
        this.locatorAddress = locatorAddress;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.characterCount = text.codePointCount(0, text.length());
        this.tokenCount = tokenCount;
        this.tokenCounterVersion = tokenCounterVersion;
        this.sourceTextHash = sourceTextHash;
        this.normalizationVersion = normalizationVersion;
        this.parserVersion = parserVersion;
        this.boundingBoxesJson = boundingBoxesJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public DocumentRevisionEntity getRevision() {
        return revision;
    }

    public int getBlockOrder() {
        return blockOrder;
    }

    public ContentBlockType getBlockType() {
        return blockType;
    }

    public String getText() {
        return text;
    }

    public String getHeadingPath() {
        return headingPath;
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

    public int getCharacterCount() {
        return characterCount;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public String getTokenCounterVersion() {
        return tokenCounterVersion;
    }

    public String getSourceTextHash() {
        return sourceTextHash;
    }

    public String getParserVersion() {
        return parserVersion;
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
