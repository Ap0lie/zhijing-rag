package com.example.rag.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRoleAndEnabledTrue(UserRole role);

    List<UserEntity> findAllByOrderByUsernameAsc();
}
