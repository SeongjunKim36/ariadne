package com.ariadne.api;

import com.ariadne.api.dto.ScanStatusResponse;
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

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
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
    public ScanStatusResponse getLatestScan() {
        return ScanStatusResponse.from(scanService.getLatestScan());
    }
}
