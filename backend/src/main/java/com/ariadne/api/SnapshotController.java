package com.ariadne.api;

import com.ariadne.api.dto.EdgeDiffResponse;
import com.ariadne.api.dto.NodeDiffResponse;
import com.ariadne.api.dto.SnapshotDiffResponse;
import com.ariadne.api.dto.SnapshotResponse;
import com.ariadne.api.dto.SnapshotSummaryResponse;
import com.ariadne.snapshot.EdgeChange;
import com.ariadne.snapshot.NodeChange;
import com.ariadne.snapshot.SnapshotDiff;
import com.ariadne.snapshot.SnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    public SnapshotController(SnapshotService snapshotService, ObjectMapper objectMapper) {
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<SnapshotSummaryResponse> listSnapshots(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        return snapshotService.listSnapshots(period, from, to, page, size).stream()
                .map(SnapshotSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{snapshotId}")
    public SnapshotResponse getSnapshot(@PathVariable long snapshotId) {
        var snapshot = snapshotService.getSnapshot(snapshotId);
        var metadata = snapshot.getMetadataJson() == null || snapshot.getMetadataJson().isBlank()
                ? Map.<String, Object>of()
                : parseMetadata(snapshot.getMetadataJson());
        return new SnapshotResponse(
                SnapshotSummaryResponse.from(snapshot),
                snapshotService.readGraph(snapshot),
                metadata
        );
    }

    @GetMapping("/diff")
    public SnapshotDiffResponse diff(
            @RequestParam("from") long fromSnapshotId,
            @RequestParam("to") long toSnapshotId
    ) {
        return toResponse(snapshotService.getOrComputeDiff(fromSnapshotId, toSnapshotId));
    }

    @GetMapping("/diff/latest")
    public ResponseEntity<SnapshotDiffResponse> latestDiff() {
        try {
            return ResponseEntity.ok(toResponse(snapshotService.latestDiff()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.noContent().build();
        }
    }

    private SnapshotDiffResponse toResponse(SnapshotDiff diff) {
        var baseSnapshot = snapshotService.getSnapshot(diff.getBaseSnapshotId());
        var targetSnapshot = snapshotService.getSnapshot(diff.getTargetSnapshotId());
        return SnapshotDiffResponse.of(
                diff,
                baseSnapshot,
                targetSnapshot,
                snapshotService.readJsonList(diff.getAddedNodesJson(), NodeChange.class).stream().map(NodeDiffResponse::from).toList(),
                snapshotService.readJsonList(diff.getRemovedNodesJson(), NodeChange.class).stream().map(NodeDiffResponse::from).toList(),
                snapshotService.readJsonList(diff.getModifiedNodesJson(), NodeChange.class).stream().map(NodeDiffResponse::from).toList(),
                snapshotService.readJsonList(diff.getAddedEdgesJson(), EdgeChange.class).stream().map(EdgeDiffResponse::from).toList(),
                snapshotService.readJsonList(diff.getRemovedEdgesJson(), EdgeChange.class).stream().map(EdgeDiffResponse::from).toList(),
                snapshotService.readJsonList(diff.getModifiedEdgesJson(), EdgeChange.class).stream().map(EdgeDiffResponse::from).toList()
        );
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse snapshot metadata JSON.", exception);
        }
    }
}
