package com.ariadne.api.dto;

import com.ariadne.audit.AuditFinding;
import com.ariadne.audit.RiskLevel;

public record AuditFindingResponse(
        Long id,
        String ruleId,
        String ruleName,
        RiskLevel riskLevel,
        String category,
        String resourceArn,
        String resourceName,
        String resourceType,
        String secondaryArn,
        String secondaryName,
        String detail,
        String remediationHint
) {

    public static AuditFindingResponse from(AuditFinding finding) {
        return new AuditFindingResponse(
                finding.getId(),
                finding.getRuleId(),
                finding.getRuleName(),
                finding.getRiskLevel(),
                finding.getCategory(),
                finding.getResourceArn(),
                finding.getResourceName(),
                finding.getResourceType(),
                finding.getSecondaryArn(),
                finding.getSecondaryName(),
                finding.getDetail(),
                finding.getRemediationHint()
        );
    }
}
