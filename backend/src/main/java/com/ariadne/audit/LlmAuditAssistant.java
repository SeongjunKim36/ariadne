package com.ariadne.audit;

import com.ariadne.api.dto.AuditExplanationResponse;
import com.ariadne.api.dto.AuditReportResponse;
import com.ariadne.llm.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class LlmAuditAssistant {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public LlmAuditAssistant(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public AuditExplanationResponse explain(AuditReportResponse report) {
        if (report == null || report.findings().isEmpty()) {
            throw new IllegalArgumentException("audit report is empty");
        }

        var prompt = """
                당신은 AWS 보안 리뷰 도우미입니다.
                아래 감사 결과를 읽고 JSON만 응답하세요.

                요구 형식:
                {
                  "summary": "한 문단 요약",
                  "priorities": ["우선순위 1", "우선순위 2"],
                  "actions": ["구체적 액션 1", "구체적 액션 2"]
                }

                감사 결과:
                %s
                """.formatted(objectMapper.valueToTree(report).toPrettyString());

        try {
            var raw = llmGateway.queryText(prompt);
            return parse(raw);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("LLM audit explanation unavailable", exception);
        }
    }

    private AuditExplanationResponse parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned an empty audit explanation");
        }

        var cleaned = raw.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            var summary = root.path("summary").asText();
            var priorities = readStringArray(root.path("priorities"));
            var actions = readStringArray(root.path("actions"));
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("LLM audit explanation is missing summary");
            }
            return new AuditExplanationResponse(OffsetDateTime.now(), summary, priorities, actions);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse audit explanation", exception);
        }
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (JsonNode item : node) {
            var text = item.asText();
            if (text != null && !text.isBlank()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }
}
