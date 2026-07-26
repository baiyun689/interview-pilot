import { request } from './request'
import type { TokenPairResponse, LoginInput, RegisterInput } from '../types/auth'

const jsonHeaders = { 'Content-Type': 'application/json' }

export function login(input: LoginInput): Promise<TokenPairResponse> {
  return request('/api/auth/login', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(input),
  })
}

export function register(input: RegisterInput): Promise<TokenPairResponse> {
  return request('/api/auth/register', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(input),
  })
}

export function refresh(): Promise<TokenPairResponse> {
  return request('/api/auth/refresh', { method: 'POST' })
}

export function logout(): Promise<void> {
  return request('/api/auth/logout', { method: 'POST' })
}
