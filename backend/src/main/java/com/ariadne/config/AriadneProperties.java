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

        private String apiKey;
        private String defaultModel = "claude-sonnet-4-6-20250514";
        private String complexModel = "claude-opus-4-6-20250610";
        private String transmissionLevel;
        private int maxInputTokens = 8000;
        private int maxOutputTokens = 4096;
        private double dailyBudgetUsd = 5.0;
        private int timeoutSeconds = 30;
        private int retryMaxAttempts = 2;
        private int retryBackoffSeconds = 2;
        private List<String> allowedFields = new ArrayList<>(defaultAllowedFields());
        private List<String> verboseAdditionalFields = new ArrayList<>(defaultVerboseAdditionalFields());

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            if (defaultModel == null || defaultModel.isBlank()) {
                return;
            }
            this.defaultModel = defaultModel.trim();
        }

        public String getComplexModel() {
            return complexModel;
        }

        public void setComplexModel(String complexModel) {
            if (complexModel == null || complexModel.isBlank()) {
                return;
            }
            this.complexModel = complexModel.trim();
        }

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

        public int getMaxInputTokens() {
            return maxInputTokens;
        }

        public void setMaxInputTokens(int maxInputTokens) {
            if (maxInputTokens <= 0) {
                return;
            }
            this.maxInputTokens = maxInputTokens;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            if (maxOutputTokens <= 0) {
                return;
            }
            this.maxOutputTokens = maxOutputTokens;
        }

        public double getDailyBudgetUsd() {
            return dailyBudgetUsd;
        }

        public void setDailyBudgetUsd(double dailyBudgetUsd) {
            if (dailyBudgetUsd <= 0) {
                return;
            }
            this.dailyBudgetUsd = dailyBudgetUsd;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                return;
            }
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getRetryMaxAttempts() {
            return retryMaxAttempts;
        }

        public void setRetryMaxAttempts(int retryMaxAttempts) {
            if (retryMaxAttempts < 0) {
                return;
            }
            this.retryMaxAttempts = retryMaxAttempts;
        }

        public int getRetryBackoffSeconds() {
            return retryBackoffSeconds;
        }

        public void setRetryBackoffSeconds(int retryBackoffSeconds) {
            if (retryBackoffSeconds < 0) {
                return;
            }
            this.retryBackoffSeconds = retryBackoffSeconds;
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
