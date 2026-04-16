package com.ariadne.drift;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformStateParserTest {

    private final TerraformStateParser parser = new TerraformStateParser(new ObjectMapper());

    @Test
    void parsesSupportedResourcesIntoNormalizedTfResources() {
        var resources = parser.parse("""
                {
                  "resources": [
                    {
                      "type": "aws_instance",
                      "name": "web",
                      "instances": [
                        {
                          "attributes": {
                            "arn": "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234",
                            "id": "i-1234",
                            "instance_type": "t3.micro",
                            "private_ip": "10.0.0.10",
                            "subnet_id": "subnet-1234",
                            "tags": {
                              "Name": "prod-web-a"
                            }
                          }
                        }
                      ]
                    },
                    {
                      "type": "aws_lb",
                      "name": "public",
                      "instances": [
                        {
                          "attributes": {
                            "arn": "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-alb/50dc6c495c0c9188",
                            "id": "app/prod-alb/50dc6c495c0c9188",
                            "name": "prod-alb",
                            "dns_name": "prod-alb-123.ap-northeast-2.elb.amazonaws.com",
                            "internal": false
                          }
                        }
                      ]
                    },
                    {
                      "type": "aws_s3_bucket",
                      "name": "assets",
                      "instances": [
                        {
                          "attributes": {
                            "bucket": "prod-assets"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        assertThat(resources).hasSize(3);
        assertThat(resources)
                .extracting(TfResource::resourceType, TfResource::resourceId, TfResource::name)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("EC2", "i-1234", "prod-web-a"),
                        org.assertj.core.groups.Tuple.tuple("LOAD_BALANCER", "app/prod-alb/50dc6c495c0c9188", "prod-alb"),
                        org.assertj.core.groups.Tuple.tuple("S3_BUCKET", "prod-assets", "prod-assets")
                );
        assertThat(resources.stream().filter(resource -> resource.resourceType().equals("LOAD_BALANCER")).findFirst())
                .get()
                .satisfies(resource -> {
                    assertThat(resource.properties()).containsEntry("scheme", "internet-facing");
                    assertThat(resource.properties()).containsEntry("dnsName", "prod-alb-123.ap-northeast-2.elb.amazonaws.com");
                });
    }
}
