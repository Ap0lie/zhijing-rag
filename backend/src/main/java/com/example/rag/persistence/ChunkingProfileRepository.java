package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkingProfileRepository extends JpaRepository<ChunkingProfileEntity, String> {
}
