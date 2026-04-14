package com.ariadne.api.dto;

public record ScanPreflightResponse(
        boolean ready,
        String region,
        String accountId,
        String callerArn,
        String authenticationMode,
        String message
) {
}
