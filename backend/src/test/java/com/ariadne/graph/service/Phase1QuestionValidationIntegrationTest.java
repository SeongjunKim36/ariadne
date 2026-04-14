package com.ariadne.graph.service;

import com.ariadne.api.dto.ResourceDetailResponse;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.DbSubnetGroup;
import com.ariadne.graph.node.Ec2Instance;
import com.ariadne.graph.node.EcsCluster;
import com.ariadne.graph.node.EcsService;
import com.ariadne.graph.node.EcsTaskDefinition;
import com.ariadne.graph.node.LambdaFunction;
import com.ariadne.graph.node.LoadBalancer;
import com.ariadne.graph.node.RdsInstance;
import com.ariadne.graph.node.Route53Zone;
import com.ariadne.graph.node.S3Bucket;
import com.ariadne.graph.node.SecurityGroup;
import com.ariadne.graph.node.Subnet;
import com.ariadne.graph.node.Vpc;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class Phase1QuestionValidationIntegrationTest {

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

    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-04-14T02:00:00Z");

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private GraphPersistenceService graphPersistenceService;

    @Autowired
    private GraphInferenceService graphInferenceService;

    @Autowired
    private GraphQueryService graphQueryService;

    @Autowired
    private ResourceQueryService resourceQueryService;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @BeforeEach
    void resetGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        scanRunRepository.deleteAll();
    }

    @Test
    void answersProdTopologyAndRdsUsageQuestionsThroughGraphApis() {
        var seeded = seedGraph();
        var inferredRelationships = graphInferenceService.refreshLikelyUsesRelationships();

        var scanRun = new ScanRun(UUID.randomUUID(), ScanStatus.RUNNING, COLLECTED_AT.minusMinutes(3));
        scanRun.markCompleted(
                COLLECTED_AT,
                seeded.resources().size(),
                seeded.relationships().size() + inferredRelationships,
                180_000L,
                null
        );
        scanRunRepository.save(scanRun);

        var prodGraph = graphQueryService.fetchGraph("prod", Set.of(), "vpc-1234");
        var names = prodGraph.nodes().stream()
                .map(node -> String.valueOf(node.data().get("name")))
                .collect(Collectors.toSet());

        assertThat(names)
                .contains("prod-main-vpc", "prod-app-a", "prod-db-a", "shared-db-sg", "prod-api-1", "prod-cluster", "prod-api", "prod-db-1", "prod-web");
        assertThat(names).doesNotContain("staging-api-1", "prod-api:7");
        assertThat(prodGraph.edges())
                .extracting(edge -> edge.type(), edge -> edge.source(), edge -> edge.target())
                .contains(
                        org.assertj.core.groups.Tuple.tuple(RelationshipTypes.LIKELY_USES, arn("ec2", "instance/i-prod-api-1"), arn("rds", "db:prod-db-1")),
                        org.assertj.core.groups.Tuple.tuple(RelationshipTypes.LIKELY_USES, arn("ecs", "service/prod-cluster/prod-api"), arn("rds", "db:prod-db-1"))
                );

        ResourceDetailResponse dbDetail = resourceQueryService.findByResourceId("prod-db-1");

        assertThat(dbDetail.connections())
                .filteredOn(connection -> RelationshipTypes.LIKELY_USES.equals(connection.relationshipType()))
                .extracting(connection -> String.valueOf(connection.node().data().get("name")), connection -> connection.direction(), connection -> connection.relationshipData().get("confidence"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("prod-api-1", "incoming", "medium"),
                        org.assertj.core.groups.Tuple.tuple("prod-api", "incoming", "medium")
                );

        ResourceDetailResponse serviceDetail = resourceQueryService.findByResourceId("prod-api");

        assertThat(serviceDetail.connections())
                .filteredOn(connection -> RelationshipTypes.USES_TASK_DEF.equals(connection.relationshipType()))
                .extracting(connection -> String.valueOf(connection.node().data().get("resourceId")), connection -> connection.node().type())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("prod-api:7", "ecs-task-def"));
    }

    private CollectResult seedGraph() {
        var resources = List.of(
                new Vpc(
                        arn("ec2", "vpc/vpc-1234"),
                        "vpc-1234",
                        "prod-main-vpc",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "10.0.0.0/16",
                        false,
                        "available"
                ),
                new Subnet(
                        arn("ec2", "subnet/subnet-app-a"),
                        "subnet-app-a",
                        "prod-app-a",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "10.0.1.0/24",
                        "ap-northeast-2a",
                        true,
                        250
                ),
                new Subnet(
                        arn("ec2", "subnet/subnet-db-a"),
                        "subnet-db-a",
                        "prod-db-a",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "10.0.11.0/24",
                        "ap-northeast-2a",
                        false,
                        245
                ),
                new SecurityGroup(
                        arn("ec2", "security-group/sg-db-shared"),
                        "sg-db-shared",
                        "shared-db-sg",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "sg-db-shared",
                        "shared db sg",
                        2,
                        1
                ),
                new Ec2Instance(
                        arn("ec2", "instance/i-prod-api-1"),
                        "i-prod-api-1",
                        "prod-api-1",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "t3.micro",
                        "running",
                        "10.0.1.10",
                        null,
                        "ami-12345678",
                        COLLECTED_AT.minusDays(7),
                        "linux"
                ),
                new EcsCluster(
                        arn("ecs", "cluster/prod-cluster"),
                        "prod-cluster",
                        "prod-cluster",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "ACTIVE",
                        3,
                        0,
                        1
                ),
                new EcsService(
                        arn("ecs", "service/prod-cluster/prod-api"),
                        "prod-api",
                        "prod-api",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        2,
                        2,
                        0,
                        "FARGATE",
                        "prod-api:7"
                ),
                new EcsTaskDefinition(
                        arn("ecs", "task-definition/prod-api:7"),
                        "prod-api:7",
                        "prod-api:7",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "prod-api",
                        7,
                        "512",
                        "1024",
                        "awsvpc",
                        "arn:aws:iam::123456789012:role/prod-task",
                        "arn:aws:iam::123456789012:role/prod-exec",
                        "FARGATE",
                        1,
                        "[{\"name\":\"web\",\"image\":\"123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/prod-api:v7\"}]"
                ),
                new DbSubnetGroup(
                        arn("rds", "subgrp:prod-db-subnets"),
                        "prod-db-subnets",
                        "prod-db-subnets",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "prod-db-subnets",
                        "prod database subnets",
                        "Complete",
                        1
                ),
                new RdsInstance(
                        arn("rds", "db:prod-db-1"),
                        "prod-db-1",
                        "prod-db-1",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "postgres",
                        "16.3",
                        "db.t3.micro",
                        "available",
                        "prod-db.cluster.local:5432",
                        false,
                        20,
                        true
                ),
                new LoadBalancer(
                        arn("elasticloadbalancing", "loadbalancer/app/prod-web/123456"),
                        "prod-web",
                        "prod-web",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "application",
                        "internet-facing",
                        "prod-web.ap-northeast-2.elb.amazonaws.com",
                        "active",
                        1,
                        2
                ),
                new LambdaFunction(
                        arn("lambda", "function:prod-worker"),
                        "prod-worker",
                        "prod-worker",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "java21",
                        "com.example.Worker::handleRequest",
                        1024,
                        30,
                        "2026-04-14T00:00:00.000+0000",
                        4096L,
                        "Active",
                        "Zip"
                ),
                new S3Bucket(
                        arn("s3", "prod-artifacts"),
                        "prod-artifacts",
                        "prod-artifacts",
                        "ap-northeast-2",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        COLLECTED_AT.minusDays(30),
                        true,
                        "AES256",
                        true
                ),
                new Route53Zone(
                        arn("route53", "hostedzone/Z1234"),
                        "Z1234",
                        "example.com",
                        "global",
                        "123456789012",
                        "prod",
                        COLLECTED_AT,
                        Map.of("environment", "prod"),
                        "Z1234",
                        "example.com",
                        false,
                        12L,
                        null
                ),
                new Ec2Instance(
                        arn("ec2", "instance/i-staging-api-1"),
                        "i-staging-api-1",
                        "staging-api-1",
                        "ap-northeast-2",
                        "123456789012",
                        "staging",
                        COLLECTED_AT,
                        Map.of("environment", "staging"),
                        "t3.micro",
                        "running",
                        "10.9.1.10",
                        null,
                        "ami-87654321",
                        COLLECTED_AT.minusDays(2),
                        "linux"
                )
        );

        var relationships = List.of(
                GraphRelationship.belongsTo(arn("ec2", "subnet/subnet-app-a"), arn("ec2", "vpc/vpc-1234")),
                GraphRelationship.belongsTo(arn("ec2", "subnet/subnet-db-a"), arn("ec2", "vpc/vpc-1234")),
                GraphRelationship.belongsTo(arn("ec2", "security-group/sg-db-shared"), arn("ec2", "vpc/vpc-1234")),
                GraphRelationship.belongsTo(arn("ec2", "instance/i-prod-api-1"), arn("ec2", "subnet/subnet-app-a")),
                GraphRelationship.belongsTo(arn("rds", "subgrp:prod-db-subnets"), arn("ec2", "vpc/vpc-1234")),
                new GraphRelationship(arn("rds", "subgrp:prod-db-subnets"), arn("ec2", "subnet/subnet-db-a"), RelationshipTypes.CONTAINS, Map.of()),
                new GraphRelationship(arn("rds", "db:prod-db-1"), arn("rds", "subgrp:prod-db-subnets"), RelationshipTypes.IN_SUBNET_GROUP, Map.of()),
                new GraphRelationship(arn("ec2", "instance/i-prod-api-1"), arn("ec2", "security-group/sg-db-shared"), RelationshipTypes.HAS_SG, Map.of()),
                new GraphRelationship(arn("ecs", "service/prod-cluster/prod-api"), arn("ec2", "security-group/sg-db-shared"), RelationshipTypes.HAS_SG, Map.of()),
                new GraphRelationship(arn("rds", "db:prod-db-1"), arn("ec2", "security-group/sg-db-shared"), RelationshipTypes.HAS_SG, Map.of()),
                new GraphRelationship(arn("ecs", "service/prod-cluster/prod-api"), arn("ecs", "cluster/prod-cluster"), RelationshipTypes.RUNS_IN, Map.of()),
                new GraphRelationship(arn("ecs", "service/prod-cluster/prod-api"), arn("ecs", "task-definition/prod-api:7"), RelationshipTypes.USES_TASK_DEF, Map.of()),
                GraphRelationship.belongsTo(arn("elasticloadbalancing", "loadbalancer/app/prod-web/123456"), arn("ec2", "vpc/vpc-1234")),
                new GraphRelationship(arn("elasticloadbalancing", "loadbalancer/app/prod-web/123456"), arn("ec2", "security-group/sg-db-shared"), RelationshipTypes.HAS_SG, Map.of()),
                new GraphRelationship(arn("elasticloadbalancing", "loadbalancer/app/prod-web/123456"), arn("ec2", "instance/i-prod-api-1"), RelationshipTypes.ROUTES_TO, Map.of("port", 80, "protocol", "HTTP")),
                new GraphRelationship(arn("elasticloadbalancing", "loadbalancer/app/prod-web/123456"), arn("ecs", "service/prod-cluster/prod-api"), RelationshipTypes.ROUTES_TO, Map.of("port", 8080, "protocol", "HTTP")),
                GraphRelationship.belongsTo(arn("lambda", "function:prod-worker"), arn("ec2", "subnet/subnet-app-a")),
                new GraphRelationship(arn("lambda", "function:prod-worker"), arn("ec2", "security-group/sg-db-shared"), RelationshipTypes.HAS_SG, Map.of()),
                new GraphRelationship(arn("s3", "prod-artifacts"), arn("lambda", "function:prod-worker"), RelationshipTypes.TRIGGERS, Map.of()),
                new GraphRelationship(arn("route53", "hostedzone/Z1234"), arn("elasticloadbalancing", "loadbalancer/app/prod-web/123456"), RelationshipTypes.HAS_RECORD, Map.of("recordType", "A", "recordName", "api.example.com")),
                GraphRelationship.belongsTo(arn("ec2", "instance/i-staging-api-1"), arn("ec2", "subnet/subnet-app-a"))
        );

        var result = new CollectResult(resources, relationships);
        graphPersistenceService.save(result, COLLECTED_AT, resources.stream().map(resource -> resource.getResourceType()).collect(Collectors.toSet()));
        return result;
    }

    private static String arn(String service, String suffix) {
        return switch (service) {
            case "ec2" -> "arn:aws:ec2:ap-northeast-2:123456789012:%s".formatted(suffix);
            case "ecs" -> "arn:aws:ecs:ap-northeast-2:123456789012:%s".formatted(suffix);
            case "rds" -> "arn:aws:rds:ap-northeast-2:123456789012:%s".formatted(suffix);
            case "elasticloadbalancing" -> "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:%s".formatted(suffix);
            case "lambda" -> "arn:aws:lambda:ap-northeast-2:123456789012:%s".formatted(suffix);
            case "route53" -> "arn:aws:route53:::%s".formatted(suffix);
            case "s3" -> "arn:aws:s3:::%s".formatted(suffix);
            default -> throw new IllegalArgumentException("Unknown service: " + service);
        };
    }
}
