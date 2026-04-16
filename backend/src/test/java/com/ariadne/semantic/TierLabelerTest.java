package com.ariadne.semantic;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TierLabelerTest {

    @Mock
    private SemanticGraphService semanticGraphService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Neo4jClient neo4jClient;

    @Mock
    private LlmGateway llmGateway;

    @Test
    void combinesRuleLabelsWithLlmSuggestions() {
        var graph = new GraphResponse(
                List.of(
                        new GraphResponse.GraphNode(
                                "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-web",
                                "alb",
                                Map.of("scheme", "internet-facing", "resourceType", "LOAD_BALANCER", "name", "prod-web"),
                                null
                        ),
                        new GraphResponse.GraphNode(
                                "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-api",
                                "ec2",
                                Map.of("resourceType", "EC2", "name", "prod-api"),
                                null
                        ),
                        new GraphResponse.GraphNode(
                                "arn:aws:rds:ap-northeast-2:123456789012:db:prod-db",
                                "rds",
                                Map.of("resourceType", "RDS", "name", "prod-db"),
                                null
                        ),
                        new GraphResponse.GraphNode(
                                "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-worker",
                                "ec2",
                                Map.of("resourceType", "EC2", "name", "payment-worker"),
                                null
                        )
                ),
                List.of(
                        new GraphResponse.GraphEdge(
                                "lb-routes-api",
                                "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-web",
                                "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-api",
                                "ROUTES_TO",
                                Map.of()
                        )
                ),
                new GraphResponse.GraphMetadata(4, 1, null, 0)
        );
        when(semanticGraphService.fetchFullGraph()).thenReturn(graph);
        when(llmGateway.queryText(anyString())).thenReturn("""
                [
                  {"arn": "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-worker", "tier": "batch-tier", "confidence": 0.84}
                ]
                """);

        var labeler = new TierLabeler(semanticGraphService, neo4jClient, llmGateway, new ObjectMapper());

        var labels = labeler.generateLabels();

        assertThat(labels)
                .extracting(label -> label.arn() + ":" + label.tier() + ":" + label.source())
                .contains(
                        "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-web:web-tier:rule",
                        "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-api:web-tier:rule",
                        "arn:aws:rds:ap-northeast-2:123456789012:db:prod-db:db-tier:rule",
                        "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-worker:batch-tier:llm"
                );
    }

    @Test
    void fallsBackToHeuristicsWhenLlmIsUnavailable() {
        var graph = new GraphResponse(
                List.of(
                        new GraphResponse.GraphNode(
                                "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-cache",
                                "ec2",
                                Map.of("resourceType", "EC2", "name", "redis-cache"),
                                null
                        )
                ),
                List.of(),
                new GraphResponse.GraphMetadata(1, 0, null, 0)
        );
        when(semanticGraphService.fetchFullGraph()).thenReturn(graph);
        when(llmGateway.queryText(anyString())).thenThrow(new IllegalStateException("Claude unavailable"));

        var labeler = new TierLabeler(semanticGraphService, neo4jClient, llmGateway, new ObjectMapper());

        var labels = labeler.generateLabels();

        assertThat(labels).singleElement().satisfies(label -> {
            assertThat(label.tier()).isEqualTo("cache-tier");
            assertThat(label.source()).isEqualTo("heuristic");
        });
    }
}
