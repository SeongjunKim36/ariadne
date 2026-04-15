package com.ariadne.llm;

import java.util.Locale;

enum TransmissionLevel {
    STRICT,
    NORMAL,
    VERBOSE;

    static TransmissionLevel from(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return STRICT;
        }

        try {
            return TransmissionLevel.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return STRICT;
        }
    }
}
