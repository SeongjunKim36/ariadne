package com.ariadne.audit;

import com.ariadne.api.dto.AuditExplanationResponse;
import com.ariadne.api.dto.AuditReportResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class AuditExplanationService {

    private final LlmAuditAssistant llmAuditAssistant;

    public AuditExplanationService(LlmAuditAssistant llmAuditAssistant) {
        this.llmAuditAssistant = llmAuditAssistant;
    }

    public AuditExplanationResponse explain(AuditReportResponse report) {
        if (report == null || report.findings().isEmpty()) {
            return new AuditExplanationResponse(
                    OffsetDateTime.now(),
                    "최근 감사 결과에서 설명할 finding이 없습니다.",
                    List.of(),
                    List.of("먼저 /api/audit/run 으로 감사를 실행하세요.")
            );
        }

        try {
            return llmAuditAssistant.explain(report);
        } catch (RuntimeException exception) {
            // Audit explanations should always be available. If LLM is unavailable, fall back to deterministic text.
        }

        var topFindings = report.findings().stream()
                .sorted(Comparator.comparingInt((com.ariadne.api.dto.AuditFindingResponse finding) -> finding.riskLevel().severity()).reversed())
                .limit(3)
                .toList();

        var priorities = new ArrayList<String>();
        var actions = new ArrayList<String>();
        for (var finding : topFindings) {
            priorities.add("%s · %s · %s".formatted(
                    finding.riskLevel().name(),
                    finding.ruleId(),
                    finding.resourceName()
            ));
            actions.add(finding.remediationHint());
        }

        var summary = "최근 감사에서 %d건의 finding이 발견됐습니다. HIGH %d건, MEDIUM %d건, LOW %d건이며, "
                .formatted(report.totalFindings(), report.highCount(), report.mediumCount(), report.lowCount())
                + topFindings.stream()
                .map(finding -> "%s(%s)".formatted(finding.resourceName(), finding.ruleId()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("대표 위험 항목이 없습니다.")
                + " 순으로 우선 조치하는 흐름을 권장합니다.";

        return new AuditExplanationResponse(
                OffsetDateTime.now(),
                summary,
                List.copyOf(priorities),
                actions.stream()
                        .map(action -> action.trim())
                        .filter(action -> !action.isEmpty())
                        .distinct()
                        .toList()
        );
    }
}
