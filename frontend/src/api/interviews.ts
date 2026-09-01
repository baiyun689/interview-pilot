import { request, requestWithMeta, type ApiResponse } from './request'
import type { CreateInterviewInput, CreateInterviewResult, InterviewHistory, InterviewPreset, InterviewReportResult, InterviewReportStatus, InterviewSession } from '../types/interview'

export function listInterviewPresets(signal?: AbortSignal): Promise<InterviewPreset[]> {
  return request('/api/interview-presets', { signal })
}

export function createInterview(input: CreateInterviewInput, signal?: AbortSignal): Promise<ApiResponse<CreateInterviewResult>> {
  return requestWithMeta('/api/interviews', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input), signal })
}

export function startInterview(sessionId: string, signal?: AbortSignal): Promise<unknown> {
  return request(`/api/interviews/${encodeURIComponent(sessionId)}/start`, { method: 'POST', signal })
}

export function listInterviews(signal?: AbortSignal): Promise<InterviewHistory[]> {
  return request('/api/interviews', { signal })
}

export function getInterview(sessionId: string, signal?: AbortSignal): Promise<InterviewSession> {
  return request(`/api/interviews/${encodeURIComponent(sessionId)}`, { signal })
}

export function getInterviewReport(sessionId: string, signal?: AbortSignal): Promise<ApiResponse<InterviewReportStatus | InterviewReportResult>> {
  return requestWithMeta(`/api/interviews/${encodeURIComponent(sessionId)}/report`, { signal })
}

export function retryInterviewReport(taskId: string, signal?: AbortSignal): Promise<unknown> {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/retry`, { method: 'POST', signal })
}

export const retryInterviewPreparation = retryInterviewReport
