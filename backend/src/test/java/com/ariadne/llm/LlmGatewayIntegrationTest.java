package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class LlmGatewayIntegrationTest {

    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(
            DockerImageName.parse("neo4j:5.26.0")
    );

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    static {
        NEO4J.start();
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", NEO4J::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", NEO4J::getAdminPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("aws.region", () -> "ap-northeast-2");
        registry.add("aws.access-key-id", () -> "test");
        registry.add("aws.secret-access-key", () -> "test");
    }

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private AriadneProperties ariadneProperties;

    @Autowired
    private LlmAuditLogRepository llmAuditLogRepository;

    @MockBean
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        llmAuditLogRepository.deleteAll();
        ariadneProperties.getLlm().setTransmissionLevel("verbose");
        ariadneProperties.getLlm().setAllowedFields(List.of("resourceId", "name", "engine", "port"));
        ariadneProperties.getLlm().setVerboseAdditionalFields(List.of("tags", "env"));
    }

    @Test
    void recordsSuccessfulVerboseRequestsWithAllowlistedPayload() {
        when(llmClient.query(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, LlmRequest.class);
            @SuppressWarnings("unchecked")
            var nodeData = (Map<String, Object>) ((Map<String, Object>) ((List<?>) request.contextData().payload().get("nodes")).get(0)).get("data");

            assertThat(nodeData)
                    .containsEntry("resourceId", "i-0123456789")
                    .containsEntry("name", "prod-api")
                    .containsKey("tags")
                    .containsKey("env")
                    .doesNotContainKey("databaseUrl");
            assertThat((Map<String, Object>) nodeData.get("env"))
                    .containsEntry("DB_PASSWORD", "***REDACTED***");
            return "answer";
        });

        var response = llmGateway.query("prod 연결 설명", new GraphData("subgraph:prod", graphPayload()));

        assertThat(response).isEqualTo("answer");
        var logs = llmAuditLogRepository.findAllByOrderByTimestampDesc();
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getStatus()).isEqualTo(LlmAuditStatus.SUCCEEDED);
            assertThat(log.getTransmissionLevel()).isEqualTo("verbose");
            assertThat(log.getNodeCount()).isEqualTo(2);
            assertThat(log.getEdgeCount()).isEqualTo(1);
            assertThat(log.getFailureMessage()).isNull();
        });
    }

    @Test
    void recordsFailedStrictRequestsWithAnonymizedIdentifiers() {
        ariadneProperties.getLlm().setTransmissionLevel(null);

        when(llmClient.query(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, LlmRequest.class);
            @SuppressWarnings("unchecked")
            var nodeData = (Map<String, Object>) ((Map<String, Object>) ((List<?>) request.contextData().payload().get("nodes")).get(0)).get("data");
            @SuppressWarnings("unchecked")
            var edge = (Map<String, Object>) ((List<?>) request.contextData().payload().get("edges")).get(0);

            assertThat(request.contextData().transmissionLevel()).isEqualTo(TransmissionLevel.STRICT);
            assertThat(nodeData)
                    .containsEntry("resourceId", "EC2-1")
                    .containsEntry("name", "EC2-1")
                    .doesNotContainKeys("tags", "env", "databaseUrl");
            assertThat(edge)
                    .containsEntry("source", "EC2-1")
                    .containsEntry("target", "RDS-1");
            throw new IllegalStateException("mock llm failure");
        });

        assertThatThrownBy(() -> llmGateway.query("실패 케이스", new GraphData("subgraph:prod", graphPayload())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock llm failure");

        var logs = llmAuditLogRepository.findAllByOrderByTimestampDesc();
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getStatus()).isEqualTo(LlmAuditStatus.FAILED);
            assertThat(log.getTransmissionLevel()).isEqualTo("strict");
            assertThat(log.getFailureMessage()).contains("mock llm failure");
        });
    }

    private Map<String, Object> graphPayload() {
        return Map.of(
                "nodes", List.of(
                        node("ec2-node-1", "ec2", null, Map.of(
                                "resourceId", "i-0123456789",
                                "name", "prod-api",
                                "databaseUrl", "jdbc:postgresql://db.internal:5432/app?password=topsecret",
                                "tags", Map.of("Service", "api"),
                                "env", Map.of(
                                        "SPRING_PROFILES_ACTIVE", "prod",
                                        "DB_PASSWORD", "super-secret"
                                )
                        )),
                        node("rds-node-1", "rds", null, Map.of(
                                "resourceId", "dongne-prod-db",
                                "name", "dongne-prod-db",
                                "engine", "postgres"
                        ))
                ),
                "edges", List.of(
                        Map.of(
                                "id", "ec2->rds",
                                "source", "ec2-node-1",
                                "target", "rds-node-1",
                                "type", "LIKELY_USES",
                                "data", Map.of(
                                        "port", 5432,
                                        "confidence", "medium"
                                )
                        )
                ),
                "metadata", Map.of("totalNodes", 2, "totalEdges", 1)
        );
    }

    private Map<String, Object> node(String id, String type, String parentNode, Map<String, Object> data) {
        var node = new LinkedHashMap<String, Object>();
        node.put("id", id);
        node.put("type", type);
        node.put("parentNode", parentNode);
        node.put("data", data);
        return node;
    }
}
