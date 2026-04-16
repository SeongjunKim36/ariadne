package com.ariadne.audit;

import software.amazon.awssdk.services.iam.model.GenerateCredentialReportRequest;
import software.amazon.awssdk.services.iam.model.GetCredentialReportRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class RootAccessKeyAuditRule implements AuditRule {

    @Override
    public String ruleId() {
        return "IAM-007";
    }

    @Override
    public String name() {
        return "루트 계정 Access Key 활성";
    }

    @Override
    public String description() {
        return "루트 계정에 활성 Access Key가 남아 있으면 계정 장악 위험이 커집니다.";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public String category() {
        return "iam";
    }

    @Override
    public String remediationHint() {
        return "루트 계정 Access Key를 삭제하고, 필요한 작업은 관리자 역할 + MFA로 대체하세요.";
    }

    @Override
    public List<AuditFindingCandidate> evaluate(AuditRuleContext context) {
        try {
            context.iamClient().generateCredentialReport(GenerateCredentialReportRequest.builder().build());
            var report = context.iamClient().getCredentialReport(GetCredentialReportRequest.builder().build());
            var csv = report.content().asString(StandardCharsets.UTF_8);
            return parse(csv, context.accountId());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<AuditFindingCandidate> parse(String csv, String accountId) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }

        var lines = csv.split("\\R");
        if (lines.length < 2) {
            return List.of();
        }

        var headers = splitCsvLine(lines[0]);
        var findings = new ArrayList<AuditFindingCandidate>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) {
                continue;
            }
            var values = splitCsvLine(lines[index]);
            var row = toMap(headers, values);
            if (!"<root_account>".equals(row.get("user"))) {
                continue;
            }

            var activeKeys = new ArrayList<String>();
            if (Boolean.parseBoolean(row.getOrDefault("access_key_1_active", "false"))) {
                activeKeys.add("access_key_1");
            }
            if (Boolean.parseBoolean(row.getOrDefault("access_key_2_active", "false"))) {
                activeKeys.add("access_key_2");
            }
            if (activeKeys.isEmpty()) {
                continue;
            }

            findings.add(new AuditFindingCandidate(
                    accountId == null || accountId.isBlank()
                            ? "arn:aws:iam:::root"
                            : "arn:aws:iam::" + accountId + ":root",
                    "root-account",
                    "ACCOUNT_ROOT",
                    null,
                    null,
                    String.join(", ", activeKeys)
            ));
        }
        return List.copyOf(findings);
    }

    private LinkedHashMap<String, String> toMap(List<String> headers, List<String> values) {
        var row = new LinkedHashMap<String, String>();
        for (int index = 0; index < headers.size(); index++) {
            row.put(headers.get(index), index < values.size() ? values.get(index) : "");
        }
        return row;
    }

    private List<String> splitCsvLine(String line) {
        var values = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            var ch = line.charAt(index);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        values.add(current.toString());
        return values;
    }
}
