package com.ariadne.graph.service;

import com.ariadne.graph.relationship.RelationshipTypes;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GraphViewSupport {

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

    static String toFrontendType(String resourceType) {
        if (resourceType == null) {
            return "unknown";
        }
        return switch (resourceType.toUpperCase(Locale.ROOT)) {
            case "VPC" -> "vpc-group";
            case "SUBNET", "DB_SUBNET_GROUP" -> "subnet-group";
            case "SECURITY_GROUP" -> "sg";
            case "LOAD_BALANCER" -> "alb";
            case "ECS_CLUSTER" -> "ecs-cluster-group";
            case "ECS_SERVICE" -> "ecs-service";
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
