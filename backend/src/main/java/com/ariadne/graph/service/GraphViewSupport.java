package com.ariadne.graph.service;

import com.ariadne.graph.relationship.RelationshipTypes;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GraphViewSupport {

    private static final Set<String> DETAIL_ONLY_RESOURCE_TYPES = Set.of(
            "ECS_TASK_DEFINITION",
            "NGINX_CONFIG"
    );

    private static final Set<String> PARENT_RELATIONSHIP_TYPES = Set.of(
            RelationshipTypes.BELONGS_TO,
            RelationshipTypes.IN_SUBNET_GROUP,
            RelationshipTypes.RUNS_IN
    );

    private GraphViewSupport() {
    }

    static boolean isParentRelationship(String relationshipType) {
        return PARENT_RELATIONSHIP_TYPES.contains(relationshipType);
    }

    static boolean isDetailOnlyResourceType(String resourceType) {
        return resourceType != null && DETAIL_ONLY_RESOURCE_TYPES.contains(resourceType.toUpperCase(Locale.ROOT));
    }

    static Set<String> detailOnlyResourceTypes() {
        return DETAIL_ONLY_RESOURCE_TYPES;
    }

    static Set<String> parentRelationshipTypes() {
        return PARENT_RELATIONSHIP_TYPES;
    }

    static String toFrontendType(String resourceType) {
        if (resourceType == null) {
            return "unknown";
        }
        return switch (resourceType.toUpperCase(Locale.ROOT)) {
            case "VPC" -> "vpc-group";
            case "SUBNET", "DB_SUBNET_GROUP" -> "subnet-group";
            case "SECURITY_GROUP" -> "sg";
            case "CIDR_SOURCE" -> "cidr";
            case "IAM_ROLE" -> "iam-role";
            case "NGINX_CONFIG" -> "nginx-config";
            case "LOAD_BALANCER" -> "alb";
            case "ECS_CLUSTER" -> "ecs-cluster-group";
            case "ECS_SERVICE" -> "ecs-service";
            case "ECS_TASK_DEFINITION" -> "ecs-task-def";
            case "S3_BUCKET" -> "s3";
            case "LAMBDA_FUNCTION" -> "lambda";
            case "ROUTE53_ZONE" -> "route53";
            default -> resourceType.toLowerCase(Locale.ROOT);
        };
    }

    static boolean isGroupParent(String resourceType) {
        return "VPC".equals(resourceType)
                || "SUBNET".equals(resourceType)
                || "DB_SUBNET_GROUP".equals(resourceType)
                || "ECS_CLUSTER".equals(resourceType);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asProperties(Object value) {
        return (Map<String, Object>) value;
    }
}
