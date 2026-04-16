package com.ariadne.snapshot;

public record PropertyChange(
        Object beforeValue,
        Object afterValue
) {
}
