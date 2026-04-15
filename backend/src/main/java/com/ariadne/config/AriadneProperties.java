package com.ariadne.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ariadne")
public class AriadneProperties {

    private final Scan scan = new Scan();
    private final Plugins plugins = new Plugins();

    public Scan getScan() {
        return scan;
    }

    public Plugins getPlugins() {
        return plugins;
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
