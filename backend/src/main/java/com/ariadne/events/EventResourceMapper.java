package com.ariadne.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EventResourceMapper {

    private final ObjectMapper objectMapper;

    public EventResourceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventResourceMapping map(String rawEventJson) {
        try {
            var root = objectMapper.readTree(rawEventJson);
            var eventId = text(root, "id");
            var source = text(root, "source");
            var detailType = text(root, "detail-type");
            var eventTime = parseTime(text(root, "time"));
            var detail = root.path("detail");
            var action = text(detail, "eventName");

            var resourceArn = firstNonBlank(
                    firstResourceArn(detail),
                    buildArnFromDetail(source, detail)
            );
            var resourceType = inferResourceType(source, resourceArn, detail);
            var collectorTypes = collectorTypes(resourceType);
            var summary = firstNonBlank(action, detailType, source, "event");

            if (resourceType == null || collectorTypes.isEmpty()) {
                return new EventResourceMapping(eventId, eventTime, source, detailType, action, resourceArn, null, Set.of(), summary);
            }

            return new EventResourceMapping(
                    eventId,
                    eventTime,
                    source,
                    detailType,
                    action,
                    resourceArn,
                    resourceType,
                    collectorTypes,
                    summary
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to parse EventBridge event payload.", exception);
        }
    }

    private String inferResourceType(String source, String resourceArn, JsonNode detail) {
        if (resourceArn != null) {
            if (resourceArn.contains(":instance/")) {
                return "EC2";
            }
            if (resourceArn.contains(":security-group/")) {
                return "SECURITY_GROUP";
            }
            if (resourceArn.contains(":subnet/")) {
                return "SUBNET";
            }
            if (resourceArn.contains(":vpc/")) {
                return "VPC";
            }
            if (resourceArn.contains(":db:") || resourceArn.contains(":db/")) {
                return "RDS";
            }
            if (resourceArn.contains(":loadbalancer/")) {
                return "LOAD_BALANCER";
            }
            if (resourceArn.contains(":function:")) {
                return "LAMBDA_FUNCTION";
            }
            if (resourceArn.startsWith("arn:aws:s3:::")) {
                return "S3_BUCKET";
            }
            if (resourceArn.contains(":hostedzone/")) {
                return "ROUTE53_ZONE";
            }
            if (resourceArn.contains(":cluster/")) {
                return "ECS_CLUSTER";
            }
            if (resourceArn.contains(":service/")) {
                return "ECS_SERVICE";
            }
            if (resourceArn.contains(":role/")) {
                return "IAM_ROLE";
            }
        }

        return switch (source == null ? "" : source.toLowerCase(Locale.ROOT)) {
            case "aws.ec2" -> inferEc2Type(detail);
            case "aws.rds" -> "RDS";
            case "aws.elasticloadbalancing" -> "LOAD_BALANCER";
            case "aws.lambda" -> "LAMBDA_FUNCTION";
            case "aws.s3" -> "S3_BUCKET";
            case "aws.route53" -> "ROUTE53_ZONE";
            case "aws.ecs" -> "ECS_SERVICE";
            case "aws.iam" -> "IAM_ROLE";
            default -> null;
        };
    }

    private String inferEc2Type(JsonNode detail) {
        if (!detail.path("requestParameters").path("groupId").isMissingNode()) {
            return "SECURITY_GROUP";
        }
        if (!detail.path("requestParameters").path("subnetId").isMissingNode()) {
            return "SUBNET";
        }
        if (!detail.path("requestParameters").path("vpcId").isMissingNode()) {
            return "VPC";
        }
        if (!detail.path("requestParameters").path("instanceId").isMissingNode()) {
            return "EC2";
        }
        return "EC2";
    }

    private Set<String> collectorTypes(String resourceType) {
        if (resourceType == null) {
            return Set.of();
        }
        return Map.<String, Set<String>>ofEntries(
                Map.entry("EC2", Set.of("EC2")),
                Map.entry("SECURITY_GROUP", Set.of("SECURITY_GROUP")),
                Map.entry("SUBNET", Set.of("SUBNET")),
                Map.entry("VPC", Set.of("VPC")),
                Map.entry("RDS", Set.of("RDS")),
                Map.entry("LOAD_BALANCER", Set.of("LOAD_BALANCER")),
                Map.entry("LAMBDA_FUNCTION", Set.of("LAMBDA_FUNCTION")),
                Map.entry("S3_BUCKET", Set.of("S3_BUCKET")),
                Map.entry("ROUTE53_ZONE", Set.of("ROUTE53_ZONE")),
                Map.entry("ECS_CLUSTER", Set.of("ECS_CLUSTER")),
                Map.entry("ECS_SERVICE", Set.of("ECS_SERVICE")),
                Map.entry("IAM_ROLE", Set.of("IAM_ROLE"))
        ).getOrDefault(resourceType, Set.of());
    }

    private String firstResourceArn(JsonNode detail) {
        var resources = detail.path("resources");
        if (resources.isArray() && !resources.isEmpty()) {
            var first = resources.get(0);
            return firstNonBlank(text(first, "ARN"), text(first, "arn"));
        }
        return null;
    }

    private String buildArnFromDetail(String source, JsonNode detail) {
        var requestParameters = detail.path("requestParameters");
        return firstNonBlank(
                text(requestParameters, "loadBalancerArn"),
                text(requestParameters, "targetGroupArn"),
                text(requestParameters, "functionName"),
                text(requestParameters, "bucketName"),
                text(requestParameters, "instanceId"),
                text(requestParameters, "groupId"),
                text(requestParameters, "subnetId"),
                text(requestParameters, "vpcId"),
                text(requestParameters, "dBInstanceIdentifier"),
                text(requestParameters, "hostedZoneId"),
                text(requestParameters, "cluster"),
                text(requestParameters, "service"),
                text(requestParameters, "roleName")
        );
    }

    private OffsetDateTime parseTime(String value) {
        return value == null ? null : OffsetDateTime.parse(value);
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
