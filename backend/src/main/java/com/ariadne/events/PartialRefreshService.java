package com.ariadne.events;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.CollectResult;
import com.ariadne.collector.ResourceCollector;
import com.ariadne.config.AwsProperties;
import com.ariadne.graph.service.GraphInferenceService;
import com.ariadne.graph.service.GraphPersistenceService;
import com.ariadne.snapshot.SnapshotService;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PartialRefreshService {

    private final Map<String, ResourceCollector> collectorByType;
    private final GraphPersistenceService graphPersistenceService;
    private final GraphInferenceService graphInferenceService;
    private final SnapshotService snapshotService;
    private final StsClient stsClient;
    private final AwsProperties awsProperties;

    public PartialRefreshService(
            List<ResourceCollector> collectors,
            GraphPersistenceService graphPersistenceService,
            GraphInferenceService graphInferenceService,
            SnapshotService snapshotService,
            StsClient stsClient,
            AwsProperties awsProperties
    ) {
        this.collectorByType = collectors.stream()
                .sorted(Comparator.comparing(ResourceCollector::resourceType))
                .collect(Collectors.toMap(
                        collector -> collector.resourceType().toUpperCase(),
                        collector -> collector,
                        (left, right) -> left
                ));
        this.graphPersistenceService = graphPersistenceService;
        this.graphInferenceService = graphInferenceService;
        this.snapshotService = snapshotService;
        this.stsClient = stsClient;
        this.awsProperties = awsProperties;
    }

    public String refresh(EventResourceMapping mapping) {
        var context = new AwsCollectContext(
                stsClient.getCallerIdentity().account(),
                awsProperties.region(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        var aggregate = CollectResult.empty();
        var managedTypes = new LinkedHashSet<String>();
        var warnings = new ArrayList<String>();

        for (var collectorType : mapping.collectorTypes()) {
            var collector = collectorByType.get(collectorType.toUpperCase());
            if (collector == null) {
                warnings.add("No collector for " + collectorType);
                continue;
            }
            try {
                var result = collector.collect(context);
                aggregate = aggregate.merge(result);
                managedTypes.addAll(collector.managedResourceTypes());
            } catch (RuntimeException exception) {
                warnings.add(collector.resourceType() + ": " + exception.getMessage());
            }
        }

        if (!aggregate.resources().isEmpty() || !aggregate.relationships().isEmpty()) {
            graphPersistenceService.save(aggregate, context.collectedAt(), managedTypes);
            try {
                graphInferenceService.refreshLikelyUsesRelationships();
            } catch (RuntimeException exception) {
                warnings.add("inference: " + exception.getMessage());
            }
            snapshotService.captureEventbridge("Partial refresh for " + mapping.summary());
        }

        if (warnings.isEmpty()) {
            return "Refreshed " + String.join(", ", mapping.collectorTypes());
        }
        return "Processed with warnings: " + String.join(" | ", warnings);
    }
}
