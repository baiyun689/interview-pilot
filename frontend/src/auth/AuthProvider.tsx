import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as authApi from '../api/auth'
import { ApiClientError, onUnauthorized, setAccessToken } from '../api/request'
import type { AuthenticatedUser, LoginInput, RegisterInput } from '../types/auth'

interface AuthContextValue {
  user: AuthenticatedUser | null
  loading: boolean
  error: ApiClientError | null
  login: (input: LoginInput) => Promise<void>
  register: (input: RegisterInput) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function asApiClientError(error: unknown): ApiClientError | null {
  return error instanceof ApiClientError ? error : null
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiClientError | null>(null)

  useEffect(() => {
    let active = true
    const unsubscribe = onUnauthorized(() => {
      if (active) {
        setUser(null)
        setAccessToken(null)
      }
    })

    void authApi.refresh()
      .then((result) => {
        if (!active) return
        setAccessToken(result.accessToken)
        setUser({ userId: result.userId, email: result.email, displayName: result.displayName })
        setError(null)
      })
      .catch(() => {
        if (!active) return
        setAccessToken(null)
        setUser(null)
        setError(null)
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
      unsubscribe()
    }
  }, [])

  async function login(input: LoginInput) {
    setError(null)
    try {
      const result = await authApi.login(input)
      setAccessToken(result.accessToken)
      setUser({ userId: result.userId, email: result.email, displayName: result.displayName })
    } catch (failure) {
      const apiError = asApiClientError(failure)
      if (apiError?.status === 401) {
        setUser(null)
        setAccessToken(null)
      }
      setError(apiError)
      throw failure
    }
  }

  async function register(input: RegisterInput) {
    setError(null)
    try {
      const result = await authApi.register(input)
      setAccessToken(result.accessToken)
      setUser({ userId: result.userId, email: result.email, displayName: result.displayName })
    } catch (failure) {
      const apiError = asApiClientError(failure)
      if (apiError?.status === 401) {
        setUser(null)
        setAccessToken(null)
      }
      setError(apiError)
      throw failure
    }
  }

  async function logout() {
    setError(null)
    try {
      await authApi.logout()
    } finally {
      setAccessToken(null)
      setUser(null)
    }
  }

  const value = useMemo(() => ({ user, loading, error, login, register, logout }), [user, loading, error])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth 必须在 AuthProvider 中使用')
  return context
}
