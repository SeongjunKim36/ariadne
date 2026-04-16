package com.ariadne.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "llm_audit_log")
public class LlmAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime timestamp;

    @Column(nullable = false, length = 24)
    private String transmissionLevel;

    @Column(nullable = false, length = 2000)
    private String queryText;

    @Column(nullable = false, length = 255)
    private String dataScope;

    @Column(nullable = false)
    private int nodeCount;

    @Column(nullable = false)
    private int edgeCount;

    private Integer tokensSent;

    private Integer tokensReceived;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LlmAuditStatus status;

    @Column(length = 2048)
    private String failureMessage;

    protected LlmAuditLog() {
    }

    public LlmAuditLog(
            OffsetDateTime timestamp,
            String transmissionLevel,
            String queryText,
            String dataScope,
            int nodeCount,
            int edgeCount,
            Integer tokensSent,
            Integer tokensReceived,
            LlmAuditStatus status,
            String failureMessage
    ) {
        this.timestamp = timestamp;
        this.transmissionLevel = transmissionLevel;
        this.queryText = queryText;
        this.dataScope = dataScope;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.tokensSent = tokensSent;
        this.tokensReceived = tokensReceived;
        this.status = status;
        this.failureMessage = failureMessage;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public String getTransmissionLevel() {
        return transmissionLevel;
    }

    public String getQueryText() {
        return queryText;
    }

    public String getDataScope() {
        return dataScope;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public Integer getTokensSent() {
        return tokensSent;
    }

    public Integer getTokensReceived() {
        return tokensReceived;
    }

    public LlmAuditStatus getStatus() {
        return status;
    }

    public String getFailureMessage() {
        return failureMessage;
    }
}
