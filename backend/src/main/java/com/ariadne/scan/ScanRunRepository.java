package com.ariadne.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScanRunRepository extends JpaRepository<ScanRun, UUID> {

    Optional<ScanRun> findTopByOrderByStartedAtDesc();

    Optional<ScanRun> findTopByStatusOrderByCompletedAtDesc(ScanStatus status);
}
