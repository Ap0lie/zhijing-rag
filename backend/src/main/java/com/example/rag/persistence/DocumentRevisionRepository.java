package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

public interface DocumentRevisionRepository extends JpaRepository<DocumentRevisionEntity, UUID> {
    @EntityGraph(attributePaths = "document")
    Optional<DocumentRevisionEntity> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select revision from DocumentRevisionEntity revision join fetch revision.document where revision.idempotencyKey = :key")
    Optional<DocumentRevisionEntity> findByIdempotencyKeyForUpdate(@Param("key") String key);

    Optional<DocumentRevisionEntity> findByIdAndDocumentId(UUID id, UUID documentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select revision from DocumentRevisionEntity revision join fetch revision.document where revision.id = :id")
    Optional<DocumentRevisionEntity> findForUpdate(@Param("id") UUID id);

    List<DocumentRevisionEntity> findAllByDocumentIdOrderByRevisionNumberDesc(UUID documentId);

    Optional<DocumentRevisionEntity> findFirstByDocumentIdOrderByRevisionNumberDesc(UUID documentId);

    @Query("""
            select revision
            from DocumentRevisionEntity revision
            where revision.document.id in :documentIds and revision.status in :statuses
            order by revision.document.id, revision.revisionNumber desc
            """)
    List<DocumentRevisionEntity> findCandidates(
            @Param("documentIds") Collection<UUID> documentIds,
            @Param("statuses") Collection<RevisionStatus> statuses
    );

    Optional<DocumentRevisionEntity> findFirstByDocumentIdAndStatusInOrderByRevisionNumberDesc(
            UUID documentId,
            Collection<RevisionStatus> statuses
    );

    List<DocumentRevisionEntity> findAllByDocumentDeletedAtIsNotNullAndStatusNot(RevisionStatus status);

    @Query(value = """
            select id
            from document_revisions
            where status = 'STAGED' and staging_expires_at <= :cutoff
            order by staging_expires_at
            limit 50
            """, nativeQuery = true)
    List<UUID> findExpiredStagedIds(@Param("cutoff") java.time.Instant cutoff);

    boolean existsByDocumentIdAndStatusNotIn(UUID documentId, Collection<RevisionStatus> statuses);

    @Query("select revision.sourceObjectKey from DocumentRevisionEntity revision where revision.status not in :unreferenced")
    List<String> findReferencedObjectKeys(@Param("unreferenced") Collection<RevisionStatus> unreferenced);
}
