package com.ariadne.snapshot;

import com.ariadne.api.dto.GraphResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiffCalculatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DiffCalculator diffCalculator = new DiffCalculator(objectMapper);

    @Test
    void computesNodeAndEdgeDiffsWhileIgnoringVolatileNodeFields() throws Exception {
        var arnA = "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234";
        var arnB = "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-5678";
        var arnSg = "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234";

        var base = snapshot(graph(
                List.of(
                        node(arnA, "ec2", Map.of(
                                "arn", arnA,
                                "name", "web-a",
                                "resourceType", "EC2",
                                "instanceType", "t3.micro",
                                "collectedAt", "2026-04-16T08:00:00Z"
                        )),
                        node(arnSg, "sg", Map.of(
                                "arn", arnSg,
                                "name", "web-sg",
                                "resourceType", "SECURITY_GROUP"
                        ))
                ),
                List.of(
                        edge(arnA, arnSg, "HAS_SG", Map.of("port", 443))
                )
        ));

        var target = snapshot(graph(
                List.of(
                        node(arnA, "ec2", Map.of(
                                "arn", arnA,
                                "name", "web-a",
                                "resourceType", "EC2",
                                "instanceType", "t3.small",
                                "collectedAt", "2026-04-16T09:00:00Z"
                        )),
                        node(arnSg, "sg", Map.of(
                                "arn", arnSg,
                                "name", "web-sg",
                                "resourceType", "SECURITY_GROUP"
                        )),
                        node(arnB, "ec2", Map.of(
                                "arn", arnB,
                                "name", "worker-a",
                                "resourceType", "EC2"
                        ))
                ),
                List.of(
                        edge(arnA, arnSg, "HAS_SG", Map.of("port", 8443)),
                        edge(arnB, arnSg, "HAS_SG", Map.of("port", 443))
                )
        ));

        var diff = diffCalculator.compute(base, target);

        assertThat(diff.getAddedCount()).isEqualTo(2);
        assertThat(diff.getModifiedCount()).isEqualTo(2);
        assertThat(diff.getRemovedCount()).isZero();
        assertThat(diff.getTotalChanges()).isEqualTo(4);

        List<NodeChange> modifiedNodes = objectMapper.readValue(
                diff.getModifiedNodesJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, NodeChange.class)
        );
        List<EdgeChange> addedEdges = objectMapper.readValue(
                diff.getAddedEdgesJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EdgeChange.class)
        );
        List<EdgeChange> modifiedEdges = objectMapper.readValue(
                diff.getModifiedEdgesJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, EdgeChange.class)
        );

        assertThat(modifiedNodes).hasSize(1);
        assertThat(modifiedNodes.get(0)).satisfies(change -> {
            assertThat(change.arn()).isEqualTo(arnA);
            assertThat(change.propertyChanges()).containsOnlyKeys("instanceType");
            assertThat(change.propertyChanges().get("instanceType").beforeValue()).isEqualTo("t3.micro");
            assertThat(change.propertyChanges().get("instanceType").afterValue()).isEqualTo("t3.small");
        });
        assertThat(addedEdges).hasSize(1);
        assertThat(addedEdges.get(0)).satisfies(change -> {
            assertThat(change.sourceArn()).isEqualTo(arnB);
            assertThat(change.targetArn()).isEqualTo(arnSg);
            assertThat(change.relationshipType()).isEqualTo("HAS_SG");
        });
        assertThat(modifiedEdges).hasSize(1);
        assertThat(modifiedEdges.get(0)).satisfies(change -> {
            assertThat(change.propertyChanges()).containsOnlyKeys("port");
            assertThat(change.propertyChanges().get("port").beforeValue()).isEqualTo(443);
            assertThat(change.propertyChanges().get("port").afterValue()).isEqualTo(8443);
        });
    }

    private Snapshot snapshot(GraphResponse graph) throws Exception {
        return new Snapshot(
                OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                "123456789012",
                "ap-northeast-2",
                graph.nodes().size(),
                graph.edges().size(),
                1200,
                SnapshotTrigger.SCHEDULED,
                null,
                objectMapper.writeValueAsString(graph),
                "{}"
        );
    }

    private GraphResponse graph(List<GraphResponse.GraphNode> nodes, List<GraphResponse.GraphEdge> edges) {
        return new GraphResponse(nodes, edges, new GraphResponse.GraphMetadata(nodes.size(), edges.size(), OffsetDateTime.now(), 1200));
    }

    private GraphResponse.GraphNode node(String id, String type, Map<String, Object> data) {
        return new GraphResponse.GraphNode(id, type, data, null);
    }

    private GraphResponse.GraphEdge edge(String source, String target, String type, Map<String, Object> data) {
        return new GraphResponse.GraphEdge(source + "|" + type + "|" + target, source, target, type, data);
    }
}
