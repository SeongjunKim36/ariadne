package com.ariadne.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface LlmAuditLogRepository extends JpaRepository<LlmAuditLog, Long> {

    List<LlmAuditLog> findAllByOrderByTimestampDesc();

    List<LlmAuditLog> findByTimestampBetweenOrderByTimestampDesc(OffsetDateTime from, OffsetDateTime to);
}
