package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SourceSpanRepository extends JpaRepository<SourceSpanEntity, UUID> {
    List<SourceSpanEntity> findAllByChunkIdInOrderByChunkIdAscSpanOrderAsc(Collection<UUID> chunkIds);

    void deleteByRevisionId(UUID revisionId);
}
