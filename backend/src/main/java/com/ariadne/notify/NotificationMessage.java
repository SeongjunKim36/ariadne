package com.ariadne.notify;

public record NotificationMessage(
        NotificationSeverity severity,
        String title,
        String body
) {

    public String render() {
        return """
                [%s] %s
                %s
                """.formatted(severity.name(), title, body);
    }
}
