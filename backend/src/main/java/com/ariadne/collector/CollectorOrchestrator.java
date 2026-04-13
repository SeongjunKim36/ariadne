package com.ariadne.collector;

import com.ariadne.config.AwsProperties;
import com.ariadne.graph.service.GraphPersistenceService;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Service
public class CollectorOrchestrator {

    private final List<ResourceCollector> collectors;
    private final GraphPersistenceService graphPersistenceService;
    private final StsClient stsClient;
    private final AwsProperties awsProperties;

    public CollectorOrchestrator(
            List<ResourceCollector> collectors,
            GraphPersistenceService graphPersistenceService,
            StsClient stsClient,
            AwsProperties awsProperties
    ) {
        this.collectors = collectors.stream()
                .sorted(Comparator.comparing(ResourceCollector::resourceType))
                .toList();
        this.graphPersistenceService = graphPersistenceService;
        this.stsClient = stsClient;
        this.awsProperties = awsProperties;
    }

    public ScanSummary collectAll() {
        var context = buildContext();
        var aggregate = CollectResult.empty();
        for (var collector : collectors) {
            aggregate = aggregate.merge(collector.collect(context));
        }

        graphPersistenceService.save(aggregate);

        return new ScanSummary(
                aggregate.resources().size(),
                aggregate.relationships().size(),
                context.collectedAt()
        );
    }

    private AwsCollectContext buildContext() {
        var callerIdentity = stsClient.getCallerIdentity();
        return new AwsCollectContext(
                callerIdentity.account(),
                awsProperties.region(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public record ScanSummary(
            int totalResources,
            int totalRelationships,
            OffsetDateTime collectedAt
    ) {
    }
}
