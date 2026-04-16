import axios from 'axios';

import type {
  ArchitectureSummaryResponse,
  AuditExplanationResponse,
  AuditFinding,
  AuditReportResponse,
  AuditRuleResponse,
  DriftReportResponse,
  EventLogResponse,
  GraphResponse,
  LabelResponse,
  NlQueryResponse,
  ResourceDetailResponse,
  ScanPreflightResponse,
  ScanStatusResponse,
  SnapshotDiffResponse,
  SnapshotResponse,
  SnapshotSummaryResponse,
  TimelineEntryResponse,
} from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

export async function fetchGraph(): Promise<GraphResponse> {
  const response = await apiClient.get<GraphResponse>('/api/graph');
  return response.data;
}

export async function fetchSnapshots(
  period = '24h',
  range?: { from?: string; to?: string },
): Promise<SnapshotSummaryResponse[]> {
  const response = await apiClient.get<SnapshotSummaryResponse[]>('/api/snapshots', {
    params: {
      ...(period && period !== 'custom' ? { period } : {}),
      ...(range?.from ? { from: range.from } : {}),
      ...(range?.to ? { to: range.to } : {}),
    },
  });
  return response.data;
}

export async function fetchSnapshot(snapshotId: number): Promise<SnapshotResponse> {
  const response = await apiClient.get<SnapshotResponse>(`/api/snapshots/${snapshotId}`);
  return response.data;
}

export async function fetchSnapshotDiff(from: number, to: number): Promise<SnapshotDiffResponse> {
  const response = await apiClient.get<SnapshotDiffResponse>('/api/snapshots/diff', {
    params: { from, to },
  });
  return response.data;
}

export async function fetchLatestSnapshotDiff(): Promise<SnapshotDiffResponse | null> {
  const response = await apiClient.get<SnapshotDiffResponse>('/api/snapshots/diff/latest', {
    validateStatus: (status) => status === 200 || status === 204,
  });
  if (response.status === 204) {
    return null;
  }
  return response.data;
}

export async function fetchTimeline(
  period = '24h',
  range?: { from?: string; to?: string },
): Promise<TimelineEntryResponse[]> {
  const response = await apiClient.get<TimelineEntryResponse[]>('/api/timeline', {
    params: {
      ...(period && period !== 'custom' ? { period } : {}),
      ...(range?.from ? { from: range.from } : {}),
      ...(range?.to ? { to: range.to } : {}),
    },
  });
  return response.data;
}

export async function runTerraformDrift(payload?: { path?: string; rawStateJson?: string }): Promise<DriftReportResponse> {
  const response = await apiClient.post<DriftReportResponse>('/api/drift/terraform', payload ?? {});
  return response.data;
}

export async function fetchLatestTerraformDrift(): Promise<DriftReportResponse | null> {
  const response = await apiClient.get<DriftReportResponse>('/api/drift/latest', {
    validateStatus: (status) => status === 200 || status === 204,
  });
  if (response.status === 204) {
    return null;
  }
  return response.data;
}

export async function fetchEventLogs(range?: { from?: string; to?: string }): Promise<EventLogResponse[]> {
  const response = await apiClient.get<EventLogResponse[]>('/api/events', {
    params: {
      ...(range?.from ? { from: range.from } : {}),
      ...(range?.to ? { to: range.to } : {}),
    },
  });
  return response.data;
}

export async function fetchLatestScan(): Promise<ScanStatusResponse | null> {
  const response = await apiClient.get<ScanStatusResponse>('/api/scan/latest', {
    validateStatus: (status) => status === 200 || status === 204,
  });
  if (response.status === 204) {
    return null;
  }
  return response.data;
}

export async function fetchScanStatus(scanId: string): Promise<ScanStatusResponse> {
  const response = await apiClient.get<ScanStatusResponse>(`/api/scan/${scanId}/status`);
  return response.data;
}

export async function triggerScan(): Promise<ScanStatusResponse> {
  const response = await apiClient.post<ScanStatusResponse>('/api/scan');
  return response.data;
}

export async function fetchScanPreflight(): Promise<ScanPreflightResponse> {
  const response = await apiClient.get<ScanPreflightResponse>('/api/scan/preflight');
  return response.data;
}

export async function fetchResourceDetail(arn: string): Promise<ResourceDetailResponse> {
  const response = await apiClient.get<ResourceDetailResponse>('/api/resources', {
    params: { arn },
  });
  return response.data;
}

export async function fetchLatestAudit(): Promise<AuditReportResponse | null> {
  const response = await apiClient.get<AuditReportResponse>('/api/audit/latest', {
    validateStatus: (status) => status === 200 || status === 204,
  });
  if (response.status === 204) {
    return null;
  }
  return response.data;
}

export async function runAudit(): Promise<AuditReportResponse> {
  const response = await apiClient.post<AuditReportResponse>('/api/audit/run');
  return response.data;
}

export async function fetchAuditFindings(level?: string, category?: string): Promise<AuditFinding[]> {
  const response = await apiClient.get<AuditFinding[]>('/api/audit/findings', {
    validateStatus: (status) => status === 200 || status === 204,
    params: {
      ...(level ? { level } : {}),
      ...(category ? { category } : {}),
    },
  });
  if (response.status === 204) {
    return [];
  }
  return response.data;
}

export async function fetchAuditRules(): Promise<AuditRuleResponse[]> {
  const response = await apiClient.get<AuditRuleResponse[]>('/api/audit/rules');
  return response.data;
}

export async function fetchAuditExplanation(): Promise<AuditExplanationResponse | null> {
  const response = await apiClient.post<AuditExplanationResponse>('/api/audit/explain', undefined, {
    validateStatus: (status) => status === 200 || status === 204,
  });
  if (response.status === 204) {
    return null;
  }
  return response.data;
}

export async function runNlQuery(query: string): Promise<NlQueryResponse> {
  const response = await apiClient.post<NlQueryResponse>('/api/query', { query });
  return response.data;
}

export async function fetchQueryExamples(): Promise<string[]> {
  const response = await apiClient.get<string[]>('/api/query/examples');
  return response.data;
}

export async function fetchLabels(): Promise<LabelResponse[]> {
  const response = await apiClient.get<LabelResponse[]>('/api/labels');
  return response.data;
}

export async function generateLabels(): Promise<LabelResponse[]> {
  const response = await apiClient.post<LabelResponse[]>('/api/labels/generate');
  return response.data;
}

export async function generateArchitectureSummary(language: 'ko' | 'en'): Promise<ArchitectureSummaryResponse> {
  const response = await apiClient.post<ArchitectureSummaryResponse>('/api/summary/generate', undefined, {
    params: { lang: language },
  });
  return response.data;
}
