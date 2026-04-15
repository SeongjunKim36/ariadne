package com.ariadne.api;

import com.ariadne.api.dto.ScanPreflightResponse;
import com.ariadne.scan.ScanPreflightService;
import com.ariadne.scan.ScanRun;
import com.ariadne.scan.ScanService;
import com.ariadne.scan.ScanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScanController.class)
class ScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScanService scanService;

    @MockBean
    private ScanPreflightService scanPreflightService;

    @Test
    void returnsNoContentWhenNoScanHasRunYet() throws Exception {
        when(scanService.findLatestScan()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/scan/latest"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void exposesScanPreflightState() throws Exception {
        when(scanPreflightService.inspect()).thenReturn(new ScanPreflightResponse(
                false,
                "ap-northeast-2",
                null,
                null,
                "default-chain",
                "AWS credentials are missing or the local SSO session expired. Run `aws sso login` or set static credentials before scanning.",
                null
        ));

        mockMvc.perform(get("/api/scan/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.region").value("ap-northeast-2"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("aws sso login")))
                .andExpect(jsonPath("$.warningMessage").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void returnsLatestScanWhenItExists() throws Exception {
        var scanRun = new ScanRun(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), ScanStatus.RUNNING, OffsetDateTime.parse("2026-04-14T02:00:00Z"));
        scanRun.markCompleted(
                OffsetDateTime.parse("2026-04-14T02:01:00Z"),
                12,
                18,
                60_000L,
                null
        );
        when(scanService.findLatestScan()).thenReturn(Optional.of(scanRun));

        mockMvc.perform(get("/api/scan/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalNodes").value(12))
                .andExpect(jsonPath("$.totalEdges").value(18));
    }
}
