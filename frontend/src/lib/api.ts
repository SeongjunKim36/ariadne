import axios from 'axios';

import type {
  GraphResponse,
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
