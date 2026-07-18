import type { ResumeStatus } from '../types/resume'

const labels: Record<ResumeStatus, string> = {
  PENDING: '等待分析',
  ANALYZING: '分析中',
  READY: '已完成',
  FAILED: '分析失败',
}

export function ResumeStatusBadge({ status }: { status: ResumeStatus }) {
  return <span className={`resume-status resume-status-${status.toLowerCase()}`}>{labels[status]}</span>
}
