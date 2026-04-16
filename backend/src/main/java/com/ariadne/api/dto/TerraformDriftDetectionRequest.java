package com.ariadne.api.dto;

public record TerraformDriftDetectionRequest(
        String path,
        String rawStateJson
) {
}
