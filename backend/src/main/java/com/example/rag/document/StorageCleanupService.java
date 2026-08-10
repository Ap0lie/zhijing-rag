package com.example.rag.document;

import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.RevisionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StorageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);

    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final DocumentRevisionRepository revisions;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    public StorageCleanupService(
            ObjectStorageService storage,
            StorageProperties properties,
            DocumentRevisionRepository revisions,
            TransactionTemplate transactions,
            JdbcTemplate jdbc
    ) {
        this.storage = storage;
        this.properties = properties;
        this.revisions = revisions;
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @Scheduled(
            fixedDelayString = "${rag.storage.cleanup-delay-ms:10000}",
            initialDelayString = "${rag.storage.cleanup-delay-ms:10000}"
    )
    public void runCleanup() {
        cleanupExpiredStaging();
        cleanupDeletedDocuments();
        cleanupOrphans();
    }

    private void cleanupExpiredStaging() {
        Instant now = Instant.now();
        for (UUID revisionId : revisions.findExpiredStagedIds(now)) {
            ExpiredUpload expired = transactions.execute(status -> {
                var revision = revisions.findForUpdate(revisionId).orElse(null);
                if (revision == null
                        || revision.getStatus() != RevisionStatus.STAGED
                        || revision.getStagingExpiresAt().isAfter(now)) {
                    return null;
                }
                UUID documentId = revision.getDocument().getId();
                String objectKey = revision.getSourceObjectKey();
                revision.markUploadFailed();
                if (!revisions.existsByDocumentIdAndStatusNotIn(
                        documentId,
                        Set.of(RevisionStatus.DELETED, RevisionStatus.UPLOAD_FAILED)
                )) {
                    revision.getDocument().markDeleted();
                }
                revisions.flush();
                return new ExpiredUpload(revisionId, objectKey);
            });
            if (expired != null) {
                delete(expired.revisionId(), expired.objectKey());
            }
        }
    }

    private void cleanupDeletedDocuments() {
        for (var candidate : revisions.findAllByDocumentDeletedAtIsNotNullAndStatusNot(RevisionStatus.DELETED)) {
            try {
                storage.delete(candidate.getSourceObjectKey());
                transactions.executeWithoutResult(status -> revisions.findForUpdate(candidate.getId())
                        .filter(revision -> revision.getDocument().getDeletedAt() != null)
                        .filter(revision -> revision.getStatus() != RevisionStatus.DELETED)
                        .ifPresent(revision -> {
                            revision.markDeleted();
                            revisions.flush();
                        }));
            } catch (RuntimeException exception) {
                log.warn("Failed to clean deleted revision {}", candidate.getId(), exception);
            }
        }
    }

    private void cleanupOrphans() {
        var referenced = new HashSet<>(revisions.findReferencedObjectKeys(
                Set.of(RevisionStatus.DELETED, RevisionStatus.UPLOAD_FAILED)
        ));
        referenced.addAll(jdbc.queryForList(
                """
                SELECT asset.object_key
                FROM document_image_assets asset
                JOIN document_revisions revision ON revision.id = asset.revision_id
                JOIN documents document ON document.id = asset.document_id
                WHERE revision.status NOT IN ('DELETED', 'UPLOAD_FAILED')
                  AND document.deleted_at IS NULL
                """,
                String.class
        ));
        Instant cutoff = Instant.now().minus(properties.orphanRetention());
        for (var object : storage.list()) {
            if (!referenced.contains(object.key()) && !object.lastModified().isAfter(cutoff)) {
                try {
                    storage.delete(object.key());
                } catch (RuntimeException exception) {
                    log.warn("Failed to clean orphan object {}", object.key(), exception);
                }
            }
        }
    }

    private void delete(UUID revisionId, String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("Failed to clean expired upload {}", revisionId, exception);
        }
    }

    private record ExpiredUpload(UUID revisionId, String objectKey) {
    }
}
