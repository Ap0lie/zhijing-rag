package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    @EntityGraph(attributePaths = {"owner", "currentRevision"})
    @Query(value = """
            select document
            from DocumentEntity document
            where document.deletedAt is null
              and (:query = '' or lower(document.title) like concat('%', lower(:query), '%'))
              and (:visibility is null or document.visibility = :visibility)
              and (
                :admin = true
                or (document.currentRevision is not null and exists (
                    select currentRevision.id
                    from DocumentRevisionEntity currentRevision
                    where currentRevision = document.currentRevision
                      and currentRevision.status in :downloadableStatuses
                ))
              )
              and (
                :admin = true
                or document.visibility = :publicVisibility
                or document.owner.id = :userId
                or exists (
                    select entry.id
                    from DocumentAclEntryEntity entry
                    where entry.document = document and entry.user.id = :userId
                )
              )
            order by document.createdAt desc
            """, countQuery = """
            select count(document)
            from DocumentEntity document
            where document.deletedAt is null
              and (:query = '' or lower(document.title) like concat('%', lower(:query), '%'))
              and (:visibility is null or document.visibility = :visibility)
              and (
                :admin = true
                or (document.currentRevision is not null and exists (
                    select currentRevision.id
                    from DocumentRevisionEntity currentRevision
                    where currentRevision = document.currentRevision
                      and currentRevision.status in :downloadableStatuses
                ))
              )
              and (
                :admin = true
                or document.visibility = :publicVisibility
                or document.owner.id = :userId
                or exists (
                    select entry.id
                    from DocumentAclEntryEntity entry
                    where entry.document = document and entry.user.id = :userId
                )
              )
            """)
    Page<DocumentEntity> findAccessible(
            @Param("userId") UUID userId,
            @Param("admin") boolean admin,
            @Param("publicVisibility") DocumentVisibility publicVisibility,
            @Param("query") String query,
            @Param("visibility") DocumentVisibility visibility,
            @Param("downloadableStatuses") Collection<RevisionStatus> downloadableStatuses,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"owner", "currentRevision"})
    Optional<DocumentEntity> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from DocumentEntity document where document.id = :id and document.deletedAt is null")
    Optional<DocumentEntity> findActiveForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from DocumentEntity document where document.id in :ids order by document.id")
    List<DocumentEntity> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
