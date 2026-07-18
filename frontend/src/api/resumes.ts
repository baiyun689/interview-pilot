import { request, requestWithMeta, type ApiResponse } from './request'
import type { ResumeDetail, ResumeUploadResult } from '../types/resume'

export function listResumes(signal?: AbortSignal): Promise<ResumeDetail[]> {
  return request('/api/resumes', { signal })
}

export function getResume(id: number, signal?: AbortSignal): Promise<ResumeDetail> {
  return request(`/api/resumes/${id}`, { signal })
}

export function uploadResume(file: File, signal?: AbortSignal): Promise<ApiResponse<ResumeUploadResult>> {
  const body = new FormData()
  body.append('file', file)
  return requestWithMeta('/api/resumes', { method: 'POST', body, signal })
}

export function retryResumeAnalysis(taskId: string): Promise<unknown> {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/retry`, { method: 'POST' })
}
