package com.ariadne.collector.vpc;

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
import software.amazon.awssdk.services.ec2.model.Tag;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VpcSubnetScanIntegrationTest {

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
    void collectsVpcAndSubnetFromLocalStackIntoGraph() {
        var vpcId = ec2Client.createVpc(request -> request.cidrBlock("10.30.0.0/16"))
                .vpc()
                .vpcId();

        ec2Client.createTags(request -> request
                .resources(vpcId)
                .tags(
                        Tag.builder().key("Name").value("prod-main-vpc").build(),
                        Tag.builder().key("environment").value("prod").build()
                ));

        var subnetId = ec2Client.createSubnet(request -> request
                        .vpcId(vpcId)
                        .cidrBlock("10.30.1.0/24")
                        .availabilityZone("ap-northeast-2a"))
                .subnet()
                .subnetId();

        ec2Client.createTags(request -> request
                .resources(subnetId)
                .tags(
                        Tag.builder().key("Name").value("prod-app-subnet-a").build(),
                        Tag.builder().key("environment").value("prod").build()
                ));

        var scanRun = scanService.triggerScan();
        var completedScan = awaitCompletion(scanRun);

        assertThat(completedScan.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(completedScan.getTotalNodes()).isGreaterThanOrEqualTo(2);
        assertThat(completedScan.getTotalEdges()).isGreaterThanOrEqualTo(1);

        GraphResponse graph = graphQueryService.fetchGraph("prod", Set.of("VPC", "SUBNET"), null);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.nodes())
                .extracting(node -> node.data().get("name"))
                .containsExactlyInAnyOrder("prod-main-vpc", "prod-app-subnet-a");
        assertThat(graph.edges().get(0).type()).isEqualTo("BELONGS_TO");
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
