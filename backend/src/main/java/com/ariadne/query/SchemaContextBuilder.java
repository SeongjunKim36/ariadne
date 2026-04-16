package com.ariadne.query;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class SchemaContextBuilder {

    private final Neo4jClient neo4jClient;

    public SchemaContextBuilder(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public String buildContext() {
        var labels = nodeLabels();
        var relationshipTypes = relationshipTypes();
        var propertiesByLabel = propertiesByLabel();
        var relationshipProperties = relationshipProperties();

        var builder = new StringBuilder();
        builder.append("Node labels:\n");
        for (var label : labels) {
            builder.append("- ").append(label);
            var properties = propertiesByLabel.getOrDefault(label, Set.of());
            if (!properties.isEmpty()) {
                builder.append(" (").append(String.join(", ", properties)).append(")");
            }
            builder.append('\n');
        }
        builder.append("\nRelationship types:\n");
        for (var relationshipType : relationshipTypes) {
            builder.append("- ").append(relationshipType);
            var properties = relationshipProperties.getOrDefault(relationshipType, Set.of());
            if (!properties.isEmpty()) {
                builder.append(" (").append(String.join(", ", properties)).append(")");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    public Set<String> nodeLabels() {
        return neo4jClient.query("""
                        MATCH (n)
                        UNWIND labels(n) AS label
                        RETURN DISTINCT label
                        ORDER BY label
                        """)
                .fetch()
                .all()
                .stream()
                .map(row -> (String) row.get("label"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> relationshipTypes() {
        return neo4jClient.query("""
                        MATCH ()-[r]->()
                        RETURN DISTINCT type(r) AS relationshipType
                        ORDER BY relationshipType
                        """)
                .fetch()
                .all()
                .stream()
                .map(row -> (String) row.get("relationshipType"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> allowedProperties() {
        var properties = new TreeSet<String>();
        properties.addAll(collectNodeProperties().values().stream().flatMap(Set::stream).toList());
        properties.addAll(collectRelationshipProperties().values().stream().flatMap(Set::stream).toList());
        return properties;
    }

    private Map<String, Set<String>> propertiesByLabel() {
        return collectNodeProperties();
    }

    private Map<String, Set<String>> relationshipProperties() {
        return collectRelationshipProperties();
    }

    private Map<String, Set<String>> collectNodeProperties() {
        var rows = neo4jClient.query("""
                        MATCH (n)
                        UNWIND labels(n) AS label
                        UNWIND keys(n) AS property
                        RETURN label, collect(DISTINCT property) AS properties
                        ORDER BY label
                        """)
                .fetch()
                .all();
        var propertiesByLabel = new LinkedHashMap<String, Set<String>>();
        for (var row : rows) {
            @SuppressWarnings("unchecked")
            var properties = (java.util.List<String>) row.get("properties");
            propertiesByLabel.put((String) row.get("label"), new TreeSet<>(properties));
        }
        return propertiesByLabel;
    }

    private Map<String, Set<String>> collectRelationshipProperties() {
        var rows = neo4jClient.query("""
                        MATCH ()-[r]->()
                        UNWIND keys(r) AS property
                        RETURN type(r) AS relationshipType, collect(DISTINCT property) AS properties
                        ORDER BY relationshipType
                        """)
                .fetch()
                .all();
        var propertiesByRelationship = new LinkedHashMap<String, Set<String>>();
        for (var row : rows) {
            @SuppressWarnings("unchecked")
            var properties = (java.util.List<String>) row.get("properties");
            propertiesByRelationship.put((String) row.get("relationshipType"), new TreeSet<>(properties));
        }
        return propertiesByRelationship;
    }
}
