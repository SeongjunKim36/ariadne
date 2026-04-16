package com.ariadne.query;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CypherValidator {

    private static final Pattern NODE_LABEL_PATTERN = Pattern.compile("\\([^)]*:(\\w+)");
    private static final Pattern REL_TYPE_PATTERN = Pattern.compile("\\[:(\\w+)");
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\.(\\w+)");
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "CREATE", "MERGE", "DELETE", "DETACH DELETE", "SET ", "REMOVE ", "CALL ", "LOAD CSV", "FOREACH", "DROP "
    );

    private final SchemaContextBuilder schemaContextBuilder;
    private final Neo4jClient neo4jClient;

    public CypherValidator(SchemaContextBuilder schemaContextBuilder, Neo4jClient neo4jClient) {
        this.schemaContextBuilder = schemaContextBuilder;
        this.neo4jClient = neo4jClient;
    }

    public CypherValidationResult validate(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            return CypherValidationResult.failure("빈 Cypher는 실행할 수 없습니다.");
        }

        var normalized = cypher.toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("MATCH") && !normalized.startsWith("OPTIONAL MATCH") && !normalized.startsWith("WITH")) {
            return CypherValidationResult.failure("읽기 전용 MATCH/OPTIONAL MATCH 기반 쿼리만 허용됩니다.");
        }
        for (var keyword : FORBIDDEN_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return CypherValidationResult.failure("허용되지 않은 구문이 포함되어 있습니다: " + keyword.trim());
            }
        }

        var allowedLabels = schemaContextBuilder.nodeLabels();
        for (var label : extractAll(NODE_LABEL_PATTERN, cypher)) {
            if (!allowedLabels.contains(label)) {
                return CypherValidationResult.failure("존재하지 않는 노드 레이블: " + label);
            }
        }

        var allowedRelationshipTypes = schemaContextBuilder.relationshipTypes();
        for (var relationshipType : extractAll(REL_TYPE_PATTERN, cypher)) {
            if (!allowedRelationshipTypes.contains(relationshipType)) {
                return CypherValidationResult.failure("존재하지 않는 관계 타입: " + relationshipType);
            }
        }

        var allowedProperties = schemaContextBuilder.allowedProperties();
        for (var property : extractAll(PROPERTY_PATTERN, cypher)) {
            if (!allowedProperties.contains(property) && !Set.of("size", "count").contains(property)) {
                return CypherValidationResult.failure("허용되지 않은 프로퍼티: " + property);
            }
        }

        try {
            neo4jClient.query("EXPLAIN " + cypher).fetch().all();
        } catch (RuntimeException exception) {
            return CypherValidationResult.failure("EXPLAIN 검증 실패: " + exception.getMessage());
        }

        return CypherValidationResult.success();
    }

    private Set<String> extractAll(Pattern pattern, String input) {
        var values = new LinkedHashSet<String>();
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
