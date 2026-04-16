package com.ariadne.collector.sg;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.graph.service.GraphQueryService;
import com.ariadne.scan.ScanRun;
import com.ariadne.scan.ScanRunRepository;
import com.ariadne.scan.ScanService;
import com.ariadne.scan.ScanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;

import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityGroupRuleGraphIntegrationTest {

    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8.1")
    ).withServices(
            LocalStackContainer.Service.EC2,
            LocalStackContainer.Service.STS
    );

    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(
            DockerImageName.parse("neo4j:5.26.0")
    );

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    static {
        LOCALSTACK.start();
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
        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.endpoint-url", () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.EC2).toString());
        registry.add("aws.access-key-id", LOCALSTACK::getAccessKey);
        registry.add("aws.secret-access-key", LOCALSTACK::getSecretKey);
    }

    @Autowired
    private Ec2Client ec2Client;

    @Autowired
    private ScanService scanService;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @Autowired
    private GraphQueryService graphQueryService;

    @Test
    void collectsSecurityGroupRuleEdgesAndCidrSources() {
        var vpcId = ec2Client.createVpc(request -> request.cidrBlock("10.50.0.0/16"))
                .vpc()
                .vpcId();

        ec2Client.createTags(request -> request
                .resources(vpcId)
                .tags(
                        Tag.builder().key("Name").value("phase2-rule-vpc").build(),
                        Tag.builder().key("environment").value("prod").build()
                ));

        var appSecurityGroupId = ec2Client.createSecurityGroup(request -> request
                        .groupName("phase2-app-sg")
                        .description("App SG")
                        .vpcId(vpcId))
                .groupId();

        var dbSecurityGroupId = ec2Client.createSecurityGroup(request -> request
                        .groupName("phase2-db-sg")
                        .description("DB SG")
                        .vpcId(vpcId))
                .groupId();

        ec2Client.createTags(request -> request
                .resources(appSecurityGroupId, dbSecurityGroupId)
                .tags(Tag.builder().key("environment").value("prod").build()));

        ec2Client.authorizeSecurityGroupIngress(request -> request
                .groupId(dbSecurityGroupId)
                .ipPermissions(
                        IpPermission.builder()
                                .ipProtocol("tcp")
                                .fromPort(5432)
                                .toPort(5432)
                                .userIdGroupPairs(UserIdGroupPair.builder().groupId(appSecurityGroupId).build())
                                .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                                .build()
                ));

        ec2Client.authorizeSecurityGroupIngress(request -> request
                .groupId(appSecurityGroupId)
                .ipPermissions(
                        IpPermission.builder()
                                .ipProtocol("tcp")
                                .fromPort(8080)
                                .toPort(8080)
                                .userIdGroupPairs(UserIdGroupPair.builder().groupId(appSecurityGroupId).build())
                                .build()
                ));

        ec2Client.authorizeSecurityGroupEgress(request -> request
                .groupId(appSecurityGroupId)
                .ipPermissions(
                        IpPermission.builder()
                                .ipProtocol("tcp")
                                .fromPort(5432)
                                .toPort(5432)
                                .userIdGroupPairs(UserIdGroupPair.builder().groupId(dbSecurityGroupId).build())
                                .build(),
                        IpPermission.builder()
                                .ipProtocol("tcp")
                                .fromPort(443)
                                .toPort(443)
                                .ipRanges(IpRange.builder().cidrIp("10.0.0.0/8").build())
                                .build()
                ));

        var scanRun = scanService.triggerScan();
        var completedScan = awaitCompletion(scanRun);

        assertThat(completedScan.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(completedScan.getTotalNodes()).isGreaterThanOrEqualTo(5);
        assertThat(completedScan.getTotalEdges()).isGreaterThanOrEqualTo(6);

        GraphResponse graph = graphQueryService.fetchGraph(
                "prod",
                Set.of("VPC", "SECURITY_GROUP", "CIDR_SOURCE"),
                vpcId,
                null
        );

        var nodesByName = graph.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        node -> (String) node.data().get("name"),
                        Function.identity(),
                        (left, right) -> left
                ));

        assertThat(nodesByName.keySet())
                .contains("phase2-rule-vpc", "phase2-app-sg", "phase2-db-sg", "Public Internet", "Private Network");

        var publicInternet = nodesByName.get("Public Internet");
        assertThat(publicInternet.type()).isEqualTo("cidr");
        assertThat(publicInternet.data())
                .containsEntry("cidr", "0.0.0.0/0")
                .containsEntry("riskLevel", "HIGH")
                .containsEntry("isPublic", true);

        assertThat(graph.edges())
                .extracting(GraphResponse.GraphEdge::type)
                .contains("ALLOWS_FROM", "ALLOWS_TO", "ALLOWS_SELF", "EGRESS_TO");
    }

    private ScanRun awaitCompletion(ScanRun scanRun) {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var current = scanRunRepository.findById(scanRun.getScanId()).orElseThrow();
            if (current.getStatus() != ScanStatus.RUNNING) {
                return current;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for scan completion", interruptedException);
            }
        }
        throw new AssertionError("Timed out waiting for scan to complete");
    }
}
