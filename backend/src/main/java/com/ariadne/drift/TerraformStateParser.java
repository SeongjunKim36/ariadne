package com.ariadne.drift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TerraformStateParser {

    private final ObjectMapper objectMapper;

    public TerraformStateParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TfResource> parse(String rawStateJson) {
        try {
            var root = objectMapper.readTree(rawStateJson);
            var resources = root.path("resources");
            var results = new ArrayList<TfResource>();

            for (var resourceNode : resources) {
                var tfType = resourceNode.path("type").asText();
                var baseAddress = resourceNode.path("module").isMissingNode()
                        ? resourceNode.path("type").asText() + "." + resourceNode.path("name").asText()
                        : resourceNode.path("module").asText() + "." + resourceNode.path("type").asText() + "." + resourceNode.path("name").asText();
                var instances = resourceNode.path("instances");
                for (int index = 0; index < instances.size(); index++) {
                    var instance = instances.get(index);
                    var attributes = instance.path("attributes");
                    var resource = toResource(tfType, baseAddress + "[" + index + "]", attributes);
                    if (resource != null) {
                        results.add(resource);
                    }
                }
            }

            return results;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to parse Terraform state JSON.", exception);
        }
    }

    private TfResource toResource(String tfType, String address, JsonNode attributes) {
        var resourceType = mapResourceType(tfType);
        if (resourceType == null) {
            return null;
        }

        var properties = new LinkedHashMap<String, Object>();
        String arn = text(attributes, "arn");
        String resourceId = text(attributes, "id");
        String name = text(attributes, "name");

        switch (tfType) {
            case "aws_instance" -> {
                properties.put("instanceType", text(attributes, "instance_type"));
                properties.put("privateIp", text(attributes, "private_ip"));
                properties.put("publicIp", text(attributes, "public_ip"));
                properties.put("subnetId", text(attributes, "subnet_id"));
                name = firstNonBlank(name, tag(attributes, "Name"));
            }
            case "aws_db_instance" -> {
                resourceId = firstNonBlank(resourceId, text(attributes, "identifier"));
                name = firstNonBlank(name, resourceId);
                properties.put("engine", text(attributes, "engine"));
                properties.put("engineVersion", text(attributes, "engine_version"));
                properties.put("instanceClass", text(attributes, "instance_class"));
                properties.put("publiclyAccessible", attributes.path("publicly_accessible").asBoolean(false));
                properties.put("storageEncrypted", attributes.path("storage_encrypted").asBoolean(false));
            }
            case "aws_security_group" -> {
                resourceId = firstNonBlank(resourceId, text(attributes, "id"));
                name = firstNonBlank(name, text(attributes, "name"));
                properties.put("description", text(attributes, "description"));
            }
            case "aws_vpc" -> {
                properties.put("cidrBlock", text(attributes, "cidr_block"));
                name = firstNonBlank(name, tag(attributes, "Name"));
            }
            case "aws_subnet" -> {
                properties.put("cidrBlock", text(attributes, "cidr_block"));
                properties.put("availabilityZone", text(attributes, "availability_zone"));
                properties.put("isPublic", attributes.path("map_public_ip_on_launch").asBoolean(false));
                name = firstNonBlank(name, tag(attributes, "Name"), resourceId);
            }
            case "aws_lb", "aws_alb" -> {
                resourceId = firstNonBlank(resourceId, text(attributes, "arn_suffix"), text(attributes, "id"));
                name = firstNonBlank(name, text(attributes, "name"));
                properties.put("dnsName", text(attributes, "dns_name"));
                properties.put("scheme", attributes.path("internal").asBoolean(false) ? "internal" : "internet-facing");
            }
            case "aws_s3_bucket" -> {
                resourceId = firstNonBlank(text(attributes, "bucket"), resourceId);
                name = firstNonBlank(name, resourceId);
                arn = firstNonBlank(arn, resourceId == null ? null : "arn:aws:s3:::" + resourceId);
            }
            case "aws_lambda_function" -> {
                resourceId = firstNonBlank(text(attributes, "function_name"), resourceId);
                name = firstNonBlank(name, resourceId);
                properties.put("runtime", text(attributes, "runtime"));
                properties.put("handler", text(attributes, "handler"));
            }
            case "aws_route53_zone" -> {
                resourceId = firstNonBlank(text(attributes, "zone_id"), resourceId);
                name = firstNonBlank(name, text(attributes, "name"), resourceId);
                properties.put("recordCount", attributes.path("resource_record_set_count").asInt(0));
            }
            case "aws_ecs_cluster" -> {
                resourceId = firstNonBlank(text(attributes, "arn"), resourceId);
                name = firstNonBlank(name, text(attributes, "name"));
            }
            case "aws_ecs_service" -> {
                resourceId = firstNonBlank(text(attributes, "id"), resourceId);
                name = firstNonBlank(name, text(attributes, "name"));
                properties.put("desiredCount", attributes.path("desired_count").asInt(0));
                properties.put("launchType", text(attributes, "launch_type"));
            }
            case "aws_iam_role" -> {
                resourceId = firstNonBlank(text(attributes, "name"), resourceId);
                name = firstNonBlank(name, resourceId);
            }
            default -> {
                return null;
            }
        }

        properties.values().removeIf(value -> value == null || value.toString().isBlank());
        if (name != null) {
            properties.put("name", name);
        }
        if (resourceId != null) {
            properties.put("resourceId", resourceId);
        }

        return new TfResource(address, tfType, resourceType, arn, resourceId, name, Map.copyOf(properties));
    }

    private String mapResourceType(String tfType) {
        return switch (tfType) {
            case "aws_instance" -> "EC2";
            case "aws_db_instance" -> "RDS";
            case "aws_security_group" -> "SECURITY_GROUP";
            case "aws_vpc" -> "VPC";
            case "aws_subnet" -> "SUBNET";
            case "aws_lb", "aws_alb" -> "LOAD_BALANCER";
            case "aws_s3_bucket" -> "S3_BUCKET";
            case "aws_lambda_function" -> "LAMBDA_FUNCTION";
            case "aws_route53_zone" -> "ROUTE53_ZONE";
            case "aws_ecs_cluster" -> "ECS_CLUSTER";
            case "aws_ecs_service" -> "ECS_SERVICE";
            case "aws_iam_role" -> "IAM_ROLE";
            default -> null;
        };
    }

    private String tag(JsonNode attributes, String key) {
        var tags = attributes.path("tags");
        var tagNode = tags.path(key);
        return tagNode.isMissingNode() || tagNode.isNull() ? null : tagNode.asText();
    }

    private String text(JsonNode node, String key) {
        var child = node.path(key);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        var value = child.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
