import { request } from './request'
import type { AiProvider, ProviderTestResult } from '../types/provider'

export function listProviders(signal?: AbortSignal): Promise<AiProvider[]> {
  return request('/api/ai/providers', { signal })
}

export function testProvider(id: string): Promise<ProviderTestResult> {
  return request(`/api/ai/providers/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

export function setDefaultProvider(providerId: string): Promise<AiProvider> {
  return request('/api/ai/providers/default', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ providerId }),
  })
}
