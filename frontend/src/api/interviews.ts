import { request, requestWithMeta, type ApiResponse } from './request'
import type { CreateInterviewInput, InterviewHistory, InterviewReportResult, InterviewReportStatus, InterviewSession, InterviewSkill } from '../types/interview'

export function listInterviewSkills(signal?: AbortSignal): Promise<InterviewSkill[]> {
  return request('/api/interview-skills', { signal })
}

export function createInterview(input: CreateInterviewInput, signal?: AbortSignal): Promise<ApiResponse<InterviewSession>> {
  return requestWithMeta('/api/interviews', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input), signal })
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
