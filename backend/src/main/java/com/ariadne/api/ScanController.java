package com.ariadne.api;

import com.ariadne.api.dto.ScanStatusResponse;
import com.ariadne.scan.ScanPreflightService;
import com.ariadne.scan.ScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final ScanService scanService;
    private final ScanPreflightService scanPreflightService;

    public ScanController(ScanService scanService, ScanPreflightService scanPreflightService) {
        this.scanService = scanService;
        this.scanPreflightService = scanPreflightService;
    }

    @PostMapping
    public ResponseEntity<ScanStatusResponse> triggerScan() {
        var scanRun = scanService.triggerScan();
        return ResponseEntity.accepted()
                .location(URI.create("/api/scan/" + scanRun.getScanId() + "/status"))
                .body(ScanStatusResponse.from(scanRun));
    }

    @GetMapping("/{scanId}/status")
    public ScanStatusResponse getScanStatus(@PathVariable UUID scanId) {
        return ScanStatusResponse.from(scanService.getScan(scanId));
    }

    @GetMapping("/latest")
    public ResponseEntity<ScanStatusResponse> getLatestScan() {
        return scanService.findLatestScan()
                .map(ScanStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/preflight")
    public com.ariadne.api.dto.ScanPreflightResponse getScanPreflight() {
        return scanPreflightService.inspect();
    }
}
