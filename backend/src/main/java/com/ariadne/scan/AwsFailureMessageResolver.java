package com.ariadne.scan;

import java.util.Locale;

final class AwsFailureMessageResolver {

    private AwsFailureMessageResolver() {
    }

    static String toUserMessage(Throwable throwable) {
        var message = rootCauseMessage(throwable);
        if (message == null || message.isBlank()) {
            return "AWS access could not be verified. Check local credentials or refresh your SSO session.";
        }

        var normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("unable to load credentials")
                || normalized.contains("unable to locate credentials")
                || normalized.contains("profile file contained no credentials")
                || normalized.contains("session token not found or invalid")) {
            return "AWS credentials are missing or the local SSO session expired. Run `aws sso login` or set static credentials before scanning.";
        }

        if (normalized.contains("expiredtoken")
                || normalized.contains("security token included in the request is invalid")
                || normalized.contains("request signature we calculated does not match")) {
            return "AWS credentials are present but expired or invalid. Refresh the session with `aws sso login` before scanning.";
        }

        if (normalized.contains("accessdenied")
                || normalized.contains("access denied")
                || normalized.contains("not authorized")
                || normalized.contains("is not authorized to perform")) {
            return "AWS credentials are valid, but the scan role is missing required read-only permissions. Check sts:GetCallerIdentity and AWS Describe/List access.";
        }

        return message;
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        String lastMessage = throwable == null ? null : throwable.getMessage();

        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                lastMessage = current.getMessage();
            }
            current = current.getCause();
        }

        return lastMessage;
    }
}
