import {
  Boxes,
  Globe2,
  Database,
  FolderTree,
  Network,
  Package,
  Server,
  Shield,
  Zap,
  type LucideIcon,
} from 'lucide-react';
import { Handle, Position, type NodeProps } from 'reactflow';

import { formatNodeStatus, labelTierBadge } from '../lib/uiCopy';

export type TopologyNodeData = {
  label: string;
  subtitle: string;
  kind: string;
  status?: string;
  detail?: string;
  tier?: string;
  tierConfidence?: string;
  selected: boolean;
  faded: boolean;
  accent: 'amber' | 'blue' | 'rose' | 'slate' | 'emerald' | 'violet';
};

function TierBadge({ tier, confidence }: { tier: string; confidence?: string }) {
  return (
    <span className="topology-tier-badge" data-tier={tier} data-confidence={confidence ?? 'auto'}>
      {labelTierBadge(tier)}
    </span>
  );
}

function HiddenHandles() {
  return (
    <>
      <Handle type="target" position={Position.Left} style={{ opacity: 0, pointerEvents: 'none' }} />
      <Handle type="source" position={Position.Right} style={{ opacity: 0, pointerEvents: 'none' }} />
    </>
  );
}

function resolveIcon(kind: string): LucideIcon {
  switch (kind) {
    case 'cidr':
      return Globe2;
    case 'ec2':
      return Server;
    case 'rds':
      return Database;
    case 'sg':
      return Shield;
    case 'iam-role':
      return Shield;
    case 'alb':
      return Network;
    case 'ecs-cluster-group':
      return Boxes;
    case 'ecs-service':
      return Package;
    case 's3':
      return Package;
    case 'lambda':
      return Zap;
    case 'route53':
      return Globe2;
    case 'vpc-group':
    case 'subnet-group':
      return FolderTree;
    default:
      return Boxes;
  }
}

function ResourceNodeCard({ data }: NodeProps<TopologyNodeData>) {
  const Icon = resolveIcon(data.kind);

  return (
    <div
      className="topology-node-card"
      data-selected={data.selected}
      data-faded={data.faded}
      data-accent={data.accent}
    >
      <HiddenHandles />
      <div className="topology-node-icon">
        <Icon size={18} strokeWidth={2.1} />
      </div>
      <div className="topology-node-copy">
        <p className="topology-node-label">{data.label}</p>
        <p className="topology-node-subtitle">{data.subtitle}</p>
      </div>
      {data.status ? <span className="topology-node-badge">{formatNodeStatus(data.status)}</span> : null}
      {data.tier ? <TierBadge tier={data.tier} confidence={data.tierConfidence} /> : null}
      {data.detail ? <p className="topology-node-detail">{data.detail}</p> : null}
    </div>
  );
}

function GroupNodeCard({ data }: NodeProps<TopologyNodeData>) {
  const Icon = resolveIcon(data.kind);

  return (
    <div
      className="topology-group-card"
      data-selected={data.selected}
      data-faded={data.faded}
      data-accent={data.accent}
    >
      <HiddenHandles />
      <div className="topology-group-header">
        <div className="topology-group-title">
          <span className="topology-group-icon">
            <Icon size={16} strokeWidth={2.1} />
          </span>
          <div>
            <p className="topology-group-label">{data.label}</p>
            <p className="topology-group-subtitle">{data.subtitle}</p>
          </div>
        </div>
        {data.status ? <span className="topology-node-badge">{formatNodeStatus(data.status)}</span> : null}
      </div>
      {data.tier ? <TierBadge tier={data.tier} confidence={data.tierConfidence} /> : null}
      {data.detail ? <p className="topology-group-detail">{data.detail}</p> : null}
    </div>
  );
}

export const topologyNodeTypes = {
  cidr: ResourceNodeCard,
  ec2: ResourceNodeCard,
  rds: ResourceNodeCard,
  sg: ResourceNodeCard,
  'iam-role': ResourceNodeCard,
  alb: ResourceNodeCard,
  'ecs-service': ResourceNodeCard,
  s3: ResourceNodeCard,
  lambda: ResourceNodeCard,
  route53: ResourceNodeCard,
  default: ResourceNodeCard,
  'vpc-group': GroupNodeCard,
  'subnet-group': GroupNodeCard,
  'ecs-cluster-group': GroupNodeCard,
};
