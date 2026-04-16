package com.ariadne.api;

import com.ariadne.api.dto.LlmAuditLogResponse;
import com.ariadne.api.dto.LlmAuditStatsResponse;
import com.ariadne.llm.LlmAuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LlmAuditLogService llmAuditLogService;

    @Test
    void returnsAuditLogEntries() throws Exception {
        when(llmAuditLogService.findLogs(null, null)).thenReturn(List.of(
                new LlmAuditLogResponse(
                        1L,
                        OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                        "strict",
                        "prod topology 알려줘",
                        "subgraph:prod",
                        3,
                        2,
                        "SUCCEEDED",
                        null
                )
        ));

        mockMvc.perform(get("/api/audit/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transmissionLevel").value("strict"))
                .andExpect(jsonPath("$[0].nodeCount").value(3))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void returnsAuditStats() throws Exception {
        when(llmAuditLogService.stats(null, null)).thenReturn(new LlmAuditStatsResponse(
                4,
                3,
                1,
                12.5,
                8.0
        ));

        mockMvc.perform(get("/api/audit/llm/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(4))
                .andExpect(jsonPath("$.successfulCalls").value(3))
                .andExpect(jsonPath("$.failedCalls").value(1));
    }
}
