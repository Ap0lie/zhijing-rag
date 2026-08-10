package com.example.rag.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChunkRepository extends JpaRepository<ChunkEntity, UUID> {

    @EntityGraph(attributePaths = "chunkingProfile")
    List<ChunkEntity> findAllByRevisionIdAndChunkTypeOrderByChunkOrder(UUID revisionId, ChunkType chunkType);

    @EntityGraph(attributePaths = "chunkingProfile")
    Page<ChunkEntity> findAllByRevisionIdAndChunkType(UUID revisionId, ChunkType chunkType, Pageable pageable);

    List<ChunkEntity> findAllByParentChunkIdOrderByChunkOrder(UUID parentChunkId);

    void deleteByRevisionId(UUID revisionId);
}
