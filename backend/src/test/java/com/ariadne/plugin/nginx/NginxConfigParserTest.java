package com.ariadne.plugin.nginx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NginxConfigParserTest {

    private final NginxConfigParser parser = new NginxConfigParser();

    @Test
    void extractsUpstreamsServerNamesAndProxyPasses() {
        var parsed = parser.parse("""
                # ARIADNE FILE: /etc/nginx/nginx.conf
                http {
                  upstream backend {
                    server 10.0.1.10:8080;
                    server app.internal:8081 max_fails=3;
                  }

                  upstream metrics {
                    least_conn;
                    server 127.0.0.1:9090;
                  }

                  server {
                    listen 80;
                    server_name api.example.com api.internal _;

                    location / {
                      proxy_pass http://backend;
                    }

                    location /metrics {
                      proxy_pass http://127.0.0.1:9090/metrics;
                    }
                  }
                }
                """);

        assertThat(parsed.serverNames())
                .containsExactly("api.example.com", "api.internal");
        assertThat(parsed.upstreamNames())
                .containsExactly("backend", "metrics");
        assertThat(parsed.proxyPassTargets())
                .containsExactly("http://backend", "http://127.0.0.1:9090/metrics");

        assertThat(parsed.upstreams()).hasSize(2);
        assertThat(parsed.upstreams().get(0).name()).isEqualTo("backend");
        assertThat(parsed.upstreams().get(0).servers()).hasSize(2);
        assertThat(parsed.upstreams().get(0).servers().get(0).host()).isEqualTo("10.0.1.10");
        assertThat(parsed.upstreams().get(0).servers().get(0).port()).isEqualTo(8080);
        assertThat(parsed.upstreams().get(0).servers().get(1).parameters())
                .containsExactly("max_fails=3");

        assertThat(parsed.proxyPasses()).hasSize(2);
        assertThat(parsed.proxyPasses().get(0).upstreamName()).isEqualTo("backend");
        assertThat(parsed.proxyPasses().get(1).host()).isEqualTo("127.0.0.1");
        assertThat(parsed.proxyPasses().get(1).port()).isEqualTo(9090);
        assertThat(parsed.proxyPasses().get(1).path()).isEqualTo("/metrics");
    }

    @Test
    void returnsEmptyStructureForBlankConfig() {
        var parsed = parser.parse("   ");

        assertThat(parsed.serverNames()).isEmpty();
        assertThat(parsed.upstreamNames()).isEmpty();
        assertThat(parsed.proxyPassTargets()).isEmpty();
        assertThat(parsed.upstreams()).isEmpty();
        assertThat(parsed.proxyPasses()).isEmpty();
    }
}
