package com.ariadne.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditRunRepository extends JpaRepository<AuditRun, Long> {

    Optional<AuditRun> findTopByOrderByRunAtDesc();
}
