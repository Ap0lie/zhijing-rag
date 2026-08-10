package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SourceUnitRepository extends JpaRepository<SourceUnitEntity, UUID> {

    List<SourceUnitEntity> findAllByRevisionIdOrderByUnitOrder(UUID revisionId);

    void deleteByRevisionId(UUID revisionId);
}
