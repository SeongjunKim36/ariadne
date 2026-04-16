package com.ariadne.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ariadne")
public class AriadneProperties {

    private final Scan scan = new Scan();
    private final Plugins plugins = new Plugins();
    private final Llm llm = new Llm();

    public Scan getScan() {
        return scan;
    }

    public Plugins getPlugins() {
        return plugins;
    }

    public Llm getLlm() {
        return llm;
    }

    public static class Scan {

        private String schedule = "0 0 * * * *";

        public String getSchedule() {
            return schedule;
        }

        public void setSchedule(String schedule) {
            if (schedule == null || schedule.isBlank()) {
                return;
            }
            this.schedule = schedule;
        }
    }

    public static class Plugins {

        private final Nginx nginx = new Nginx();

        public Nginx getNginx() {
            return nginx;
        }
    }

    public static class Llm {

        private String transmissionLevel;
        private List<String> allowedFields = new ArrayList<>(defaultAllowedFields());
        private List<String> verboseAdditionalFields = new ArrayList<>(defaultVerboseAdditionalFields());

        public String getTransmissionLevel() {
            return transmissionLevel;
        }

        public void setTransmissionLevel(String transmissionLevel) {
            if (transmissionLevel == null) {
                this.transmissionLevel = null;
                return;
            }
            var normalized = transmissionLevel.trim();
            this.transmissionLevel = normalized.isEmpty() ? null : normalized;
        }

        public List<String> getAllowedFields() {
            return List.copyOf(allowedFields);
        }

        public void setAllowedFields(List<String> allowedFields) {
            if (allowedFields == null || allowedFields.isEmpty()) {
                this.allowedFields = new ArrayList<>(defaultAllowedFields());
                return;
            }
            this.allowedFields = new ArrayList<>(allowedFields);
        }

        public List<String> getVerboseAdditionalFields() {
            return List.copyOf(verboseAdditionalFields);
        }

        public void setVerboseAdditionalFields(List<String> verboseAdditionalFields) {
            if (verboseAdditionalFields == null || verboseAdditionalFields.isEmpty()) {
                this.verboseAdditionalFields = new ArrayList<>(defaultVerboseAdditionalFields());
                return;
            }
            this.verboseAdditionalFields = new ArrayList<>(verboseAdditionalFields);
        }

        private static List<String> defaultAllowedFields() {
            return List.of(
                    "arn",
                    "resourceId",
                    "resourceType",
                    "name",
                    "region",
                    "environment",
                    "state",
                    "status",
                    "instanceType",
                    "engine",
                    "engineVersion",
                    "cidr",
                    "cidrBlock",
                    "scheme",
                    "type",
                    "runtime",
                    "groupId",
                    "label",
                    "riskLevel",
                    "isPublic",
                    "addressFamily",
                    "availabilityZone",
                    "privateIp",
                    "publicIp",
                    "endpoint",
                    "handler",
                    "recordCount",
                    "inboundRuleCount",
                    "outboundRuleCount",
                    "port",
                    "protocol",
                    "direction",
                    "confidence",
                    "launchType",
                    "runningCount",
                    "desiredCount",
                    "memoryMb",
                    "instanceClass",
                    "dnsName"
            );
        }

        private static List<String> defaultVerboseAdditionalFields() {
            return List.of(
                    "tags",
                    "env",
                    "envVars",
                    "environmentVariables"
            );
        }
    }

    public static class Nginx {

        private boolean enabled = false;
        private int ssmTimeoutSeconds = 30;
        private List<String> configPaths = new ArrayList<>(defaultConfigPaths());

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSsmTimeoutSeconds() {
            return ssmTimeoutSeconds;
        }

        public void setSsmTimeoutSeconds(int ssmTimeoutSeconds) {
            if (ssmTimeoutSeconds <= 0) {
                return;
            }
            this.ssmTimeoutSeconds = ssmTimeoutSeconds;
        }

        public List<String> getConfigPaths() {
            return List.copyOf(configPaths);
        }

        public void setConfigPaths(List<String> configPaths) {
            if (configPaths == null || configPaths.isEmpty()) {
                this.configPaths = new ArrayList<>(defaultConfigPaths());
                return;
            }
            this.configPaths = new ArrayList<>(configPaths);
        }

        private static List<String> defaultConfigPaths() {
            return List.of(
                    "/etc/nginx/nginx.conf",
                    "/etc/nginx/conf.d/"
            );
        }
    }
}
