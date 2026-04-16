package com.ariadne.api.dto;

import com.ariadne.audit.AuditRun;

import java.time.OffsetDateTime;
import java.util.List;

public record AuditReportResponse(
        OffsetDateTime runAt,
        int totalFindings,
        int highCount,
        int mediumCount,
        int lowCount,
        List<AuditFindingResponse> findings
) {

    public static AuditReportResponse from(AuditRun auditRun) {
        return new AuditReportResponse(
                auditRun.getRunAt(),
                auditRun.getTotalFindings(),
                auditRun.getHighCount(),
                auditRun.getMediumCount(),
                auditRun.getLowCount(),
                auditRun.getFindings().stream()
                        .map(AuditFindingResponse::from)
                        .toList()
        );
    }
}
