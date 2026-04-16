import axios from 'axios';

import type {
  ArchitectureSummaryResponse,
  AuditExplanationResponse,
  AuditFinding,
  AuditReportResponse,
  AuditRuleResponse,
  GraphResponse,
  LabelResponse,
  NlQueryResponse,
  ResourceDetailResponse,
  ScanPreflightResponse,
  ScanStatusResponse,
} from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

export async function fetchGraph(): Promise<GraphResponse> {
  const response = await apiClient.get<GraphResponse>('/api/graph');
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
