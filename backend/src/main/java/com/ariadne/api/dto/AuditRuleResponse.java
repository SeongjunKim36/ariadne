package com.ariadne.api.dto;

import com.ariadne.audit.AuditRule;
import com.ariadne.audit.RiskLevel;

public record AuditRuleResponse(
        String ruleId,
        String name,
        String description,
        RiskLevel riskLevel,
        String category,
        String remediationHint
) {

    public static AuditRuleResponse from(AuditRule rule) {
        return new AuditRuleResponse(
                rule.ruleId(),
                rule.name(),
                rule.description(),
                rule.riskLevel(),
                rule.category(),
                rule.remediationHint()
        );
    }
}
