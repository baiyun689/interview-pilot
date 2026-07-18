import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { AuthProvider } from './auth/AuthProvider'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderApp(path: string, fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)
  return render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={[path]}
    >
      <AuthProvider><App /></AuthProvider>
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
