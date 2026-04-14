package com.ariadne.collector;

import com.ariadne.config.AwsProperties;
import com.ariadne.graph.service.GraphInferenceService;
import com.ariadne.graph.service.GraphPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CollectorOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CollectorOrchestrator.class);

    private final List<ResourceCollector> collectors;
    private final GraphPersistenceService graphPersistenceService;
    private final GraphInferenceService graphInferenceService;
    private final StsClient stsClient;
    private final AwsProperties awsProperties;

    public CollectorOrchestrator(
            List<ResourceCollector> collectors,
            GraphPersistenceService graphPersistenceService,
            GraphInferenceService graphInferenceService,
            StsClient stsClient,
            AwsProperties awsProperties
    ) {
        this.collectors = collectors.stream()
                .sorted(Comparator.comparing(ResourceCollector::resourceType))
                .toList();
        this.graphPersistenceService = graphPersistenceService;
        this.graphInferenceService = graphInferenceService;
        this.stsClient = stsClient;
        this.awsProperties = awsProperties;
    }

    public ScanSummary collectAll() {
        var context = buildContext();
        var aggregate = CollectResult.empty();
        var warnings = new ArrayList<String>();
        var managedResourceTypes = new LinkedHashSet<String>();

        var outcomes = collectors.parallelStream()
                .map(collector -> collectSafely(collector, context))
                .toList();

        for (var outcome : outcomes) {
            if (outcome.warning() != null) {
                warnings.add(outcome.warning());
                continue;
            }
            managedResourceTypes.addAll(outcome.managedResourceTypes());
            aggregate = aggregate.merge(outcome.result());
        }

        graphPersistenceService.save(aggregate, context.collectedAt(), managedResourceTypes);
        var inferredRelationships = inferLikelyUses(warnings);

        return new ScanSummary(
                aggregate.resources().size(),
                aggregate.relationships().size() + inferredRelationships,
                context.collectedAt(),
                warnings
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
            OffsetDateTime collectedAt,
            List<String> warnings
    ) {
    }

    private CollectorOutcome collectSafely(ResourceCollector collector, AwsCollectContext context) {
        try {
            return new CollectorOutcome(
                    collector.managedResourceTypes(),
                    collector.collect(context),
                    null
            );
        } catch (RuntimeException exception) {
            var warning = "Collector %s failed: %s".formatted(collector.resourceType(), exception.getMessage());
            log.warn(warning, exception);
            return new CollectorOutcome(Set.of(), CollectResult.empty(), warning);
        }
    }

    private int inferLikelyUses(List<String> warnings) {
        try {
            return graphInferenceService.refreshLikelyUsesRelationships();
        } catch (RuntimeException exception) {
            var warning = "Derived relationship inference failed: %s".formatted(exception.getMessage());
            log.warn(warning, exception);
            warnings.add(warning);
            return 0;
        }
    }

    private record CollectorOutcome(
            Set<String> managedResourceTypes,
            CollectResult result,
            String warning
    ) {
    }
}
