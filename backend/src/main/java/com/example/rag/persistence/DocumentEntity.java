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

@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private UserEntity owner;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentVisibility visibility;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "acl_version", nullable = false)
    private long aclVersion = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_revision_id")
    private DocumentRevisionEntity currentRevision;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentEntity() {
    }

    public DocumentEntity(UserEntity owner, String title, DocumentVisibility visibility) {
        this.owner = owner;
        this.title = title;
        this.visibility = visibility;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public UserEntity getOwner() {
        return owner;
    }

    public DocumentVisibility getVisibility() {
        return visibility;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getAclVersion() {
        return aclVersion;
    }

    public DocumentRevisionEntity getCurrentRevision() {
        return currentRevision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateMetadataAndAcl(String title, DocumentVisibility visibility) {
        this.title = title;
        this.visibility = visibility;
        this.aclVersion++;
    }

    public void markAclChanged() {
        this.aclVersion++;
    }

    public void markDeleted() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            aclVersion++;
        }
    }

    public void markContentUpdated() {
        updatedAt = Instant.now();
    }

    public boolean publishRevision(DocumentRevisionEntity revision) {
        if (!revision.getDocument().getId().equals(id)) {
            throw new IllegalArgumentException("Revision belongs to a different document");
        }
        if (currentRevision != null
                && currentRevision.getRevisionNumber() >= revision.getRevisionNumber()) {
            return false;
        }
        currentRevision = revision;
        updatedAt = Instant.now();
        return true;
    }
}
