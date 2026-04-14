import dagre from 'dagre';
import type { Edge, Node } from 'reactflow';

import type { GraphEdgeRecord, GraphNodeRecord } from './types';
import type { TopologyNodeData } from '../components/topologyNodes';

const GROUP_MIN_WIDTH = 320;
const GROUP_MIN_HEIGHT = 210;
const GROUP_PADDING = 22;
const GROUP_HEADER_HEIGHT = 72;
const GROUP_GAP = 24;
const ROOT_GAP = 64;

function isGroupNode(node: GraphNodeRecord) {
  return node.type === 'vpc-group' || node.type === 'subnet-group' || node.type === 'ecs-cluster-group';
}

function nodeSize(node: GraphNodeRecord) {
  switch (node.type) {
    case 'cidr':
      return { width: 208, height: 92 };
    case 'sg':
      return { width: 220, height: 96 };
    case 'iam-role':
      return { width: 236, height: 100 };
    case 'rds':
      return { width: 240, height: 104 };
    case 'ec2':
      return { width: 230, height: 102 };
    case 'alb':
      return { width: 236, height: 102 };
    case 'ecs-service':
      return { width: 228, height: 102 };
    case 's3':
      return { width: 230, height: 100 };
    case 'lambda':
      return { width: 235, height: 102 };
    case 'route53':
      return { width: 235, height: 96 };
    default:
      return { width: 220, height: 96 };
  }
}

function accentForType(type: string): TopologyNodeData['accent'] {
  switch (type) {
    case 'cidr':
      return 'slate';
    case 'ec2':
      return 'amber';
    case 'rds':
      return 'blue';
    case 'sg':
      return 'rose';
    case 'iam-role':
      return 'slate';
    case 'alb':
      return 'violet';
    case 'ecs-service':
    case 's3':
      return 'emerald';
    case 'lambda':
      return 'amber';
    case 'route53':
      return 'violet';
    case 'ecs-cluster-group':
    case 'vpc-group':
    case 'subnet-group':
      return 'slate';
    default:
      return 'emerald';
  }
}

function value(record: Record<string, unknown>, key: string) {
  const raw = record[key];
  return typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean'
    ? String(raw)
    : '';
}

function describeNode(node: GraphNodeRecord) {
  const { data } = node;
  switch (node.type) {
    case 'cidr':
      return {
        subtitle: value(data, 'cidr') || value(data, 'resourceId'),
        detail: [value(data, 'label'), value(data, 'riskLevel')].filter(Boolean).join(' · '),
        status: value(data, 'addressFamily') || (value(data, 'isPublic') === 'true' ? 'public' : ''),
      };
    case 'ec2':
      return {
        subtitle: [value(data, 'resourceId'), value(data, 'instanceType')].filter(Boolean).join(' · '),
        detail: value(data, 'privateIp') || value(data, 'publicIp') || value(data, 'amiId'),
        status: value(data, 'state'),
      };
    case 'rds':
      return {
        subtitle: [value(data, 'engine'), value(data, 'instanceClass')].filter(Boolean).join(' · '),
        detail: value(data, 'endpoint') || value(data, 'engineVersion'),
        status: value(data, 'status'),
      };
    case 'sg':
      return {
        subtitle: value(data, 'groupId') || value(data, 'resourceId'),
        detail: `${value(data, 'inboundRuleCount') || '0'} in · ${value(data, 'outboundRuleCount') || '0'} out`,
        status: '',
      };
    case 'iam-role': {
      const attachedPolicies = Array.isArray(data.attachedPolicies) ? data.attachedPolicies.length : 0;
      return {
        subtitle: value(data, 'roleName') || value(data, 'resourceId'),
        detail: attachedPolicies > 0 ? `${attachedPolicies} policies` : 'trust policy linked',
        status: value(data, 'environment'),
      };
    }
    case 'alb':
      return {
        subtitle: [value(data, 'type'), value(data, 'scheme')].filter(Boolean).join(' · '),
        detail: value(data, 'dnsName'),
        status: value(data, 'state'),
      };
    case 'ecs-cluster-group':
      return {
        subtitle: value(data, 'resourceId'),
        detail: [
          value(data, 'activeServiceCount') ? `${value(data, 'activeServiceCount')} svc` : '',
          value(data, 'runningTaskCount') ? `${value(data, 'runningTaskCount')} tasks` : '',
        ].filter(Boolean).join(' · '),
        status: value(data, 'status'),
      };
    case 'ecs-service':
      return {
        subtitle: [
          value(data, 'launchType'),
          `${value(data, 'runningCount') || '0'}/${value(data, 'desiredCount') || '0'}`,
        ].filter(Boolean).join(' · '),
        detail: value(data, 'taskDefinition'),
        status: value(data, 'environment'),
      };
    case 's3':
      return {
        subtitle: value(data, 'resourceId'),
        detail: [
          value(data, 'encryptionType'),
          value(data, 'versioningEnabled') === 'true' ? 'versioned' : 'unversioned',
        ].filter(Boolean).join(' · '),
        status: value(data, 'publicAccessBlocked') === 'true' ? 'blocked' : 'check public',
      };
    case 'lambda':
      return {
        subtitle: [value(data, 'runtime'), `${value(data, 'memoryMb') || '0'} MB`].filter(Boolean).join(' · '),
        detail: value(data, 'handler'),
        status: value(data, 'state'),
      };
    case 'route53':
      return {
        subtitle: value(data, 'domainName') || value(data, 'resourceId'),
        detail: `${value(data, 'recordCount') || '0'} records`,
        status: value(data, 'isPrivate') === 'true' ? 'private' : 'public',
      };
    case 'vpc-group':
      return {
        subtitle: value(data, 'resourceId'),
        detail: value(data, 'cidrBlock'),
        status: value(data, 'environment'),
      };
    case 'subnet-group':
      return {
        subtitle: value(data, 'resourceId') || value(data, 'groupName'),
        detail: value(data, 'availabilityZone') || value(data, 'cidrBlock') || value(data, 'subnetCount'),
        status: value(data, 'environment'),
      };
    default:
      return {
        subtitle: value(data, 'resourceId'),
        detail: value(data, 'resourceType'),
        status: '',
      };
  }
}

function sortNodes(nodes: GraphNodeRecord[]) {
  return [...nodes].sort((left, right) => {
    const leftName = value(left.data, 'name');
    const rightName = value(right.data, 'name');
    return leftName.localeCompare(rightName);
  });
}

function edgeVisual(type: string, selected: boolean, highlighted: boolean) {
  const emphasis = selected ? 2.6 : highlighted ? 1.7 : 1.1;
  const opacity = highlighted ? 0.92 : 0.18;

  switch (type) {
    case 'ALLOWS_FROM':
      return {
        animated: false,
        stroke: selected ? '#d97706' : '#f59e0b',
        strokeWidth: selected ? 2.4 : 1.8,
        opacity,
      };
    case 'ALLOWS_TO':
      return {
        animated: false,
        stroke: selected ? '#b45309' : '#f59e0b',
        strokeWidth: selected ? 2.2 : 1.5,
        opacity,
      };
    case 'ALLOWS_SELF':
      return {
        animated: selected,
        stroke: selected ? '#b45309' : '#f59e0b',
        strokeDasharray: '3 3',
        strokeWidth: selected ? 2.3 : 1.6,
        opacity,
      };
    case 'EGRESS_TO':
      return {
        animated: false,
        stroke: selected ? '#475569' : '#64748b',
        strokeDasharray: '5 4',
        strokeWidth: selected ? 2.1 : 1.4,
        opacity,
      };
    case 'LIKELY_USES':
      return {
        animated: true,
        stroke: selected ? '#1d4ed8' : '#3b82f6',
        strokeDasharray: '7 5',
        strokeWidth: selected ? 2.9 : 2.1,
        opacity,
      };
    case 'ROUTES_TO':
      return {
        animated: selected,
        stroke: selected ? '#c2410c' : '#f97316',
        strokeWidth: emphasis,
        opacity,
      };
    case 'TRIGGERS':
      return {
        animated: selected,
        stroke: selected ? '#047857' : '#10b981',
        strokeDasharray: '4 4',
        strokeWidth: emphasis,
        opacity,
      };
    case 'HAS_RECORD':
      return {
        animated: false,
        stroke: selected ? '#7c3aed' : '#8b5cf6',
        strokeDasharray: '3 4',
        strokeWidth: emphasis,
        opacity,
      };
    default:
      return {
        animated: selected,
        stroke: selected ? '#f97316' : '#6b7280',
        strokeWidth: emphasis,
        opacity,
      };
  }
}

function buildLeafLayout(
  leafNodes: GraphNodeRecord[],
  edges: GraphEdgeRecord[],
  parentId: string | null,
  offsetY = 0,
  selectedArn: string | null,
  emphasizedIds: Set<string>,
) {
  if (leafNodes.length === 0) {
    return { nodes: [] as Node<TopologyNodeData>[], width: 0, height: 0 };
  }

  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setGraph({ rankdir: 'LR', nodesep: 26, ranksep: 34, marginx: 0, marginy: 0 });
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  for (const node of leafNodes) {
    const size = nodeSize(node);
    dagreGraph.setNode(node.id, size);
  }

  const nodeIds = new Set(leafNodes.map((node) => node.id));
  for (const edge of edges) {
    if (nodeIds.has(edge.source) && nodeIds.has(edge.target)) {
      dagreGraph.setEdge(edge.source, edge.target);
    }
  }

  dagre.layout(dagreGraph);

  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = 0;
  let maxY = 0;

  const nodes = leafNodes.map((node) => {
    const size = nodeSize(node);
    const dagreNode = dagreGraph.node(node.id);
    const x = dagreNode.x - size.width / 2;
    const y = dagreNode.y - size.height / 2;
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x + size.width);
    maxY = Math.max(maxY, y + size.height);
    return { node, size, x, y };
  });

  const normalizedNodes = nodes.map(({ node, size, x, y }) => {
    const description = describeNode(node);
    return {
      id: node.id,
      type: node.type,
      position: {
        x: x - minX,
        y: y - minY + offsetY,
      },
      parentNode: parentId ?? undefined,
      extent: parentId ? 'parent' : undefined,
      draggable: false,
      selectable: true,
      style: { width: size.width, height: size.height, border: 'none', background: 'transparent' },
      data: {
        label: value(node.data, 'name') || value(node.data, 'resourceId') || node.id,
        subtitle: description.subtitle,
        detail: description.detail,
        status: description.status,
        kind: node.type,
        selected: selectedArn === node.id,
        faded: emphasizedIds.size > 0 && !emphasizedIds.has(node.id),
        accent: accentForType(node.type),
      } satisfies TopologyNodeData,
    } satisfies Node<TopologyNodeData>;
  });

  return {
    nodes: normalizedNodes,
    width: Math.max(0, maxX - minX),
    height: Math.max(0, maxY - minY),
  };
}

function layoutGroup(
  groupNode: GraphNodeRecord,
  childrenByParent: Map<string, GraphNodeRecord[]>,
  edges: GraphEdgeRecord[],
  selectedArn: string | null,
  emphasizedIds: Set<string>,
) {
  const directChildren = sortNodes(childrenByParent.get(groupNode.id) ?? []);
  const groupChildren = directChildren.filter(isGroupNode);
  const leafChildren = directChildren.filter((node) => !isGroupNode(node));

  const descendants: Node<TopologyNodeData>[] = [];
  let cursorX = GROUP_PADDING;
  let rowHeight = 0;

  for (const childGroup of groupChildren) {
    const childLayout = layoutGroup(childGroup, childrenByParent, edges, selectedArn, emphasizedIds);
    descendants.push({
      ...childLayout.root,
      position: {
        x: cursorX,
        y: GROUP_HEADER_HEIGHT,
      },
      parentNode: groupNode.id,
      extent: 'parent',
    });
    descendants.push(...childLayout.descendants);
    cursorX += childLayout.width + GROUP_GAP;
    rowHeight = Math.max(rowHeight, childLayout.height);
  }

  const leafOffsetY = groupChildren.length > 0
    ? GROUP_HEADER_HEIGHT + rowHeight + GROUP_GAP
    : GROUP_HEADER_HEIGHT;
  const leafLayout = buildLeafLayout(leafChildren, edges, groupNode.id, leafOffsetY, selectedArn, emphasizedIds);
  descendants.push(...leafLayout.nodes);

  const width = Math.max(
    GROUP_MIN_WIDTH,
    groupChildren.length > 0 ? cursorX - GROUP_GAP + GROUP_PADDING : 0,
    leafLayout.width + GROUP_PADDING * 2,
  );
  const height = Math.max(
    GROUP_MIN_HEIGHT,
    leafOffsetY + leafLayout.height + GROUP_PADDING,
  );

  const description = describeNode(groupNode);
  const root = {
    id: groupNode.id,
    type: groupNode.type,
    position: { x: 0, y: 0 },
    draggable: false,
    selectable: true,
    style: { width, height, border: 'none', background: 'transparent' },
    data: {
      label: value(groupNode.data, 'name') || value(groupNode.data, 'resourceId') || groupNode.id,
      subtitle: description.subtitle,
      detail: description.detail,
      status: description.status,
      kind: groupNode.type,
      selected: selectedArn === groupNode.id,
      faded: emphasizedIds.size > 0 && !emphasizedIds.has(groupNode.id),
      accent: accentForType(groupNode.type),
    } satisfies TopologyNodeData,
  } satisfies Node<TopologyNodeData>;

  return {
    root,
    descendants,
    width,
    height,
  };
}

export function buildTopologyElements(
  graphNodes: GraphNodeRecord[],
  graphEdges: GraphEdgeRecord[],
  selectedArn: string | null,
  emphasizedIds: Set<string>,
) {
  const childrenByParent = new Map<string, GraphNodeRecord[]>();
  for (const node of graphNodes) {
    if (!node.parentNode) {
      continue;
    }
    const bucket = childrenByParent.get(node.parentNode) ?? [];
    bucket.push(node);
    childrenByParent.set(node.parentNode, bucket);
  }

  const rootGroups = sortNodes(graphNodes.filter((node) => !node.parentNode && isGroupNode(node)));
  const looseNodes = sortNodes(graphNodes.filter((node) => !node.parentNode && !isGroupNode(node)));

  const flowNodes: Node<TopologyNodeData>[] = [];
  let cursorX = 24;
  let maxRootHeight = 0;

  for (const rootGroup of rootGroups) {
    const groupLayout = layoutGroup(rootGroup, childrenByParent, graphEdges, selectedArn, emphasizedIds);
    flowNodes.push({
      ...groupLayout.root,
      position: { x: cursorX, y: 24 },
    });
    flowNodes.push(...groupLayout.descendants);
    cursorX += groupLayout.width + ROOT_GAP;
    maxRootHeight = Math.max(maxRootHeight, groupLayout.height);
  }

  const looseLayout = buildLeafLayout(
    looseNodes,
    graphEdges,
    null,
    maxRootHeight > 0 ? maxRootHeight + 116 : 48,
    selectedArn,
    emphasizedIds,
  );
  flowNodes.push(...looseLayout.nodes.map((node) => ({
    ...node,
    position: {
      x: node.position.x + 24,
      y: node.position.y,
    },
  })));

  const flowEdges: Edge[] = graphEdges.map((edge) => {
    const highlighted = emphasizedIds.size === 0
      || (emphasizedIds.has(edge.source) && emphasizedIds.has(edge.target));
    const selected = selectedArn === edge.source || selectedArn === edge.target;
    const visual = edgeVisual(edge.type, selected, highlighted);

    return {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: 'smoothstep',
      animated: visual.animated,
      data: edge.data,
      label: edge.type,
      style: {
        stroke: visual.stroke,
        strokeWidth: visual.strokeWidth,
        strokeDasharray: visual.strokeDasharray,
        opacity: visual.opacity,
      },
      labelStyle: {
        fill: edge.type === 'LIKELY_USES'
          ? '#2563eb'
          : edge.type.startsWith('ALLOWS') || edge.type === 'EGRESS_TO'
            ? '#92400e'
            : '#64748b',
        fontSize: 10,
        fontWeight: 700,
      },
    };
  });

  return { nodes: flowNodes, edges: flowEdges };
}
