package com.ariadne.audit;

public record AuditFindingCandidate(
        String resourceArn,
        String resourceName,
        String resourceType,
        String secondaryArn,
        String secondaryName,
        String detail
) {
}
