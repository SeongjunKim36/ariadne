package com.ariadne.plugin.nginx;

import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.List;
import java.util.Locale;

public final class NginxPluginGuidance {

    public static final List<String> REQUIRED_IAM_ACTIONS = List.of(
            "ssm:DescribeInstanceInformation",
            "ssm:SendCommand",
            "ssm:GetCommandInvocation"
    );

    public static final String OPTIONAL_POLICY_PATH = "iam/ariadne-nginx-plugin-policy.json";

    private NginxPluginGuidance() {
    }

    public static String missingPermissionMessage() {
        return "nginx plugin is enabled, but the scan role is missing SSM permissions (%s). "
                .formatted(String.join(", ", REQUIRED_IAM_ACTIONS))
                + "Attach the optional plugin policy at `%s` before using nginx collection."
                .formatted(OPTIONAL_POLICY_PATH);
    }

    public static String missingManagedInstanceMessage() {
        return "nginx plugin is enabled, but no online SSM-managed EC2 instances were found. "
                + "Install the SSM Agent and confirm the instances appear in Systems Manager before using nginx collection.";
    }

    public static String scanFailureMessage(String instanceId, Throwable throwable) {
        if (isAccessDenied(throwable)) {
            return missingPermissionMessage();
        }
        var detail = rootCauseMessage(throwable);
        if (detail == null || detail.isBlank()) {
            detail = "Unknown SSM error";
        }
        return "Plugin nginx failed for instance %s: %s".formatted(instanceId, detail);
    }

    public static boolean isAccessDenied(Throwable throwable) {
        var message = rootCauseMessage(throwable);
        if (message != null) {
            var normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("accessdenied")
                    || normalized.contains("access denied")
                    || normalized.contains("not authorized")
                    || normalized.contains("is not authorized to perform")) {
                return true;
            }
        }

        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AwsServiceException awsServiceException) {
                var errorCode = awsServiceException.awsErrorDetails() == null
                        ? null
                        : awsServiceException.awsErrorDetails().errorCode();
                if (errorCode != null && errorCode.toLowerCase(Locale.ROOT).contains("accessdenied")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
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
