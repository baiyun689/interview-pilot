import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => vi.unstubAllGlobals())

describe('应用壳层', () => {
  it('根路径跳转到简历页，并提供四个精确导航入口', async () => {
    render(
      <MemoryRouter
        future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
        initialEntries={['/']}
      >
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '简历' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '简历' })).toHaveAttribute('href', '/resumes')
    expect(screen.getByRole('link', { name: '开始面试' })).toHaveAttribute('href', '/interviews/new')
    expect(screen.getByRole('link', { name: '面试记录' })).toHaveAttribute('href', '/interviews')
    expect(screen.getByRole('link', { name: '模型设置' })).toHaveAttribute('href', '/settings')
  })

  it('以 aria-current 标明当前导航', async () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => undefined)))

    render(
      <MemoryRouter
        future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
        initialEntries={['/settings']}
      >
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: '模型设置' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: '简历' })).not.toHaveAttribute('aria-current')
  })
})
