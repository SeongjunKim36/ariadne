package com.ariadne.api;

import com.ariadne.api.dto.LlmAuditLogResponse;
import com.ariadne.api.dto.LlmAuditStatsResponse;
import com.ariadne.llm.LlmAuditLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final LlmAuditLogService llmAuditLogService;

    public AuditController(LlmAuditLogService llmAuditLogService) {
        this.llmAuditLogService = llmAuditLogService;
    }

    @GetMapping("/llm")
    public List<LlmAuditLogResponse> getLlmAuditLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return llmAuditLogService.findLogs(from, to);
    }

    @GetMapping("/llm/stats")
    public LlmAuditStatsResponse getLlmAuditStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return llmAuditLogService.stats(from, to);
    }
}
