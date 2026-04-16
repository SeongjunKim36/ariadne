package com.ariadne.semantic;

public record LabelResult(
        String arn,
        String tier,
        String confidence,
        double confidenceScore,
        String source
) {
}
