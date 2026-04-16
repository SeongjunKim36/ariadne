package com.ariadne.audit;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "audit_run")
public class AuditRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime runAt;

    @Column(nullable = false)
    private int totalFindings;

    @Column(nullable = false)
    private int highCount;

    @Column(nullable = false)
    private int mediumCount;

    @Column(nullable = false)
    private int lowCount;

    @OneToMany(mappedBy = "auditRun", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<AuditFinding> findings = new ArrayList<>();

    protected AuditRun() {
    }

    public AuditRun(OffsetDateTime runAt, int totalFindings, int highCount, int mediumCount, int lowCount) {
        this.runAt = runAt;
        this.totalFindings = totalFindings;
        this.highCount = highCount;
        this.mediumCount = mediumCount;
        this.lowCount = lowCount;
    }

    public void addFinding(AuditFinding finding) {
        findings.add(finding);
        finding.attachTo(this);
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getRunAt() {
        return runAt;
    }

    public int getTotalFindings() {
        return totalFindings;
    }

    public int getHighCount() {
        return highCount;
    }

    public int getMediumCount() {
        return mediumCount;
    }

    public int getLowCount() {
        return lowCount;
    }

    public List<AuditFinding> getFindings() {
        return List.copyOf(findings);
    }
}
