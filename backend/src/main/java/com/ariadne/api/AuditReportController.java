package com.ariadne.api;

import com.ariadne.api.dto.AuditExplanationResponse;
import com.ariadne.api.dto.AuditFindingResponse;
import com.ariadne.api.dto.AuditReportResponse;
import com.ariadne.api.dto.AuditRuleResponse;
import com.ariadne.audit.AuditEngine;
import com.ariadne.audit.AuditExplanationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/audit")
public class AuditReportController {

    private final AuditEngine auditEngine;
    private final AuditExplanationService auditExplanationService;

    public AuditReportController(AuditEngine auditEngine, AuditExplanationService auditExplanationService) {
        this.auditEngine = auditEngine;
        this.auditExplanationService = auditExplanationService;
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.OK)
    public AuditReportResponse runAudit() {
        return auditEngine.runFullAudit();
    }

    @GetMapping("/latest")
    public AuditReportResponse latestReport() {
        return auditEngine.latestReport()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NO_CONTENT));
    }

    @GetMapping("/findings")
    public List<AuditFindingResponse> findings(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String category
    ) {
        try {
            return auditEngine.latestFindings(level, category);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NO_CONTENT, exception.getMessage(), exception);
        }
    }

    @GetMapping("/rules")
    public List<AuditRuleResponse> rules() {
        return auditEngine.rules();
    }

    @PostMapping("/explain")
    public AuditExplanationResponse explainLatest() {
        var latestReport = auditEngine.latestReport()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NO_CONTENT));
        return auditExplanationService.explain(latestReport);
    }
}
