package com.ariadne.notify;

import com.ariadne.config.AriadneProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class SlackNotifier {

    private final AriadneProperties ariadneProperties;
    private final RestClient restClient;

    public SlackNotifier(AriadneProperties ariadneProperties) {
        this.ariadneProperties = ariadneProperties;
        this.restClient = RestClient.create();
    }

    public boolean enabled() {
        var slack = ariadneProperties.getNotifications().getSlack();
        return slack.isEnabled() && slack.getWebhookUrl() != null && !slack.getWebhookUrl().isBlank();
    }

    public void send(String text) {
        if (!enabled()) {
            return;
        }
        restClient.post()
                .uri(ariadneProperties.getNotifications().getSlack().getWebhookUrl())
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
    }
}
