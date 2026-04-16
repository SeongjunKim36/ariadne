package com.ariadne.audit;

public enum RiskLevel {
    HIGH(3),
    MEDIUM(2),
    LOW(1);

    private final int severity;

    RiskLevel(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }
}
