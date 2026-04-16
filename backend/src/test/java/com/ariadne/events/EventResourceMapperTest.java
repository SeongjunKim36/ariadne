package com.ariadne.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventResourceMapperTest {

    private final EventResourceMapper mapper = new EventResourceMapper(new ObjectMapper().findAndRegisterModules());

    @Test
    void mapsSupportedLambdaEventUsingResourceArn() {
        var mapping = mapper.map("""
                {
                  "id": "evt-1",
                  "time": "2026-04-16T08:00:00Z",
                  "source": "aws.lambda",
                  "detail-type": "AWS API Call via CloudTrail",
                  "detail": {
                    "eventName": "UpdateFunctionConfiguration",
                    "resources": [
                      {
                        "ARN": "arn:aws:lambda:ap-northeast-2:123456789012:function:prod-api"
                      }
                    ]
                  }
                }
                """);

        assertThat(mapping.resourceArn()).isEqualTo("arn:aws:lambda:ap-northeast-2:123456789012:function:prod-api");
        assertThat(mapping.resourceType()).isEqualTo("LAMBDA_FUNCTION");
        assertThat(mapping.collectorTypes()).containsExactly("LAMBDA_FUNCTION");
        assertThat(mapping.summary()).isEqualTo("UpdateFunctionConfiguration");
    }

    @Test
    void returnsEmptyCollectorTypesForUnsupportedEvents() {
        var mapping = mapper.map("""
                {
                  "id": "evt-2",
                  "time": "2026-04-16T08:00:00Z",
                  "source": "aws.config",
                  "detail-type": "Configuration Item Change",
                  "detail": {
                    "eventName": "ConfigChange"
                  }
                }
                """);

        assertThat(mapping.resourceType()).isNull();
        assertThat(mapping.collectorTypes()).isEmpty();
    }
}
