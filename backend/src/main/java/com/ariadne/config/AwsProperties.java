package com.ariadne.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import software.amazon.awssdk.regions.Region;

import java.net.URI;

@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String region,
        String endpointUrl,
        String accessKeyId,
        String secretAccessKey
) {

    public AwsProperties {
        region = region == null || region.isBlank() ? "ap-northeast-2" : region;
    }

    public Region awsRegion() {
        return Region.of(region);
    }

    public boolean hasEndpointOverride() {
        return endpointUrl != null && !endpointUrl.isBlank();
    }

    public URI endpointUri() {
        return hasEndpointOverride() ? URI.create(endpointUrl) : null;
    }

    public boolean hasStaticCredentials() {
        return accessKeyId != null
                && !accessKeyId.isBlank()
                && secretAccessKey != null
                && !secretAccessKey.isBlank();
    }
}
