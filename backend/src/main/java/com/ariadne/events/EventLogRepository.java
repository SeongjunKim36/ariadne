package com.ariadne.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    List<EventLog> findTop100ByOrderByReceivedAtDesc();

    List<EventLog> findTop100ByReceivedAtBetweenOrderByReceivedAtDesc(OffsetDateTime from, OffsetDateTime to);
}
