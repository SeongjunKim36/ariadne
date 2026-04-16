package com.ariadne.snapshot;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.config.AriadneProperties;
import com.ariadne.config.AwsProperties;
import com.ariadne.graph.service.GraphQueryService;
import com.ariadne.notify.NotificationService;
import com.ariadne.scan.ScanRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sts.StsClient;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class SnapshotService {

    private final SnapshotRepository snapshotRepository;
    private final SnapshotDiffRepository snapshotDiffRepository;
    private final GraphQueryService graphQueryService;
    private final DiffCalculator diffCalculator;
    private final ObjectMapper objectMapper;
    private final StsClient stsClient;
    private final AwsProperties awsProperties;
    private final AriadneProperties ariadneProperties;
    private final NotificationService notificationService;

    public SnapshotService(
            SnapshotRepository snapshotRepository,
            SnapshotDiffRepository snapshotDiffRepository,
            GraphQueryService graphQueryService,
            DiffCalculator diffCalculator,
            ObjectMapper objectMapper,
            StsClient stsClient,
            AwsProperties awsProperties,
            AriadneProperties ariadneProperties,
            NotificationService notificationService
    ) {
        this.snapshotRepository = snapshotRepository;
        this.snapshotDiffRepository = snapshotDiffRepository;
        this.graphQueryService = graphQueryService;
        this.diffCalculator = diffCalculator;
        this.objectMapper = objectMapper;
        this.stsClient = stsClient;
        this.awsProperties = awsProperties;
        this.ariadneProperties = ariadneProperties;
        this.notificationService = notificationService;
    }

    public Optional<Snapshot> captureAfterScan(ScanRun scanRun) {
        return capture(
                SnapshotTrigger.MANUAL_SCAN,
                scanRun.getScanId(),
                scanRun.getCompletedAt(),
                scanRun.getDurationMs(),
                scanRun.getWarningMessage()
        );
    }

    public Optional<Snapshot> capturePartialRefresh(String note) {
        return capture(
                SnapshotTrigger.PARTIAL_REFRESH,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                0,
                note
        );
    }

    public Optional<Snapshot> captureEventbridge(String note) {
        return capture(
                SnapshotTrigger.EVENTBRIDGE,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                0,
                note
        );
    }

    @Scheduled(cron = "${ariadne.snapshot.schedule:0 5 * * * *}")
    public void captureScheduled() {
        capture(
                SnapshotTrigger.SCHEDULED,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                0,
                "Scheduled snapshot capture"
        );
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOldSnapshots() {
        var allSnapshots = snapshotRepository.findAllByOrderByCapturedAtAsc();
        if (allSnapshots.isEmpty()) {
            return;
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var retention = ariadneProperties.getSnapshot().getRetention();
        var hourlyCutoff = now.minusHours(retention.getHourly());
        var dailyCutoff = now.minusDays(retention.getDaily());
        var weeklyCutoff = now.minusWeeks(retention.getWeekly());
        var keepIds = new java.util.LinkedHashSet<Long>();
        var keptDailyBuckets = new java.util.HashSet<String>();
        var keptWeeklyBuckets = new java.util.HashSet<String>();
        var weekFields = WeekFields.ISO;

        for (int index = allSnapshots.size() - 1; index >= 0; index--) {
            var snapshot = allSnapshots.get(index);
            if (snapshot.getCapturedAt().isAfter(hourlyCutoff)) {
                keepIds.add(snapshot.getId());
                continue;
            }

            if (snapshot.getCapturedAt().isAfter(dailyCutoff)) {
                var bucket = snapshot.getCapturedAt().toLocalDate().toString();
                if (keptDailyBuckets.add(bucket)) {
                    keepIds.add(snapshot.getId());
                }
                continue;
            }

            if (snapshot.getCapturedAt().isAfter(weeklyCutoff)) {
                var localDate = snapshot.getCapturedAt().toLocalDate();
                var bucket = "%d-%d".formatted(localDate.getYear(), localDate.get(weekFields.weekOfWeekBasedYear()));
                if (keptWeeklyBuckets.add(bucket)) {
                    keepIds.add(snapshot.getId());
                }
            }
        }

        long maxBytes = (long) retention.getMaxStorageGb() * 1024 * 1024 * 1024;
        if (maxBytes > 0) {
            long currentBytes = allSnapshots.stream()
                    .filter(snapshot -> keepIds.contains(snapshot.getId()))
                    .mapToLong(Snapshot::estimatedStorageBytes)
                    .sum();
            for (var snapshot : allSnapshots) {
                if (currentBytes <= maxBytes) {
                    break;
                }
                if (keepIds.remove(snapshot.getId())) {
                    currentBytes -= snapshot.estimatedStorageBytes();
                }
            }
        }

        var deletableIds = allSnapshots.stream()
                .map(Snapshot::getId)
                .filter(id -> !keepIds.contains(id))
                .toList();

        if (deletableIds.isEmpty()) {
            return;
        }

        var diffsToDelete = snapshotDiffRepository.findAll().stream()
                .filter(diff -> deletableIds.contains(diff.getBaseSnapshotId()) || deletableIds.contains(diff.getTargetSnapshotId()))
                .toList();
        snapshotDiffRepository.deleteAll(diffsToDelete);
        snapshotRepository.deleteAllByIdInBatch(deletableIds);
    }

    public List<Snapshot> listSnapshots(String period, int page, int size) {
        return listSnapshots(period, null, null, page, size);
    }

    public List<Snapshot> listSnapshots(String period, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var range = resolveRange(period, from, to);
        if (range != null) {
            return snapshotRepository.findByCapturedAtBetweenOrderByCapturedAtDesc(range.from(), range.to(), pageable).getContent();
        }
        var cutoff = cutoffFor(period);
        if (cutoff != null) {
            return snapshotRepository.findByCapturedAtGreaterThanEqualOrderByCapturedAtDesc(cutoff, pageable).getContent();
        }
        return snapshotRepository.findAllByOrderByCapturedAtDesc(pageable).getContent();
    }

    public Snapshot getSnapshot(long snapshotId) {
        return snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown snapshot id: " + snapshotId));
    }

    public Optional<Snapshot> latestSnapshot() {
        return snapshotRepository.findTopByOrderByCapturedAtDesc();
    }

    public SnapshotDiff latestDiff() {
        return snapshotDiffRepository.findTopByOrderByDiffedAtDesc()
                .orElseThrow(() -> new IllegalArgumentException("No snapshot diff available."));
    }

    public SnapshotDiff getOrComputeDiff(long fromSnapshotId, long toSnapshotId) {
        return snapshotDiffRepository.findByBaseSnapshotIdAndTargetSnapshotId(fromSnapshotId, toSnapshotId)
                .orElseGet(() -> {
                    var base = getSnapshot(fromSnapshotId);
                    var target = getSnapshot(toSnapshotId);
                    var diff = diffCalculator.compute(base, target);
                    return snapshotDiffRepository.save(diff);
                });
    }

    public List<SnapshotDiff> listTimelineDiffs(String period) {
        return listTimelineDiffs(period, null, null);
    }

    public List<SnapshotDiff> listTimelineDiffs(String period, OffsetDateTime from, OffsetDateTime to) {
        var range = resolveRange(period, from, to);
        if (range != null) {
            return snapshotDiffRepository.findByDiffedAtBetweenOrderByDiffedAtAsc(range.from(), range.to());
        }
        var cutoff = cutoffFor(period);
        if (cutoff == null) {
            return snapshotDiffRepository.findAllByOrderByDiffedAtAsc();
        }
        return snapshotDiffRepository.findByDiffedAtGreaterThanEqualOrderByDiffedAtAsc(cutoff);
    }

    public GraphResponse readGraph(Snapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.getGraphJson(), GraphResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse snapshot graph payload.", exception);
        }
    }

    public <T> List<T> readJsonList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse snapshot diff payload.", exception);
        }
    }

    private Optional<Snapshot> capture(
            SnapshotTrigger trigger,
            UUID scanId,
            OffsetDateTime capturedAt,
            long durationMs,
            String note
    ) {
        var currentGraph = graphQueryService.fetchGraph(null, Set.of(), null, null);
        if (currentGraph.nodes().isEmpty() && currentGraph.edges().isEmpty()) {
            return Optional.empty();
        }

        var effectiveCapturedAt = capturedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : capturedAt;
        var effectiveDuration = durationMs > 0 ? durationMs : currentGraph.metadata().scanDurationMs();
        var graphSnapshot = new GraphResponse(
                currentGraph.nodes(),
                currentGraph.edges(),
                new GraphResponse.GraphMetadata(
                        currentGraph.nodes().size(),
                        currentGraph.edges().size(),
                        effectiveCapturedAt,
                        effectiveDuration
                )
        );

        var caller = stsClient.getCallerIdentity();
        var metadataJson = toJson(Map.of(
                "trigger", trigger.name(),
                "scanId", scanId == null ? "" : scanId.toString(),
                "note", note == null ? "" : note
        ));
        var snapshot = new Snapshot(
                effectiveCapturedAt,
                caller.account(),
                awsProperties.region(),
                graphSnapshot.nodes().size(),
                graphSnapshot.edges().size(),
                effectiveDuration,
                trigger,
                scanId,
                toJson(graphSnapshot),
                metadataJson
        );
        var savedSnapshot = snapshotRepository.save(snapshot);

        snapshotRepository.findTopByCapturedAtBeforeOrderByCapturedAtDesc(savedSnapshot.getCapturedAt())
                .ifPresent(previous -> {
                    var diff = snapshotDiffRepository.save(diffCalculator.compute(previous, savedSnapshot));
                    notificationService.notifySnapshotDiff(savedSnapshot, diff);
                });

        return Optional.of(savedSnapshot);
    }

    private OffsetDateTime cutoffFor(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return switch (period.trim().toLowerCase(Locale.ROOT)) {
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> null;
        };
    }

    private SnapshotRange resolveRange(String period, OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) {
            return null;
        }
        var normalizedFrom = from.isAfter(to) ? to : from;
        var normalizedTo = to.isBefore(from) ? from : to;
        return new SnapshotRange(normalizedFrom, normalizedTo);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record SnapshotRange(OffsetDateTime from, OffsetDateTime to) {
    }
}
