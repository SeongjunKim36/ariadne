package com.ariadne.drift;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.api.dto.TerraformDriftDetectionRequest;
import com.ariadne.config.AriadneProperties;
import com.ariadne.notify.NotificationService;
import com.ariadne.snapshot.PropertyChange;
import com.ariadne.snapshot.SnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TerraformDriftDetector {

    private final TerraformStateParser terraformStateParser;
    private final SnapshotService snapshotService;
    private final TerraformDriftRunRepository terraformDriftRunRepository;
    private final ObjectMapper objectMapper;
    private final AriadneProperties ariadneProperties;
    private final NotificationService notificationService;

    public TerraformDriftDetector(
            TerraformStateParser terraformStateParser,
            SnapshotService snapshotService,
            TerraformDriftRunRepository terraformDriftRunRepository,
            ObjectMapper objectMapper,
            AriadneProperties ariadneProperties,
            NotificationService notificationService
    ) {
        this.terraformStateParser = terraformStateParser;
        this.snapshotService = snapshotService;
        this.terraformDriftRunRepository = terraformDriftRunRepository;
        this.objectMapper = objectMapper;
        this.ariadneProperties = ariadneProperties;
        this.notificationService = notificationService;
    }

    public TerraformDriftRun detect(TerraformDriftDetectionRequest request) {
        var source = resolveSource(request);
        var tfResources = terraformStateParser.parse(source.rawStateJson());
        var snapshot = snapshotService.latestSnapshot()
                .or(() -> snapshotService.capturePartialRefresh("Terraform drift baseline capture"))
                .orElseThrow(() -> new IllegalStateException("No graph is available for drift comparison."));
        var graph = snapshotService.readGraph(snapshot);
        var reportItems = compare(tfResources, graph);
        var missingCount = (int) reportItems.stream().filter(item -> item.status() == DriftItemStatus.MISSING).count();
        var modifiedCount = (int) reportItems.stream().filter(item -> item.status() == DriftItemStatus.MODIFIED).count();
        var unmanagedCount = (int) reportItems.stream().filter(item -> item.status() == DriftItemStatus.UNMANAGED).count();
        var savedRun = terraformDriftRunRepository.save(new TerraformDriftRun(
                OffsetDateTime.now(ZoneOffset.UTC),
                source.kind(),
                source.name(),
                reportItems.size(),
                missingCount,
                modifiedCount,
                unmanagedCount,
                toJson(reportItems)
        ));
        notificationService.notifyDriftReport(savedRun);
        return savedRun;
    }

    public TerraformDriftRun latestRun() {
        return terraformDriftRunRepository.findTopByOrderByGeneratedAtDesc()
                .orElseThrow(() -> new IllegalArgumentException("No Terraform drift report available."));
    }

    public List<DriftItem> readItems(TerraformDriftRun run) {
        try {
            return objectMapper.readValue(
                    run.getReportJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, DriftItem.class)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse Terraform drift report payload.", exception);
        }
    }

    private List<DriftItem> compare(List<TfResource> tfResources, GraphResponse graph) {
        var actualByArn = new LinkedHashMap<String, GraphResponse.GraphNode>();
        var actualByTypeAndId = new LinkedHashMap<String, GraphResponse.GraphNode>();
        for (var node : graph.nodes()) {
            var arn = stringValue(node.data().get("arn"));
            var resourceType = normalizeType(stringValue(node.data().get("resourceType")));
            var resourceId = stringValue(node.data().get("resourceId"));
            if (arn != null) {
                actualByArn.put(arn, node);
            }
            if (resourceType != null && resourceId != null) {
                actualByTypeAndId.put(resourceType + "|" + resourceId, node);
            }
        }

        var items = new ArrayList<DriftItem>();
        var managedNodeIds = new LinkedHashSet<String>();
        var tfTypes = new LinkedHashSet<String>();

        for (var tfResource : tfResources) {
            tfTypes.add(tfResource.resourceType());
            var actual = resolveActualNode(tfResource, actualByArn, actualByTypeAndId);
            if (actual == null) {
                items.add(new DriftItem(
                        DriftItemStatus.MISSING,
                        tfResource.address(),
                        tfResource.resourceType(),
                        tfResource.arn(),
                        tfResource.resourceId(),
                        tfResource.name(),
                        "Terraform에는 선언되어 있지만 현재 그래프에서 보이지 않습니다.",
                        tfResource.properties(),
                        Map.of(),
                        Map.of()
                ));
                continue;
            }

            managedNodeIds.add(actual.id());
            var propertyChanges = compareProperties(tfResource.properties(), normalizeActual(actual.data()));
            if (!propertyChanges.isEmpty()) {
                items.add(new DriftItem(
                        DriftItemStatus.MODIFIED,
                        tfResource.address(),
                        tfResource.resourceType(),
                        stringValue(actual.data().get("arn")),
                        stringValue(actual.data().get("resourceId")),
                        stringValue(actual.data().get("name")),
                        "Terraform 선언과 현재 상태의 속성이 다릅니다.",
                        tfResource.properties(),
                        normalizeActual(actual.data()),
                        propertyChanges
                ));
            }
        }

        for (var node : graph.nodes()) {
            var resourceType = normalizeType(stringValue(node.data().get("resourceType")));
            if (!tfTypes.contains(resourceType)) {
                continue;
            }
            if (managedNodeIds.contains(node.id())) {
                continue;
            }
            items.add(new DriftItem(
                    DriftItemStatus.UNMANAGED,
                    null,
                    resourceType,
                    stringValue(node.data().get("arn")),
                    stringValue(node.data().get("resourceId")),
                    stringValue(node.data().get("name")),
                    "현재 그래프에는 존재하지만 Terraform state에는 없습니다.",
                    Map.of(),
                    normalizeActual(node.data()),
                    Map.of()
            ));
        }

        return items;
    }

    private SourcePayload resolveSource(TerraformDriftDetectionRequest request) {
        if (request != null && request.rawStateJson() != null && !request.rawStateJson().isBlank()) {
            return new SourcePayload(TerraformStateSourceKind.INLINE_JSON, "inline-json", request.rawStateJson());
        }
        if (request != null && request.path() != null && !request.path().isBlank()) {
            return new SourcePayload(TerraformStateSourceKind.LOCAL_FILE, request.path(), readFile(request.path()));
        }
        var configuredPath = ariadneProperties.getTerraform().getStatePath();
        if (configuredPath != null && !configuredPath.isBlank()) {
            return new SourcePayload(TerraformStateSourceKind.CONFIGURED_DEFAULT, configuredPath, readFile(configuredPath));
        }
        throw new IllegalArgumentException("Terraform state path or raw JSON must be provided.");
    }

    private String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read Terraform state file: " + path, exception);
        }
    }

    private GraphResponse.GraphNode resolveActualNode(
            TfResource tfResource,
            Map<String, GraphResponse.GraphNode> actualByArn,
            Map<String, GraphResponse.GraphNode> actualByTypeAndId
    ) {
        if (tfResource.arn() != null && actualByArn.containsKey(tfResource.arn())) {
            return actualByArn.get(tfResource.arn());
        }
        if (tfResource.resourceType() != null && tfResource.resourceId() != null) {
            return actualByTypeAndId.get(tfResource.resourceType() + "|" + tfResource.resourceId());
        }
        return null;
    }

    private Map<String, Object> normalizeActual(Map<String, Object> actualData) {
        var normalized = new LinkedHashMap<String, Object>();
        for (var entry : actualData.entrySet()) {
            var key = entry.getKey();
            if ("collectedAt".equals(key) || "stale".equals(key) || "staleSince".equals(key)) {
                continue;
            }
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    private Map<String, PropertyChange> compareProperties(Map<String, Object> desiredData, Map<String, Object> actualData) {
        var changes = new LinkedHashMap<String, PropertyChange>();
        var keys = new LinkedHashSet<String>();
        keys.addAll(desiredData.keySet());
        keys.addAll(actualData.keySet());

        for (var key : keys) {
            var desired = desiredData.get(key);
            var actual = actualData.get(key);
            if (!Objects.equals(desired, actual)) {
                changes.put(key, new PropertyChange(desired, actual));
            }
        }
        return changes;
    }

    private String normalizeType(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record SourcePayload(
            TerraformStateSourceKind kind,
            String name,
            String rawStateJson
    ) {
    }
}
