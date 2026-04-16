package com.ariadne.api.dto;

import com.ariadne.semantic.LabelResult;

public record LabelResponse(
        String arn,
        String tier,
        String confidence,
        double confidenceScore,
        String source
) {

    public static LabelResponse from(LabelResult labelResult) {
        return new LabelResponse(
                labelResult.arn(),
                labelResult.tier(),
                labelResult.confidence(),
                labelResult.confidenceScore(),
                labelResult.source()
        );
    }
}
