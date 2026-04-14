package com.ariadne.security;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RedactionEngine {

    public static final String REDACTED = "***REDACTED***";

    private static final List<Pattern> KEY_PATTERNS = List.of(
            Pattern.compile("(?i).*(password|passwd|secret|token|key|credential|auth).*"),
            Pattern.compile("(?i).*(api.?key|access.?key|private.?key).*"),
            Pattern.compile("(?i).*(jwt|bearer|oauth).*"),
            Pattern.compile("(?i).*(database.?url|connection.?string|dsn).*")
    );

    private static final List<Pattern> VALUE_PATTERNS = List.of(
            Pattern.compile("(?i)^(sk-|pk-|ak-|rk-)\\w+"),
            Pattern.compile("(?i)^AKIA[0-9A-Z]{16}$"),
            Pattern.compile("^[A-Za-z0-9+/]{40,}={0,2}$"),
            Pattern.compile("(?i)^jdbc:[a-z0-9]+://.*password=[^&\\s;]+.*$")
    );

    private static final Pattern PASSWORD_PARAMETER_PATTERN = Pattern.compile(
            "(?i)(password=)([^&\\s;]+)"
    );

    public Map<String, String> redact(Map<String, String> values) {
        var redacted = new LinkedHashMap<String, String>();
        if (values == null || values.isEmpty()) {
            return redacted;
        }
        for (var entry : values.entrySet()) {
            redacted.put(entry.getKey(), redactValue(entry.getKey(), entry.getValue()));
        }
        return redacted;
    }

    public Map<String, String> redactWhitelistMode(Map<String, String> values, Set<String> allowedKeys) {
        var redacted = new LinkedHashMap<String, String>();
        if (values == null || values.isEmpty()) {
            return redacted;
        }
        for (var entry : values.entrySet()) {
            if (allowedKeys != null && allowedKeys.contains(entry.getKey())) {
                redacted.put(entry.getKey(), redactValue(entry.getKey(), entry.getValue()));
            } else {
                redacted.put(entry.getKey(), REDACTED);
            }
        }
        return redacted;
    }

    public String redactValue(String key, String value) {
        if (value == null) {
            return null;
        }

        var partiallyRedacted = partiallyRedact(value);
        if (!partiallyRedacted.equals(value)) {
            return partiallyRedacted;
        }

        if (shouldRedactKey(key) || shouldRedactValue(value)) {
            return REDACTED;
        }
        return value;
    }

    public boolean shouldRedactKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return KEY_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(key).matches());
    }

    public boolean shouldRedactValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return VALUE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(value).matches());
    }

    private String partiallyRedact(String value) {
        return PASSWORD_PARAMETER_PATTERN.matcher(value).replaceAll("$1" + REDACTED);
    }
}
