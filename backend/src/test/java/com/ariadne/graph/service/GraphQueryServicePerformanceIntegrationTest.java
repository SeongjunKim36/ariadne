package com.ariadne.graph.service;

import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.Ec2Instance;
import com.ariadne.graph.node.Subnet;
import com.ariadne.graph.node.Vpc;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.scan.ScanRun;
import com.ariadne.scan.ScanRunRepository;
import com.ariadne.scan.ScanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GraphQueryServicePerformanceIntegrationTest {

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

    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-04-16T09:00:00Z");
    private static final int SUBNET_COUNT = 24;
    private static final int INSTANCES_PER_SUBNET = 18;
    private static final int EXPECTED_NODES = 1 + SUBNET_COUNT + (SUBNET_COUNT * INSTANCES_PER_SUBNET);
    private static final int EXPECTED_EDGES = SUBNET_COUNT + (SUBNET_COUNT * INSTANCES_PER_SUBNET);

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private GraphPersistenceService graphPersistenceService;

    @Autowired
    private GraphQueryService graphQueryService;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        scanRunRepository.deleteAll();
    }

    @Test
    void fetchesHundredsOfVisibleResourcesWithinReasonableBudget() {
        seedLargeGraph();

        // Warm up the query path once so the measured run focuses on the graph fetch itself.
        graphQueryService.fetchGraph(null, Set.of(), null, null);

        var startedAt = System.nanoTime();
        var response = graphQueryService.fetchGraph(null, Set.of(), null, null);
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        System.out.printf(
                "GraphQueryService performance: nodes=%d edges=%d elapsed=%dms%n",
                response.nodes().size(),
                response.edges().size(),
                elapsed.toMillis()
        );

        assertThat(response.nodes()).hasSize(EXPECTED_NODES);
        assertThat(response.edges()).hasSize(EXPECTED_EDGES);
        assertThat(response.metadata().totalNodes()).isEqualTo(EXPECTED_NODES);
        assertThat(response.metadata().totalEdges()).isEqualTo(EXPECTED_EDGES);
        assertThat(elapsed.toMillis()).isLessThan(2_500L);
    }

    private void seedLargeGraph() {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var accountId = "123456789012";
        var region = "ap-northeast-2";

        var vpcArn = arn("ec2", "vpc/vpc-scale-001");
        resources.add(new Vpc(
                vpcArn,
                "vpc-scale-001",
                "scale-main-vpc",
                region,
                accountId,
                "prod",
                COLLECTED_AT,
                Map.of("environment", "prod"),
                "10.42.0.0/16",
                false,
                "available"
        ));

        for (int subnetIndex = 0; subnetIndex < SUBNET_COUNT; subnetIndex++) {
            var subnetId = "subnet-scale-%02d".formatted(subnetIndex);
            var subnetArn = arn("ec2", "subnet/" + subnetId);
            resources.add(new Subnet(
                    subnetArn,
                    subnetId,
                    "scale-subnet-%02d".formatted(subnetIndex),
                    region,
                    accountId,
                    "prod",
                    COLLECTED_AT,
                    Map.of("environment", "prod"),
                    "10.42.%d.0/24".formatted(subnetIndex),
                    "ap-northeast-2a",
                    subnetIndex % 2 == 0,
                    240
            ));
            relationships.add(GraphRelationship.belongsTo(subnetArn, vpcArn));

            for (int instanceIndex = 0; instanceIndex < INSTANCES_PER_SUBNET; instanceIndex++) {
                var ordinal = (subnetIndex * INSTANCES_PER_SUBNET) + instanceIndex;
                var instanceId = "i-scale-%04d".formatted(ordinal);
                var instanceArn = arn("ec2", "instance/" + instanceId);
                resources.add(new Ec2Instance(
                        instanceArn,
                        instanceId,
                        "scale-api-%04d".formatted(ordinal),
                        region,
                        accountId,
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod", "service", "api"),
                        "t3.micro",
                        "running",
                        "10.42.%d.%d".formatted(subnetIndex, 10 + (instanceIndex % 200)),
                        null,
                        "ami-scale1234",
                        COLLECTED_AT.minusHours(ordinal % 24),
                        "linux"
                ));
                relationships.add(GraphRelationship.belongsTo(instanceArn, subnetArn));
            }
        }

        graphPersistenceService.save(
                new CollectResult(resources, relationships),
                COLLECTED_AT,
                Set.of("VPC", "SUBNET", "EC2")
        );

        var scanRun = new ScanRun(UUID.randomUUID(), ScanStatus.RUNNING, COLLECTED_AT.minusMinutes(5));
        scanRun.markCompleted(COLLECTED_AT, EXPECTED_NODES, EXPECTED_EDGES, 225_000L, null);
        scanRunRepository.save(scanRun);
    }

    private String arn(String service, String resourcePath) {
        return "arn:aws:%s:ap-northeast-2:123456789012:%s".formatted(service, resourcePath);
    }
}
