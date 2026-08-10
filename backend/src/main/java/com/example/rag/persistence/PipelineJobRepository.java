package com.example.rag.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineJobRepository extends JpaRepository<PipelineJobEntity, UUID> {

    @EntityGraph(attributePaths = {"revision", "revision.document"})
    @Query(value = """
            select job
            from PipelineJobEntity job
            where (:stage is null or job.stage = :stage)
              and (:status is null or job.status = :status)
            order by job.createdAt desc
            """, countQuery = """
            select count(job)
            from PipelineJobEntity job
            where (:stage is null or job.stage = :stage)
              and (:status is null or job.status = :status)
            """)
    Page<PipelineJobEntity> findFiltered(
            @Param("stage") PipelineStage stage,
            @Param("status") PipelineJobStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"revision", "revision.document"})
    List<PipelineJobEntity> findAllByRevisionIdOrderByCreatedAtAsc(UUID revisionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from PipelineJobEntity job join fetch job.revision where job.id = :id")
    Optional<PipelineJobEntity> findForUpdate(@Param("id") UUID id);
}
