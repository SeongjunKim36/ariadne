package com.ariadne.api;

import com.ariadne.api.dto.ArchitectureSummaryResponse;
import com.ariadne.semantic.ArchitectureSummaryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final ArchitectureSummaryService architectureSummaryService;

    public SummaryController(ArchitectureSummaryService architectureSummaryService) {
        this.architectureSummaryService = architectureSummaryService;
    }

    @PostMapping("/generate")
    public ArchitectureSummaryResponse generate(
            @RequestParam(required = false, defaultValue = "ko") String lang
    ) {
        return architectureSummaryService.generate(lang);
    }
}
