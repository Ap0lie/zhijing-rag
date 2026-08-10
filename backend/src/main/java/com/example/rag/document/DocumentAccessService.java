package com.example.rag.document;

import com.example.rag.common.ApiException;
import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.UserRole;
import com.example.rag.security.PlatformUserPrincipal;
import io.minio.GetObjectResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentAccessService {

    private static final Set<RevisionStatus> DOWNLOADABLE = Set.of(
            RevisionStatus.UPLOADED,
            RevisionStatus.PROCESSING,
            RevisionStatus.READY,
            RevisionStatus.FAILED,
            RevisionStatus.QUARANTINED
    );
    private static final Set<RevisionStatus> VISIBLE_TO_ADMIN =
            EnumSet.complementOf(EnumSet.of(RevisionStatus.DELETED));

    private final DocumentRepository documents;
    private final DocumentRevisionRepository revisions;
    private final DocumentAclEntryRepository aclEntries;
    private final DocumentAclPolicy aclPolicy;
    private final ObjectStorageService storage;
    private final TransactionTemplate transactions;

    public DocumentAccessService(
            DocumentRepository documents,
            DocumentRevisionRepository revisions,
            DocumentAclEntryRepository aclEntries,
            DocumentAclPolicy aclPolicy,
            ObjectStorageService storage,
            TransactionTemplate transactions
    ) {
        this.documents = documents;
        this.revisions = revisions;
        this.aclEntries = aclEntries;
        this.aclPolicy = aclPolicy;
        this.storage = storage;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public DocumentPageResponse listAccessible(
            PlatformUserPrincipal user,
            String query,
            DocumentVisibility visibility,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        boolean admin = user.role() == UserRole.ADMIN;
        var result = documents.findAccessible(
                user.id(),
                admin,
                DocumentVisibility.ALL_USERS,
                query == null ? "" : query.trim(),
                visibility,
                DOWNLOADABLE,
                PageRequest.of(safePage, safeSize)
        );
        List<DocumentEntity> pageDocuments = result.getContent();
        Map<UUID, DocumentRevisionEntity> effective = effectiveByDocument(pageDocuments);
        Map<UUID, DocumentRevisionEntity> latest = admin
                ? latestByDocument(pageDocuments.stream().map(DocumentEntity::getId).toList(), VISIBLE_TO_ADMIN)
                : effective;
        return new DocumentPageResponse(
                pageDocuments.stream()
                        .map(document -> DocumentSummaryResponse.from(
                                document,
                                latest.get(document.getId()),
                                effective.get(document.getId())
                        ))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getAccessible(UUID id, PlatformUserPrincipal user) {
        DocumentEntity document = requireAccessible(id, user);
        DocumentRevisionEntity effective = effectiveRevision(document);
        boolean admin = user.role() == UserRole.ADMIN;
        if (!admin && effective == null) {
            throw notFound();
        }
        DocumentRevisionEntity latest = admin
                ? revisions.findFirstByDocumentIdAndStatusInOrderByRevisionNumberDesc(id, VISIBLE_TO_ADMIN)
                        .orElse(null)
                : effective;
        List<DocumentRevisionEntity> visibleRevisions = admin
                ? revisions.findAllByDocumentIdOrderByRevisionNumberDesc(id)
                : effective == null ? List.of() : List.of(effective);
        return DocumentDetailResponse.from(
                document,
                latest,
                effective,
                admin ? aclEntries.findGrantedUsers(id) : List.of(),
                visibleRevisions
        );
    }

    public DocumentDownload openDownload(UUID documentId, UUID revisionId, PlatformUserPrincipal user) {
        DownloadMetadata metadata = transactions.execute(status -> authorizeDownload(documentId, revisionId, user));
        if (metadata == null) {
            throw new IllegalStateException("Download authorization returned no result");
        }
        GetObjectResponse stream = storage.open(metadata.objectKey());
        return new DocumentDownload(
                stream,
                metadata.filename(),
                metadata.size(),
                metadata.mediaType()
        );
    }

    @Transactional(readOnly = true)
    public DocumentRevisionEntity requireVisibleRevision(
            UUID documentId,
            UUID revisionId,
            PlatformUserPrincipal user
    ) {
        DocumentEntity document = requireAccessible(documentId, user);
        DocumentRevisionEntity revision = revisions.findByIdAndDocumentId(revisionId, documentId)
                .filter(candidate -> VISIBLE_TO_ADMIN.contains(candidate.getStatus()))
                .orElseThrow(DocumentAccessService::notFound);
        if (user.role() != UserRole.ADMIN) {
            DocumentRevisionEntity effective = effectiveRevision(document);
            if (effective == null || !effective.getId().equals(revisionId)) {
                throw notFound();
            }
        }
        return revision;
    }

    private DownloadMetadata authorizeDownload(
            UUID documentId,
            UUID revisionId,
            PlatformUserPrincipal user
    ) {
        DocumentRevisionEntity revision = requireVisibleRevision(documentId, revisionId, user);
        if (!DOWNLOADABLE.contains(revision.getStatus())) {
            throw notFound();
        }
        return new DownloadMetadata(
                revision.getSourceObjectKey(),
                revision.getOriginalFilename(),
                revision.getFileSizeBytes(),
                revision.getMediaType()
        );
    }

    private DocumentEntity requireAccessible(UUID id, PlatformUserPrincipal user) {
        DocumentEntity document = documents.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(DocumentAccessService::notFound);
        if (!aclPolicy.canRead(document, user)) {
            throw notFound();
        }
        return document;
    }

    private DocumentRevisionEntity effectiveRevision(DocumentEntity document) {
        return document.getCurrentRevision() != null
                && DOWNLOADABLE.contains(document.getCurrentRevision().getStatus())
                ? document.getCurrentRevision()
                : null;
    }

    private Map<UUID, DocumentRevisionEntity> effectiveByDocument(List<DocumentEntity> pageDocuments) {
        Map<UUID, DocumentRevisionEntity> effective = new LinkedHashMap<>();
        pageDocuments.forEach(document -> {
            DocumentRevisionEntity current = document.getCurrentRevision();
            if (current != null && DOWNLOADABLE.contains(current.getStatus())) {
                effective.put(document.getId(), current);
            }
        });
        return effective;
    }

    private Map<UUID, DocumentRevisionEntity> latestByDocument(
            Collection<UUID> documentIds,
            Collection<RevisionStatus> statuses
    ) {
        if (documentIds.isEmpty() || statuses.isEmpty()) {
            return Map.of();
        }
        Map<UUID, DocumentRevisionEntity> latest = new LinkedHashMap<>();
        for (var revision : revisions.findCandidates(documentIds, statuses)) {
            latest.putIfAbsent(revision.getDocument().getId(), revision);
        }
        return latest;
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "文档不存在");
    }

    public record DocumentDownload(
            GetObjectResponse stream,
            String filename,
            long size,
            String mediaType
    ) {
    }

    private record DownloadMetadata(
            String objectKey,
            String filename,
            long size,
            String mediaType
    ) {
    }
}
