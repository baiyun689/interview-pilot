import { request } from './request'

export type Role = 'ADMIN' | 'RECRUITER' | 'INTERVIEWER'
export interface Organization { id: number; name: string; role: Role }
export interface Member { userId: number; displayName: string; email: string; role: Role; active: boolean }
export interface Job {
  id: number; organizationId: number; organizationName: string; title: string; description: string
  location: string; employmentType: string; status: string; publishedRevision: number; version: number; canManage: boolean
}
export interface Application {
  id: number; organizationId: number; jobId: number; organizationName: string; jobTitle: string
  candidateId: number; candidateName: string; status: string; submissionNo: number; jobRevision: number
  resumeRevisionId: number; submittedAt: string; version: number
}
export interface ApplicationDetail {
  application: Application; jobDescription: string; resumeFilename: string; resumeText: string
  events: { action: string; submissionNo: number; jobRevision: number; resumeRevisionId: number; createdAt: string }[]
}
export interface Page<T> { items: T[]; page: number; hasMore: boolean }
export function hiringWrite<T>(path: string, body: unknown = {}, method = 'POST'): Promise<T> {
  return request(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
}
export const roleLabels: Record<Role, string> = { ADMIN: '企业管理员', RECRUITER: '招聘人员', INTERVIEWER: '面试官' }
export const statusLabels: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', CLOSED: '已关闭', SUBMITTED: '已投递', IN_PROCESS: '招聘进行中',
  FINISHED: '已结束', WITHDRAWN: '已撤回', RESUBMITTED: '重新投递',
  FEEDBACK_NEXT_ROUND: '企业发布反馈：进入下一轮', FEEDBACK_MORE_ASSESSMENT: '企业发布反馈：待补充评估', FEEDBACK_END_PROCESS: '企业发布反馈：结束流程',
}
