package com.ariadne.query;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueryTemplateEngine {

    private static final Pattern RESOURCE_NAME_PATTERN = Pattern.compile("'([^']+)'|\"([^\"]+)\"");

    public Optional<String> generateCypher(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.empty();
        }

        var query = rawQuery.trim();
        var normalized = query.toLowerCase(Locale.ROOT);

        if ((normalized.contains("prod") && normalized.contains("staging"))
                || normalized.contains("차이")) {
            return Optional.of("""
                    MATCH (n:AwsResource)
                    WHERE coalesce(n.stale, false) = false
                      AND n.environment IN ['prod', 'staging']
                    RETURN n.environment AS environment,
                           n.resourceType AS resourceType,
                           count(*) AS count
                    ORDER BY resourceType, environment
                    LIMIT 50
                    """);
        }

        if (normalized.contains("0.0.0.0/0") || (normalized.contains("sg") && (normalized.contains("위험") || normalized.contains("열린")))) {
            return Optional.of("""
                    MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[:ALLOWS_TO]->(sg:SecurityGroup)
                    OPTIONAL MATCH (resource:AwsResource)-[:HAS_SG]->(sg)
                    WHERE coalesce(sg.stale, false) = false
                      AND coalesce(resource.stale, false) = false
                    RETURN DISTINCT sg.arn AS resourceArn,
                           coalesce(sg.name, sg.groupId) AS resourceName,
                           sg.resourceType AS resourceType,
                           sg.inboundRuleCount AS inboundRuleCount,
                           collect(DISTINCT coalesce(resource.name, resource.resourceId))[0..5] AS attachedResources
                    LIMIT 25
                    """);
        }

        if (normalized.contains("쓰") || normalized.contains("use") || normalized.contains("consumer")) {
            var target = extractResourceName(query, normalized);
            if (target != null) {
                return Optional.of("""
                        MATCH (db:RdsInstance)
                        WHERE toLower(coalesce(db.name, '')) = toLower('%s')
                           OR toLower(coalesce(db.resourceId, '')) = toLower('%s')
                        OPTIONAL MATCH (consumer:AwsResource)-[rel:LIKELY_USES]->(db)
                        WHERE consumer.resourceType IN ['EC2', 'ECS_SERVICE', 'LAMBDA_FUNCTION']
                        RETURN consumer.arn AS resourceArn,
                               coalesce(consumer.name, consumer.resourceId) AS resourceName,
                               consumer.resourceType AS resourceType,
                               db.arn AS databaseArn,
                               db.name AS databaseName,
                               rel.port AS port
                        LIMIT 25
                        """.formatted(escape(target), escape(target)));
            }
        }

        if (normalized.contains("뭐가 돌아가") || normalized.contains("running") || normalized.contains("what is running")) {
            var environment = extractEnvironment(normalized);
            return Optional.of("""
                    MATCH (resource:AwsResource)
                    WHERE coalesce(resource.stale, false) = false
                      AND resource.environment = '%s'
                    RETURN resource.arn AS resourceArn,
                           coalesce(resource.name, resource.resourceId) AS resourceName,
                           resource.resourceType AS resourceType,
                           resource.state AS state,
                           resource.status AS status
                    ORDER BY resource.resourceType, resource.name
                    LIMIT 25
                    """.formatted(escape(environment == null ? "prod" : environment)));
        }

        var term = normalized.replace("보여줘", "").replace("show me", "").trim();
        if (term.length() >= 2) {
            return Optional.of("""
                    MATCH (resource:AwsResource)
                    WHERE coalesce(resource.stale, false) = false
                      AND (
                           toLower(coalesce(resource.name, '')) CONTAINS '%s'
                           OR toLower(coalesce(resource.resourceId, '')) CONTAINS '%s'
                           OR toLower(coalesce(resource.environment, '')) CONTAINS '%s'
                      )
                    RETURN resource.arn AS resourceArn,
                           coalesce(resource.name, resource.resourceId) AS resourceName,
                           resource.resourceType AS resourceType,
                           resource.environment AS environment
                    LIMIT 25
                    """.formatted(escape(term), escape(term), escape(term)));
        }

        return Optional.empty();
    }

    public List<String> examples() {
        return List.of(
                "prod에 뭐가 돌아가고 있어?",
                "dongne-prod-db를 쓰는 서비스 보여줘",
                "prod vs staging 차이가 뭐야?",
                "0.0.0.0/0으로 열린 SG 중 위험한 거 보여줘"
        );
    }

    private String extractEnvironment(String normalized) {
        if (normalized.contains("staging")) {
            return "staging";
        }
        if (normalized.contains("dev")) {
            return "dev";
        }
        if (normalized.contains("prod")) {
            return "prod";
        }
        return null;
    }

    private String extractResourceName(String query, String normalized) {
        var matcher = RESOURCE_NAME_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }
        if (normalized.contains("db")) {
            return query.replaceAll(".*?([A-Za-z0-9_-]*db[A-Za-z0-9_-]*).*", "$1");
        }
        return null;
    }

    private String escape(String value) {
        return value.replace("'", "\\'");
    }
}
