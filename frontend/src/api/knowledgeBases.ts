import { request } from './request'
import type { KnowledgeBase, KnowledgeDocument } from '../types/knowledge'

export function listKnowledgeBases(signal?: AbortSignal): Promise<KnowledgeBase[]> {
  return request('/api/knowledge-bases', { signal }) as Promise<KnowledgeBase[]>
}

export function createKnowledgeBase(name: string, signal?: AbortSignal): Promise<KnowledgeBase> {
  return request('/api/knowledge-bases', { method: 'POST', body: JSON.stringify({ name }), signal }) as Promise<KnowledgeBase>
}

export function listDocuments(knowledgeBaseId: string, signal?: AbortSignal): Promise<KnowledgeDocument[]> {
  return request(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, { signal }) as Promise<KnowledgeDocument[]>
}

export function uploadDocument(knowledgeBaseId: string, file: File, signal?: AbortSignal): Promise<KnowledgeDocument> {
  const form = new FormData()
  form.append('file', file)
  return request(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, { method: 'POST', body: form, signal }) as Promise<KnowledgeDocument>
}

export function reindexDocument(knowledgeBaseId: string, documentId: string, signal?: AbortSignal): Promise<KnowledgeDocument> {
  return request(`/api/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents/${encodeURIComponent(documentId)}/reindex`, { method: 'POST', signal }) as Promise<KnowledgeDocument>
}
