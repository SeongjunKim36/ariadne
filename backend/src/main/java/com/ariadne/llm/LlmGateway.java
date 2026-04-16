package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LlmGateway {

    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5;
    private static final Duration CIRCUIT_BREAKER_COOLDOWN = Duration.ofSeconds(30);

    private final LlmClient llmClient;
    private final LlmDataSanitizer sanitizer;
    private final LlmFieldAllowlist fieldAllowlist;
    private final LlmAuditLogService auditLogService;
    private final AriadneProperties ariadneProperties;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<OffsetDateTime> circuitOpenUntil = new AtomicReference<>();
    private final AtomicReference<DailyUsage> dailyUsage = new AtomicReference<>(new DailyUsage(LocalDate.now(ZoneOffset.UTC), 0));

    public LlmGateway(
            LlmClient llmClient,
            LlmDataSanitizer sanitizer,
            LlmFieldAllowlist fieldAllowlist,
            LlmAuditLogService auditLogService,
            AriadneProperties ariadneProperties
    ) {
        this.llmClient = llmClient;
        this.sanitizer = sanitizer;
        this.fieldAllowlist = fieldAllowlist;
        this.auditLogService = auditLogService;
        this.ariadneProperties = ariadneProperties;
    }

    public String query(String prompt, GraphData contextData) {
        guardBudget(prompt);
        guardCircuitBreaker();

        var transmissionLevel = TransmissionLevel.from(ariadneProperties.getLlm().getTransmissionLevel());
        var sanitizedContext = sanitizer.sanitize(contextData, transmissionLevel);
        var allowlistedContext = fieldAllowlist.apply(sanitizedContext);

        RuntimeException lastFailure = null;
        var maxAttempts = Math.max(1, ariadneProperties.getLlm().getRetryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                var response = llmClient.query(new LlmRequest(prompt, allowlistedContext));
                consecutiveFailures.set(0);
                circuitOpenUntil.set(null);
                registerUsage(prompt);
                auditLogService.recordSuccess(prompt, allowlistedContext);
                return response;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isRetryable(exception) || attempt >= maxAttempts) {
                    break;
                }
                sleepBackoff();
            }
        }

        if (consecutiveFailures.incrementAndGet() >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
            circuitOpenUntil.set(OffsetDateTime.now(ZoneOffset.UTC).plus(CIRCUIT_BREAKER_COOLDOWN));
        }
        auditLogService.recordFailure(prompt, allowlistedContext, lastFailure);
        throw lastFailure;
    }

    public String queryText(String prompt) {
        return query(prompt, new GraphData("text-only", Map.of(
                "nodes", List.of(),
                "edges", List.of(),
                "metadata", Map.of()
        )));
    }

    private void guardCircuitBreaker() {
        var openUntil = circuitOpenUntil.get();
        if (openUntil != null && openUntil.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("LLM circuit breaker is open");
        }
    }

    private void guardBudget(String prompt) {
        var usage = refreshUsage();
        if (usage.estimatedCostUsd() >= ariadneProperties.getLlm().getDailyBudgetUsd()) {
            throw new IllegalStateException("Daily LLM budget exceeded");
        }
        if (estimateTokens(prompt) > ariadneProperties.getLlm().getMaxInputTokens()) {
            throw new IllegalStateException("Prompt exceeds max-input-tokens");
        }
    }

    private void registerUsage(String prompt) {
        var currentDay = LocalDate.now(ZoneOffset.UTC);
        var additionalCost = estimateCostUsd(estimateTokens(prompt));
        dailyUsage.updateAndGet(existing -> existing.day().equals(currentDay)
                ? new DailyUsage(currentDay, existing.estimatedCostUsd() + additionalCost)
                : new DailyUsage(currentDay, additionalCost));
    }

    private DailyUsage refreshUsage() {
        var currentDay = LocalDate.now(ZoneOffset.UTC);
        return dailyUsage.updateAndGet(existing -> existing.day().equals(currentDay)
                ? existing
                : new DailyUsage(currentDay, 0));
    }

    private int estimateTokens(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return 0;
        }
        return Math.max(1, prompt.length() / 4);
    }

    private double estimateCostUsd(int tokens) {
        return (tokens / 1000.0) * 0.003;
    }

    private boolean isRetryable(RuntimeException exception) {
        if (!(exception instanceof IllegalStateException illegalStateException)) {
            return true;
        }
        var message = illegalStateException.getMessage();
        if (message == null) {
            return true;
        }
        return !message.contains("Daily LLM budget exceeded")
                && !message.contains("LLM circuit breaker is open")
                && !message.contains("CLAUDE_API_KEY is not configured")
                && !message.contains("Prompt exceeds max-input-tokens");
    }

    private void sleepBackoff() {
        var backoffSeconds = Math.max(0, ariadneProperties.getLlm().getRetryBackoffSeconds());
        if (backoffSeconds == 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofSeconds(backoffSeconds).toMillis());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM retry backoff interrupted", interruptedException);
        }
    }

    private record DailyUsage(LocalDate day, double estimatedCostUsd) {
    }
}
