package com.ariadne.api;

import com.ariadne.api.dto.NlQueryRequest;
import com.ariadne.api.dto.NlQueryResponse;
import com.ariadne.query.NlQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final NlQueryService nlQueryService;

    public QueryController(NlQueryService nlQueryService) {
        this.nlQueryService = nlQueryService;
    }

    @PostMapping
    public NlQueryResponse query(@RequestBody NlQueryRequest request) {
        return nlQueryService.query(request.query());
    }

    @GetMapping("/examples")
    public List<String> examples() {
        return nlQueryService.examples();
    }
}
