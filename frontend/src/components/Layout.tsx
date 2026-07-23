import { BookOpen, BrainCircuit, FileText, History, LogOut, PlayCircle, Settings2 } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'

interface NavigationItem {
  label: string
  to: string
  icon: LucideIcon
}

const navigation: NavigationItem[] = [
  { label: '简历', to: '/resumes', icon: FileText },
  { label: '开始面试', to: '/interviews/new', icon: PlayCircle },
  { label: '面试记录', to: '/interviews', icon: History },
  { label: '知识库', to: '/knowledge', icon: BookOpen },
  { label: '模型设置', to: '/settings', icon: Settings2 },
]

export function Layout() {
  const { user, logout } = useAuth()

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark"><BrainCircuit size={24} aria-hidden /></span>
          <span>
            <strong>InterviewPilot</strong>
            <small>自适应 AI 面试练习</small>
          </span>
        </div>
        <nav className="navigation" aria-label="主导航">
          {navigation.map(({ label, to, icon: Icon }) => (
            <NavLink
              className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`}
              end
              key={to}
              to={to}
            >
              <Icon size={19} aria-hidden />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="account-summary">
            <strong>{user?.displayName}</strong>
            <span>{user?.email}</span>
          </div>
          <button className="logout-button" onClick={() => void logout().catch(() => undefined)} type="button">
            <LogOut size={16} aria-hidden />退出登录
          </button>
          <p className="sidebar-note">专注回答，剩下的交给你的 AI 面试官。</p>
        </div>
      </aside>

      <main className="main-content">
        <Outlet />
      </main>
    </div>
  )
}
