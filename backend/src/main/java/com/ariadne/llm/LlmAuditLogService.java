package com.ariadne.llm;

import com.ariadne.api.dto.LlmAuditLogResponse;
import com.ariadne.api.dto.LlmAuditStatsResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Service
public class LlmAuditLogService {

    private final LlmAuditLogRepository repository;

    public LlmAuditLogService(LlmAuditLogRepository repository) {
        this.repository = repository;
    }

    public void recordSuccess(String prompt, SanitizedGraphData data) {
        repository.save(new LlmAuditLog(
                OffsetDateTime.now(ZoneOffset.UTC),
                data.transmissionLevel().name().toLowerCase(Locale.ROOT),
                normalizePrompt(prompt),
                data.scope(),
                nodeCount(data),
                edgeCount(data),
                null,
                null,
                LlmAuditStatus.SUCCEEDED,
                null
        ));
    }

    public void recordFailure(String prompt, SanitizedGraphData data, Throwable throwable) {
        repository.save(new LlmAuditLog(
                OffsetDateTime.now(ZoneOffset.UTC),
                data.transmissionLevel().name().toLowerCase(Locale.ROOT),
                normalizePrompt(prompt),
                data.scope(),
                nodeCount(data),
                edgeCount(data),
                null,
                null,
                LlmAuditStatus.FAILED,
                failureMessage(throwable)
        ));
    }

    public List<LlmAuditLogResponse> findLogs(OffsetDateTime from, OffsetDateTime to) {
        return findEntities(from, to).stream()
                .map(LlmAuditLogResponse::from)
                .toList();
    }

    public LlmAuditStatsResponse stats(OffsetDateTime from, OffsetDateTime to) {
        var logs = findEntities(from, to);
        var totalCalls = logs.size();
        var successCalls = logs.stream().filter(log -> log.getStatus() == LlmAuditStatus.SUCCEEDED).count();
        var failedCalls = logs.stream().filter(log -> log.getStatus() == LlmAuditStatus.FAILED).count();
        var averageNodes = logs.stream().mapToInt(LlmAuditLog::getNodeCount).average().orElse(0);
        var averageEdges = logs.stream().mapToInt(LlmAuditLog::getEdgeCount).average().orElse(0);

        return new LlmAuditStatsResponse(
                totalCalls,
                (int) successCalls,
                (int) failedCalls,
                Math.round(averageNodes * 10.0) / 10.0,
                Math.round(averageEdges * 10.0) / 10.0
        );
    }

    private List<LlmAuditLog> findEntities(OffsetDateTime from, OffsetDateTime to) {
        if (from == null && to == null) {
            return repository.findAllByOrderByTimestampDesc();
        }
        return repository.findAllByOrderByTimestampDesc().stream()
                .filter(log -> from == null || !log.getTimestamp().isBefore(from))
                .filter(log -> to == null || !log.getTimestamp().isAfter(to))
                .toList();
    }

    private int nodeCount(SanitizedGraphData data) {
        var nodes = data.payload().get("nodes");
        return nodes instanceof List<?> values ? values.size() : 0;
    }

    private int edgeCount(SanitizedGraphData data) {
        var edges = data.payload().get("edges");
        return edges instanceof List<?> values ? values.size() : 0;
    }

    private String normalizePrompt(String prompt) {
        return prompt == null ? "" : prompt;
    }

    private String failureMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "LLM request failed";
        }
        return throwable.getMessage();
    }
}
