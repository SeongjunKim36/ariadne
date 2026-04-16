package com.ariadne.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditRuleConfiguration {

    @Bean
    AuditRule sg001InternetOpenRule() {
        return rule(
                "SG-001",
                "인터넷 전체 오픈",
                "0.0.0.0/0에서 인바운드 접근을 허용하는 SG를 찾습니다.",
                RiskLevel.HIGH,
                "security-group",
                "인터넷 전체 대신 필요한 CIDR만 허용하고, 외부 노출은 ALB나 WAF 뒤로 제한하세요.",
                """
                MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[rel:ALLOWS_TO]->(sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       cidr.arn AS secondaryArn,
                       cidr.label AS secondaryName,
                       coalesce(rel.port, 'all-ports') AS detail
                """
        );
    }

    @Bean
    AuditRule sg002SshInternetOpenRule() {
        return rule(
                "SG-002",
                "SSH 전체 오픈",
                "22번 포트가 0.0.0.0/0에 열려 있는 SG를 찾습니다.",
                RiskLevel.HIGH,
                "security-group",
                "관리 포트는 Bastion, VPN, SSM Session Manager 같은 제한된 경로로만 노출하세요.",
                """
                MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[rel:ALLOWS_TO]->(sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                  AND ('22' IN coalesce(rel.ports, []) OR coalesce(rel.port, '') = '22')
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       cidr.arn AS secondaryArn,
                       'SSH 22' AS secondaryName,
                       coalesce(rel.protocol, 'tcp') AS detail
                """
        );
    }

    @Bean
    AuditRule sg003RdpInternetOpenRule() {
        return rule(
                "SG-003",
                "RDP 전체 오픈",
                "3389 포트가 인터넷에 열려 있는 SG를 찾습니다.",
                RiskLevel.HIGH,
                "security-group",
                "RDP는 사설망이나 관리망으로만 제한하고, 외부 직접 노출은 피하세요.",
                """
                MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[rel:ALLOWS_TO]->(sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                  AND ('3389' IN coalesce(rel.ports, []) OR coalesce(rel.port, '') = '3389')
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       cidr.arn AS secondaryArn,
                       'RDP 3389' AS secondaryName,
                       coalesce(rel.protocol, 'tcp') AS detail
                """
        );
    }

    @Bean
    AuditRule sg004PublicDatabasePortRule() {
        return rule(
                "SG-004",
                "DB 포트 퍼블릭 노출",
                "대표 DB 포트가 0.0.0.0/0에 열려 있는 SG를 찾습니다.",
                RiskLevel.HIGH,
                "security-group",
                "DB 포트는 내부 네트워크에서만 접근하게 하고, 애플리케이션 계층을 통해 우회하세요.",
                """
                MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[rel:ALLOWS_TO]->(sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                  AND ANY(port IN coalesce(rel.ports, []) WHERE port IN ['3306', '5432', '1521', '27017'])
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       cidr.arn AS secondaryArn,
                       'public-db-port' AS secondaryName,
                       coalesce(rel.port, 'db-port') AS detail
                """
        );
    }

    @Bean
    AuditRule sg005ExcessiveCidrRule() {
        return rule(
                "SG-005",
                "과도한 CIDR 범위",
                "프라이빗/관리 경계 없이 /8 이하의 넓은 CIDR을 허용하는 SG를 찾습니다.",
                RiskLevel.MEDIUM,
                "security-group",
                "조직/환경별 실제 허용 대역으로 CIDR 범위를 더 좁게 나누세요.",
                """
                MATCH (cidr:CidrSource)-[rel:ALLOWS_TO]->(sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                  AND cidr.cidr CONTAINS '/'
                  AND toInteger(split(cidr.cidr, '/')[1]) <= 8
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       cidr.arn AS secondaryArn,
                       cidr.cidr AS secondaryName,
                       coalesce(rel.port, 'all-ports') AS detail
                """
        );
    }

    @Bean
    AuditRule sg006WideOpenEgressRule() {
        return rule(
                "SG-006",
                "아웃바운드 전체 오픈",
                "0.0.0.0/0으로 제한 없는 egress를 허용하는 SG를 찾습니다.",
                RiskLevel.LOW,
                "security-group",
                "필요한 목적지/포트만 허용하고, egress 제어가 필요한 워크로드는 별도 SG로 분리하세요.",
                """
                MATCH (sg:SecurityGroup)-[rel:EGRESS_TO]->(cidr:CidrSource {cidr: '0.0.0.0/0'})
                WHERE coalesce(sg.stale, false) = false
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       cidr.arn AS secondaryArn,
                       cidr.label AS secondaryName,
                       coalesce(rel.port, 'all-ports') AS detail
                """
        );
    }

    @Bean
    AuditRule sg007UnusedSecurityGroupRule() {
        return rule(
                "SG-007",
                "미사용 보안 그룹",
                "아무 리소스에도 연결되지 않은 Security Group을 찾습니다.",
                RiskLevel.LOW,
                "security-group",
                "실제 연결이 없는 보안 그룹은 정리해 운영 노이즈와 정책 혼선을 줄이세요.",
                """
                MATCH (sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                  AND NOT EXISTS { MATCH (:AwsResource)-[:HAS_SG]->(sg) }
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'unused' AS detail
                """
        );
    }

    @Bean
    AuditRule sg008TooManyRulesRule() {
        return rule(
                "SG-008",
                "과다 규칙",
                "인바운드 규칙이 50개를 넘는 Security Group을 찾습니다.",
                RiskLevel.MEDIUM,
                "security-group",
                "규칙 수가 많은 SG는 역할별로 분리하고, 공통 정책은 재사용 가능한 SG로 나누세요.",
                """
                MATCH (sg:SecurityGroup)
                WHERE coalesce(sg.stale, false) = false
                  AND coalesce(sg.inboundRuleCount, 0) > 50
                RETURN sg.arn AS resourceArn,
                       coalesce(sg.name, sg.groupId) AS resourceName,
                       sg.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       toString(sg.inboundRuleCount) AS detail
                """
        );
    }

    @Bean
    AuditRule iam001WildcardActionRule() {
        return rule(
                "IAM-001",
                "와일드카드 Action",
                "정책 문서에 Action '*'이 포함된 역할을 찾습니다.",
                RiskLevel.HIGH,
                "iam",
                "정확한 액션 목록으로 정책을 줄이고, 서비스별 읽기/쓰기 권한을 분리하세요.",
                """
                MATCH (role:IamRole)
                WHERE coalesce(role.stale, false) = false
                  AND coalesce(role.hasWildcardAction, false) = true
                RETURN role.arn AS resourceArn,
                       role.roleName AS resourceName,
                       role.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'wildcard-action' AS detail
                """
        );
    }

    @Bean
    AuditRule iam002WildcardWriteResourceRule() {
        return rule(
                "IAM-002",
                "와일드카드 Resource + write 계열",
                "쓰기 계열 액션에 Resource '*'가 결합된 역할을 찾습니다.",
                RiskLevel.HIGH,
                "iam",
                "쓰기 정책은 리소스 ARN 범위를 최대한 좁히고, 공용 와일드카드는 피하세요.",
                """
                MATCH (role:IamRole)
                WHERE coalesce(role.stale, false) = false
                  AND coalesce(role.hasWildcardWriteResource, false) = true
                RETURN role.arn AS resourceArn,
                       role.roleName AS resourceName,
                       role.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'wildcard-write-resource' AS detail
                """
        );
    }

    @Bean
    AuditRule iam003CrossAccountAssumeRule() {
        return rule(
                "IAM-003",
                "교차 계정 AssumeRole",
                "외부 계정이 trust policy에 들어간 역할을 찾습니다.",
                RiskLevel.MEDIUM,
                "iam",
                "외부 계정 신뢰는 필요 최소한으로 제한하고, 조건(ExternalId, 조직 ID 등)을 함께 거세요.",
                """
                MATCH (role:IamRole)
                WHERE coalesce(role.stale, false) = false
                  AND coalesce(role.hasCrossAccountAssume, false) = true
                RETURN role.arn AS resourceArn,
                       role.roleName AS resourceName,
                       role.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'cross-account-assume' AS detail
                """
        );
    }

    @Bean
    AuditRule iam004UnusedRoleRule() {
        return rule(
                "IAM-004",
                "장기 미사용 역할",
                "90일 이상 사용 흔적이 없는 IAM 역할을 찾습니다.",
                RiskLevel.LOW,
                "iam",
                "장기간 미사용 역할은 제거하거나 용도를 문서화해 정책 노이즈를 줄이세요.",
                """
                MATCH (role:IamRole)
                WHERE coalesce(role.stale, false) = false
                  AND role.lastUsedAt IS NOT NULL
                  AND datetime(role.lastUsedAt) < datetime() - duration({days: 90})
                RETURN role.arn AS resourceArn,
                       role.roleName AS resourceName,
                       role.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       toString(role.lastUsedAt) AS detail
                """
        );
    }

    @Bean
    AuditRule iam005AdministratorAccessRule() {
        return rule(
                "IAM-005",
                "AdministratorAccess 연결",
                "AdministratorAccess 관리형 정책이 붙은 역할을 찾습니다.",
                RiskLevel.HIGH,
                "iam",
                "관리자 권한은 break-glass 용도로만 남기고, 일상 작업은 역할별 최소권한으로 분리하세요.",
                """
                MATCH (role:IamRole)
                WHERE coalesce(role.stale, false) = false
                  AND coalesce(role.hasAdministratorAccess, false) = true
                RETURN role.arn AS resourceArn,
                       role.roleName AS resourceName,
                       role.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'AdministratorAccess' AS detail
                """
        );
    }

    @Bean
    AuditRule iam006PassRoleWildcardRule() {
        return rule(
                "IAM-006",
                "PassRole 와일드카드",
                "iam:PassRole이 Resource '*'와 함께 허용된 역할을 찾습니다.",
                RiskLevel.HIGH,
                "iam",
                "PassRole은 허용 가능한 역할 ARN으로 범위를 좁히고, 서비스 계정별로 분리하세요.",
                """
                MATCH (role:IamRole)
                WHERE coalesce(role.stale, false) = false
                  AND coalesce(role.hasPassRoleWildcard, false) = true
                RETURN role.arn AS resourceArn,
                       role.roleName AS resourceName,
                       role.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'iam:PassRole *' AS detail
                """
        );
    }

    @Bean
    AuditRule iam007RootAccessKeyRule() {
        return new RootAccessKeyAuditRule();
    }

    @Bean
    AuditRule net001PublicRdsRule() {
        return rule(
                "NET-001",
                "RDS 퍼블릭 접근",
                "퍼블릭 서브넷 + 퍼블릭 SG 조합에 노출된 RDS를 찾습니다.",
                RiskLevel.HIGH,
                "network",
                "RDS는 private subnet에 두고, 접근은 애플리케이션 계층을 통해서만 허용하세요.",
                """
                MATCH (rds:RdsInstance)-[:IN_SUBNET_GROUP]->(:DbSubnetGroup)-[:CONTAINS]->(subnet:Subnet)
                MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[:ALLOWS_TO]->(:SecurityGroup)<-[:HAS_SG]-(rds)
                WHERE coalesce(rds.stale, false) = false
                  AND coalesce(subnet.isPublic, false) = true
                RETURN DISTINCT rds.arn AS resourceArn,
                       coalesce(rds.name, rds.resourceId) AS resourceName,
                       rds.resourceType AS resourceType,
                       subnet.arn AS secondaryArn,
                       subnet.resourceId AS secondaryName,
                       rds.endpoint AS detail
                """
        );
    }

    @Bean
    AuditRule net002PublicEc2Rule() {
        return rule(
                "NET-002",
                "퍼블릭 IP + 넓은 SG",
                "퍼블릭 IP가 있으면서 0.0.0.0/0에 열린 SG를 가진 EC2를 찾습니다.",
                RiskLevel.HIGH,
                "network",
                "인터넷 노출 EC2는 ALB/WAF 뒤로 두고, 직접 SSH/RDP 노출은 제거하세요.",
                """
                MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[:ALLOWS_TO]->(:SecurityGroup)<-[:HAS_SG]-(ec2:Ec2Instance)
                WHERE coalesce(ec2.stale, false) = false
                  AND ec2.publicIp IS NOT NULL
                RETURN DISTINCT ec2.arn AS resourceArn,
                       coalesce(ec2.name, ec2.resourceId) AS resourceName,
                       ec2.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       ec2.publicIp AS detail
                """
        );
    }

    @Bean
    AuditRule net003AlbWithoutHttpsRule() {
        return rule(
                "NET-003",
                "ALB에 HTTPS 없음",
                "인터넷 facing ALB 중 443 리스너가 없는 리소스를 찾습니다.",
                RiskLevel.MEDIUM,
                "network",
                "외부 노출 ALB는 443 리스너와 유효한 TLS 인증서를 기본값으로 두세요.",
                """
                MATCH (lb:LoadBalancer)
                WHERE coalesce(lb.stale, false) = false
                  AND lb.scheme = 'internet-facing'
                  AND NOT 443 IN coalesce(lb.listenerPorts, [])
                RETURN lb.arn AS resourceArn,
                       coalesce(lb.name, lb.resourceId) AS resourceName,
                       lb.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       coalesce(lb.dnsName, 'no-dns') AS detail
                """
        );
    }

    @Bean
    AuditRule net004InternalServiceOnPublicSubnetRule() {
        return rule(
                "NET-004",
                "퍼블릭 서브넷 직접 배치",
                "RDS나 ECS 서비스처럼 내부 계층이어야 하는 리소스가 퍼블릭 서브넷에 있는지 찾습니다.",
                RiskLevel.HIGH,
                "network",
                "내부 계층 리소스는 private subnet으로 이동하고, 외부 연결은 전용 ingress 계층으로 제한하세요.",
                """
                MATCH (resource:AwsResource)-[:BELONGS_TO]->(subnet:Subnet)
                WHERE coalesce(resource.stale, false) = false
                  AND coalesce(subnet.isPublic, false) = true
                  AND resource.resourceType IN ['RDS', 'ECS_SERVICE', 'LAMBDA_FUNCTION']
                RETURN resource.arn AS resourceArn,
                       coalesce(resource.name, resource.resourceId) AS resourceName,
                       resource.resourceType AS resourceType,
                       subnet.arn AS secondaryArn,
                       subnet.resourceId AS secondaryName,
                       subnet.cidrBlock AS detail
                """
        );
    }

    @Bean
    AuditRule s3001PublicAccessBlockedRule() {
        return rule(
                "S3-001",
                "퍼블릭 접근 차단 미적용",
                "S3 Public Access Block이 완전히 켜지지 않은 버킷을 찾습니다.",
                RiskLevel.HIGH,
                "s3",
                "버킷 단위와 계정 단위 Public Access Block을 모두 활성화하세요.",
                """
                MATCH (bucket:S3Bucket)
                WHERE coalesce(bucket.stale, false) = false
                  AND coalesce(bucket.publicAccessBlocked, false) = false
                RETURN bucket.arn AS resourceArn,
                       bucket.name AS resourceName,
                       bucket.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'public-access-block-disabled' AS detail
                """
        );
    }

    @Bean
    AuditRule s3002NoBucketEncryptionRule() {
        return rule(
                "S3-002",
                "암호화 미적용",
                "기본 버킷 암호화가 비어 있는 S3 버킷을 찾습니다.",
                RiskLevel.MEDIUM,
                "s3",
                "SSE-S3 또는 SSE-KMS를 활성화해 저장 데이터 암호화를 기본값으로 강제하세요.",
                """
                MATCH (bucket:S3Bucket)
                WHERE coalesce(bucket.stale, false) = false
                  AND coalesce(bucket.encryptionType, 'none') = 'none'
                RETURN bucket.arn AS resourceArn,
                       bucket.name AS resourceName,
                       bucket.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'encryption:none' AS detail
                """
        );
    }

    @Bean
    AuditRule s3003VersioningDisabledRule() {
        return rule(
                "S3-003",
                "버전관리 미적용",
                "버전 관리가 꺼져 있는 S3 버킷을 찾습니다.",
                RiskLevel.LOW,
                "s3",
                "실수 복구와 랜섬웨어 대응을 위해 중요한 버킷은 버전 관리를 켜세요.",
                """
                MATCH (bucket:S3Bucket)
                WHERE coalesce(bucket.stale, false) = false
                  AND coalesce(bucket.versioningEnabled, false) = false
                RETURN bucket.arn AS resourceArn,
                       bucket.name AS resourceName,
                       bucket.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'versioning:false' AS detail
                """
        );
    }

    @Bean
    AuditRule enc001RdsNotEncryptedRule() {
        return rule(
                "ENC-001",
                "RDS 암호화 미적용",
                "스토리지 암호화가 꺼진 RDS를 찾습니다.",
                RiskLevel.HIGH,
                "encryption",
                "RDS 인스턴스는 기본적으로 storage encryption을 사용하고, 신규 생성은 KMS 키를 강제하세요.",
                """
                MATCH (rds:RdsInstance)
                WHERE coalesce(rds.stale, false) = false
                  AND coalesce(rds.encrypted, false) = false
                RETURN rds.arn AS resourceArn,
                       coalesce(rds.name, rds.resourceId) AS resourceName,
                       rds.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       coalesce(rds.engine, 'unknown') AS detail
                """
        );
    }

    @Bean
    AuditRule enc002PublicBucketPolicyRule() {
        return rule(
                "ENC-002",
                "S3 버킷 정책 퍼블릭",
                "버킷 정책이 퍼블릭으로 판별된 S3 버킷을 찾습니다.",
                RiskLevel.HIGH,
                "encryption",
                "퍼블릭 정책은 제거하고 필요한 공개는 CloudFront/OAC 등 더 좁은 경로로 대체하세요.",
                """
                MATCH (bucket:S3Bucket)
                WHERE coalesce(bucket.stale, false) = false
                  AND coalesce(bucket.bucketPolicyPublic, false) = true
                RETURN bucket.arn AS resourceArn,
                       bucket.name AS resourceName,
                       bucket.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'bucket-policy-public' AS detail
                """
        );
    }

    @Bean
    AuditRule enc003SslNotEnforcedRule() {
        return rule(
                "ENC-003",
                "S3 SSL 미강제",
                "버킷 정책에 aws:SecureTransport 강제가 보이지 않는 버킷을 찾습니다.",
                RiskLevel.MEDIUM,
                "encryption",
                "민감 버킷은 SecureTransport 조건으로 비암호화 요청을 명시적으로 거부하세요.",
                """
                MATCH (bucket:S3Bucket)
                WHERE coalesce(bucket.stale, false) = false
                  AND coalesce(bucket.sslEnforced, false) = false
                RETURN bucket.arn AS resourceArn,
                       bucket.name AS resourceName,
                       bucket.resourceType AS resourceType,
                       null AS secondaryArn,
                       null AS secondaryName,
                       'ssl-not-enforced' AS detail
                """
        );
    }

    private AuditRule rule(
            String ruleId,
            String name,
            String description,
            RiskLevel riskLevel,
            String category,
            String remediationHint,
            String cypher
    ) {
        return new CypherAuditRule(ruleId, name, description, riskLevel, category, remediationHint, cypher);
    }
}
