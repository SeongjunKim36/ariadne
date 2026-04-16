package com.ariadne.api;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.graph.service.GraphQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphQueryService graphQueryService;

    public GraphController(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    @GetMapping
    public GraphResponse getGraph(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String vpc,
            @RequestParam(required = false) String tier
    ) {
        return graphQueryService.fetchGraph(env, parseTypes(type), vpc, tier);
    }

    private Set<String> parseTypes(String type) {
        if (type == null || type.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(type.split(","))
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }
}
