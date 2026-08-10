package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentAclEntryRepository extends JpaRepository<DocumentAclEntryEntity, UUID> {
    boolean existsByDocumentIdAndUserId(UUID documentId, UUID userId);

    @Query("select entry.user from DocumentAclEntryEntity entry where entry.document.id = :documentId order by entry.user.username")
    List<UserEntity> findGrantedUsers(@Param("documentId") UUID documentId);

    @Query("select entry.document.id from DocumentAclEntryEntity entry where entry.user.id = :userId order by entry.document.id")
    List<UUID> findDocumentIdsByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from DocumentAclEntryEntity entry where entry.document.id = :documentId")
    void deleteAllByDocumentId(@Param("documentId") UUID documentId);

    @Modifying
    @Query("delete from DocumentAclEntryEntity entry where entry.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from DocumentAclEntryEntity entry where entry.document.id = :documentId and entry.user.id = :userId")
    int deleteByDocumentIdAndUserId(
            @Param("documentId") UUID documentId,
            @Param("userId") UUID userId
    );
}
