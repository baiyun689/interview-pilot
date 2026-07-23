export type KnowledgeDocumentStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED' | 'DELETING'

export interface KnowledgeBase {
  knowledgeBaseId: string
  name: string
  status: string
  readyDocumentCount: number
  createdAt: string
}

export interface KnowledgeDocument {
  documentId: string
  originalFilename: string
  status: KnowledgeDocumentStatus
  indexRevision: number
  chunkCount: number
  failureReason: string | null
  createdAt: string
}
