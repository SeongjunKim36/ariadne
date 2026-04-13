CREATE CONSTRAINT unique_arn IF NOT EXISTS FOR (n:AwsResource) REQUIRE n.arn IS UNIQUE;
CREATE INDEX idx_resource_id IF NOT EXISTS FOR (n:AwsResource) ON (n.resourceId);
CREATE INDEX idx_resource_type IF NOT EXISTS FOR (n:AwsResource) ON (n.resourceType);
CREATE INDEX idx_environment IF NOT EXISTS FOR (n:AwsResource) ON (n.environment);
CREATE INDEX idx_vpc_id IF NOT EXISTS FOR (n:Vpc) ON (n.resourceId);
