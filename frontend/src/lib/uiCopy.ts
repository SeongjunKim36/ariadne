import { formatDistanceToNow } from 'date-fns';
import { ko } from 'date-fns/locale';

import type { ScanStatusResponse } from './types';

const COLLECTOR_LABELS: Record<string, string> = {
  ALB: 'ALB',
  DB_SUBNET_GROUP: 'DB 서브넷 그룹',
  EC2: 'EC2',
  ECS: 'ECS',
  ECS_CLUSTER: 'ECS',
  IAM_ROLE: 'IAM 역할',
  LAMBDA: 'Lambda',
  LOAD_BALANCER: '로드 밸런서',
  RDS: 'RDS',
  ROUTE53_ZONE: 'Route53',
  S3: 'S3',
  SECURITY_GROUP: '보안 그룹',
  SUBNET: '서브넷',
  TASK_DEFINITION: '태스크 정의',
  VPC: 'VPC',
};

const NODE_TYPE_LABELS: Record<string, string> = {
  alb: 'ALB',
  cidr: 'CIDR',
  ec2: 'EC2',
  'ecs-cluster-group': 'ECS 클러스터',
  'ecs-service': 'ECS 서비스',
  'db-subnet-group': 'DB 서브넷 그룹',
  'iam-role': 'IAM 역할',
  lambda: 'Lambda',
  'nginx-config': 'nginx 설정',
  rds: 'RDS',
  route53: 'Route53',
  s3: 'S3',
  sg: '보안 그룹',
  subnet: '서브넷',
  'subnet-group': '서브넷',
  'task-definition': '태스크 정의',
  vpc: 'VPC',
  'vpc-group': 'VPC',
};

const EDGE_LABELS: Record<string, string> = {
  ALLOWS_FROM: '인바운드 허용',
  ALLOWS_SELF: '자기 참조',
  ALLOWS_TO: '공개 허용',
  EGRESS_TO: '아웃바운드',
  HAS_ROLE: '역할 연결',
  HAS_SG: '보안 그룹',
  IN_SUBNET_GROUP: 'DB 서브넷',
  LIKELY_USES: '추론 연결',
  PROXIES_TO: '프록시',
  ROUTES_TO: '라우팅',
  RUNS_IN: '실행 위치',
  RUNS_NGINX: 'nginx',
  USES_TASK_DEF: '태스크 정의',
};

const TIER_BADGE_LABELS: Record<string, string> = {
  'app-tier': '앱',
  'batch-tier': '배치',
  'cache-tier': '캐시',
  'db-tier': 'DB',
  'network-tier': '네트워크',
  'storage-tier': '스토리지',
  'web-tier': '웹',
};

function humanizeCollectorName(name: string) {
  return COLLECTOR_LABELS[name]
    ?? name
      .toLowerCase()
      .split('_')
      .map((chunk) => chunk.charAt(0).toUpperCase() + chunk.slice(1))
      .join(' ');
}

export function formatRelativeTime(
  value: string | number | Date | null | undefined,
  fallback = '알 수 없음',
) {
  if (!value) {
    return fallback;
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return fallback;
  }
  return formatDistanceToNow(date, { addSuffix: true, locale: ko });
}

export function formatScanStatusLabel(status: ScanStatusResponse['status'] | null | undefined) {
  switch (status) {
    case 'COMPLETED':
      return '완료';
    case 'FAILED':
      return '실패';
    case 'RUNNING':
      return '진행 중';
    default:
      return '대기';
  }
}

export function summarizeWarningMessage(message: string | null | undefined) {
  if (!message?.trim()) {
    return null;
  }

  const collectorMatches = Array.from(message.matchAll(/Collector ([A-Z0-9_]+) failed:/g))
    .map(([, collector]) => collector);

  if (collectorMatches.length > 0) {
    const collectorLabels = Array.from(
      new Set(collectorMatches.map((collector) => humanizeCollectorName(collector))),
    );
    const unsupportedInCurrentEnvironment = /(?:501|not implemented|unsupported|operation not supported|internalfailure)/i.test(message);

    return unsupportedInCurrentEnvironment
      ? `현재 환경에서 지원되지 않는 수집기는 건너뛰었습니다: ${collectorLabels.join(', ')}`
      : `일부 수집기에서 경고가 발생했습니다: ${collectorLabels.join(', ')}`;
  }

  const firstLine = message
    .split('\n')
    .map((line) => line.trim())
    .find(Boolean);

  if (!firstLine) {
    return null;
  }

  return firstLine.length > 140 ? `${firstLine.slice(0, 137)}...` : firstLine;
}

export function labelNodeType(type: string) {
  return NODE_TYPE_LABELS[type] ?? type;
}

export function labelEdgeType(type: string) {
  return EDGE_LABELS[type] ?? '';
}

export function labelTierBadge(tier: string) {
  return TIER_BADGE_LABELS[tier] ?? tier.replace('-tier', '');
}

export function formatNodeStatus(status: string | null | undefined) {
  if (!status) {
    return '';
  }

  switch (status.toLowerCase()) {
    case 'active':
      return '활성';
    case 'available':
      return '사용 가능';
    case 'blocked':
      return '차단됨';
    case 'check public':
      return '공개 여부 확인';
    case 'dev':
      return '개발';
    case 'private':
      return '비공개';
    case 'prod':
      return '운영';
    case 'public':
      return '공개';
    case 'running':
      return '실행 중';
    case 'shared':
      return '공용';
    case 'staging':
      return '스테이징';
    case 'stopped':
      return '중지';
    case 'unknown':
      return '미분류';
    default:
      return status;
  }
}

export function formatRiskLabel(riskLevel: string | null | undefined) {
  if (!riskLevel) {
    return '';
  }
  switch (riskLevel.toUpperCase()) {
    case 'HIGH':
      return '높음';
    case 'MEDIUM':
      return '중간';
    case 'LOW':
      return '낮음';
    default:
      return riskLevel;
  }
}
