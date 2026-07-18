export interface AiProvider {
  id: string
  displayName: string
  model: string
  enabled: boolean
  defaultProvider: boolean
}

export interface ProviderTestResult {
  success: boolean
  latency?: unknown
  error: string | null
}
