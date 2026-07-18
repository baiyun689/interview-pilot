import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { AuthProvider } from './auth/AuthProvider'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{JSON.stringify(location)}</output>
}

function locationSnapshot() {
  return JSON.parse(screen.getByTestId('location').textContent ?? '{}') as {
    pathname: string
    search: string
    state: unknown
  }
}

function renderApp(path: string | { pathname: string; search?: string; state?: unknown }, fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)
  return render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={[path]}
    >
      <AuthProvider><App /><LocationProbe /></AuthProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
})

describe('应用壳层', () => {
  it('匿名用户访问受保护的简历页会跳转到登录页', async () => {
    renderApp('/resumes', vi.fn().mockResolvedValue(jsonResponse({
      code: 'UNAUTHENTICATED',
      message: '请先登录',
    }, 401)))

    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
  })

  it('已登录用户访问登录页会跳转到简历页', async () => {
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da',
        email: 'ada@example.com',
        displayName: 'Ada',
      }))
      return Promise.resolve(jsonResponse([]))
    })

    renderApp('/login', fetchMock)

    expect(await screen.findByRole('heading', { name: '简历' })).toBeInTheDocument()
  })

  it('匿名用户登录后回到最初请求的深层内部路由', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({ code: 'UNAUTHENTICATED' }, 401))
      if (path === '/api/auth/login') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da', email: 'ada@example.com', displayName: 'Ada',
      }))
      if (path === '/api/resumes/42') return Promise.resolve(jsonResponse({ code: 'NOT_FOUND' }, 404))
      return Promise.resolve(jsonResponse([]))
    })
    renderApp('/resumes/42?tab=profile', fetchMock)

    await screen.findByRole('heading', { name: '登录' })
    await user.type(screen.getByLabelText('邮箱'), 'ada@example.com')
    await user.type(screen.getByLabelText('密码'), 'correct-password')
    await user.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(locationSnapshot()).toMatchObject({
      pathname: '/resumes/42', search: '?tab=profile',
    }))
  })

  it('登录和注册页面互链时保留 return-to 状态', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 'UNAUTHENTICATED' }, 401))
    const from = { pathname: '/interviews/9', search: '?view=report' }
    renderApp({ pathname: '/login', state: { from } }, fetchMock)

    await screen.findByRole('heading', { name: '登录' })
    await user.click(screen.getByRole('link', { name: '创建账户' }))

    await waitFor(() => expect(locationSnapshot()).toMatchObject({ pathname: '/register', state: { from } }))
    await user.click(screen.getByRole('link', { name: '去登录' }))
    await waitFor(() => expect(locationSnapshot()).toMatchObject({ pathname: '/login', state: { from } }))
  })

  it('恶意 return-to 在认证成功后回退到简历页', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({ code: 'UNAUTHENTICATED' }, 401))
      if (path === '/api/auth/login') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da', email: 'ada@example.com', displayName: 'Ada',
      }))
      return Promise.resolve(jsonResponse([]))
    })
    renderApp({ pathname: '/login', state: { from: { pathname: '//evil.example/phish' } } }, fetchMock)

    await screen.findByRole('heading', { name: '登录' })
    await user.type(screen.getByLabelText('邮箱'), 'ada@example.com')
    await user.type(screen.getByLabelText('密码'), 'correct-password')
    await user.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => expect(locationSnapshot()).toMatchObject({ pathname: '/resumes' }))
  })

  it('已登录用户访问匿名页面时忽略恶意 return-to 并进入简历页', async () => {
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da', email: 'ada@example.com', displayName: 'Ada',
      }))
      return Promise.resolve(jsonResponse([]))
    })
    renderApp({ pathname: '/login', state: { from: { pathname: '//evil.example/phish' } } }, fetchMock)

    await screen.findByRole('heading', { name: '简历' })
    expect(locationSnapshot()).toMatchObject({ pathname: '/resumes' })
  })

  it('根路径跳转到简历页，并提供四个精确导航入口', async () => {
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da',
        email: 'ada@example.com',
        displayName: 'Ada',
      }))
      return Promise.resolve(jsonResponse([]))
    })
    renderApp('/', fetchMock)

    expect(await screen.findByRole('heading', { name: '简历' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '简历' })).toHaveAttribute('href', '/resumes')
    expect(screen.getByRole('link', { name: '开始面试' })).toHaveAttribute('href', '/interviews/new')
    expect(screen.getByRole('link', { name: '面试记录' })).toHaveAttribute('href', '/interviews')
    expect(screen.getByRole('link', { name: '模型设置' })).toHaveAttribute('href', '/settings')
  })

  it('以 aria-current 标明当前导航', async () => {
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da',
        email: 'ada@example.com',
        displayName: 'Ada',
      }))
      return new Promise(() => undefined)
    })
    renderApp('/settings', fetchMock)

    expect(await screen.findByRole('link', { name: '模型设置' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: '简历' })).not.toHaveAttribute('aria-current')
  })

  it('退出后清除登录状态并回到登录页', async () => {
    const fetchMock = vi.fn((path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(jsonResponse({
        userId: '31883977-a15f-4c8a-9e37-10cc267ea5da',
        email: 'ada@example.com',
        displayName: 'Ada',
      }))
      if (path === '/api/auth/logout') return Promise.resolve(new Response(null, { status: 204 }))
      return Promise.resolve(jsonResponse([]))
    })
    renderApp('/resumes', fetchMock)

    await screen.findByRole('heading', { name: '简历' })
    screen.getByRole('button', { name: '退出登录' }).click()

    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
  })
})
