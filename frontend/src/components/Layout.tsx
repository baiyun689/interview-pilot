import { BookOpen, BrainCircuit, BriefcaseBusiness, FileText, History, LogOut, PlayCircle, Settings2 } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { useHiringLoad } from './HiringUi'

interface NavigationItem {
  label: string
  to: string
  icon: LucideIcon
  group?: string
}

const navigation: NavigationItem[] = [
  { label: '企业招聘', to: '/enterprise', icon: BriefcaseBusiness, group: '招聘工作台' },
  { label: '开放岗位', to: '/jobs', icon: BookOpen, group: '我的求职' },
  { label: '我的投递', to: '/candidate/applications', icon: FileText },
  { label: '面试邀请', to: '/candidate/invitations', icon: BriefcaseBusiness },
  { label: '通知中心', to: '/notifications', icon: FileText },
  { label: '简历', to: '/resumes', icon: FileText },
  { label: '开始面试', to: '/interviews/new', icon: PlayCircle, group: '面试练习' },
  { label: '面试记录', to: '/interviews', icon: History },
  { label: '知识库', to: '/knowledge', icon: BookOpen },
  { label: '模型设置', to: '/settings', icon: Settings2, group: '偏好设置' },
]

export function Layout() {
  const { user, logout } = useAuth()
  const platform = useHiringLoad<{ allowed: boolean }>('/api/platform/access')

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark"><BrainCircuit size={24} aria-hidden /></span>
          <span>
            <strong>InterviewPilot</strong>
            <small>招聘协作 · AI 面试</small>
          </span>
        </div>
        <nav className="navigation" aria-label="主导航">
          {navigation.map(({ label, to, icon: Icon, group }) => (
            <div className="navigation-item" key={to}>
            {group && <span className="navigation-label">{group}</span>}
            <NavLink
              className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`}
              end
              to={to}
            >
              <Icon size={19} aria-hidden />
              <span>{label}</span>
            </NavLink>
            </div>
          ))}
          {platform.data?.allowed && <NavLink className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`} to="/platform"><Settings2 size={19} aria-hidden /><span>平台管理</span></NavLink>}
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
