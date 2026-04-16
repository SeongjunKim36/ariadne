package com.ariadne.audit;

import com.ariadne.api.dto.AuditFindingResponse;
import com.ariadne.api.dto.AuditReportResponse;
import com.ariadne.api.dto.AuditRuleResponse;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.iam.IamClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class AuditEngine {

    private final List<AuditRule> rules;
    private final Neo4jClient neo4jClient;
    private final IamClient iamClient;
    private final AuditRunRepository auditRunRepository;
    private final AuditFindingRepository auditFindingRepository;

    public AuditEngine(
            List<AuditRule> rules,
            Neo4jClient neo4jClient,
            IamClient iamClient,
            AuditRunRepository auditRunRepository,
            AuditFindingRepository auditFindingRepository
    ) {
        this.rules = rules.stream()
                .sorted(Comparator.comparing(AuditRule::ruleId))
                .toList();
        this.neo4jClient = neo4jClient;
        this.iamClient = iamClient;
        this.auditRunRepository = auditRunRepository;
        this.auditFindingRepository = auditFindingRepository;
    }

    @Transactional
    public AuditReportResponse runFullAudit() {
        var accountId = neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE coalesce(n.stale, false) = false
                        RETURN n.accountId AS accountId
                        ORDER BY n.collectedAt DESC
                        LIMIT 1
                        """)
                .fetch()
                .one()
                .map(row -> (String) row.get("accountId"))
                .orElse(null);
        var context = new AuditRuleContext(neo4jClient, iamClient, accountId);

        var allFindings = rules.stream()
                .flatMap(rule -> rule.evaluate(context).stream()
                        .map(candidate -> toEntity(rule, candidate)))
                .sorted(findingComparator())
                .toList();

        var auditRun = new AuditRun(
                OffsetDateTime.now(ZoneOffset.UTC),
                allFindings.size(),
                countByLevel(allFindings, RiskLevel.HIGH),
                countByLevel(allFindings, RiskLevel.MEDIUM),
                countByLevel(allFindings, RiskLevel.LOW)
        );
        for (var finding : allFindings) {
            auditRun.addFinding(finding);
        }
        return AuditReportResponse.from(auditRunRepository.save(auditRun));
    }

    @Transactional(readOnly = true)
    public Optional<AuditReportResponse> latestReport() {
        return auditRunRepository.findTopByOrderByRunAtDesc()
                .map(AuditReportResponse::from);
    }

    @Transactional(readOnly = true)
    public List<AuditFindingResponse> latestFindings(String level, String category) {
        var latestRun = auditRunRepository.findTopByOrderByRunAtDesc()
                .orElseThrow(() -> new NoSuchElementException("No audit report has been run yet"));

        return auditFindingRepository.findByAuditRunId(latestRun.getId()).stream()
                .filter(finding -> matchesLevel(finding, level))
                .filter(finding -> matchesCategory(finding, category))
                .sorted(findingComparator())
                .map(AuditFindingResponse::from)
                .toList();
    }

    public List<AuditRuleResponse> rules() {
        return rules.stream()
                .map(AuditRuleResponse::from)
                .toList();
    }

    private AuditFinding toEntity(AuditRule rule, AuditFindingCandidate candidate) {
        return new AuditFinding(
                rule.ruleId(),
                rule.name(),
                rule.riskLevel(),
                rule.category(),
                blankFallback(candidate.resourceArn(), "unknown"),
                blankFallback(candidate.resourceName(), "unknown"),
                blankFallback(candidate.resourceType(), "unknown"),
                candidate.secondaryArn(),
                candidate.secondaryName(),
                candidate.detail(),
                rule.remediationHint()
        );
    }

    private Comparator<AuditFinding> findingComparator() {
        return Comparator
                .comparingInt((AuditFinding finding) -> finding.getRiskLevel().severity()).reversed()
                .thenComparing(AuditFinding::getCategory, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AuditFinding::getRuleId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AuditFinding::getResourceName, String.CASE_INSENSITIVE_ORDER);
    }

    private int countByLevel(List<AuditFinding> findings, RiskLevel riskLevel) {
        return (int) findings.stream()
                .filter(finding -> finding.getRiskLevel() == riskLevel)
                .count();
    }

    private boolean matchesLevel(AuditFinding finding, String level) {
        if (level == null || level.isBlank()) {
            return true;
        }
        return finding.getRiskLevel().name().equalsIgnoreCase(level);
    }

    private boolean matchesCategory(AuditFinding finding, String category) {
        if (category == null || category.isBlank() || "all".equalsIgnoreCase(category)) {
            return true;
        }
        return finding.getCategory().toLowerCase(Locale.ROOT).equals(category.toLowerCase(Locale.ROOT));
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
