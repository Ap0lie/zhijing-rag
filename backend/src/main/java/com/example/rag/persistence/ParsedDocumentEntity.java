package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

import com.example.rag.pipeline.ParserProviderKind;

@Entity
@Table(name = "parsed_documents")
public class ParsedDocumentEntity {

    @Id
    @Column(name = "revision_id")
    private UUID revisionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private DocumentRevisionEntity revision;

    @Column(nullable = false, columnDefinition = "text")
    private String markdown;

    @Column(name = "parser_version", nullable = false, length = 64)
    private String parserVersion;

    @Column(name = "parser_revision", length = 128)
    private String parserRevision;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "output_hash", length = 64)
    private String outputHash;

    @Column(name = "result_schema_version", nullable = false, length = 64)
    private String resultSchemaVersion;

    @Column(name = "offset_encoding", nullable = false, length = 32)
    private String offsetEncoding = "UTF16_CODE_UNIT";

    @Column(name = "result_manifest_json", nullable = false, columnDefinition = "text")
    private String resultManifestJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_format", nullable = false, length = 16)
    private DocumentFormat documentFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "parser_provider", nullable = false, length = 32)
    private ParserProviderKind parserProvider;

    @Column(name = "source_unit_count", nullable = false)
    private int sourceUnitCount;

    @Column(name = "character_count", nullable = false)
    private long characterCount;

    @Column(name = "parse_duration_ms", nullable = false)
    private long parseDurationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParsedDocumentEntity() {
    }

    public ParsedDocumentEntity(
            DocumentRevisionEntity revision,
            String markdown,
            String parserVersion,
            String parserRevision,
            String inputHash,
            String outputHash,
            String resultSchemaVersion,
            String resultManifestJson,
            int sourceUnitCount,
            long characterCount,
            long parseDurationMs
    ) {
        this(
                revision,
                markdown,
                parserVersion,
                parserRevision,
                inputHash,
                outputHash,
                resultSchemaVersion,
                resultManifestJson,
                DocumentFormat.PDF,
                ParserProviderKind.PDFBOX,
                sourceUnitCount,
                characterCount,
                parseDurationMs
        );
    }

    public ParsedDocumentEntity(
            DocumentRevisionEntity revision,
            String markdown,
            String parserVersion,
            String parserRevision,
            String inputHash,
            String outputHash,
            String resultSchemaVersion,
            String resultManifestJson,
            DocumentFormat documentFormat,
            ParserProviderKind parserProvider,
            int sourceUnitCount,
            long characterCount,
            long parseDurationMs
    ) {
        this.revision = revision;
        this.markdown = markdown;
        this.parserVersion = parserVersion;
        this.parserRevision = parserRevision;
        this.inputHash = inputHash;
        this.outputHash = outputHash;
        this.resultSchemaVersion = resultSchemaVersion;
        this.resultManifestJson = resultManifestJson;
        this.documentFormat = documentFormat;
        this.parserProvider = parserProvider;
        this.sourceUnitCount = sourceUnitCount;
        this.characterCount = characterCount;
        this.parseDurationMs = parseDurationMs;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public DocumentRevisionEntity getRevision() {
        return revision;
    }

    public String getMarkdown() {
        return markdown;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getParserRevision() {
        return parserRevision;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public String getResultSchemaVersion() {
        return resultSchemaVersion;
    }

    public String getOffsetEncoding() {
        return offsetEncoding;
    }

    public String getResultManifestJson() {
        return resultManifestJson;
    }

    public DocumentFormat getDocumentFormat() {
        return documentFormat;
    }

    public ParserProviderKind getParserProvider() {
        return parserProvider;
    }

    public int getSourceUnitCount() {
        return sourceUnitCount;
    }

    /**
     * PDF-only compatibility projection.
     */
    public int getPageCount() {
        return documentFormat == DocumentFormat.PDF ? sourceUnitCount : 0;
    }

    public long getCharacterCount() {
        return characterCount;
    }

    public long getParseDurationMs() {
        return parseDurationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
