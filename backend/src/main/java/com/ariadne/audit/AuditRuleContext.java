package com.ariadne.audit;

import org.springframework.data.neo4j.core.Neo4jClient;
import software.amazon.awssdk.services.iam.IamClient;

import java.util.Collection;
import java.util.Map;

public final class AuditRuleContext {

    private final Neo4jClient neo4jClient;
    private final IamClient iamClient;
    private final String accountId;

    public AuditRuleContext(Neo4jClient neo4jClient, IamClient iamClient, String accountId) {
        this.neo4jClient = neo4jClient;
        this.iamClient = iamClient;
        this.accountId = accountId;
    }

    public Collection<Map<String, Object>> rows(String cypher) {
        return neo4jClient.query(cypher).fetch().all();
    }

    public IamClient iamClient() {
        return iamClient;
    }

    public String accountId() {
        return accountId;
    }
}
