package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmFieldAllowlistTest {

    @Test
    void keepsOnlyConfiguredFieldsForNormalTraffic() {
        var properties = new AriadneProperties();
        properties.getLlm().setAllowedFields(List.of("resourceId", "name", "port"));
        var allowlist = new LlmFieldAllowlist(properties);

        var filtered = allowlist.apply(new SanitizedGraphData(
                "subgraph:prod",
                TransmissionLevel.NORMAL,
                Map.of(
                        "nodes", List.of(
                                Map.of(
                                        "id", "ec2-node-1",
                                        "type", "ec2",
                                        "parentNode", "subnet-node-1",
                                        "data", Map.of(
                                                "resourceId", "i-1234",
                                                "name", "prod-api",
                                                "databaseUrl", "jdbc:postgresql://db.internal:5432/app?password=***REDACTED***"
                                        )
                                )
                        ),
                        "edges", List.of(
                                Map.of(
                                        "id", "edge-1",
                                        "source", "ec2-node-1",
                                        "target", "rds-node-1",
                                        "type", "LIKELY_USES",
                                        "data", Map.of(
                                                "port", 5432,
                                                "confidence", "medium"
                                        )
                                )
                        ),
                        "metadata", Map.of("totalNodes", 1, "totalEdges", 1)
                )
        ));

        @SuppressWarnings("unchecked")
        var nodeData = (Map<String, Object>) ((Map<String, Object>) ((List<?>) filtered.payload().get("nodes")).get(0)).get("data");
        @SuppressWarnings("unchecked")
        var edgeData = (Map<String, Object>) ((Map<String, Object>) ((List<?>) filtered.payload().get("edges")).get(0)).get("data");

        assertThat(nodeData)
                .containsEntry("resourceId", "i-1234")
                .containsEntry("name", "prod-api")
                .doesNotContainKey("databaseUrl");
        assertThat(edgeData)
                .containsEntry("port", 5432)
                .doesNotContainKey("confidence");
    }

    @Test
    void keepsVerboseExtrasWhenTransmissionLevelIsVerbose() {
        var properties = new AriadneProperties();
        properties.getLlm().setAllowedFields(List.of("resourceId"));
        properties.getLlm().setVerboseAdditionalFields(List.of("tags", "env"));
        var allowlist = new LlmFieldAllowlist(properties);

        var filtered = allowlist.apply(new SanitizedGraphData(
                "subgraph:prod",
                TransmissionLevel.VERBOSE,
                Map.of(
                        "nodes", List.of(verboseNode())
                )
        ));

        @SuppressWarnings("unchecked")
        var nodeData = (Map<String, Object>) ((Map<String, Object>) ((List<?>) filtered.payload().get("nodes")).get(0)).get("data");

        assertThat(nodeData)
                .containsEntry("resourceId", "i-1234")
                .containsKey("tags")
                .containsKey("env");
    }

    private Map<String, Object> verboseNode() {
        var node = new LinkedHashMap<String, Object>();
        node.put("id", "ec2-node-1");
        node.put("type", "ec2");
        node.put("parentNode", null);
        node.put("data", Map.of(
                "resourceId", "i-1234",
                "tags", Map.of("Service", "api"),
                "env", Map.of("DB_PASSWORD", "***REDACTED***")
        ));
        return node;
    }
}
