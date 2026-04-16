package com.ariadne.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CypherAuditRule implements AuditRule {

    private final String ruleId;
    private final String name;
    private final String description;
    private final RiskLevel riskLevel;
    private final String category;
    private final String remediationHint;
    private final String cypher;

    CypherAuditRule(
            String ruleId,
            String name,
            String description,
            RiskLevel riskLevel,
            String category,
            String remediationHint,
            String cypher
    ) {
        this.ruleId = ruleId;
        this.name = name;
        this.description = description;
        this.riskLevel = riskLevel;
        this.category = category;
        this.remediationHint = remediationHint;
        this.cypher = cypher;
    }

    @Override
    public String ruleId() {
        return ruleId;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public RiskLevel riskLevel() {
        return riskLevel;
    }

    @Override
    public String category() {
        return category;
    }

    @Override
    public String remediationHint() {
        return remediationHint;
    }

    @Override
    public List<AuditFindingCandidate> evaluate(AuditRuleContext context) {
        var findings = new ArrayList<AuditFindingCandidate>();
        for (var row : context.rows(cypher)) {
            findings.add(new AuditFindingCandidate(
                    stringValue(row, "resourceArn"),
                    stringValue(row, "resourceName"),
                    stringValue(row, "resourceType"),
                    stringValue(row, "secondaryArn"),
                    stringValue(row, "secondaryName"),
                    stringValue(row, "detail")
            ));
        }
        return List.copyOf(findings);
    }

    private String stringValue(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
