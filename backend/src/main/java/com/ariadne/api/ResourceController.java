package com.ariadne.api;

import com.ariadne.api.dto.ResourceDetailResponse;
import com.ariadne.graph.service.ResourceQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceQueryService resourceQueryService;

    public ResourceController(ResourceQueryService resourceQueryService) {
        this.resourceQueryService = resourceQueryService;
    }

    @GetMapping
    public ResourceDetailResponse getResource(
            @RequestParam(required = false) String arn,
            @RequestParam(required = false) String resourceId
    ) {
        if ((arn == null || arn.isBlank()) && (resourceId == null || resourceId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either arn or resourceId is required");
        }
        if (arn != null && !arn.isBlank() && resourceId != null && !resourceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use either arn or resourceId, not both");
        }

        try {
            if (arn != null && !arn.isBlank()) {
                return resourceQueryService.findByArn(arn);
            }
            return resourceQueryService.findByResourceId(resourceId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
