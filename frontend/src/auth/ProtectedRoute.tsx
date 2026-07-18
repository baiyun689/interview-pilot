import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthProvider'

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) return <p className="page-status" role="status">正在确认登录状态</p>
  if (!user) return <Navigate replace state={{ from: location }} to="/login" />
  return <>{children}</>
}

export function PublicOnlyRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <p className="page-status" role="status">正在确认登录状态</p>
  if (user) return <Navigate replace to="/resumes" />
  return <>{children}</>
}
