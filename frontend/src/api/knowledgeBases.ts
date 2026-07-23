import { request, requestWithMeta, type ApiResponse } from './request'
import type { KnowledgeBase, KnowledgeDocument } from '../types/knowledge'

export function listKnowledgeBases(signal?: AbortSignal): Promise<KnowledgeBase[]> {
  return request('/api/knowledge-bases', { signal })
}

export function createKnowledgeBase(name: string, signal?: AbortSignal): Promise<KnowledgeBase> {
  return request('/api/knowledge-bases', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
    signal,
  })
}

export function listDocuments(knowledgeBaseId: string, signal?: AbortSignal): Promise<KnowledgeDocument[]> {
  return request(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, { signal })
}

export function uploadDocument(
  knowledgeBaseId: string,
  file: File,
  signal?: AbortSignal,
): Promise<ApiResponse<KnowledgeDocument>> {
  const body = new FormData()
  body.append('file', file)
  return requestWithMeta(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, {
    method: 'POST',
    body,
    signal,
  })
}

export function reindexDocument(
  knowledgeBaseId: string,
  documentId: string,
  signal?: AbortSignal,
): Promise<ApiResponse<KnowledgeDocument>> {
  return requestWithMeta(
    `/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents/${encodeURIComponent(documentId)}/reindex`,
    { method: 'POST', signal },
  )
}
