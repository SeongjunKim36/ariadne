package com.ariadne.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwsFailureMessageResolverTest {

    @Test
    void mapsMissingCredentialErrorsToActionableMessage() {
        var error = new IllegalStateException("Unable to load credentials from any of the providers in the chain");

        assertThat(AwsFailureMessageResolver.toUserMessage(error))
                .contains("aws sso login")
                .contains("missing");
    }

    @Test
    void mapsPermissionErrorsToReadOnlyPolicyHint() {
        var error = new IllegalStateException("User is not authorized to perform: sts:GetCallerIdentity");

        assertThat(AwsFailureMessageResolver.toUserMessage(error))
                .contains("read-only permissions")
                .contains("sts:GetCallerIdentity");
    }
}
