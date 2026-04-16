package com.ariadne.api;

import com.ariadne.api.dto.DriftItemResponse;
import com.ariadne.api.dto.DriftReportResponse;
import com.ariadne.api.dto.TerraformDriftDetectionRequest;
import com.ariadne.drift.TerraformDriftDetector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drift")
public class DriftController {

    private final TerraformDriftDetector terraformDriftDetector;

    public DriftController(TerraformDriftDetector terraformDriftDetector) {
        this.terraformDriftDetector = terraformDriftDetector;
    }

    @PostMapping("/terraform")
    public DriftReportResponse detect(@RequestBody(required = false) TerraformDriftDetectionRequest request) {
        var run = terraformDriftDetector.detect(request);
        return DriftReportResponse.of(
                run,
                terraformDriftDetector.readItems(run).stream().map(DriftItemResponse::from).toList()
        );
    }

    @GetMapping("/latest")
    public ResponseEntity<DriftReportResponse> latest() {
        try {
            var run = terraformDriftDetector.latestRun();
            return ResponseEntity.ok(DriftReportResponse.of(
                    run,
                    terraformDriftDetector.readItems(run).stream().map(DriftItemResponse::from).toList()
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.noContent().build();
        }
    }
}
