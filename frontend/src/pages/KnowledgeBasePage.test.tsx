import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { KnowledgeBasePage } from './KnowledgeBasePage'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const base = {
  knowledgeBaseId: 'kb-1',
  name: 'Backend KB',
  status: 'ACTIVE',
  readyDocumentCount: 1,
  createdAt: '2026-07-29T01:00:00Z',
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('知识库页面', () => {
  it('展示全部可见文档状态，轮询索引中文档，并支持删除', async () => {
    const fetchMock = vi.fn((path: string, init?: RequestInit) => {
      if (path === '/api/knowledge-bases') return Promise.resolve(json([base]))
      if (path === '/api/knowledge-bases/kb-1/documents/doc-ready' && init?.method === 'DELETE') {
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      if (path === '/api/knowledge-bases/kb-1/documents') {
        const documentCalls = fetchMock.mock.calls
          .filter(([url, options]) => url === '/api/knowledge-bases/kb-1/documents' && options?.method !== 'DELETE')
          .length
        if (documentCalls <= 1) {
          return Promise.resolve(json([
            { documentId: 'doc-processing', originalFilename: 'indexing.md', status: 'PROCESSING', indexRevision: 1, chunkCount: 0, failureReason: null, createdAt: '2026-07-29T01:01:00Z' },
            { documentId: 'doc-failed', originalFilename: 'failed.md', status: 'FAILED', indexRevision: 1, chunkCount: 0, failureReason: 'parse failed', createdAt: '2026-07-29T01:02:00Z' },
            { documentId: 'doc-ready', originalFilename: 'ready.md', status: 'READY', indexRevision: 1, chunkCount: 3, failureReason: null, createdAt: '2026-07-29T01:03:00Z' },
          ]))
        }
        return Promise.resolve(json([
          { documentId: 'doc-processing', originalFilename: 'indexing.md', status: 'READY', indexRevision: 1, chunkCount: 2, failureReason: null, createdAt: '2026-07-29T01:01:00Z' },
          { documentId: 'doc-failed', originalFilename: 'failed.md', status: 'FAILED', indexRevision: 1, chunkCount: 0, failureReason: 'parse failed', createdAt: '2026-07-29T01:02:00Z' },
          { documentId: 'doc-ready', originalFilename: 'ready.md', status: 'READY', indexRevision: 1, chunkCount: 3, failureReason: null, createdAt: '2026-07-29T01:03:00Z' },
        ]))
      }
      return Promise.resolve(json({ message: 'not found' }, 404))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()

    render(<KnowledgeBasePage pollIntervalMs={50} />)
    await user.click(await screen.findByRole('button', { name: /Backend KB/ }))

    expect(await screen.findByText('索引中')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
    expect(screen.getByText('parse failed')).toBeInTheDocument()

    await waitFor(() => expect(screen.getAllByText('就绪')).toHaveLength(2))

    const readyRow = screen.getByText('ready.md').closest('li')
    expect(readyRow).not.toBeNull()
    await user.click(within(readyRow as HTMLElement).getByRole('button', { name: /删除/ }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/knowledge-bases/kb-1/documents/doc-ready',
      expect.objectContaining({ method: 'DELETE' }),
    ))
    expect(screen.queryByText('ready.md')).not.toBeInTheDocument()
  })
})
