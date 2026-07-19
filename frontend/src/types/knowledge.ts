export interface KnowledgeBase {
  knowledgeBaseId: string
  name: string
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETING'
  readyDocumentCount: number
  createdAt: string
}

export interface KnowledgeDocument {
  documentId: string
  originalFilename: string | null
  status: 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED' | 'DELETING'
  indexRevision: number
  chunkCount: number
  failureReason: string | null
  createdAt: string | null
}
