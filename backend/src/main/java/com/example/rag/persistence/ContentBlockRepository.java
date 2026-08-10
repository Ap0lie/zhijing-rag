package com.example.rag.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentBlockRepository extends JpaRepository<ContentBlockEntity, UUID> {
    List<ContentBlockEntity> findAllByRevisionIdOrderByBlockOrder(UUID revisionId);

    Page<ContentBlockEntity> findAllByRevisionId(UUID revisionId, Pageable pageable);

    void deleteByRevisionId(UUID revisionId);
}
