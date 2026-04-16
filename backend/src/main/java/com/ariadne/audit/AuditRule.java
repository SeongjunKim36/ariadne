package com.ariadne.audit;

import java.util.List;

public interface AuditRule {

    String ruleId();

    String name();

    String description();

    RiskLevel riskLevel();

    String category();

    String remediationHint();

    List<AuditFindingCandidate> evaluate(AuditRuleContext context);
}
