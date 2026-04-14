import axios from 'axios';

import type { GraphResponse, ResourceDetailResponse, ScanStatusResponse } from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

export async function fetchGraph(): Promise<GraphResponse> {
  const response = await apiClient.get<GraphResponse>('/api/graph');
  return response.data;
}

export async function fetchLatestScan(): Promise<ScanStatusResponse> {
  const response = await apiClient.get<ScanStatusResponse>('/api/scan/latest');
  return response.data;
}

export async function fetchResourceDetail(arn: string): Promise<ResourceDetailResponse> {
  const response = await apiClient.get<ResourceDetailResponse>('/api/resources', {
    params: { arn },
  });
  return response.data;
}
