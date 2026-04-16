package com.ariadne.snapshot;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {

    Optional<Snapshot> findTopByOrderByCapturedAtDesc();

    Optional<Snapshot> findTopByCapturedAtBeforeOrderByCapturedAtDesc(OffsetDateTime capturedAt);

    Page<Snapshot> findAllByOrderByCapturedAtDesc(Pageable pageable);

    Page<Snapshot> findByCapturedAtGreaterThanEqualOrderByCapturedAtDesc(OffsetDateTime capturedAt, Pageable pageable);

    Page<Snapshot> findByCapturedAtBetweenOrderByCapturedAtDesc(
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable
    );

    List<Snapshot> findAllByOrderByCapturedAtAsc();
}
