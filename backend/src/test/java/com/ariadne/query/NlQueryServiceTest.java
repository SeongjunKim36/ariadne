package com.ariadne.query;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.llm.LlmGateway;
import com.ariadne.semantic.SemanticGraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NlQueryServiceTest {

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private SchemaContextBuilder schemaContextBuilder;

    @Mock
    private CypherValidator cypherValidator;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Neo4jClient neo4jClient;

    @Mock
    private QueryTemplateEngine queryTemplateEngine;

    @Mock
    private SemanticGraphService semanticGraphService;

    @Test
    void returnsSuggestionsWhenQueryIsValidButNoRowsMatch() {
        var service = service();
        var cypher = "MATCH (resource:AwsResource) RETURN resource.arn AS resourceArn LIMIT 25";

        when(queryTemplateEngine.generateCypher("prod에 뭐가 돌아가고 있어?")).thenReturn(Optional.of(cypher));
        when(queryTemplateEngine.examples()).thenReturn(List.of("prod에 뭐가 돌아가고 있어?"));
        when(cypherValidator.validate(cypher)).thenReturn(CypherValidationResult.success());
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(List.of());
        when(semanticGraphService.fetchFullGraph()).thenReturn(emptyGraph());

        var response = service.query("prod에 뭐가 돌아가고 있어?");

        assertThat(response.success()).isTrue();
        assertThat(response.results()).isEmpty();
        assertThat(response.error()).isNull();
        assertThat(response.suggestions()).contains("prod에 뭐가 돌아가고 있어?");
        assertThat(response.explanation()).contains("조건에 맞는 리소스를 찾지 못했습니다");
    }

    @Test
    void asksForClarificationWhenMultipleSameNameMatchesExistWithoutEnvironmentHint() {
        var service = service();
        var cypher = "MATCH (resource:AwsResource) RETURN resource.arn AS resourceArn LIMIT 25";
        var results = List.of(
                Map.<String, Object>of(
                        "resourceArn", "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod",
                        "resourceName", "api-server",
                        "resourceType", "EC2",
                        "environment", "prod"
                ),
                Map.<String, Object>of(
                        "resourceArn", "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-staging",
                        "resourceName", "api-server",
                        "resourceType", "EC2",
                        "environment", "staging"
                )
        );

        when(queryTemplateEngine.generateCypher("api-server 보여줘")).thenReturn(Optional.of(cypher));
        when(cypherValidator.validate(cypher)).thenReturn(CypherValidationResult.success());
        when(neo4jClient.query(anyString()).fetch().all()).thenReturn(results);
        when(semanticGraphService.buildSubgraph(
                eq(Set.of(
                        "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod",
                        "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-staging"
                )),
                eq(1)
        )).thenReturn(emptyGraph());

        var response = service.query("api-server 보여줘");

        assertThat(response.success()).isTrue();
        assertThat(response.clarificationNeeded()).isTrue();
        assertThat(response.clarificationOptions()).hasSize(2);
        assertThat(response.generatedCypher()).contains("MATCH");
    }

    private NlQueryService service() {
        return new NlQueryService(
                llmGateway,
                schemaContextBuilder,
                cypherValidator,
                neo4jClient,
                queryTemplateEngine,
                semanticGraphService
        );
    }

    private GraphResponse emptyGraph() {
        return new GraphResponse(
                List.of(),
                List.of(),
                new GraphResponse.GraphMetadata(0, 0, null, 0)
        );
    }
}
