package com.ariadne.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_finding")
public class AuditFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_run_id", nullable = false)
    private AuditRun auditRun;

    @Column(nullable = false, length = 32)
    private String ruleId;

    @Column(nullable = false, length = 255)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskLevel riskLevel;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 1024)
    private String resourceArn;

    @Column(nullable = false, length = 512)
    private String resourceName;

    @Column(nullable = false, length = 64)
    private String resourceType;

    @Column(length = 1024)
    private String secondaryArn;

    @Column(length = 512)
    private String secondaryName;

    @Column(length = 2048)
    private String detail;

    @Column(nullable = false, length = 2048)
    private String remediationHint;

    protected AuditFinding() {
    }

    public AuditFinding(
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
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.riskLevel = riskLevel;
        this.category = category;
        this.resourceArn = resourceArn;
        this.resourceName = resourceName;
        this.resourceType = resourceType;
        this.secondaryArn = secondaryArn;
        this.secondaryName = secondaryName;
        this.detail = detail;
        this.remediationHint = remediationHint;
    }

    void attachTo(AuditRun auditRun) {
        this.auditRun = auditRun;
    }

    public Long getId() {
        return id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getCategory() {
        return category;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getSecondaryArn() {
        return secondaryArn;
    }

    public String getSecondaryName() {
        return secondaryName;
    }

    public String getDetail() {
        return detail;
    }

    public String getRemediationHint() {
        return remediationHint;
    }
}
