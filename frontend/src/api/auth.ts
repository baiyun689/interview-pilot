import { request } from './request'
import type { AuthenticatedUser, LoginInput, RegisterInput } from '../types/auth'

const jsonHeaders = { 'Content-Type': 'application/json' }

export function getCurrentUser(): Promise<AuthenticatedUser> {
  return request('/api/auth/me')
}

export function login(input: LoginInput): Promise<AuthenticatedUser> {
  return request('/api/auth/login', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(input),
  })
}

export function register(input: RegisterInput): Promise<AuthenticatedUser> {
  return request('/api/auth/register', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(input),
  })
}

export function logout(): Promise<void> {
  return request('/api/auth/logout', { method: 'POST' })
}
