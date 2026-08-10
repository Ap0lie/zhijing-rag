package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ParsedDocumentRepository extends JpaRepository<ParsedDocumentEntity, UUID> {
    Optional<ParsedDocumentEntity> findByRevisionId(UUID revisionId);

    void deleteByRevisionId(UUID revisionId);
}
