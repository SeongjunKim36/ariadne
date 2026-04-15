package com.ariadne.llm;

import com.ariadne.security.RedactionEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmDataSanitizerTest {

    private final LlmDataSanitizer sanitizer = new LlmDataSanitizer(new RedactionEngine());

    @Test
    void strictLevelAnonymizesIdentifiersAndKeepsGraphStructure() {
        var raw = new GraphData("subgraph:prod", graphPayload());

        var sanitized = sanitizer.sanitize(raw, TransmissionLevel.STRICT);
        var nodes = listOfMaps(sanitized.payload().get("nodes"));
        var edges = listOfMaps(sanitized.payload().get("edges"));
        var ec2Node = findBy(nodes, "id", "EC2-1");
        var subnetNode = findBy(nodes, "id", "SUBNET-1");
        var rdsNode = findBy(nodes, "id", "RDS-1");
        var edge = edges.get(0);

        assertThat(sanitized.scope()).isEqualTo("subgraph:prod");
        assertThat(sanitized.transmissionLevel()).isEqualTo(TransmissionLevel.STRICT);
        assertThat(nodes).extracting(node -> node.get("id"))
                .containsExactly("EC2-1", "SUBNET-1", "RDS-1");
        assertThat(map(ec2Node.get("data")))
                .containsEntry("resourceId", "EC2-1")
                .containsEntry("name", "EC2-1")
                .containsEntry("arn", "EC2-1")
                .containsEntry("instanceType", "t3.micro")
                .doesNotContainKeys("tags", "env");
        assertThat(ec2Node).containsEntry("parentNode", "SUBNET-1");
        assertThat(map(subnetNode.get("data"))).containsEntry("cidrBlock", "10.0.1.0/24");
        assertThat(map(rdsNode.get("data"))).containsEntry("engine", "postgres");
        assertThat(edge)
                .containsEntry("source", "EC2-1")
                .containsEntry("target", "RDS-1")
                .containsEntry("type", "LIKELY_USES");
        assertThat(edge.get("id")).isEqualTo("EDGE-1");
    }

    @Test
    void normalLevelKeepsIdentifiersButExcludesTagsAndEnvironmentVariables() {
        var raw = new GraphData("subgraph:prod", graphPayload());

        var sanitized = sanitizer.sanitize(raw, TransmissionLevel.NORMAL);
        var ec2Node = findBy(listOfMaps(sanitized.payload().get("nodes")), "id", "ec2-node-1");
        var ec2Data = map(ec2Node.get("data"));

        assertThat(sanitized.transmissionLevel()).isEqualTo(TransmissionLevel.NORMAL);
        assertThat(ec2Data)
                .containsEntry("resourceId", "i-0123456789")
                .containsEntry("name", "prod-api")
                .containsEntry("databaseUrl", "jdbc:postgresql://db.internal:5432/app?password=***REDACTED***")
                .doesNotContainKeys("tags", "env");
    }

    @Test
    void verboseLevelKeepsTagsAndRedactsEnvironmentVariableValues() {
        var raw = new GraphData("subgraph:prod", graphPayload());

        var sanitized = sanitizer.sanitize(raw, TransmissionLevel.VERBOSE);
        var ec2Node = findBy(listOfMaps(sanitized.payload().get("nodes")), "id", "ec2-node-1");
        var ec2Data = map(ec2Node.get("data"));
        var env = map(ec2Data.get("env"));
        var tags = map(ec2Data.get("tags"));

        assertThat(sanitized.transmissionLevel()).isEqualTo(TransmissionLevel.VERBOSE);
        assertThat(tags).containsEntry("Service", "api");
        assertThat(env)
                .containsEntry("SPRING_PROFILES_ACTIVE", "prod")
                .containsEntry("DB_PASSWORD", RedactionEngine.REDACTED);
    }

    @Test
    void returnsEmptyPayloadWhenContextDataIsMissing() {
        var sanitized = sanitizer.sanitize(null, TransmissionLevel.STRICT);

        assertThat(sanitized.scope()).isEqualTo("global");
        assertThat(sanitized.transmissionLevel()).isEqualTo(TransmissionLevel.STRICT);
        assertThat(sanitized.payload()).isEmpty();
    }

    private Map<String, Object> graphPayload() {
        return Map.of(
                "nodes", List.of(
                        node(
                                "ec2-node-1",
                                "ec2",
                                "subnet-node-1",
                                Map.of(
                                        "resourceId", "i-0123456789",
                                        "name", "prod-api",
                                        "arn", "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-0123456789",
                                        "instanceType", "t3.micro",
                                        "databaseUrl", "jdbc:postgresql://db.internal:5432/app?password=topsecret",
                                        "tags", Map.of("Service", "api"),
                                        "env", Map.of(
                                                "SPRING_PROFILES_ACTIVE", "prod",
                                                "DB_PASSWORD", "super-secret"
                                        )
                                )
                        ),
                        node(
                                "subnet-node-1",
                                "subnet",
                                null,
                                Map.of(
                                        "resourceId", "subnet-abc123",
                                        "name", "private-a",
                                        "cidrBlock", "10.0.1.0/24"
                                )
                        ),
                        node(
                                "rds-node-1",
                                "rds",
                                "subnet-node-1",
                                Map.of(
                                        "resourceId", "dongne-prod-db",
                                        "name", "dongne-prod-db",
                                        "engine", "postgres"
                                )
                        )
                ),
                "edges", List.of(
                        Map.of(
                                "id", "ec2-node-1->rds-node-1",
                                "source", "ec2-node-1",
                                "target", "rds-node-1",
                                "type", "LIKELY_USES",
                                "data", Map.of("port", 5432)
                        )
                ),
                "metadata", Map.of(
                        "totalNodes", 3,
                        "totalEdges", 1
                )
        );
    }

    private Map<String, Object> node(String id, String type, String parentNode, Map<String, Object> data) {
        var node = new LinkedHashMap<String, Object>();
        node.put("id", id);
        node.put("type", type);
        node.put("data", data);
        node.put("parentNode", parentNode);
        return node;
    }

    private Map<String, Object> findBy(List<Map<String, Object>> values, String key, String expectedValue) {
        return values.stream()
                .filter(value -> expectedValue.equals(value.get(key)))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
