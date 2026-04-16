package com.ariadne.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SnapshotDiffRepository extends JpaRepository<SnapshotDiff, Long> {

    Optional<SnapshotDiff> findTopByOrderByDiffedAtDesc();

    Optional<SnapshotDiff> findByBaseSnapshotIdAndTargetSnapshotId(Long baseSnapshotId, Long targetSnapshotId);

    Optional<SnapshotDiff> findByTargetSnapshotId(Long targetSnapshotId);

    List<SnapshotDiff> findByDiffedAtGreaterThanEqualOrderByDiffedAtAsc(OffsetDateTime diffedAt);

    List<SnapshotDiff> findByDiffedAtBetweenOrderByDiffedAtAsc(OffsetDateTime from, OffsetDateTime to);

    List<SnapshotDiff> findAllByOrderByDiffedAtAsc();
}
